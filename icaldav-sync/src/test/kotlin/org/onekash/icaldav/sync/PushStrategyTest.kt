package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventCreateResult
import org.onekash.icaldav.sync.OperationStatus
import org.onekash.icaldav.sync.OperationType
import org.onekash.icaldav.sync.PendingOperation
import org.onekash.icaldav.sync.InMemoryPendingOperationStore
import org.onekash.icaldav.model.DavResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("PushStrategy Tests")
class PushStrategyTest {

    private lateinit var client: CalDavClient
    private lateinit var pendingStore: InMemoryPendingOperationStore
    private lateinit var pushStrategy: PushStrategy

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        client = mock()
        pendingStore = InMemoryPendingOperationStore()
        pushStrategy = PushStrategy(client, pendingStore)
    }

    @Nested
    @DisplayName("CREATE Operations")
    inner class CreateOperationTests {

        @Test
        fun `pushCreate sends PUT and deletes operation on success`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                operation = OperationType.CREATE,
                icalData = "BEGIN:VCALENDAR..."
            )
            pendingStore.enqueue(op)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.Success(EventCreateResult("$calendarUrl/event1.ics", "new-etag")))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).created)
            assertEquals(0, pendingStore.count())

            verify(client).createEventRaw(calendarUrl, "event1", "BEGIN:VCALENDAR...")
        }

        @Test
        fun `pushCreate handles 412 as conflict`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                operation = OperationType.CREATE
            )
            pendingStore.enqueue(op)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.HttpError(412, "Precondition Failed"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).conflicts)
            assertEquals(1, pendingStore.count())

            val failedOp = pendingStore.getByEventUid("event1")
            assertEquals(OperationStatus.FAILED, failedOp?.status)
            assertTrue(failedOp?.errorMessage?.contains("Conflict") == true)
        }
    }

    @Nested
    @DisplayName("UPDATE Operations")
    inner class UpdateOperationTests {

        @Test
        fun `pushUpdate sends PUT with ETag and deletes on success`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = "$calendarUrl/event1.ics",
                operation = OperationType.UPDATE,
                etag = "old-etag"
            )
            pendingStore.enqueue(op)

            whenever(client.updateEventRaw(any(), any(), anyOrNull()))
                .thenReturn(DavResult.Success("new-etag"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).updated)
            assertEquals(0, pendingStore.count())

            verify(client).updateEventRaw(eq("$calendarUrl/event1.ics"), any(), eq("old-etag"))
        }

        @Test
        fun `pushUpdate handles 412 as conflict`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = "$calendarUrl/event1.ics",
                operation = OperationType.UPDATE,
                etag = "old-etag"
            )
            pendingStore.enqueue(op)

            whenever(client.updateEventRaw(any(), any(), anyOrNull()))
                .thenReturn(DavResult.HttpError(412, "ETag mismatch"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).conflicts)
        }

        @Test
        fun `pushUpdate returns error when no URL`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = null,
                operation = OperationType.UPDATE
            )
            pendingStore.enqueue(op)

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).failed)
        }
    }

    @Nested
    @DisplayName("DELETE Operations")
    inner class DeleteOperationTests {

        @Test
        fun `pushDelete sends DELETE and removes operation on success`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = "$calendarUrl/event1.ics",
                operation = OperationType.DELETE,
                etag = "etag1"
            )
            pendingStore.enqueue(op)

            whenever(client.deleteEvent(any(), anyOrNull()))
                .thenReturn(DavResult.Success(Unit))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).deleted)
            assertEquals(0, pendingStore.count())

            verify(client).deleteEvent(eq("$calendarUrl/event1.ics"), eq("etag1"))
        }

        @Test
        fun `pushDelete treats 404 as success`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = "$calendarUrl/event1.ics",
                operation = OperationType.DELETE,
                etag = null
            )
            pendingStore.enqueue(op)

            whenever(client.deleteEvent(any(), anyOrNull()))
                .thenReturn(DavResult.HttpError(404, "Not Found"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).deleted)
            assertEquals(0, pendingStore.count())
        }

        @Test
        fun `pushDelete handles 412 as conflict`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                eventUrl = "$calendarUrl/event1.ics",
                operation = OperationType.DELETE,
                etag = "old-etag"
            )
            pendingStore.enqueue(op)

            whenever(client.deleteEvent(any(), anyOrNull()))
                .thenReturn(DavResult.HttpError(412, "ETag mismatch"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).conflicts)
        }
    }

    @Nested
    @DisplayName("Retry Logic")
    inner class RetryLogicTests {

        @Test
        fun `schedules retry for retryable errors`() = runTest {
            val op = createPendingOperation(
                eventUid = "event1",
                operation = OperationType.CREATE
            )
            pendingStore.enqueue(op)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.HttpError(500, "Server Error"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).failed)
            assertEquals(1, pendingStore.count())

            val failedOp = pendingStore.getByEventUid("event1")
            assertEquals(OperationStatus.FAILED, failedOp?.status)
            assertEquals(1, failedOp?.retryCount)
            assertTrue(failedOp?.nextRetryAt ?: 0 > System.currentTimeMillis())
        }

        @Test
        fun `marks non-retryable errors as permanent failure`() = runTest {
            // Create operation that's already at max retries
            val op = createPendingOperation(
                eventUid = "event1",
                operation = OperationType.CREATE,
                retryCount = PendingOperation.MAX_RETRIES
            )
            pendingStore.enqueue(op)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.HttpError(500, "Server Error"))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).failed)

            val failedOp = pendingStore.getByEventUid("event1")
            assertTrue(failedOp?.errorMessage?.contains("Permanent failure") == true)
        }
    }

    @Nested
    @DisplayName("Batch Processing")
    inner class BatchProcessingTests {

        @Test
        fun `returns NoPendingOperations when queue is empty`() = runTest {
            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.NoPendingOperations)
        }

        @Test
        fun `processes multiple operations`() = runTest {
            val op1 = createPendingOperation("event1", operation = OperationType.CREATE)
            val op2 = createPendingOperation("event2", operation = OperationType.CREATE)
            pendingStore.enqueue(op1)
            pendingStore.enqueue(op2)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.Success(EventCreateResult("url", "etag")))

            val result = pushStrategy.pushAll()

            assertTrue(result is PushResult.Success)
            assertEquals(2, (result as PushResult.Success).created)
            assertEquals(0, pendingStore.count())
        }

        @Test
        fun `pushForCalendar only processes matching calendar`() = runTest {
            val op1 = createPendingOperation("event1", calendarUrl = calendarUrl, operation = OperationType.CREATE)
            val op2 = createPendingOperation("event2", calendarUrl = "https://other.com/cal/", operation = OperationType.CREATE)
            pendingStore.enqueue(op1)
            pendingStore.enqueue(op2)

            whenever(client.createEventRaw(any(), any(), any()))
                .thenReturn(DavResult.Success(EventCreateResult("url", "etag")))

            val result = pushStrategy.pushForCalendar(calendarUrl)

            assertTrue(result is PushResult.Success)
            assertEquals(1, (result as PushResult.Success).created)
            assertEquals(1, pendingStore.count()) // Other calendar's op should remain
        }
    }

    private fun createPendingOperation(
        eventUid: String,
        calendarUrl: String = this.calendarUrl,
        eventUrl: String? = null,
        operation: OperationType = OperationType.CREATE,
        icalData: String = "BEGIN:VCALENDAR...",
        etag: String? = null,
        retryCount: Int = 0
    ): PendingOperation {
        return PendingOperation(
            id = "op-$eventUid",
            calendarUrl = calendarUrl,
            eventUid = eventUid,
            eventUrl = eventUrl,
            operation = operation,
            status = OperationStatus.PENDING,
            icalData = icalData,
            etag = etag,
            retryCount = retryCount,
            nextRetryAt = 0
        )
    }
}
