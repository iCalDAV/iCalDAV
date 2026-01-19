package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.sync.LocalEventProvider
import org.onekash.icaldav.sync.OperationStatus
import org.onekash.icaldav.sync.OperationType
import org.onekash.icaldav.sync.PendingOperation
import org.onekash.icaldav.sync.SyncResultHandler
import org.onekash.icaldav.sync.PendingOperationStore
import org.onekash.icaldav.sync.errorMessage
import org.onekash.icaldav.model.DavResult

/**
 * Resolves conflicts between local pending operations and server state.
 *
 * Conflicts occur when a push operation fails with 412 (ETag mismatch),
 * indicating the server has a different version than expected.
 */
class ConflictResolver(
    private val client: CalDavClient,
    private val localProvider: LocalEventProvider,
    private val resultHandler: SyncResultHandler,
    private val pendingStore: PendingOperationStore
) {
    /**
     * Resolve a conflict using the specified strategy.
     *
     * @param operation The pending operation that caused the conflict
     * @param strategy How to resolve the conflict
     * @return Result indicating what action was taken
     */
    suspend fun resolve(
        operation: PendingOperation,
        strategy: ConflictStrategy = ConflictStrategy.SERVER_WINS
    ): ConflictResult {
        return when (strategy) {
            ConflictStrategy.SERVER_WINS -> resolveServerWins(operation)
            ConflictStrategy.LOCAL_WINS -> resolveLocalWins(operation)
            ConflictStrategy.NEWEST_WINS -> resolveNewestWins(operation)
            ConflictStrategy.MANUAL -> resolveManual(operation)
        }
    }

    /**
     * Resolve conflict by accepting server version.
     * Fetches server event and overwrites local.
     */
    private suspend fun resolveServerWins(op: PendingOperation): ConflictResult {
        val url = op.eventUrl ?: return ConflictResult.Error("No URL for conflict resolution")

        val fetchResult = client.getEvent(url)

        return when (fetchResult) {
            is DavResult.Success -> {
                applyServerVersion(op, fetchResult.value)
            }
            is DavResult.HttpError -> {
                if (fetchResult.code == 404) {
                    // Server deleted - delete local too
                    resultHandler.deleteEvent(op.eventUid)
                    pendingStore.delete(op.id)
                    ConflictResult.LocalDeleted
                } else {
                    ConflictResult.Error("Failed to fetch: ${fetchResult.errorMessage()}")
                }
            }
            else -> {
                ConflictResult.Error("Failed to fetch: ${fetchResult.errorMessage()}")
            }
        }
    }

    /**
     * Apply an already-fetched server version to local storage.
     * Avoids duplicate fetch when called from resolveNewestWins.
     */
    private suspend fun applyServerVersion(
        op: PendingOperation,
        serverData: EventWithMetadata
    ): ConflictResult {
        resultHandler.upsertEvent(serverData.event, serverData.href, serverData.etag)
        pendingStore.delete(op.id)
        return ConflictResult.ServerVersionKept
    }

    /**
     * Resolve conflict by forcing local version.
     *
     * For DELETE: force delete without ETag.
     * For UPDATE: cannot force without server support (returns error).
     */
    private suspend fun resolveLocalWins(op: PendingOperation): ConflictResult {
        // For DELETE: force delete without ETag
        if (op.operation == OperationType.DELETE) {
            val url = op.eventUrl ?: return ConflictResult.Error("No URL for delete")
            val result = client.deleteEvent(url, null)  // No ETag = force

            return when (result) {
                is DavResult.Success -> {
                    pendingStore.delete(op.id)
                    ConflictResult.LocalVersionPushed
                }
                is DavResult.HttpError -> {
                    if (result.code == 404) {
                        // Already gone
                        pendingStore.delete(op.id)
                        ConflictResult.LocalVersionPushed
                    } else {
                        ConflictResult.Error("Force delete failed: ${result.errorMessage()}")
                    }
                }
                else -> {
                    ConflictResult.Error("Force delete failed: ${result.errorMessage()}")
                }
            }
        }

        // For CREATE/UPDATE: Can't force without server support (no PUT force mode)
        return ConflictResult.Error("LOCAL_WINS for ${op.operation} not supported - use NEWEST_WINS or SERVER_WINS")
    }

    /**
     * Resolve conflict by comparing SEQUENCE numbers and timestamps.
     *
     * RFC 5545 SEQUENCE handling:
     * - Higher SEQUENCE number wins
     * - If equal, compare DTSTAMP timestamps
     */
    private suspend fun resolveNewestWins(op: PendingOperation): ConflictResult {
        val url = op.eventUrl ?: return ConflictResult.Error("No URL for conflict resolution")

        // Fetch server version ONCE
        val fetchResult = client.getEvent(url)

        return when (fetchResult) {
            is DavResult.Success -> {
                val serverData = fetchResult.value
                val serverEvent = serverData.event
                val localEvent = localProvider.getEventByImportId(op.eventUid)
                    ?: return ConflictResult.Error("Local event not found: ${op.eventUid}")

                // Compare SEQUENCE numbers (RFC 5545)
                val serverWins = when {
                    serverEvent.sequence > localEvent.sequence -> true
                    serverEvent.sequence < localEvent.sequence -> false
                    // Equal sequence - compare DTSTAMP timestamps
                    else -> {
                        val serverTimestamp = serverEvent.dtstamp?.timestamp ?: 0L
                        val localTimestamp = localEvent.dtstamp?.timestamp ?: 0L
                        serverTimestamp > localTimestamp
                    }
                }

                if (serverWins) {
                    // Reuse already-fetched data - NO second fetch
                    applyServerVersion(op, serverData)
                } else {
                    // Reset operation for retry with fresh attempt
                    val resetOp = op.copy(
                        retryCount = 0,
                        nextRetryAt = 0,
                        status = OperationStatus.PENDING,
                        etag = null  // Clear ETag to force push without If-Match
                    )
                    pendingStore.update(resetOp)
                    ConflictResult.LocalVersionPushed
                }
            }
            is DavResult.HttpError -> {
                if (fetchResult.code == 404) {
                    // Server deleted - delete local too
                    resultHandler.deleteEvent(op.eventUid)
                    pendingStore.delete(op.id)
                    ConflictResult.LocalDeleted
                } else {
                    ConflictResult.Error("Failed to fetch: ${fetchResult.errorMessage()}")
                }
            }
            else -> {
                ConflictResult.Error("Failed to fetch: ${fetchResult.errorMessage()}")
            }
        }
    }

    /**
     * Mark operation for manual resolution.
     * Keeps operation in failed state for user review.
     */
    private suspend fun resolveManual(op: PendingOperation): ConflictResult {
        pendingStore.markFailed(
            op.id,
            "Conflict - manual resolution required",
            System.currentTimeMillis()
        )
        return ConflictResult.MarkedForManualResolution
    }
}

/**
 * Result of conflict resolution.
 */
sealed class ConflictResult {
    /** Server version was applied to local storage */
    object ServerVersionKept : ConflictResult()

    /** Local version was pushed (or will be retried) */
    object LocalVersionPushed : ConflictResult()

    /** Event was deleted locally (server deleted it) */
    object LocalDeleted : ConflictResult()

    /** Operation marked for manual user resolution */
    object MarkedForManualResolution : ConflictResult()

    /** Resolution failed */
    data class Error(val message: String) : ConflictResult()

    val isSuccess: Boolean
        get() = this is ServerVersionKept ||
            this is LocalVersionPushed ||
            this is LocalDeleted ||
            this is MarkedForManualResolution
}
