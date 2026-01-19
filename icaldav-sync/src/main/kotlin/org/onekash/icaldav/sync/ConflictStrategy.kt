package org.onekash.icaldav.sync

/**
 * Strategy for resolving conflicts between local and server versions.
 *
 * Used when a push operation fails with 412 (ETag mismatch) indicating
 * the server has a different version than expected.
 */
enum class ConflictStrategy {
    /**
     * Server version overwrites local.
     * Safest for shared calendars where others may have made changes.
     */
    SERVER_WINS,

    /**
     * Force push local version.
     * Can overwrite others' changes. Use with caution.
     */
    LOCAL_WINS,

    /**
     * Compare SEQUENCE numbers and timestamps.
     * Keep the version with higher SEQUENCE, or newer timestamp if equal.
     * Implements RFC 5545 conflict resolution semantics.
     */
    NEWEST_WINS,

    /**
     * Mark for user to resolve manually.
     * Keeps operation in failed state for user review.
     */
    MANUAL
}
