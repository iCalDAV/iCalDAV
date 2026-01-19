package org.onekash.icaldav.sync

import org.onekash.icaldav.sync.OperationStatus
import org.onekash.icaldav.sync.PendingOperation
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of PendingOperationStore.
 *
 * Suitable for testing and simple single-process use cases.
 * For production Android apps, implement PendingOperationStore with Room.
 */
class InMemoryPendingOperationStore : PendingOperationStore {
    private val operations = ConcurrentHashMap<String, PendingOperation>()

    override suspend fun getReadyOperations(now: Long): List<PendingOperation> {
        return operations.values.filter { op ->
            (op.status == OperationStatus.PENDING && op.nextRetryAt <= now) ||
            (op.status == OperationStatus.FAILED && op.shouldRetry && op.nextRetryAt <= now)
        }.sortedBy { it.createdAt }
    }

    override suspend fun getOperationsForCalendar(calendarUrl: String): List<PendingOperation> {
        return operations.values.filter { it.calendarUrl == calendarUrl }
            .sortedBy { it.createdAt }
    }

    override suspend fun getByEventUid(eventUid: String): PendingOperation? {
        return operations.values.find { it.eventUid == eventUid }
    }

    override suspend fun enqueue(op: PendingOperation): String {
        operations[op.id] = op
        return op.id
    }

    override suspend fun update(op: PendingOperation) {
        operations[op.id] = op
    }

    override suspend fun delete(id: String) {
        operations.remove(id)
    }

    override suspend fun markInProgress(id: String, timestamp: Long) {
        operations.computeIfPresent(id) { _, op ->
            op.copy(status = OperationStatus.IN_PROGRESS)
        }
    }

    override suspend fun markFailed(id: String, error: String, timestamp: Long) {
        operations.computeIfPresent(id) { _, op ->
            val newRetryCount = op.retryCount + 1
            // Calculate backoff using NEW retry count
            val backoff = PendingOperation.INITIAL_BACKOFF_MS *
                Math.pow(PendingOperation.BACKOFF_MULTIPLIER, newRetryCount.toDouble())
            val nextRetry = timestamp + backoff.toLong()
                .coerceIn(PendingOperation.INITIAL_BACKOFF_MS, PendingOperation.MAX_BACKOFF_MS)

            op.copy(
                status = OperationStatus.FAILED,
                errorMessage = error,
                retryCount = newRetryCount,
                nextRetryAt = nextRetry
            )
        }
    }

    override suspend fun count(): Int = operations.size

    /** Clear all operations (for test setup/teardown) */
    fun clear() = operations.clear()
}
