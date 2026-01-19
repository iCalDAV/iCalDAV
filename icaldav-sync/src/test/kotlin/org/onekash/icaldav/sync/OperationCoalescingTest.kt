package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.model.*
import org.onekash.icaldav.sync.*
import org.onekash.icaldav.sync.InMemoryPendingOperationStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Operation Coalescing Tests")
class OperationCoalescingTest {

    private lateinit var client: CalDavClient
    private lateinit var localProvider: LocalEventProvider
    private lateinit var resultHandler: SyncResultHandler
    private lateinit var pendingStore: InMemoryPendingOperationStore
    private lateinit var engine: CalDavSyncEngine

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        client = mock()
        localProvider = mock()
        resultHandler = mock()
        pendingStore = InMemoryPendingOperationStore()
        engine = CalDavSyncEngine(
            client = client,
            localProvider = localProvider,
            resultHandler = resultHandler,
            pendingStore = pendingStore
        )
    }

    @Nested
    @DisplayName("CREATE + UPDATE Coalescing")
    inner class CreateUpdateCoalescingTests {

        @Test
        fun `CREATE then UPDATE updates CREATE icalData`() = runTest {
            val event = createEvent("event1", "Original Summary")
            engine.queueCreate(calendarUrl, event)

            val updatedEvent = createEvent("event1", "Updated Summary")
            engine.queueUpdate(updatedEvent, "$calendarUrl/event1.ics", null)

            // Should still be one CREATE operation
            assertEquals(1, pendingStore.count())

            val ops = pendingStore.getReadyOperations(System.currentTimeMillis())
            assertEquals(1, ops.size)
            assertEquals(OperationType.CREATE, ops[0].operation)
            assertTrue(ops[0].icalData.contains("Updated Summary"))
        }

        @Test
        fun `multiple UPDATEs after CREATE use latest data`() = runTest {
            val event = createEvent("event1", "V1")
            engine.queueCreate(calendarUrl, event)

            engine.queueUpdate(createEvent("event1", "V2"), "$calendarUrl/event1.ics", null)
            engine.queueUpdate(createEvent("event1", "V3"), "$calendarUrl/event1.ics", null)
            engine.queueUpdate(createEvent("event1", "Final"), "$calendarUrl/event1.ics", null)

            assertEquals(1, pendingStore.count())

            val op = pendingStore.getByEventUid("event1")
            assertEquals(OperationType.CREATE, op?.operation)
            assertTrue(op?.icalData?.contains("Final") == true)
        }
    }

    @Nested
    @DisplayName("CREATE + DELETE Coalescing")
    inner class CreateDeleteCoalescingTests {

        @Test
        fun `CREATE then DELETE removes pending op entirely`() = runTest {
            val event = createEvent("event1", "Test Event")
            engine.queueCreate(calendarUrl, event)

            assertEquals(1, pendingStore.count())

            engine.queueDelete("event1", "$calendarUrl/event1.ics", null)

            // Should have no pending operations - never synced, nothing to delete
            assertEquals(0, pendingStore.count())
        }
    }

    @Nested
    @DisplayName("UPDATE Coalescing")
    inner class UpdateCoalescingTests {

        @Test
        fun `multiple UPDATEs coalesce to latest`() = runTest {
            engine.queueUpdate(createEvent("event1", "V1"), "$calendarUrl/event1.ics", "etag1")
            engine.queueUpdate(createEvent("event1", "V2"), "$calendarUrl/event1.ics", "etag1")
            engine.queueUpdate(createEvent("event1", "V3"), "$calendarUrl/event1.ics", "etag1")

            assertEquals(1, pendingStore.count())

            val op = pendingStore.getByEventUid("event1")
            assertEquals(OperationType.UPDATE, op?.operation)
            assertTrue(op?.icalData?.contains("V3") == true)
        }
    }

    @Nested
    @DisplayName("UPDATE + DELETE Coalescing")
    inner class UpdateDeleteCoalescingTests {

        @Test
        fun `UPDATE then DELETE replaces with DELETE`() = runTest {
            engine.queueUpdate(createEvent("event1", "Updated"), "$calendarUrl/event1.ics", "etag1")
            engine.queueDelete("event1", "$calendarUrl/event1.ics", "etag1")

            assertEquals(1, pendingStore.count())

            val op = pendingStore.getByEventUid("event1")
            assertEquals(OperationType.DELETE, op?.operation)
        }
    }

    @Nested
    @DisplayName("DELETE Operations")
    inner class DeleteOperationTests {

        @Test
        fun `multiple DELETEs are idempotent`() = runTest {
            engine.queueDelete("event1", "$calendarUrl/event1.ics", "etag1")
            engine.queueDelete("event1", "$calendarUrl/event1.ics", "etag1")

            assertEquals(1, pendingStore.count())
        }
    }

    @Nested
    @DisplayName("Invalid Sequences")
    inner class InvalidSequenceTests {

        @Test
        fun `UPDATE after DELETE throws exception`() = runTest {
            engine.queueDelete("event1", "$calendarUrl/event1.ics", "etag1")

            assertThrows<IllegalStateException> {
                runTest {
                    engine.queueUpdate(createEvent("event1", "Updated"), "$calendarUrl/event1.ics", "etag1")
                }
            }
        }
    }

    @Nested
    @DisplayName("Calendar URL Extraction")
    inner class CalendarUrlExtractionTests {

        @Test
        fun `extracts calendar URL from event URL`() = runTest {
            engine.queueUpdate(
                createEvent("event1", "Test"),
                "https://caldav.example.com/calendars/user/work/event1.ics",
                "etag1"
            )

            val op = pendingStore.getByEventUid("event1")
            assertEquals("https://caldav.example.com/calendars/user/work/", op?.calendarUrl)
        }
    }

    private fun createEvent(uid: String, summary: String): ICalEvent {
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
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
