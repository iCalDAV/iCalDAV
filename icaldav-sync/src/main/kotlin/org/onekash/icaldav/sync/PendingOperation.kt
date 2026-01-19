package org.onekash.icaldav.sync

/**
 * Represents a pending sync operation queued for server push.
 *
 * Operations are stored locally and pushed to the server when connectivity
 * is available. Failed operations are retried with exponential backoff.
 */
data class PendingOperation(
    /** Unique identifier for this operation */
    val id: String,

    /** Calendar collection URL this operation belongs to */
    val calendarUrl: String,

    /** Event UID */
    val eventUid: String,

    /** Full CalDAV URL for the event (null for CREATE operations) */
    val eventUrl: String?,

    /** Type of operation to perform */
    val operation: OperationType,

    /** Current status of the operation */
    val status: OperationStatus,

    /** Serialized iCalendar data for CREATE/UPDATE operations */
    val icalData: String,

    /** ETag for conflict detection (UPDATE/DELETE operations) */
    val etag: String?,

    /** Number of retry attempts so far */
    val retryCount: Int = 0,

    /** Timestamp when this operation can be retried */
    val nextRetryAt: Long = 0,

    /** Error message from last failed attempt */
    val errorMessage: String? = null,

    /** Timestamp when this operation was created */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Maximum number of retry attempts before giving up */
        const val MAX_RETRIES = 5

        /** Initial backoff delay (1 minute) */
        const val INITIAL_BACKOFF_MS = 60_000L

        /** Maximum backoff delay (1 hour) */
        const val MAX_BACKOFF_MS = 3_600_000L

        /** Multiplier for exponential backoff */
        const val BACKOFF_MULTIPLIER = 2.0
    }

    /** Whether this operation should be retried */
    val shouldRetry: Boolean get() = retryCount < MAX_RETRIES

    /**
     * Calculate next retry timestamp using exponential backoff.
     * Note: Uses current retryCount, so call this BEFORE incrementing.
     */
    fun calculateNextRetry(): Long {
        val backoff = INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount.toDouble())
        return System.currentTimeMillis() + backoff.toLong().coerceIn(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS)
    }
}

/**
 * Type of sync operation.
 */
enum class OperationType {
    /** Create a new event on the server */
    CREATE,

    /** Update an existing event on the server */
    UPDATE,

    /** Delete an event from the server */
    DELETE
}

/**
 * Status of a pending operation.
 */
enum class OperationStatus {
    /** Waiting to be processed */
    PENDING,

    /** Currently being processed */
    IN_PROGRESS,

    /** Failed and waiting for retry */
    FAILED
}
