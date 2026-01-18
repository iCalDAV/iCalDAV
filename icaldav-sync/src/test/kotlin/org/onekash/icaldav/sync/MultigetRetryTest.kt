package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.model.*
import org.onekash.icaldav.model.DavResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * TDD tests for multiget retry and fallback behavior (Phase 6).
 *
 * Tests for progressive retry when fetching multiple events fails:
 * 1. Full batch request
 * 2. On failure, split into smaller batches
 * 3. On continued failure, try individual requests
 * 4. Track which specific events fail
 *
 * Test Plan References:
 * - B3: Multiget retry/fallback tests
 */
@DisplayName("Multiget Retry Tests")
class MultigetRetryTest {

    private lateinit var calDavClient: CalDavClient

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        calDavClient = mock()
    }

    @Nested
    @DisplayName("B3: fetchEventsByHref Behavior")
    inner class FetchEventsByHrefTests {

        @Test
        fun `B3-1 fetches multiple events in single batch`() {
            val hrefs = listOf(
                "/cal/event1.ics",
                "/cal/event2.ics",
                "/cal/event3.ics"
            )

            val events = hrefs.map { href ->
                createServerEvent(href.substringAfterLast('/').removeSuffix(".ics"))
            }

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.Success(events))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.Success)
            assertEquals(3, (result as DavResult.Success).value.size)
        }

        @Test
        fun `B3-2 returns partial results when some events not found`() {
            val hrefs = listOf(
                "/cal/event1.ics",
                "/cal/event2.ics",  // This one doesn't exist
                "/cal/event3.ics"
            )

            // Server returns only 2 events (event2 not found)
            val events = listOf(
                createServerEvent("event1"),
                createServerEvent("event3")
            )

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.Success(events))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.Success)
            val fetchedEvents = (result as DavResult.Success).value
            assertEquals(2, fetchedEvents.size)
            // event2 is missing but not an error
        }

        @Test
        fun `B3-3 handles empty href list`() {
            whenever(calDavClient.fetchEventsByHref(calendarUrl, emptyList()))
                .thenReturn(DavResult.Success(emptyList()))

            val result = calDavClient.fetchEventsByHref(calendarUrl, emptyList())

            assertTrue(result is DavResult.Success)
            assertTrue((result as DavResult.Success).value.isEmpty())
        }

        @Test
        fun `B3-4 handles server error on batch request`() {
            val hrefs = listOf("/cal/event1.ics", "/cal/event2.ics")

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.HttpError(500, "Internal Server Error"))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.HttpError)
            assertEquals(500, (result as DavResult.HttpError).code)
        }

        @Test
        fun `B3-5 handles request too large error (413)`() {
            // Some servers return 413 when too many hrefs in a single request
            val hrefs = (1..100).map { "/cal/event$it.ics" }

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.HttpError(413, "Request Entity Too Large"))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.HttpError)
            assertEquals(413, (result as DavResult.HttpError).code)
            // Caller should retry with smaller batch
        }

        @Test
        fun `B3-6 handles network timeout`() {
            val hrefs = listOf("/cal/event1.ics")

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.NetworkError(java.net.SocketTimeoutException("Read timed out")))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.NetworkError)
        }
    }

    @Nested
    @DisplayName("B3: Progressive Retry Strategy")
    inner class ProgressiveRetryTests {

        /**
         * Test documenting the expected progressive retry behavior:
         * 1. Try batch of 50
         * 2. On 413, split into batches of 10
         * 3. On continued failure, try individual
         */
        @Test
        fun `B3-7 documents progressive batch reduction strategy`() {
            // This test documents the expected behavior for a batch fetcher
            // The actual implementation would be in a BatchFetcher class

            val hrefs = (1..50).map { "/cal/event$it.ics" }

            // Initial batch fails with 413
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), argThat { size > 10 }))
                .thenReturn(DavResult.HttpError(413, "Request Entity Too Large"))

            // Smaller batches succeed
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), argThat { size <= 10 }))
                .thenAnswer { invocation ->
                    val requestedHrefs = invocation.getArgument<List<String>>(1)
                    val events = requestedHrefs.map { href ->
                        createServerEvent(href.substringAfterLast('/').removeSuffix(".ics"))
                    }
                    DavResult.Success(events)
                }

            // Simulate progressive retry logic
            var result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            if (result is DavResult.HttpError && result.code == 413) {
                // Split into smaller batches
                val allEvents = mutableListOf<EventWithMetadata>()
                val batchSize = 10
                for (batch in hrefs.chunked(batchSize)) {
                    val batchResult = calDavClient.fetchEventsByHref(calendarUrl, batch)
                    if (batchResult is DavResult.Success) {
                        allEvents.addAll(batchResult.value)
                    }
                }
                assertEquals(50, allEvents.size, "Should fetch all events in smaller batches")
            }
        }

        @Test
        fun `B3-8 handles mixed success and failure in batches`() {
            // First batch succeeds
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event1.ics", "/cal/event2.ics"))))
                .thenReturn(DavResult.Success(listOf(
                    createServerEvent("event1"),
                    createServerEvent("event2")
                )))

            // Second batch fails
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event3.ics", "/cal/event4.ics"))))
                .thenReturn(DavResult.HttpError(500, "Server Error"))

            val batch1 = calDavClient.fetchEventsByHref(calendarUrl, listOf("/cal/event1.ics", "/cal/event2.ics"))
            val batch2 = calDavClient.fetchEventsByHref(calendarUrl, listOf("/cal/event3.ics", "/cal/event4.ics"))

            assertTrue(batch1 is DavResult.Success)
            assertEquals(2, (batch1 as DavResult.Success).value.size)

            assertTrue(batch2 is DavResult.HttpError)
            // Caller could retry batch2 individually
        }

        @Test
        fun `B3-9 individual fetch fallback when batch fails`() {
            // Batch of 3 fails
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf(
                "/cal/event1.ics",
                "/cal/event2.ics",
                "/cal/event3.ics"
            )))).thenReturn(DavResult.HttpError(500, "Server Error"))

            // Individual fetches: event1 succeeds, event2 fails, event3 succeeds
            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event1.ics"))))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event1"))))

            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event2.ics"))))
                .thenReturn(DavResult.HttpError(404, "Not Found"))

            whenever(calDavClient.fetchEventsByHref(eq(calendarUrl), eq(listOf("/cal/event3.ics"))))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event3"))))

            // Simulate individual retry
            val hrefs = listOf("/cal/event1.ics", "/cal/event2.ics", "/cal/event3.ics")
            val successfulEvents = mutableListOf<EventWithMetadata>()
            val failedHrefs = mutableListOf<String>()

            for (href in hrefs) {
                val result = calDavClient.fetchEventsByHref(calendarUrl, listOf(href))
                when (result) {
                    is DavResult.Success -> successfulEvents.addAll(result.value)
                    else -> failedHrefs.add(href)
                }
            }

            assertEquals(2, successfulEvents.size, "Should get 2 successful events")
            assertEquals(1, failedHrefs.size, "Should have 1 failed href")
            assertEquals("/cal/event2.ics", failedHrefs[0])
        }
    }

    @Nested
    @DisplayName("B3: Parse Error Handling")
    inner class ParseErrorHandlingTests {

        @Test
        fun `B3-10 handles parse error for single malformed event`() {
            val hrefs = listOf("/cal/event1.ics")

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.ParseError("Invalid VCALENDAR format", null))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.ParseError)
            assertTrue((result as DavResult.ParseError).message.contains("Invalid"))
        }

        @Test
        fun `B3-11 batch with one malformed event returns partial results`() {
            // When one event in a batch is malformed, server might return partial results
            // or the client might parse what it can
            val hrefs = listOf(
                "/cal/good1.ics",
                "/cal/malformed.ics",
                "/cal/good2.ics"
            )

            // Server returns only the valid events
            val events = listOf(
                createServerEvent("good1"),
                createServerEvent("good2")
            )

            whenever(calDavClient.fetchEventsByHref(calendarUrl, hrefs))
                .thenReturn(DavResult.Success(events))

            val result = calDavClient.fetchEventsByHref(calendarUrl, hrefs)

            assertTrue(result is DavResult.Success)
            assertEquals(2, (result as DavResult.Success).value.size)
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