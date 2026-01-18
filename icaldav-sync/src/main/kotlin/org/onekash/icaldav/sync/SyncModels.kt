package org.onekash.icaldav.sync

import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.model.ICalEvent

/**
 * Result of a sync operation.
 */
data class SyncReport(
    /** Events added or updated */
    val upserted: List<ICalEvent>,

    /** Events deleted (by importId) */
    val deleted: List<String>,

    /** Events with conflicts (local and remote both modified) */
    val conflicts: List<SyncConflict>,

    /** Number of events fetched from server */
    val serverEventCount: Int,

    /** Previous ctag before sync */
    val previousCtag: String?,

    /** New ctag after sync */
    val newCtag: String?,

    /** Sync token for incremental sync */
    val syncToken: String?,

    /** Whether this was a full sync or incremental */
    val isFullSync: Boolean,

    /** Duration of sync in milliseconds */
    val durationMs: Long,

    /** Any errors that occurred */
    val errors: List<SyncError>
) {
    val totalChanges: Int get() = upserted.size + deleted.size
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val success: Boolean get() = !hasErrors

    companion object {
        fun empty() = SyncReport(
            upserted = emptyList(),
            deleted = emptyList(),
            conflicts = emptyList(),
            serverEventCount = 0,
            previousCtag = null,
            newCtag = null,
            syncToken = null,
            isFullSync = false,
            durationMs = 0,
            errors = emptyList()
        )
    }
}

/**
 * Conflict between local and remote event modifications.
 */
data class SyncConflict(
    /** The local version of the event */
    val localEvent: ICalEvent,

    /** The remote version of the event */
    val remoteEvent: ICalEvent,

    /** Type of conflict */
    val type: ConflictType
)

/**
 * Types of sync conflicts.
 */
enum class ConflictType {
    /** Both local and remote were modified */
    BOTH_MODIFIED,

    /** Local was deleted but remote was modified */
    DELETE_UPDATE,

    /** Local was modified but remote was deleted */
    UPDATE_DELETE,

    /** Both created same UID (rare) */
    DUPLICATE_UID
}

/**
 * Error that occurred during sync.
 */
data class SyncError(
    val eventUid: String?,
    val message: String,
    val type: SyncErrorType,
    val exception: Exception? = null
)

/**
 * Types of sync errors.
 */
enum class SyncErrorType {
    NETWORK,
    AUTHENTICATION,
    PARSE,
    CONFLICT,
    SERVER_ERROR,
    UNKNOWN
}

/**
 * Callback interface for sync progress and conflict resolution.
 */
interface SyncCallback {
    /**
     * Called when sync starts.
     */
    fun onSyncStarted(calendarUrl: String) {}

    /**
     * Called with progress updates.
     */
    fun onProgress(message: String, current: Int, total: Int) {}

    /**
     * Called when a conflict needs resolution.
     *
     * @return Resolution strategy to apply
     */
    fun onConflict(conflict: SyncConflict): ConflictResolution = ConflictResolution.USE_REMOTE

    /**
     * Called when sync completes.
     */
    fun onSyncComplete(report: SyncReport) {}

    /**
     * Called when sync fails.
     */
    fun onSyncError(error: SyncError) {}
}

/**
 * Strategy for resolving conflicts.
 */
enum class ConflictResolution {
    /** Use the remote (server) version */
    USE_REMOTE,

    /** Use the local version */
    USE_LOCAL,

    /** Keep both as separate events */
    KEEP_BOTH,

    /** Skip this event */
    SKIP
}

/**
 * State stored between syncs.
 */
data class SyncState(
    /** Calendar URL */
    val calendarUrl: String,

    /** Last known ctag */
    val ctag: String?,

    /** Sync token for incremental sync */
    val syncToken: String?,

    /** Map of event URL to ETag */
    val etags: Map<String, String>,

    /** Map of importId to event URL */
    val urlMap: Map<String, String>,

    /** Last sync timestamp */
    val lastSync: Long
) {
    fun getEtag(url: String): String? = etags[url]
    fun getUrl(importId: String): String? = urlMap[importId]

    companion object {
        fun initial(calendarUrl: String) = SyncState(
            calendarUrl = calendarUrl,
            ctag = null,
            syncToken = null,
            etags = emptyMap(),
            urlMap = emptyMap(),
            lastSync = 0
        )
    }
}

/**
 * Provider for local event data.
 */
interface LocalEventProvider {
    /**
     * Get all local events for a calendar.
     */
    fun getLocalEvents(calendarUrl: String): List<ICalEvent>

    /**
     * Get local event by importId.
     */
    fun getEventByImportId(importId: String): ICalEvent?

    /**
     * Check if event exists locally.
     */
    fun hasEvent(importId: String): Boolean
}

/**
 * Receiver for sync results.
 */
interface SyncResultHandler {
    /**
     * Called for each event that should be upserted.
     */
    fun upsertEvent(event: ICalEvent, url: String, etag: String?)

    /**
     * Called for each event that should be deleted.
     */
    fun deleteEvent(importId: String)

    /**
     * Save sync state for next sync.
     */
    fun saveSyncState(state: SyncState)
}
