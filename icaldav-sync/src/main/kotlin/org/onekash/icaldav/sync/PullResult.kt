package org.onekash.icaldav.sync

/**
 * Result of a pull operation.
 *
 * Summarizes changes from a sync without exposing full event data.
 */
data class PullResult(
    /** Number of events added or updated */
    val upserted: Int,

    /** Number of events deleted */
    val deleted: Int,

    /** Number of conflicts encountered */
    val conflicts: Int,

    /** New ctag after sync */
    val newCtag: String?,

    /** New sync token for incremental sync */
    val newSyncToken: String?,

    /** Whether this was a full sync */
    val isFullSync: Boolean,

    /** Whether sync completed successfully */
    val success: Boolean,

    /** Any errors that occurred */
    val errors: List<SyncError>
) {
    companion object {
        /**
         * Create PullResult from a SyncReport.
         */
        fun fromReport(report: SyncReport): PullResult = PullResult(
            upserted = report.upserted.size,
            deleted = report.deleted.size,
            conflicts = report.conflicts.size,
            newCtag = report.newCtag,
            newSyncToken = report.syncToken,
            isFullSync = report.isFullSync,
            success = report.success,
            errors = report.errors
        )
    }
}
