package org.onekash.icaldav.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import kotlinx.coroutines.test.runTest
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Transparency
import java.time.Instant

/**
 * Integration tests for CalDavClient using MockWebServer.
 *
 * Tests the full HTTP communication flow including:
 * - Request formatting and headers
 * - Response parsing
 * - Error handling
 * - ETag handling for conflict detection
 */
class CalDavClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var webDavClient: WebDavClient
    private lateinit var calDavClient: CalDavClient

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        // Use test client with short timeouts
        val httpClient = WebDavClient.testHttpClient()
        webDavClient = WebDavClient(httpClient, DavAuth.Basic("user", "pass"))
        calDavClient = CalDavClient(webDavClient)
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    // ==================== Fetch Events Tests ====================

    @Nested
    inner class FetchEventsTests {

        @Test
        fun `fetchEvents returns parsed events from REPORT response`() = runTest {
            val multistatus = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response>
                    <D:href>/calendars/user/default/event1.ics</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:getetag>"etag123"</D:getetag>
                        <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:event-uid-123
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR</C:calendar-data>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent()

            mockServer.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody(multistatus)
                .addHeader("Content-Type", "application/xml"))

            val result = calDavClient.fetchEvents(
                calendarUrl = mockServer.url("/calendars/user/default/").toString()
            )

            assertTrue(result is DavResult.Success)
            val events = (result as DavResult.Success).value
            assertEquals(1, events.size)
            assertEquals("event-uid-123", events[0].event.uid)
            assertEquals("Test Event", events[0].event.summary)
            assertEquals("etag123", events[0].etag)
        }

        @Test
        fun `fetchEvents with time range adds time-range filter`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
                .addHeader("Content-Type", "application/xml"))

            val start = Instant.parse("2023-12-01T00:00:00Z")
            val end = Instant.parse("2023-12-31T23:59:59Z")

            calDavClient.fetchEvents(
                calendarUrl = mockServer.url("/calendars/user/default/").toString(),
                start = start,
                end = end
            )

            val request = mockServer.takeRequest()
            assertTrue(request.body.readUtf8().contains("time-range"))
        }

        @Test
        fun `fetchEvents returns empty list on 207 with no events`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody(emptyMultistatus())
                .addHeader("Content-Type", "application/xml"))

            val result = calDavClient.fetchEvents(
                calendarUrl = mockServer.url("/calendars/user/default/").toString()
            )

            assertTrue(result is DavResult.Success)
            val events = (result as DavResult.Success).value
            assertTrue(events.isEmpty())
        }

        @Test
        fun `fetchEvents handles HTTP 401 unauthorized`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(401))

            val result = calDavClient.fetchEvents(
                calendarUrl = mockServer.url("/calendars/user/default/").toString()
            )

            assertTrue(result is DavResult.HttpError)
            assertEquals(401, (result as DavResult.HttpError).code)
        }

        @Test
        fun `fetchEvents handles HTTP 404 not found`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(404))

            val result = calDavClient.fetchEvents(
                calendarUrl = mockServer.url("/calendars/user/default/").toString()
            )

            assertTrue(result is DavResult.HttpError)
            assertEquals(404, (result as DavResult.HttpError).code)
        }
    }

    // ==================== Create Event Tests ====================

    @Nested
    inner class CreateEventTests {

        @Test
        fun `createEvent sends PUT with If-None-Match header`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(201)
                .addHeader("ETag", "\"new-etag\""))

            val event = createTestEvent(
                uid = "new-event-123",
                summary = "New Event"
            )

            val result = calDavClient.createEvent(
                calendarUrl = mockServer.url("/calendars/user/default/").toString(),
                event = event
            )

            assertTrue(result is DavResult.Success)
            val createResult = (result as DavResult.Success).value
            assertEquals("new-etag", createResult.etag)
            assertTrue(createResult.href.endsWith("new-event-123.ics"))

            // Verify If-None-Match header was sent
            val request = mockServer.takeRequest()
            assertEquals("*", request.getHeader("If-None-Match"))
        }

        @Test
        fun `createEvent handles 412 conflict when event exists`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(412))

            val event = createTestEvent(
                uid = "existing-event",
                summary = "Duplicate Event"
            )

            val result = calDavClient.createEvent(
                calendarUrl = mockServer.url("/calendars/user/default/").toString(),
                event = event
            )

            assertTrue(result is DavResult.HttpError)
            assertEquals(412, (result as DavResult.HttpError).code)
        }
    }

    // ==================== Update Event Tests ====================

    @Nested
    inner class UpdateEventTests {

        @Test
        fun `updateEvent sends PUT with If-Match header`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("ETag", "\"updated-etag\""))

            val event = createTestEvent(
                uid = "event-123",
                summary = "Updated Event"
            )

            val result = calDavClient.updateEvent(
                eventUrl = mockServer.url("/calendars/user/default/event-123.ics").toString(),
                event = event,
                etag = "old-etag"
            )

            assertTrue(result is DavResult.Success)
            assertEquals("updated-etag", (result as DavResult.Success).value)

            // Verify If-Match header was sent
            val request = mockServer.takeRequest()
            assertEquals("\"old-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `updateEvent handles 412 etag mismatch`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(412))

            val event = createTestEvent(
                uid = "event-123",
                summary = "Updated Event"
            )

            val result = calDavClient.updateEvent(
                eventUrl = mockServer.url("/calendars/user/default/event-123.ics").toString(),
                event = event,
                etag = "stale-etag"
            )

            assertTrue(result is DavResult.HttpError)
            assertEquals(412, (result as DavResult.HttpError).code)
        }
    }

    // ==================== Delete Event Tests ====================

    @Nested
    inner class DeleteEventTests {

        @Test
        fun `deleteEvent sends DELETE request`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(204))

            val result = calDavClient.deleteEvent(
                eventUrl = mockServer.url("/calendars/user/default/event-123.ics").toString()
            )

            assertTrue(result is DavResult.Success)

            val request = mockServer.takeRequest()
            assertEquals("DELETE", request.method)
        }

        @Test
        fun `deleteEvent with etag sends If-Match header`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(204))

            calDavClient.deleteEvent(
                eventUrl = mockServer.url("/calendars/user/default/event-123.ics").toString(),
                etag = "delete-etag"
            )

            val request = mockServer.takeRequest()
            assertEquals("\"delete-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `deleteEvent succeeds on 404 (already deleted)`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(404))

            val result = calDavClient.deleteEvent(
                eventUrl = mockServer.url("/calendars/user/default/event-123.ics").toString()
            )

            // 404 on delete is treated as success (already deleted)
            assertTrue(result is DavResult.Success)
        }
    }

    // ==================== Sync Collection Tests ====================

    @Nested
    inner class SyncCollectionTests {

        @Test
        fun `syncCollection returns added and deleted events`() = runTest {
            val multistatus = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response>
                    <D:href>/calendars/user/default/new-event.ics</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:getetag>"etag456"</D:getetag>
                        <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//EN
BEGIN:VEVENT
UID:new-event-uid
DTSTART:20231216T100000Z
DTEND:20231216T110000Z
SUMMARY:New Event
END:VEVENT
END:VCALENDAR</C:calendar-data>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/calendars/user/default/deleted-event.ics</D:href>
                    <D:status>HTTP/1.1 404 Not Found</D:status>
                  </D:response>
                  <D:sync-token>http://server/sync/token2</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            mockServer.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody(multistatus)
                .addHeader("Content-Type", "application/xml"))

            val result = calDavClient.syncCollection(
                calendarUrl = mockServer.url("/calendars/user/default/").toString(),
                syncToken = "token1"
            )

            assertTrue(result is DavResult.Success)
            val syncResult = (result as DavResult.Success).value

            assertEquals(1, syncResult.added.size)
            assertEquals("new-event-uid", syncResult.added[0].event.uid)

            assertEquals(1, syncResult.deleted.size)
            assertTrue(syncResult.deleted[0].endsWith("deleted-event.ics"))

            assertEquals("http://server/sync/token2", syncResult.newSyncToken)
        }

        @Test
        fun `syncCollection handles expired token (410 Gone)`() = runTest {
            mockServer.enqueue(MockResponse().setResponseCode(410))

            val result = calDavClient.syncCollection(
                calendarUrl = mockServer.url("/calendars/user/default/").toString(),
                syncToken = "expired-token"
            )

            assertTrue(result is DavResult.HttpError)
            assertEquals(410, (result as DavResult.HttpError).code)
        }
    }

    // ==================== Get Ctag Tests ====================

    @Nested
    inner class GetCtagTests {

        @Test
        fun `getCtag returns ctag from PROPFIND response`() = runTest {
            val multistatus = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                  <D:response>
                    <D:href>/calendars/user/default/</D:href>
                    <D:propstat>
                      <D:prop>
                        <CS:getctag>ctag-value-123</CS:getctag>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent()

            mockServer.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody(multistatus)
                .addHeader("Content-Type", "application/xml"))

            val result = calDavClient.getCtag(
                calendarUrl = mockServer.url("/calendars/user/default/").toString()
            )

            assertTrue(result is DavResult.Success)
            assertEquals("ctag-value-123", (result as DavResult.Success).value)
        }
    }

    // ==================== Build Event URL Tests ====================

    @Nested
    inner class BuildEventUrlTests {

        @Test
        fun `buildEventUrl creates correct URL`() {
            val url = calDavClient.buildEventUrl(
                "https://server.com/calendars/user/default/",
                "event-123"
            )
            assertEquals("https://server.com/calendars/user/default/event-123.ics", url)
        }

        @Test
        fun `buildEventUrl handles trailing slash`() {
            val urlWithSlash = calDavClient.buildEventUrl(
                "https://server.com/calendars/user/default/",
                "event-123"
            )
            val urlWithoutSlash = calDavClient.buildEventUrl(
                "https://server.com/calendars/user/default",
                "event-123"
            )
            assertEquals(urlWithSlash, urlWithoutSlash)
        }

        @Test
        fun `buildEventUrl sanitizes UID with special characters`() {
            val url = calDavClient.buildEventUrl(
                "https://server.com/calendars/user/default/",
                "event with spaces@example.com"
            )
            // Spaces should be replaced with underscore
            assertTrue(url.contains("event_with_spaces"))
        }

        @Test
        fun `buildEventUrl rejects empty UID`() {
            assertThrows(IllegalArgumentException::class.java) {
                calDavClient.buildEventUrl(
                    "https://server.com/calendars/user/default/",
                    ""
                )
            }
        }

        @Test
        fun `buildEventUrl rejects path traversal attempts`() {
            assertThrows(IllegalArgumentException::class.java) {
                calDavClient.buildEventUrl(
                    "https://server.com/calendars/user/default/",
                    "../../../etc/passwd"
                )
            }
        }
    }

    // ==================== Capabilities Tests ====================

    @Nested
    inner class CapabilitiesTests {

        @Test
        fun `getCapabilities parses DAV header`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, calendar-access, calendar-auto-schedule")
                .addHeader("Allow", "OPTIONS, GET, HEAD, DELETE, PROPFIND, PUT, REPORT"))

            val result = calDavClient.getCapabilities(
                serverUrl = mockServer.url("/").toString()
            )

            assertTrue(result is DavResult.Success)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.supportsAutoSchedule)
        }

        @Test
        fun `getCapabilities caches result`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, calendar-access"))
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, calendar-access"))

            val serverUrl = mockServer.url("/").toString()

            // First call
            calDavClient.getCapabilities(serverUrl)
            // Second call should use cache
            calDavClient.getCapabilities(serverUrl)

            // Should only have made one request
            assertEquals(1, mockServer.requestCount)
        }

        @Test
        fun `getCapabilities forceRefresh bypasses cache`() = runTest {
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, calendar-access"))
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .addHeader("DAV", "1, 2, calendar-access"))

            val serverUrl = mockServer.url("/").toString()

            calDavClient.getCapabilities(serverUrl)
            calDavClient.getCapabilities(serverUrl, forceRefresh = true)

            // Should have made two requests
            assertEquals(2, mockServer.requestCount)
        }
    }

    // ==================== Helper Functions ====================

    private fun emptyMultistatus(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
        </D:multistatus>
    """.trimIndent()

    /**
     * Create a test ICalEvent with all required parameters.
     */
    private fun createTestEvent(
        uid: String,
        summary: String = "Test Event",
        dtStart: ICalDateTime = ICalDateTime.parse("20231215T100000Z"),
        dtEnd: ICalDateTime? = ICalDateTime.parse("20231215T110000Z")
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            rdates = emptyList(),
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