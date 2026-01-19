package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.sync.*
import org.onekash.icaldav.model.DavResult

/**
 * Sync engine for CalDAV calendar synchronization.
 *
 * Implements ctag-based change detection with optional sync-token
 * incremental sync (RFC 6578).
 *
 * Sync flow:
 * 1. Check ctag - if unchanged, skip sync
 * 2. If changed, fetch all events
 * 3. Compare with local events
 * 4. Apply changes through handler
 * 5. Save new sync state
 *
 * Production-tested sync implementation with conflict handling.
 */
class SyncEngine(
    private val calDavClient: CalDavClient
) {
    /**
     * Perform sync for a calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @param previousState Sync state from last sync
     * @param localProvider Provider for local event data
     * @param handler Handler for sync results
     * @param callback Optional progress callback
     * @return Sync report with results
     */
    fun sync(
        calendarUrl: String,
        previousState: SyncState,
        localProvider: LocalEventProvider,
        handler: SyncResultHandler,
        callback: SyncCallback? = null
    ): SyncReport {
        val startTime = System.currentTimeMillis()
        callback?.onSyncStarted(calendarUrl)

        try {
            // Step 1: Check ctag for changes
            callback?.onProgress("Checking for changes...", 0, 100)
            val ctagResult = calDavClient.getCtag(calendarUrl)

            if (ctagResult !is DavResult.Success) {
                return handleDavError(ctagResult, previousState, startTime)
            }

            val currentCtag = ctagResult.value

            // If ctag unchanged, skip sync
            if (currentCtag != null && currentCtag == previousState.ctag) {
                callback?.onProgress("No changes detected", 100, 100)
                return SyncReport(
                    upserted = emptyList(),
                    deleted = emptyList(),
                    conflicts = emptyList(),
                    serverEventCount = 0,
                    previousCtag = previousState.ctag,
                    newCtag = currentCtag,
                    syncToken = previousState.syncToken,
                    isFullSync = false,
                    durationMs = System.currentTimeMillis() - startTime,
                    errors = emptyList()
                )
            }

            // Step 2: Fetch all events from server
            callback?.onProgress("Fetching events...", 20, 100)
            val fetchResult = calDavClient.fetchEvents(calendarUrl)

            if (fetchResult !is DavResult.Success) {
                return handleDavError(fetchResult, previousState, startTime)
            }

            val serverEvents = fetchResult.value
            callback?.onProgress("Processing ${serverEvents.size} events...", 50, 100)

            // Step 3: Compare with local and determine changes
            val localEvents = localProvider.getLocalEvents(calendarUrl)
            val changes = computeChanges(serverEvents, localEvents, previousState, callback)

            // Step 4: Apply changes
            callback?.onProgress("Applying changes...", 80, 100)
            applyChanges(changes, handler)

            // Step 5: Build new sync state
            val newState = buildNewState(calendarUrl, currentCtag, serverEvents)
            handler.saveSyncState(newState)

            // Build report
            val report = SyncReport(
                upserted = changes.upserted,
                deleted = changes.deleted,
                conflicts = changes.conflicts,
                serverEventCount = serverEvents.size,
                previousCtag = previousState.ctag,
                newCtag = currentCtag,
                syncToken = newState.syncToken,
                isFullSync = true,
                durationMs = System.currentTimeMillis() - startTime,
                errors = emptyList()
            )

            callback?.onProgress("Sync complete", 100, 100)
            callback?.onSyncComplete(report)

            return report

        } catch (e: Exception) {
            val error = SyncError(
                eventUid = null,
                message = e.message ?: "Unknown error",
                type = SyncErrorType.UNKNOWN,
                exception = e
            )
            callback?.onSyncError(error)
            return SyncReport.empty().copy(
                durationMs = System.currentTimeMillis() - startTime,
                errors = listOf(error)
            )
        }
    }

    /**
     * Perform sync with incremental sync support (RFC 6578).
     *
     * If a sync token exists in previousState, attempts incremental sync first.
     * Falls back to full sync if token is invalid.
     *
     * @param calendarUrl Calendar collection URL
     * @param previousState Sync state from last sync
     * @param localProvider Provider for local event data
     * @param handler Handler for sync results
     * @param forceFullSync If true, skip incremental and do full sync
     * @param callback Optional progress callback
     * @return Sync report with results
     */
    fun syncWithIncremental(
        calendarUrl: String,
        previousState: SyncState,
        localProvider: LocalEventProvider,
        handler: SyncResultHandler,
        forceFullSync: Boolean = false,
        callback: SyncCallback? = null
    ): SyncReport {
        val startTime = System.currentTimeMillis()
        callback?.onSyncStarted(calendarUrl)

        try {
            // Step 1: Decide sync strategy
            val useIncremental = !forceFullSync &&
                previousState.syncToken != null &&
                previousState.syncToken.isNotEmpty()

            callback?.onProgress(
                if (useIncremental) "Checking for changes..." else "Starting full sync...",
                0, 100
            )

            // Step 2: Fetch events
            val fetchResult = if (useIncremental) {
                fetchIncremental(calendarUrl, previousState.syncToken!!, callback)
            } else {
                fetchFull(calendarUrl, callback)
            }

            // Step 3: Process deletions from incremental sync
            val deleted = mutableListOf<String>()
            for (deletedHref in fetchResult.deleted) {
                // Find importId from urlMap and delete
                val importId = previousState.urlMap.entries
                    .find { it.value == deletedHref }?.key
                if (importId != null) {
                    handler.deleteEvent(importId)
                    deleted.add(importId)
                }
            }

            // Step 4: Fetch events for addedHrefs (iCloud returns hrefs without data)
            val additionalEvents = if (fetchResult.addedHrefs.isNotEmpty()) {
                callback?.onProgress("Fetching ${fetchResult.addedHrefs.size} additional events...", 40, 100)
                val hrefs = fetchResult.addedHrefs.map { it.href }
                calDavClient.fetchEventsByHref(calendarUrl, hrefs).getOrNull() ?: emptyList()
            } else {
                emptyList()
            }

            val serverEvents = fetchResult.added + additionalEvents
            callback?.onProgress("Processing ${serverEvents.size} events...", 50, 100)

            // Step 5: Compare with local and determine changes
            val localEvents = localProvider.getLocalEvents(calendarUrl)
            val changes = computeChanges(serverEvents, localEvents, previousState, callback)

            // Step 6: Apply changes
            callback?.onProgress("Applying changes...", 80, 100)
            applyChanges(changes, handler)

            // Add pre-computed deletions
            val allDeleted = deleted + changes.deleted

            // Step 7: Get current ctag
            val currentCtag = calDavClient.getCtag(calendarUrl).getOrNull()

            // Step 8: Build new sync state
            val newState = buildNewState(
                calendarUrl,
                currentCtag,
                serverEvents,
                fetchResult.newSyncToken
            )
            handler.saveSyncState(newState)

            // Build report
            val report = SyncReport(
                upserted = changes.upserted,
                deleted = allDeleted,
                conflicts = changes.conflicts,
                serverEventCount = serverEvents.size,
                previousCtag = previousState.ctag,
                newCtag = currentCtag,
                syncToken = fetchResult.newSyncToken,
                isFullSync = fetchResult.isFullSync,
                durationMs = System.currentTimeMillis() - startTime,
                errors = emptyList()
            )

            callback?.onProgress("Sync complete", 100, 100)
            callback?.onSyncComplete(report)

            return report

        } catch (e: SyncException) {
            val error = SyncError(
                eventUid = null,
                message = e.message ?: "Sync error",
                type = SyncErrorType.UNKNOWN,
                exception = e
            )
            callback?.onSyncError(error)
            return SyncReport.empty().copy(
                durationMs = System.currentTimeMillis() - startTime,
                errors = listOf(error)
            )
        } catch (e: Exception) {
            val error = SyncError(
                eventUid = null,
                message = e.message ?: "Unknown error",
                type = SyncErrorType.UNKNOWN,
                exception = e
            )
            callback?.onSyncError(error)
            return SyncReport.empty().copy(
                durationMs = System.currentTimeMillis() - startTime,
                errors = listOf(error)
            )
        }
    }

    /**
     * Fetch events incrementally using sync-collection REPORT.
     */
    private fun fetchIncremental(
        calendarUrl: String,
        syncToken: String,
        callback: SyncCallback?
    ): IncrementalFetchResult {
        callback?.onProgress("Performing incremental sync...", 20, 100)

        val result = calDavClient.syncCollection(calendarUrl, syncToken)

        return when (result) {
            is DavResult.Success -> {
                val syncResult = result.value

                // If empty response with no new token, might be invalid - fall back
                if (syncResult.added.isEmpty() &&
                    syncResult.deleted.isEmpty() &&
                    syncResult.addedHrefs.isEmpty() &&
                    syncResult.newSyncToken.isEmpty()
                ) {
                    callback?.onProgress("Sync token invalid, performing full sync...", 20, 100)
                    return fetchFull(calendarUrl, callback)
                }

                IncrementalFetchResult(
                    added = syncResult.added,
                    deleted = syncResult.deleted,
                    addedHrefs = syncResult.addedHrefs,
                    newSyncToken = syncResult.newSyncToken.ifEmpty { null },
                    isFullSync = false
                )
            }
            is DavResult.HttpError -> {
                if (result.code == 403) {
                    // Sync token invalid - fall back to full sync
                    callback?.onProgress("Sync token expired, performing full sync...", 20, 100)
                    fetchFull(calendarUrl, callback)
                } else {
                    throw SyncException("Incremental sync failed: HTTP ${result.code}: ${result.message}")
                }
            }
            is DavResult.NetworkError -> {
                throw SyncException("Network error during sync: ${result.exception.message}")
            }
            is DavResult.ParseError -> {
                throw SyncException("Parse error during sync: ${result.message}")
            }
        }
    }

    /**
     * Fetch all events for full sync.
     */
    private fun fetchFull(
        calendarUrl: String,
        callback: SyncCallback?
    ): IncrementalFetchResult {
        callback?.onProgress("Fetching all events...", 20, 100)

        val result = calDavClient.fetchEvents(calendarUrl)

        return when (result) {
            is DavResult.Success -> {
                // Get sync token for future incremental syncs
                val syncTokenResult = calDavClient.syncCollection(calendarUrl, "")
                val newToken = (syncTokenResult as? DavResult.Success)?.value?.newSyncToken

                IncrementalFetchResult(
                    added = result.value,
                    deleted = emptyList(),  // Full sync doesn't know what was deleted
                    addedHrefs = emptyList(),
                    newSyncToken = newToken?.ifEmpty { null },
                    isFullSync = true
                )
            }
            is DavResult.HttpError -> {
                throw SyncException("Full sync failed: HTTP ${result.code}: ${result.message}")
            }
            is DavResult.NetworkError -> {
                throw SyncException("Network error during sync: ${result.exception.message}")
            }
            is DavResult.ParseError -> {
                throw SyncException("Parse error during sync: ${result.message}")
            }
        }
    }

    /**
     * Compute changes between server and local events.
     */
    private fun computeChanges(
        serverEvents: List<EventWithMetadata>,
        localEvents: List<ICalEvent>,
        previousState: SyncState,
        callback: SyncCallback?
    ): ChangeSet {
        val upserted = mutableListOf<ICalEvent>()
        val deleted = mutableListOf<String>()
        val conflicts = mutableListOf<SyncConflict>()
        val urlMap = mutableMapOf<String, String>()

        // Index local events by importId
        val localByImportId = localEvents.associateBy { it.importId }

        // Index server events by importId
        val serverByImportId = serverEvents.associateBy { it.event.importId }

        // Process server events
        for (serverEvent in serverEvents) {
            val event = serverEvent.event
            val importId = event.importId
            val localEvent = localByImportId[importId]

            urlMap[importId] = serverEvent.href

            if (localEvent == null) {
                // New event from server
                upserted.add(event)
            } else {
                // Event exists locally - check for conflicts
                val oldEtag = previousState.getEtag(serverEvent.href)
                val serverChanged = oldEtag != null && oldEtag != serverEvent.etag

                if (serverChanged) {
                    // Server was modified - check if local was also modified
                    val localChanged = hasLocalChanges(localEvent, event)

                    if (localChanged) {
                        // Conflict - both modified
                        val conflict = SyncConflict(localEvent, event, ConflictType.BOTH_MODIFIED)
                        val resolution = callback?.onConflict(conflict) ?: ConflictResolution.USE_REMOTE

                        when (resolution) {
                            ConflictResolution.USE_REMOTE -> upserted.add(event)
                            ConflictResolution.USE_LOCAL -> { /* Keep local, don't update */ }
                            ConflictResolution.KEEP_BOTH -> {
                                // Create copy with new UID for local version
                                upserted.add(event)
                                conflicts.add(conflict)
                            }
                            ConflictResolution.SKIP -> conflicts.add(conflict)
                        }
                    } else {
                        // Only server changed - safe to update
                        upserted.add(event)
                    }
                } else {
                    // No changes or first sync - use server version
                    upserted.add(event)
                }
            }
        }

        // Find deleted events (present locally but not on server)
        for (localEvent in localEvents) {
            if (localEvent.importId !in serverByImportId) {
                deleted.add(localEvent.importId)
            }
        }

        return ChangeSet(upserted, deleted, conflicts, urlMap)
    }

    /**
     * Check if local event differs from server event.
     */
    private fun hasLocalChanges(local: ICalEvent, server: ICalEvent): Boolean {
        // Compare key fields
        return local.summary != server.summary ||
                local.description != server.description ||
                local.location != server.location ||
                local.dtStart.timestamp != server.dtStart.timestamp ||
                local.dtEnd?.timestamp != server.dtEnd?.timestamp ||
                local.rrule?.toICalString() != server.rrule?.toICalString()
    }

    /**
     * Apply changes through the handler.
     */
    private fun applyChanges(changes: ChangeSet, handler: SyncResultHandler) {
        // Upsert events
        for (event in changes.upserted) {
            val url = changes.urlMap[event.importId] ?: continue
            handler.upsertEvent(event, url, null)  // ETag will be tracked in state
        }

        // Delete events
        for (importId in changes.deleted) {
            handler.deleteEvent(importId)
        }
    }

    /**
     * Build new sync state from results.
     */
    private fun buildNewState(
        calendarUrl: String,
        ctag: String?,
        serverEvents: List<EventWithMetadata>,
        syncToken: String? = null
    ): SyncState {
        val etags = serverEvents
            .filter { it.etag != null }
            .associate { it.href to it.etag!! }

        val urlMap = serverEvents
            .associate { it.event.importId to it.href }

        return SyncState(
            calendarUrl = calendarUrl,
            ctag = ctag,
            syncToken = syncToken,
            etags = etags,
            urlMap = urlMap,
            lastSync = System.currentTimeMillis()
        )
    }

    /**
     * Handle DAV error result.
     */
    private fun handleDavError(
        result: DavResult<*>,
        previousState: SyncState,
        startTime: Long
    ): SyncReport {
        val error = when (result) {
            is DavResult.HttpError -> SyncError(
                eventUid = null,
                message = "HTTP ${result.code}: ${result.message}",
                type = if (result.code == 401) SyncErrorType.AUTHENTICATION else SyncErrorType.SERVER_ERROR
            )
            is DavResult.NetworkError -> SyncError(
                eventUid = null,
                message = result.exception.message ?: "Network error",
                type = SyncErrorType.NETWORK,
                exception = result.exception
            )
            is DavResult.ParseError -> SyncError(
                eventUid = null,
                message = result.message,
                type = SyncErrorType.PARSE
            )
            else -> SyncError(
                eventUid = null,
                message = "Unknown error",
                type = SyncErrorType.UNKNOWN
            )
        }

        return SyncReport.empty().copy(
            previousCtag = previousState.ctag,
            durationMs = System.currentTimeMillis() - startTime,
            errors = listOf(error)
        )
    }

    /**
     * Internal change set.
     */
    private data class ChangeSet(
        val upserted: List<ICalEvent>,
        val deleted: List<String>,
        val conflicts: List<SyncConflict>,
        val urlMap: Map<String, String>
    )
}

/**
 * Exception thrown during sync operations.
 */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
