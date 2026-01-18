package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.sync.ConflictResolver
import org.onekash.icaldav.sync.ConflictResult
import org.onekash.icaldav.sync.ConflictStrategy
import org.onekash.icaldav.sync.*
import org.onekash.icaldav.sync.PendingOperationStore
import org.onekash.icaldav.sync.PushResult
import org.onekash.icaldav.sync.PushStrategy
import org.onekash.icaldav.quirks.CalDavQuirks
import org.onekash.icaldav.quirks.DefaultQuirks
import java.util.UUID

/**
 * High-level CalDAV sync engine that orchestrates pull and push operations.
 *
 * Combines:
 * - Pull: Fetching events from server with incremental sync support
 * - Push: Pushing pending local changes to server
 * - Conflict resolution: Handling 412 conflicts with configurable strategies
 * - Operation queuing: Local change tracking with coalescing
 *
 * Usage:
 * ```
 * val engine = CalDavSyncEngine(
 *     client = calDavClient,
 *     localProvider = myLocalProvider,
 *     resultHandler = myResultHandler,
 *     pendingStore = InMemoryPendingOperationStore()
 * )
 *
 * // Queue local changes
 * engine.queueCreate(calendarUrl, event)
 * engine.queueUpdate(event, url, etag)
 * engine.queueDelete(eventUid, url, etag)
 *
 * // Sync
 * val result = engine.sync(calendarUrl, previousState)
 * ```
 */
class CalDavSyncEngine(
    private val client: CalDavClient,
    private val localProvider: LocalEventProvider,
    private val resultHandler: SyncResultHandler,
    private val pendingStore: PendingOperationStore,
    private val serializer: ICalGenerator = ICalGenerator(),
    @Suppress("unused") private val quirks: CalDavQuirks = DefaultQuirks("generic", "CalDAV", ""),
    @Suppress("unused") private val config: SyncConfig = SyncConfig()
) {
    private val pullEngine = SyncEngine(client)
    private val pushStrategy = PushStrategy(client, pendingStore)
    private val conflictResolver = ConflictResolver(client, localProvider, resultHandler, pendingStore)

    /**
     * Pull events from server.
     *
     * Uses incremental sync if a sync token is available in previousState.
     *
     * @param calendarUrl Calendar collection URL
     * @param previousState Sync state from last sync
     * @param forceFullSync If true, skip incremental and do full sync
     * @param callback Optional progress callback
     * @return Pull result summary
     */
    suspend fun pull(
        calendarUrl: String,
        previousState: SyncState,
        forceFullSync: Boolean = false,
        callback: SyncCallback? = null
    ): PullResult {
        val report = pullEngine.syncWithIncremental(
            calendarUrl = calendarUrl,
            previousState = previousState,
            localProvider = localProvider,
            handler = resultHandler,
            forceFullSync = forceFullSync,
            callback = callback
        )
        return PullResult.fromReport(report)
    }

    /**
     * Push all pending local changes to server.
     *
     * @return Push result summary
     */
    suspend fun push(): PushResult = pushStrategy.pushAll()

    /**
     * Push only pending changes for a specific calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @return Push result summary
     */
    suspend fun pushForCalendar(calendarUrl: String): PushResult =
        pushStrategy.pushForCalendar(calendarUrl)

    /**
     * Full sync: push then pull.
     *
     * Pushes local changes first to avoid overwriting them during pull.
     *
     * @param calendarUrl Calendar collection URL
     * @param previousState Sync state from last sync
     * @param callback Optional progress callback
     * @return Combined sync result
     */
    suspend fun sync(
        calendarUrl: String,
        previousState: SyncState,
        callback: SyncCallback? = null
    ): CombinedSyncResult {
        // Push first (so local changes aren't overwritten)
        val pushResult = pushForCalendar(calendarUrl)

        // Then pull
        val pullResult = pull(calendarUrl, previousState, callback = callback)

        return CombinedSyncResult(pullResult, pushResult)
    }

    /**
     * Resolve a conflict using the specified strategy.
     *
     * @param operation The pending operation that caused the conflict
     * @param strategy How to resolve the conflict
     * @return Result indicating what action was taken
     */
    suspend fun resolveConflict(
        operation: PendingOperation,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS
    ): ConflictResult = conflictResolver.resolve(operation, strategy)

    /**
     * Queue a new event for sync to the server.
     *
     * @param calendarUrl Calendar collection URL
     * @param event Event to create
     */
    suspend fun queueCreate(calendarUrl: String, event: ICalEvent) {
        val icalData = serializer.generate(event)
        val op = PendingOperation(
            id = UUID.randomUUID().toString(),
            calendarUrl = calendarUrl,
            eventUid = event.uid,
            eventUrl = null,
            operation = OperationType.CREATE,
            status = OperationStatus.PENDING,
            icalData = icalData,
            etag = null
        )
        pendingStore.enqueue(op)
    }

    /**
     * Queue an event update for sync to the server.
     *
     * Operation Coalescing:
     * - If CREATE pending: keep CREATE with updated icalData (never hit server yet)
     * - If UPDATE pending: replace with new UPDATE (latest data wins)
     * - If DELETE pending: ERROR - can't update deleted event
     *
     * @param event Updated event
     * @param url Event URL on server
     * @param etag Current ETag for conflict detection
     * @throws IllegalStateException if event is pending DELETE
     */
    suspend fun queueUpdate(event: ICalEvent, url: String, etag: String?) {
        val existing = pendingStore.getByEventUid(event.uid)
        val icalData = serializer.generate(event)

        when (existing?.operation) {
            OperationType.CREATE -> {
                // Update the pending CREATE with new data
                val updated = existing.copy(icalData = icalData)
                pendingStore.update(updated)
                return
            }
            OperationType.UPDATE -> {
                // Replace pending UPDATE with new one
                pendingStore.delete(existing.id)
            }
            OperationType.DELETE -> {
                // Can't update a deleted event - this is a logic error
                throw IllegalStateException("Cannot queue UPDATE for event pending DELETE: ${event.uid}")
            }
            null -> { /* No existing operation - proceed */ }
        }

        val op = PendingOperation(
            id = UUID.randomUUID().toString(),
            calendarUrl = extractCalendarUrl(url),
            eventUid = event.uid,
            eventUrl = url,
            operation = OperationType.UPDATE,
            status = OperationStatus.PENDING,
            icalData = icalData,
            etag = etag
        )
        pendingStore.enqueue(op)
    }

    /**
     * Queue an event deletion for sync to the server.
     *
     * Operation Coalescing:
     * - If CREATE pending: delete the pending op (never synced, nothing to delete)
     * - If UPDATE pending: replace with DELETE (remote still has old version)
     * - If DELETE pending: no-op (already queued)
     *
     * @param eventUid Event UID to delete
     * @param url Event URL on server
     * @param etag Current ETag for conflict detection
     */
    suspend fun queueDelete(eventUid: String, url: String, etag: String?) {
        val existing = pendingStore.getByEventUid(eventUid)

        when (existing?.operation) {
            OperationType.CREATE -> {
                // Never synced to server - just remove pending op
                pendingStore.delete(existing.id)
                return  // No server DELETE needed
            }
            OperationType.UPDATE -> {
                // Replace UPDATE with DELETE
                pendingStore.delete(existing.id)
            }
            OperationType.DELETE -> {
                // Already queued for deletion
                return
            }
            null -> { /* No existing operation - proceed */ }
        }

        val op = PendingOperation(
            id = UUID.randomUUID().toString(),
            calendarUrl = extractCalendarUrl(url),
            eventUid = eventUid,
            eventUrl = url,
            operation = OperationType.DELETE,
            status = OperationStatus.PENDING,
            icalData = "",
            etag = etag
        )
        pendingStore.enqueue(op)
    }

    /**
     * Get the number of pending operations.
     *
     * @return Count of pending operations
     */
    suspend fun pendingCount(): Int = pendingStore.count()

    /**
     * Get pending operations for a calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @return List of pending operations
     */
    suspend fun getPendingOperations(calendarUrl: String): List<PendingOperation> =
        pendingStore.getOperationsForCalendar(calendarUrl)

    /**
     * Extract calendar collection URL from an event URL.
     *
     * Example: "https://caldav.icloud.com/123/calendars/home/event.ics"
     *       -> "https://caldav.icloud.com/123/calendars/home/"
     */
    private fun extractCalendarUrl(eventUrl: String): String {
        val lastSlash = eventUrl.lastIndexOf('/')
        return if (lastSlash > 0) {
            eventUrl.substring(0, lastSlash + 1)
        } else {
            eventUrl
        }
    }
}

/**
 * Configuration for sync engine behavior.
 */
data class SyncConfig(
    /** Maximum retry attempts for failed operations */
    val maxRetries: Int = 5,

    /** Initial backoff delay in milliseconds */
    val initialBackoffMs: Long = 60_000,

    /** Maximum backoff delay in milliseconds */
    val maxBackoffMs: Long = 3_600_000
)

/**
 * Combined result of pull and push operations.
 */
data class CombinedSyncResult(
    val pullResult: PullResult,
    val pushResult: PushResult
) {
    val success: Boolean
        get() = pullResult.success && pushResult !is PushResult.Error
}
