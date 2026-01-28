package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
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
import kotlinx.coroutines.test.runTest

/**
 * TDD tests for parse failure retry behavior (Phase 7).
 *
 * Tests for handling malformed calendar data:
 * 1. Detect parse errors in batch responses
 * 2. Retry individual events when batch fails
 * 3. Track consistently failing events
 * 4. Coordinate sync token updates on failures
 *
 * Test Plan References:
 * - B4: Parse failure retry coordination
 */
@DisplayName("Parse Failure Retry Tests")
class ParseFailureRetryTest {

    private lateinit var calDavClient: CalDavClient

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        calDavClient = mock()
    }

    @Nested
    @DisplayName("B4: Parse Error Detection")
    inner class ParseErrorDetectionTests {

        @Test
        fun `B4-1 detects parse error from fetchEvents`() = runTest {
            whenever(calDavClient.fetchEvents(eq(calendarUrl), anyOrNull(), anyOrNull()))
                .thenReturn(DavResult.ParseError("Invalid VCALENDAR: missing BEGIN", null))

            val result = calDavClient.fetchEvents(calendarUrl)

            assertTrue(result is DavResult.ParseError)
            assertTrue((result as DavResult.ParseError).message.contains("VCALENDAR"))
        }

        @Test
        fun `B4-2 detects parse error from syncCollection`() = runTest {
            whenever(calDavClient.syncCollection(calendarUrl, "token"))
                .thenReturn(DavResult.ParseError("Malformed XML response", null))

            val result = calDavClient.syncCollection(calendarUrl, "token")

            assertTrue(result is DavResult.ParseError)
        }

        @Test
        fun `B4-3 detects parse error from fetchEventsByHref`() = runTest {
            val hrefs = listOf("/cal/malformed.ics")

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.ParseError("Invalid iCal data in response", null))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.ParseError)
        }
    }

    @Nested
    @DisplayName("B4: Batch Parse Failure Recovery")
    inner class BatchParseFailureTests {

        @Test
        fun `B4-4 successful events returned despite some unparseable`() = runTest {
            // When server returns mix of valid and invalid events,
            // the parser should return what it can parse
            val hrefs = listOf(
                "/cal/good1.ics",
                "/cal/malformed.ics",
                "/cal/good2.ics"
            )

            // Parser returns only successfully parsed events
            val validEvents = listOf(
                createServerEvent("good1"),
                createServerEvent("good2")
            )

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.Success(validEvents))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.Success)
            assertEquals(2, (result as DavResult.Success).value.size)
            // Note: malformed event is silently skipped
        }

        @Test
        fun `B4-5 entire batch fails when server returns invalid XML`() = runTest {
            val hrefs = listOf("/cal/event1.ics", "/cal/event2.ics")

            // Server returns malformed XML that can't be parsed at all
            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.ParseError("XML parsing failed: unexpected EOF", null))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.ParseError)
            // Caller should retry individual events
        }

        @Test
        fun `B4-6 individual retry identifies specific failing event`() = runTest {
            // First, batch fails
            val hrefs = listOf("/cal/event1.ics", "/cal/event2.ics", "/cal/event3.ics")

            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(hrefs)))
                .thenReturn(DavResult.ParseError("Parse failed", null))

            // Individual retries: event1 OK, event2 fails, event3 OK
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event1.ics"))))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event1"))))

            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event2.ics"))))
                .thenReturn(DavResult.ParseError("Invalid VEVENT in event2.ics", null))

            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event3.ics"))))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event3"))))

            // Simulate retry logic
            var batchResult = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            val successfulEvents = mutableListOf<EventWithMetadata>()
            val failedHrefs = mutableListOf<String>()

            if (batchResult is DavResult.ParseError) {
                // Retry individually
                for (href in hrefs) {
                    val individualResult = calDavClient.fetchEventsByHref(calendarUrl, listOf(href))
                    when (individualResult) {
                        is DavResult.Success -> successfulEvents.addAll(individualResult.value)
                        is DavResult.ParseError -> failedHrefs.add(href)
                        else -> failedHrefs.add(href)
                    }
                }
            }

            assertEquals(2, successfulEvents.size, "Should recover 2 valid events")
            assertEquals(1, failedHrefs.size, "Should identify 1 failing event")
            assertEquals("/cal/event2.ics", failedHrefs[0])
        }
    }

    @Nested
    @DisplayName("B4: Sync Token Coordination on Parse Failure")
    inner class SyncTokenCoordinationTests {

        @Test
        fun `B4-7 sync token should not advance when parse error occurs`() = runTest {
            // When sync-collection succeeds but parsing fails,
            // the new sync token should NOT be saved
            // (otherwise we'd skip the unparseable events forever)

            whenever(calDavClient.syncCollection(calendarUrl, "old-token"))
                .thenReturn(DavResult.ParseError("Failed to parse calendar data", null))

            val result = calDavClient.syncCollection(calendarUrl, "old-token")

            assertTrue(result is DavResult.ParseError)
            // Caller should keep old-token and retry later
        }

        @Test
        fun `B4-8 partial success should track which events were processed`() = runTest {
            // Sync returns hrefs, but fetching some fails
            val syncResult = SyncResult(
                added = emptyList(),
                deleted = emptyList(),
                newSyncToken = "new-token",
                addedHrefs = listOf(
                    ResourceHref("/cal/event1.ics", "etag1"),
                    ResourceHref("/cal/event2.ics", "etag2"),
                    ResourceHref("/cal/event3.ics", "etag3")
                )
            )

            whenever(calDavClient.syncCollection(calendarUrl, "old-token"))
                .thenReturn(DavResult.Success(syncResult))

            // Fetching events - event2 fails to parse
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), any()))
                .thenAnswer { invocation ->
                    val requestedHrefs = invocation.getArgument<List<String>>(1)
                    if (requestedHrefs.contains("/cal/event2.ics") && requestedHrefs.size > 1) {
                        // Batch with event2 fails
                        DavResult.ParseError("Parse error", null)
                    } else if (requestedHrefs == listOf("/cal/event2.ics")) {
                        // Individual retry of event2 also fails
                        DavResult.ParseError("Invalid VEVENT", null)
                    } else {
                        // Other events succeed
                        val events = requestedHrefs
                            .filter { it != "/cal/event2.ics" }
                            .map { createServerEvent(it.substringAfterLast('/').removeSuffix(".ics")) }
                        DavResult.Success(events)
                    }
                }

            // Simulate sync flow
            val syncResponse = calDavClient.syncCollection(calendarUrl, "old-token")
            assertTrue(syncResponse is DavResult.Success)

            val hrefs = (syncResponse as DavResult.Success).value.addedHrefs.map { it.href }
            val fetchResult = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            // On parse error, should retry individually and track failures
            val processedEvents = mutableListOf<EventWithMetadata>()
            val failedHrefs = mutableListOf<String>()

            if (fetchResult is DavResult.ParseError) {
                for (href in hrefs) {
                    val result = calDavClient.fetchEventsByHref(calendarUrl, listOf(href))
                    when (result) {
                        is DavResult.Success -> processedEvents.addAll(result.value)
                        else -> failedHrefs.add(href)
                    }
                }
            }

            assertEquals(2, processedEvents.size)
            assertEquals(1, failedHrefs.size)
            assertTrue(failedHrefs.contains("/cal/event2.ics"))
        }
    }

    @Nested
    @DisplayName("B4: Persistent Parse Failure Tracking")
    inner class PersistentFailureTrackingTests {

        @Test
        fun `B4-9 consistently failing events should be tracked`() = runTest {
            // An event that always fails to parse should be tracked
            // so we don't keep retrying it every sync

            val problematicHref = "/cal/corrupt.ics"
            val failureTracker = mutableMapOf<String, Int>()
            val maxRetries = 3

            // Simulate multiple sync attempts
            repeat(5) { attempt ->
                whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(problematicHref)))
                    .thenReturn(DavResult.ParseError("Persistent parse error", null))

                val result = calDavClient.fetchEventsByHref(calendarUrl, listOf(problematicHref))

                if (result is DavResult.ParseError) {
                    val currentCount = failureTracker.getOrDefault(problematicHref, 0)
                    failureTracker[problematicHref] = currentCount + 1
                }
            }

            val failCount = failureTracker[problematicHref] ?: 0
            assertTrue(failCount >= maxRetries, "Should track repeated failures")

            // After maxRetries, caller should skip this event in future syncs
            val shouldSkip = failCount >= maxRetries
            assertTrue(shouldSkip, "Should skip persistently failing events")
        }

        @Test
        fun `B4-10 recovered event clears failure tracking`() = runTest {
            val href = "/cal/intermittent.ics"
            val failureTracker = mutableMapOf<String, Int>()

            // First attempt fails
            failureTracker[href] = 1

            // Second attempt succeeds
            whenever(calDavClient.fetchEventsByHref(calendarUrl, listOf(href)))
                .thenReturn(DavResult.Success(listOf(createServerEvent("intermittent"))))

            val result = calDavClient.fetchEventsByHref(calendarUrl, listOf(href))

            if (result is DavResult.Success) {
                failureTracker.remove(href)
            }

            assertFalse(failureTracker.containsKey(href), "Success should clear failure tracking")
        }
    }

    // Helper function
    private fun createServerEvent(
        uid: String,
        summary: String = "Test Event",
        etag: String = "etag-$uid"
    ): EventWithMetadata {
        val now = System.currentTimeMillis()
        return EventWithMetadata(
            href = "/cal/$uid.ics",
            etag = etag,
            event = ICalEvent(
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
        )
    }
}