package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.sync.OperationStatus
import org.onekash.icaldav.sync.OperationType
import org.onekash.icaldav.sync.PendingOperation
import org.onekash.icaldav.sync.PendingOperationStore
import org.onekash.icaldav.sync.errorMessage
import org.onekash.icaldav.sync.isRetryable
import org.onekash.icaldav.model.DavResult

/**
 * Handles pushing pending local operations to the CalDAV server.
 *
 * Processes CREATE, UPDATE, and DELETE operations from the pending queue.
 * Failed operations are retried with exponential backoff.
 */
class PushStrategy(
    private val client: CalDavClient,
    private val pendingStore: PendingOperationStore
) {
    /**
     * Push all ready operations to the server.
     *
     * @return Result summarizing pushed operations
     */
    suspend fun pushAll(): PushResult {
        val readyOps = pendingStore.getReadyOperations(System.currentTimeMillis())

        if (readyOps.isEmpty()) {
            return PushResult.NoPendingOperations
        }

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        var conflicts = 0

        for (op in readyOps) {
            pendingStore.markInProgress(op.id, System.currentTimeMillis())

            when (val result = processOperation(op)) {
                is SinglePushResult.Success -> {
                    when (op.operation) {
                        OperationType.CREATE -> created++
                        OperationType.UPDATE -> updated++
                        OperationType.DELETE -> { /* counted below */ }
                    }
                    pendingStore.delete(op.id)
                }
                is SinglePushResult.Deleted -> {
                    deleted++
                    pendingStore.delete(op.id)
                }
                is SinglePushResult.Conflict -> {
                    conflicts++
                    // Mark as failed with conflict message - caller should resolve
                    pendingStore.markFailed(op.id, "Conflict: ${result.serverMessage}", System.currentTimeMillis())
                }
                is SinglePushResult.Error -> {
                    failed++
                    if (result.isRetryable && op.shouldRetry) {
                        pendingStore.markFailed(op.id, result.message, System.currentTimeMillis())
                    } else {
                        // Max retries exceeded or non-retryable error
                        pendingStore.markFailed(op.id, "Permanent failure: ${result.message}", System.currentTimeMillis())
                    }
                }
            }
        }

        return PushResult.Success(
            created = created,
            updated = updated,
            deleted = deleted,
            failed = failed,
            conflicts = conflicts
        )
    }

    /**
     * Push only operations for a specific calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @return Result summarizing pushed operations
     */
    suspend fun pushForCalendar(calendarUrl: String): PushResult {
        val calendarOps = pendingStore.getOperationsForCalendar(calendarUrl)
            .filter { op ->
                val now = System.currentTimeMillis()
                (op.status == OperationStatus.PENDING && op.nextRetryAt <= now) ||
                (op.status == OperationStatus.FAILED && op.shouldRetry && op.nextRetryAt <= now)
            }

        if (calendarOps.isEmpty()) {
            return PushResult.NoPendingOperations
        }

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        var conflicts = 0

        for (op in calendarOps) {
            pendingStore.markInProgress(op.id, System.currentTimeMillis())

            when (val result = processOperation(op)) {
                is SinglePushResult.Success -> {
                    when (op.operation) {
                        OperationType.CREATE -> created++
                        OperationType.UPDATE -> updated++
                        OperationType.DELETE -> { /* counted below */ }
                    }
                    pendingStore.delete(op.id)
                }
                is SinglePushResult.Deleted -> {
                    deleted++
                    pendingStore.delete(op.id)
                }
                is SinglePushResult.Conflict -> {
                    conflicts++
                    pendingStore.markFailed(op.id, "Conflict: ${result.serverMessage}", System.currentTimeMillis())
                }
                is SinglePushResult.Error -> {
                    failed++
                    if (result.isRetryable && op.shouldRetry) {
                        pendingStore.markFailed(op.id, result.message, System.currentTimeMillis())
                    } else {
                        pendingStore.markFailed(op.id, "Permanent failure: ${result.message}", System.currentTimeMillis())
                    }
                }
            }
        }

        return PushResult.Success(
            created = created,
            updated = updated,
            deleted = deleted,
            failed = failed,
            conflicts = conflicts
        )
    }

    /**
     * Process a single operation.
     */
    private fun processOperation(op: PendingOperation): SinglePushResult {
        return when (op.operation) {
            OperationType.CREATE -> pushCreate(op)
            OperationType.UPDATE -> pushUpdate(op)
            OperationType.DELETE -> pushDelete(op)
        }
    }

    /**
     * Push a CREATE operation.
     *
     * Uses If-None-Match: * to fail if event already exists (conflict detection).
     */
    private fun pushCreate(op: PendingOperation): SinglePushResult {
        val result = client.createEventRaw(op.calendarUrl, op.eventUid, op.icalData)

        return when (result) {
            is DavResult.Success -> {
                SinglePushResult.Success(result.value.href, result.value.etag)
            }
            is DavResult.HttpError -> when (result.code) {
                412 -> SinglePushResult.Conflict("Event already exists")
                else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
            }
            else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
        }
    }

    /**
     * Push an UPDATE operation.
     *
     * Uses If-Match with ETag for conflict detection.
     */
    private fun pushUpdate(op: PendingOperation): SinglePushResult {
        val url = op.eventUrl ?: return SinglePushResult.Error("No URL for update", false)

        val result = client.updateEventRaw(url, op.icalData, op.etag)

        return when (result) {
            is DavResult.Success -> SinglePushResult.Success(url, result.value)
            is DavResult.HttpError -> when (result.code) {
                412 -> SinglePushResult.Conflict("ETag mismatch - event was modified")
                404 -> SinglePushResult.Conflict("Event no longer exists on server")
                else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
            }
            else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
        }
    }

    /**
     * Push a DELETE operation.
     *
     * Uses If-Match with ETag for conflict detection.
     * Treats 404 as success (already deleted).
     */
    private fun pushDelete(op: PendingOperation): SinglePushResult {
        val url = op.eventUrl ?: return SinglePushResult.Error("No URL for delete", false)

        val result = client.deleteEvent(url, op.etag)

        return when (result) {
            is DavResult.Success -> SinglePushResult.Deleted
            is DavResult.HttpError -> when (result.code) {
                404 -> SinglePushResult.Deleted  // Already gone
                412 -> SinglePushResult.Conflict("ETag mismatch - event was modified")
                else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
            }
            else -> SinglePushResult.Error(result.errorMessage(), result.isRetryable())
        }
    }
}

/**
 * Result of pushing all pending operations.
 */
sealed class PushResult {
    data class Success(
        val created: Int,
        val updated: Int,
        val deleted: Int,
        val failed: Int,
        val conflicts: Int
    ) : PushResult() {
        val totalProcessed: Int get() = created + updated + deleted + failed + conflicts
    }

    object NoPendingOperations : PushResult()

    data class Error(val message: String) : PushResult()
}

/**
 * Result of pushing a single operation.
 */
sealed class SinglePushResult {
    /** Successful CREATE or UPDATE - returns URL and new ETag */
    data class Success(val url: String, val etag: String?) : SinglePushResult()

    /** Successful DELETE */
    object Deleted : SinglePushResult()

    /** Server has different version - conflict detected */
    data class Conflict(val serverMessage: String) : SinglePushResult()

    /** Operation failed */
    data class Error(val message: String, val isRetryable: Boolean) : SinglePushResult()
}
