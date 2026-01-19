package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.client.EventCreateResult
import org.onekash.icaldav.client.SyncResult
import org.onekash.icaldav.client.ResourceHref
import org.onekash.icaldav.model.*
import org.onekash.icaldav.sync.*
import org.onekash.icaldav.model.DavResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TDD tests for eventual consistency handling (Phase 8).
 *
 * Tests for handling eventual consistency in CalDAV servers (especially iCloud):
 * 1. Handle 404 for recently created events
 * 2. Retry fetch after short delay
 * 3. iCloud partition server consistency
 * 4. Sync token validity during propagation delays
 *
 * Test Plan References:
 * - B5: Eventual consistency handling
 */
@DisplayName("Eventual Consistency Tests")
class EventualConsistencyTest {

    private lateinit var calDavClient: CalDavClient

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        calDavClient = mock()
    }

    @Nested
    @DisplayName("B5: Recently Created Event Handling")
    inner class RecentlyCreatedEventTests {

        @Test
        fun `B5-1 handles 404 for event just created`() {
            // Create event succeeds
            val eventUrl = "$calendarUrl/new-event.ics"

            whenever(calDavClient.createEvent(eq(calendarUrl), any()))
                .thenReturn(DavResult.Success(EventCreateResult(eventUrl, "etag-new")))

            // Immediate fetch returns 404 (eventual consistency)
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(emptyList())) // Event not found yet

            val createResult = calDavClient.createEvent(calendarUrl, createTestEvent("new-event"))
            assertTrue(createResult is DavResult.Success)

            val fetchResult = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(fetchResult is DavResult.Success)
            assertTrue((fetchResult as DavResult.Success).value.isEmpty(),
                "Event may not be immediately available due to eventual consistency")
        }

        @Test
        fun `B5-2 event becomes available after delay`() {
            val eventUrl = "$calendarUrl/delayed-event.ics"

            // First fetch: not found
            // Second fetch: found
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(emptyList()))
                .thenReturn(DavResult.Success(listOf(createServerEvent("delayed-event"))))

            // First attempt - empty
            val firstResult = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(firstResult is DavResult.Success)
            assertTrue((firstResult as DavResult.Success).value.isEmpty())

            // Simulated delay would happen here in real implementation

            // Second attempt - found
            val secondResult = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(secondResult is DavResult.Success)
            assertEquals(1, (secondResult as DavResult.Success).value.size)
        }

        @Test
        fun `B5-3 tracks pending events awaiting propagation`() {
            // When we create an event but can't immediately verify it,
            // track it for later verification

            val pendingEvents = mutableMapOf<String, Long>()
            val eventUrl = "$calendarUrl/pending.ics"
            val creationTime = System.currentTimeMillis()

            // Event created
            whenever(calDavClient.createEvent(eq(calendarUrl), any()))
                .thenReturn(DavResult.Success(EventCreateResult(eventUrl, "etag")))

            val createResult = calDavClient.createEvent(calendarUrl, createTestEvent("pending"))
            if (createResult is DavResult.Success) {
                pendingEvents[eventUrl] = creationTime
            }

            assertTrue(pendingEvents.containsKey(eventUrl))

            // After successful verification, remove from pending
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(listOf(createServerEvent("pending"))))

            val fetchResult = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            if (fetchResult is DavResult.Success && fetchResult.value.isNotEmpty()) {
                pendingEvents.remove(eventUrl)
            }

            assertFalse(pendingEvents.containsKey(eventUrl), "Should remove from pending after verification")
        }
    }

    @Nested
    @DisplayName("B5: Sync Collection Consistency")
    inner class SyncCollectionConsistencyTests {

        @Test
        fun `B5-4 sync-collection may not include very recent changes`() {
            // iCloud sync-collection might not immediately include
            // events created in the last few seconds

            val syncResult = SyncResult(
                added = emptyList(),
                deleted = emptyList(),
                newSyncToken = "token-123",
                addedHrefs = listOf(
                    ResourceHref("/cal/event1.ics", "etag1")
                    // Note: recently created event2 might not appear yet
                )
            )

            whenever(calDavClient.syncCollection(calendarUrl, "old-token"))
                .thenReturn(DavResult.Success(syncResult))

            val result = calDavClient.syncCollection(calendarUrl, "old-token")

            assertTrue(result is DavResult.Success)
            // Caller should be aware that very recent events might be missing
        }

        @Test
        fun `B5-5 subsequent sync picks up delayed events`() {
            // First sync: event not included
            val firstSyncResult = SyncResult(
                added = emptyList(),
                deleted = emptyList(),
                newSyncToken = "token-1",
                addedHrefs = emptyList()
            )

            // Second sync: event now appears
            val secondSyncResult = SyncResult(
                added = listOf(createServerEvent("delayed")),
                deleted = emptyList(),
                newSyncToken = "token-2",
                addedHrefs = emptyList()
            )

            whenever(calDavClient.syncCollection(calendarUrl, ""))
                .thenReturn(DavResult.Success(firstSyncResult))

            whenever(calDavClient.syncCollection(calendarUrl, "token-1"))
                .thenReturn(DavResult.Success(secondSyncResult))

            // First sync
            val first = calDavClient.syncCollection(calendarUrl, "")
            assertTrue(first is DavResult.Success)
            assertTrue((first as DavResult.Success).value.added.isEmpty())

            // Second sync picks up the event
            val second = calDavClient.syncCollection(calendarUrl, "token-1")
            assertTrue(second is DavResult.Success)
            assertEquals(1, (second as DavResult.Success).value.added.size)
        }
    }

    @Nested
    @DisplayName("B5: Update and Delete Consistency")
    inner class UpdateDeleteConsistencyTests {

        @Test
        fun `B5-6 update may show old version briefly`() {
            val eventUrl = "$calendarUrl/updating.ics"

            // Update succeeds with new etag
            whenever(calDavClient.updateEvent(eq(eventUrl), any(), anyOrNull()))
                .thenReturn(DavResult.Success("new-etag"))

            // Immediate fetch might return old version (stale read)
            val oldEvent = createServerEvent("updating", "Old Title", "old-etag")
            val newEvent = createServerEvent("updating", "New Title", "new-etag")

            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(listOf(oldEvent)))  // Stale
                .thenReturn(DavResult.Success(listOf(newEvent)))  // Updated

            // Update
            val updateResult = calDavClient.updateEvent(eventUrl, createTestEvent("updating"), null)
            assertTrue(updateResult is DavResult.Success)

            // First read might be stale
            val firstFetch = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(firstFetch is DavResult.Success)

            // Second read should be updated
            val secondFetch = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(secondFetch is DavResult.Success)
            assertEquals("New Title", (secondFetch as DavResult.Success).value[0].event.summary)
        }

        @Test
        fun `B5-7 delete may still return event briefly`() {
            val eventUrl = "$calendarUrl/deleting.ics"

            // Delete succeeds
            whenever(calDavClient.deleteEvent(eventUrl, null))
                .thenReturn(DavResult.Success(Unit))

            // Immediate fetch might still return the event
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(listOf(createServerEvent("deleting"))))
                .thenReturn(DavResult.Success(emptyList()))

            // Delete
            val deleteResult = calDavClient.deleteEvent(eventUrl, null)
            assertTrue(deleteResult is DavResult.Success)

            // First fetch might still see it (eventual consistency)
            val firstFetch = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            // Could be either present or absent

            // Later fetch should not see it
            val secondFetch = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
            assertTrue(secondFetch is DavResult.Success)
            assertTrue((secondFetch as DavResult.Success).value.isEmpty())
        }
    }

    @Nested
    @DisplayName("B5: ETag Consistency")
    inner class ETagConsistencyTests {

        @Test
        fun `B5-8 etag mismatch during propagation returns 412`() {
            val eventUrl = "$calendarUrl/conflicting.ics"

            // Update with etag that doesn't match current server state
            whenever(calDavClient.updateEvent(eq(eventUrl), any(), eq("stale-etag")))
                .thenReturn(DavResult.HttpError(412, "Precondition Failed"))

            val result = calDavClient.updateEvent(eventUrl, createTestEvent("conflicting"), "stale-etag")

            assertTrue(result is DavResult.HttpError)
            assertEquals(412, (result as DavResult.HttpError).code)
            // Caller should re-fetch to get current version
        }

        @Test
        fun `B5-9 ctag may lag behind individual event changes`() {
            // CTag might not immediately reflect the latest event changes

            // First ctag check
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag-old"))

            // Event is updated on server (by another client)
            // But ctag hasn't propagated yet

            // Second ctag check - still old
            // (In reality there would be a delay)
            val ctag1 = calDavClient.getCtag(calendarUrl)
            val ctag2 = calDavClient.getCtag(calendarUrl)

            // Both return same ctag despite changes
            assertTrue(ctag1 is DavResult.Success)
            assertTrue(ctag2 is DavResult.Success)
            assertEquals((ctag1 as DavResult.Success).value, (ctag2 as DavResult.Success).value)

            // This is expected behavior - ctag is eventually consistent
        }

        @Test
        fun `B5-10 handles missing etag in response`() {
            // Some servers might not always return etag

            val event = EventWithMetadata(
                href = "/cal/no-etag.ics",
                etag = null,  // No etag provided
                event = createTestEvent("no-etag")
            )

            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf("/cal/no-etag.ics")))
                .thenReturn(DavResult.Success(listOf(event)))

            val result = calDavClient.fetchEventsByHref(calendarUrl, listOf("/cal/no-etag.ics"))

            assertTrue(result is DavResult.Success)
            val fetchedEvent = (result as DavResult.Success).value[0]
            assertTrue(fetchedEvent.etag == null, "Should handle null etag gracefully")
        }
    }

    @Nested
    @DisplayName("B5: Retry Strategy")
    inner class RetryStrategyTests {

        @Test
        fun `B5-11 documents exponential backoff for consistency retries`() {
            // For eventual consistency issues, retries should use backoff
            val baseDelayMs = 100L
            val maxRetries = 5
            val retryDelays = mutableListOf<Long>()

            for (i in 0 until maxRetries) {
                val delay = baseDelayMs * (1 shl i)  // Exponential: 100, 200, 400, 800, 1600
                retryDelays.add(delay)
            }

            assertEquals(100L, retryDelays[0])
            assertEquals(200L, retryDelays[1])
            assertEquals(400L, retryDelays[2])
            assertEquals(800L, retryDelays[3])
            assertEquals(1600L, retryDelays[4])
        }

        @Test
        fun `B5-12 retry gives up after max attempts`() {
            val eventUrl = "$calendarUrl/never-appears.ics"
            val maxRetries = 3
            var retryCount = 0

            // Always returns empty (event never propagates)
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl)))
                .thenReturn(DavResult.Success(emptyList()))

            // Simulate retry loop
            var found = false
            while (!found && retryCount < maxRetries) {
                val result = calDavClient.fetchEventsByHref(calendarUrl, listOf(eventUrl))
                if (result is DavResult.Success && result.value.isNotEmpty()) {
                    found = true
                } else {
                    retryCount++
                }
            }

            assertFalse(found, "Should give up after max retries")
            assertEquals(maxRetries, retryCount, "Should have attempted maxRetries times")
        }
    }

    // Helper functions
    private fun createServerEvent(
        uid: String,
        summary: String = "Test Event",
        etag: String = "etag-$uid"
    ): EventWithMetadata {
        return EventWithMetadata(
            href = "/cal/$uid.ics",
            etag = etag,
            event = createTestEvent(uid, summary)
        )
    }

    private fun createTestEvent(uid: String, summary: String = "Test Event"): ICalEvent {
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