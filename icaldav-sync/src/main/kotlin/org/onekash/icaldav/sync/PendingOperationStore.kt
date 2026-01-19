package org.onekash.icaldav.sync

import org.onekash.icaldav.sync.PendingOperation

/**
 * Storage interface for pending sync operations.
 *
 * Implementations can use any storage mechanism (in-memory, database, file-based).
 * All methods are suspend functions to support async storage implementations.
 */
interface PendingOperationStore {
    /**
     * Get all operations ready to be processed.
     *
     * Returns operations that are:
     * - PENDING with nextRetryAt <= now
     * - FAILED with shouldRetry=true and nextRetryAt <= now
     *
     * @param now Current timestamp (defaults to System.currentTimeMillis())
     * @return List of operations ready for processing, sorted by createdAt
     */
    suspend fun getReadyOperations(now: Long = System.currentTimeMillis()): List<PendingOperation>

    /**
     * Get all operations for a specific calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @return List of operations for this calendar, sorted by createdAt
     */
    suspend fun getOperationsForCalendar(calendarUrl: String): List<PendingOperation>

    /**
     * Get a pending operation by event UID.
     *
     * Used for operation coalescing (e.g., CREATE+UPDATE → CREATE with updated data).
     *
     * @param eventUid Event UID to search for
     * @return The pending operation, or null if none exists
     */
    suspend fun getByEventUid(eventUid: String): PendingOperation?

    /**
     * Add a new operation to the queue.
     *
     * @param op Operation to enqueue
     * @return The operation ID
     */
    suspend fun enqueue(op: PendingOperation): String

    /**
     * Update an existing operation.
     *
     * @param op Operation with updated values
     */
    suspend fun update(op: PendingOperation)

    /**
     * Delete an operation from the queue.
     *
     * @param id Operation ID to delete
     */
    suspend fun delete(id: String)

    /**
     * Mark an operation as in-progress.
     *
     * @param id Operation ID
     * @param timestamp Current timestamp
     */
    suspend fun markInProgress(id: String, timestamp: Long)

    /**
     * Mark an operation as failed and schedule retry.
     *
     * Increments retryCount and calculates next retry time with exponential backoff.
     *
     * @param id Operation ID
     * @param error Error message
     * @param timestamp Current timestamp
     */
    suspend fun markFailed(id: String, error: String, timestamp: Long)

    /**
     * Get total count of pending operations.
     *
     * @return Number of operations in the queue
     */
    suspend fun count(): Int
}
