package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.model.*
import org.onekash.icaldav.sync.*
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

@DisplayName("ConflictResolver Tests")
class ConflictResolverTest {

    private lateinit var client: CalDavClient
    private lateinit var localProvider: LocalEventProvider
    private lateinit var resultHandler: SyncResultHandler
    private lateinit var pendingStore: InMemoryPendingOperationStore
    private lateinit var conflictResolver: ConflictResolver

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        client = mock()
        localProvider = mock()
        resultHandler = mock()
        pendingStore = InMemoryPendingOperationStore()
        conflictResolver = ConflictResolver(client, localProvider, resultHandler, pendingStore)
    }

    @Nested
    @DisplayName("SERVER_WINS Strategy")
    inner class ServerWinsTests {

        @Test
        fun `fetches and applies server version`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val serverEvent = createEventWithMetadata("event1", "Server Version", sequence = 2)
            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.Success(serverEvent))

            val result = conflictResolver.resolve(op, ConflictStrategy.SERVER_WINS)

            assertTrue(result is ConflictResult.ServerVersionKept)
            assertEquals(0, pendingStore.count())

            verify(resultHandler).upsertEvent(
                eq(serverEvent.event),
                eq(serverEvent.href),
                eq(serverEvent.etag)
            )
        }

        @Test
        fun `deletes local when server returns 404`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.HttpError(404, "Not Found"))

            val result = conflictResolver.resolve(op, ConflictStrategy.SERVER_WINS)

            assertTrue(result is ConflictResult.LocalDeleted)
            assertEquals(0, pendingStore.count())

            verify(resultHandler).deleteEvent("event1")
        }

        @Test
        fun `returns error when fetch fails`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.HttpError(500, "Server Error"))

            val result = conflictResolver.resolve(op, ConflictStrategy.SERVER_WINS)

            assertTrue(result is ConflictResult.Error)
            assertEquals(1, pendingStore.count()) // Operation not removed
        }
    }

    @Nested
    @DisplayName("NEWEST_WINS Strategy")
    inner class NewestWinsTests {

        @Test
        fun `server wins when server sequence is higher`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val localEvent = createEvent("event1", "Local", sequence = 1)
            val serverEvent = createEventWithMetadata("event1", "Server", sequence = 2)

            whenever(localProvider.getEventByImportId("event1")).thenReturn(localEvent)
            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.Success(serverEvent))

            val result = conflictResolver.resolve(op, ConflictStrategy.NEWEST_WINS)

            assertTrue(result is ConflictResult.ServerVersionKept)
            assertEquals(0, pendingStore.count())
        }

        @Test
        fun `local wins when local sequence is higher`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val localEvent = createEvent("event1", "Local", sequence = 3)
            val serverEvent = createEventWithMetadata("event1", "Server", sequence = 1)

            whenever(localProvider.getEventByImportId("event1")).thenReturn(localEvent)
            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.Success(serverEvent))

            val result = conflictResolver.resolve(op, ConflictStrategy.NEWEST_WINS)

            assertTrue(result is ConflictResult.LocalVersionPushed)
            assertEquals(1, pendingStore.count())

            // Operation should be reset for retry
            val resetOp = pendingStore.getByEventUid("event1")
            assertEquals(0, resetOp?.retryCount)
            assertEquals(OperationStatus.PENDING, resetOp?.status)
        }

        @Test
        fun `uses timestamp when sequences are equal`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val now = System.currentTimeMillis()
            val localEvent = createEvent("event1", "Local", sequence = 1, dtstamp = now - 1000)
            val serverEvent = createEventWithMetadata("event1", "Server", sequence = 1, dtstamp = now)

            whenever(localProvider.getEventByImportId("event1")).thenReturn(localEvent)
            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.Success(serverEvent))

            val result = conflictResolver.resolve(op, ConflictStrategy.NEWEST_WINS)

            // Server has newer timestamp, so server wins
            assertTrue(result is ConflictResult.ServerVersionKept)
        }

        @Test
        fun `returns error when local event not found`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val serverEvent = createEventWithMetadata("event1", "Server", sequence = 1)

            whenever(localProvider.getEventByImportId("event1")).thenReturn(null)
            whenever(client.getEvent("$calendarUrl/event1.ics"))
                .thenReturn(DavResult.Success(serverEvent))

            val result = conflictResolver.resolve(op, ConflictStrategy.NEWEST_WINS)

            assertTrue(result is ConflictResult.Error)
        }
    }

    @Nested
    @DisplayName("LOCAL_WINS Strategy")
    inner class LocalWinsTests {

        @Test
        fun `force deletes for DELETE operations`() = runTest {
            val op = createPendingOperation(
                "event1",
                "$calendarUrl/event1.ics",
                operation = OperationType.DELETE
            )
            pendingStore.enqueue(op)

            whenever(client.deleteEvent("$calendarUrl/event1.ics", null))
                .thenReturn(DavResult.Success(Unit))

            val result = conflictResolver.resolve(op, ConflictStrategy.LOCAL_WINS)

            assertTrue(result is ConflictResult.LocalVersionPushed)
            assertEquals(0, pendingStore.count())

            // Should call deleteEvent without etag (force delete)
            verify(client).deleteEvent("$calendarUrl/event1.ics", null)
        }

        @Test
        fun `returns error for UPDATE operations`() = runTest {
            val op = createPendingOperation(
                "event1",
                "$calendarUrl/event1.ics",
                operation = OperationType.UPDATE
            )
            pendingStore.enqueue(op)

            val result = conflictResolver.resolve(op, ConflictStrategy.LOCAL_WINS)

            assertTrue(result is ConflictResult.Error)
            assertTrue((result as ConflictResult.Error).message.contains("not supported"))
        }
    }

    @Nested
    @DisplayName("MANUAL Strategy")
    inner class ManualStrategyTests {

        @Test
        fun `marks operation for manual resolution`() = runTest {
            val op = createPendingOperation("event1", "$calendarUrl/event1.ics")
            pendingStore.enqueue(op)

            val result = conflictResolver.resolve(op, ConflictStrategy.MANUAL)

            assertTrue(result is ConflictResult.MarkedForManualResolution)
            assertEquals(1, pendingStore.count())

            val markedOp = pendingStore.getByEventUid("event1")
            assertEquals(OperationStatus.FAILED, markedOp?.status)
            assertTrue(markedOp?.errorMessage?.contains("manual resolution") == true)
        }
    }

    private fun createPendingOperation(
        eventUid: String,
        eventUrl: String,
        operation: OperationType = OperationType.UPDATE
    ): PendingOperation {
        return PendingOperation(
            id = "op-$eventUid",
            calendarUrl = calendarUrl,
            eventUid = eventUid,
            eventUrl = eventUrl,
            operation = operation,
            status = OperationStatus.PENDING,
            icalData = "BEGIN:VCALENDAR...",
            etag = "old-etag"
        )
    }

    private fun createEvent(
        uid: String,
        summary: String,
        sequence: Int = 0,
        dtstamp: Long? = null
    ): ICalEvent {
        val now = System.currentTimeMillis()
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime(now, null, true, false),
            dtEnd = ICalDateTime(now + 3600000, null, true, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = sequence,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = dtstamp?.let { ICalDateTime(it, null, false, false) },
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun createEventWithMetadata(
        uid: String,
        summary: String,
        sequence: Int = 0,
        dtstamp: Long? = null
    ): EventWithMetadata {
        return EventWithMetadata(
            href = "$calendarUrl/$uid.ics",
            etag = "server-etag",
            event = createEvent(uid, summary, sequence, dtstamp)
        )
    }
}
