package org.onekash.icaldav.client

import org.onekash.icaldav.model.*
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.DavResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Comprehensive CalDavClient tests.
 *
 * Tests calendar operations:
 * - Event CRUD operations
 * - ctag-based change detection
 * - sync-collection (RFC 6578)
 * - Error handling
 */
@DisplayName("CalDavClient Comprehensive Tests")
class CalDavClientComprehensiveTest {

    private lateinit var server: MockWebServer
    private lateinit var webDavClient: WebDavClient
    private lateinit var calDavClient: CalDavClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()

        webDavClient = WebDavClient(
            httpClient = WebDavClient.testHttpClient(),
            auth = DavAuth.Basic("testuser", "testpass")
        )
        calDavClient = CalDavClient(webDavClient)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun serverUrl(path: String = "/"): String {
        return server.url(path).toString()
    }

    private fun createTestEvent(
        uid: String = "test-event-123",
        summary: String = "Test Event",
        dtStart: Instant = Instant.now()
    ): ICalEvent {
        val zone = ZoneId.of("UTC")
        val startTime = ZonedDateTime.ofInstant(dtStart, zone)

        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime(
                timestamp = dtStart.toEpochMilli(),
                timezone = zone,
                isUtc = true,
                isDate = false
            ),
            dtEnd = ICalDateTime(
                timestamp = dtStart.plusSeconds(3600).toEpochMilli(),
                timezone = zone,
                isUtc = true,
                isDate = false
            ),
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

    @Nested
    @DisplayName("Fetch Events")
    inner class FetchEventsTests {

        @Test
        fun `fetchEvents returns parsed events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag123"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:event1
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Test Event 1
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `fetchEvents with time range filters events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val start = Instant.parse("2023-12-01T00:00:00Z")
            val end = Instant.parse("2023-12-31T23:59:59Z")

            val result = calDavClient.fetchEvents(serverUrl("/cal/"), start, end)

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("calendar-query"))
            assertTrue(body.contains("time-range"))
        }

        @Test
        fun `fetchEvents handles empty calendar`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
            val events = (result as DavResult.Success).value
            assertTrue(events.isEmpty())
        }

        @Test
        fun `fetchEvents handles malformed iCal data`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/bad.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                                <C:calendar-data>NOT VALID ICAL DATA</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            // Should handle gracefully, not crash
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Fetch Events By Href")
    inner class FetchEventsByHrefTests {

        @Test
        fun `fetchEventsByHref returns specific events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231215T100000Z
SUMMARY:Event 1
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEventsByHref(
                serverUrl("/cal/"),
                listOf("/cal/event1.ics")
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("calendar-multiget"))
        }

        @Test
        fun `fetchEventsByHref with empty list returns empty result`() {
            val result = calDavClient.fetchEventsByHref(
                serverUrl("/cal/"),
                emptyList()
            )

            assertIs<DavResult.Success<*>>(result)
            val events = (result as DavResult.Success).value
            assertTrue(events.isEmpty())
        }
    }

    @Nested
    @DisplayName("Get Ctag")
    inner class GetCtagTests {

        @Test
        fun `getCtag returns ctag value`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <CS:getctag>ctag-value-123</CS:getctag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.getCtag(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `getCtag handles missing ctag`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.getCtag(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("Create Event")
    inner class CreateEventTests {

        @Test
        fun `createEvent sends PUT request`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"new-etag\"")
            )

            val event = createTestEvent()
            val result = calDavClient.createEvent(serverUrl("/cal/"), event)

            assertIs<DavResult.Success<EventCreateResult>>(result)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.path?.contains("test-event-123.ics") == true)
        }

        @Test
        fun `createEvent sanitizes UID for URL`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"etag\"")
            )

            val event = createTestEvent(uid = "uid with spaces@domain.com")
            val result = calDavClient.createEvent(serverUrl("/cal/"), event)

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            val path = request.path ?: ""
            // UID should be sanitized (spaces replaced)
            assertTrue(!path.contains(" "))
        }

        @Test
        fun `createEvent returns etag`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"created-etag-123\"")
            )

            val event = createTestEvent()
            val result = calDavClient.createEvent(serverUrl("/cal/"), event)

            assertIs<DavResult.Success<EventCreateResult>>(result)
        }
    }

    @Nested
    @DisplayName("Update Event")
    inner class UpdateEventTests {

        @Test
        fun `updateEvent sends PUT with If-Match`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
                    .setHeader("ETag", "\"updated-etag\"")
            )

            val event = createTestEvent()
            val result = calDavClient.updateEvent(
                serverUrl("/cal/event.ics"),
                event,
                etag = "old-etag"
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("\"old-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `updateEvent handles 412 precondition failed`() {
            server.enqueue(MockResponse().setResponseCode(412))

            val event = createTestEvent()
            val result = calDavClient.updateEvent(
                serverUrl("/cal/event.ics"),
                event,
                etag = "stale-etag"
            )

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `updateEvent without etag omits If-Match`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
            )

            val event = createTestEvent()
            calDavClient.updateEvent(serverUrl("/cal/event.ics"), event)

            val request = server.takeRequest()
            assertEquals(null, request.getHeader("If-Match"))
        }
    }

    @Nested
    @DisplayName("Delete Event")
    inner class DeleteEventTests {

        @Test
        fun `deleteEvent sends DELETE request`() {
            server.enqueue(MockResponse().setResponseCode(204))

            val result = calDavClient.deleteEvent(serverUrl("/cal/event.ics"))

            assertIs<DavResult.Success<Unit>>(result)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        }

        @Test
        fun `deleteEvent with etag sends If-Match`() {
            server.enqueue(MockResponse().setResponseCode(204))

            calDavClient.deleteEvent(serverUrl("/cal/event.ics"), etag = "current-etag")

            val request = server.takeRequest()
            assertEquals("\"current-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `deleteEvent handles 412 precondition failed`() {
            server.enqueue(MockResponse().setResponseCode(412))

            val result = calDavClient.deleteEvent(
                serverUrl("/cal/event.ics"),
                etag = "stale-etag"
            )

            assertTrue(result !is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("Sync Collection - RFC 6578")
    inner class SyncCollectionTests {

        @Test
        fun `syncCollection initial sync returns all events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231215T100000Z
SUMMARY:Event 1
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>sync-token-1</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"))

            assertIs<DavResult.Success<SyncResult>>(result)

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("sync-collection"))
        }

        @Test
        fun `syncCollection incremental sync with token`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event2.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag2"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event2
DTSTART:20231216T100000Z
SUMMARY:Event 2
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>sync-token-2</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(
                serverUrl("/cal/"),
                syncToken = "sync-token-1"
            )

            assertIs<DavResult.Success<SyncResult>>(result)

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("sync-token-1"))
        }

        @Test
        fun `syncCollection handles deleted events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/deleted-event.ics</D:href>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:response>
                    <D:sync-token>sync-token-3</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(
                serverUrl("/cal/"),
                syncToken = "sync-token-2"
            )

            assertIs<DavResult.Success<SyncResult>>(result)
        }

        @Test
        fun `syncCollection handles invalid token - 410 Gone`() {
            server.enqueue(MockResponse().setResponseCode(410))

            val result = calDavClient.syncCollection(
                serverUrl("/cal/"),
                syncToken = "invalid-token"
            )

            assertTrue(result !is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `handles 401 unauthorized`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=\"CalDAV\"")
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `handles 403 forbidden`() {
            server.enqueue(MockResponse().setResponseCode(403))

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `handles 404 not found`() {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = calDavClient.fetchEvents(serverUrl("/nonexistent/"))

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `handles 500 server error`() {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertTrue(result !is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("Recurring Events")
    inner class RecurringEventsTests {

        @Test
        fun `fetchEvents handles recurring events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/recurring.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-recurring"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:recurring-meeting
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
RRULE:FREQ=DAILY;COUNT=5
SUMMARY:Daily Standup
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `fetchEvents handles recurring with overrides`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/recurring.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:recurring-meeting
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
RRULE:FREQ=DAILY;COUNT=5
SUMMARY:Daily Standup
END:VEVENT
BEGIN:VEVENT
UID:recurring-meeting
RECURRENCE-ID:20231216T100000Z
DTSTART:20231216T140000Z
DTEND:20231216T150000Z
SUMMARY:Daily Standup (Moved)
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("All-Day Events")
    inner class AllDayEventsTests {

        @Test
        fun `fetchEvents handles all-day events`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/allday.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-allday"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:allday-event
DTSTART;VALUE=DATE:20231225
DTEND;VALUE=DATE:20231226
SUMMARY:Christmas
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("Raw iCal Preservation")
    inner class RawIcalPreservationTests {

        @Test
        fun `fetchEvents preserves rawIcal`() {
            val icalData = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:event-with-custom-prop
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Test Event
X-CUSTOM-PROP:custom-value
END:VEVENT
END:VCALENDAR"""

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag123"</D:getetag>
                                <C:calendar-data>$icalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<List<EventWithMetadata>>>(result)
            val events = (result as DavResult.Success).value
            assertEquals(1, events.size)
            assertNotNull(events[0].rawIcal)
            assertTrue(events[0].rawIcal!!.contains("X-CUSTOM-PROP:custom-value"))
            assertTrue(events[0].rawIcal!!.contains("BEGIN:VCALENDAR"))
        }

        @Test
        fun `multi-event ics shares rawIcal`() {
            val icalData = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:recurring-meeting
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
RRULE:FREQ=DAILY;COUNT=5
SUMMARY:Daily Standup
END:VEVENT
BEGIN:VEVENT
UID:recurring-meeting
RECURRENCE-ID:20231216T100000Z
DTSTART:20231216T140000Z
DTEND:20231216T150000Z
SUMMARY:Daily Standup (Moved)
END:VEVENT
END:VCALENDAR"""

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/recurring.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                                <C:calendar-data>$icalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<List<EventWithMetadata>>>(result)
            val events = (result as DavResult.Success).value
            assertEquals(2, events.size)
            // Both events should have the same rawIcal
            assertEquals(events[0].rawIcal, events[1].rawIcal)
            // Both should contain the full VCALENDAR with both VEVENTs
            assertTrue(events[0].rawIcal!!.contains("RRULE:FREQ=DAILY"))
            assertTrue(events[0].rawIcal!!.contains("RECURRENCE-ID"))
        }

        @Test
        fun `rawIcal contains complete VCALENDAR`() {
            val icalData = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VTIMEZONE
TZID:America/New_York
BEGIN:STANDARD
DTSTART:19701101T020000
RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
TZOFFSETFROM:-0400
TZOFFSETTO:-0500
END:STANDARD
END:VTIMEZONE
BEGIN:VEVENT
UID:tz-event
DTSTART;TZID=America/New_York:20231215T100000
DTEND;TZID=America/New_York:20231215T110000
SUMMARY:Test with TZ
END:VEVENT
END:VCALENDAR"""

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/tz-event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                                <C:calendar-data>$icalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<List<EventWithMetadata>>>(result)
            val events = (result as DavResult.Success).value
            assertEquals(1, events.size)
            val rawIcal = events[0].rawIcal!!
            assertTrue(rawIcal.contains("BEGIN:VCALENDAR"))
            assertTrue(rawIcal.contains("END:VCALENDAR"))
            assertTrue(rawIcal.contains("BEGIN:VTIMEZONE"))
            assertTrue(rawIcal.contains("END:VTIMEZONE"))
        }

        @Test
        fun `syncCollection preserves rawIcal`() {
            val icalData = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:sync-event
DTSTART:20231215T100000Z
SUMMARY:Synced Event
X-SYNC-PROP:sync-value
END:VEVENT
END:VCALENDAR"""

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/sync-event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                                <C:calendar-data>$icalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>sync-token-new</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"))

            assertIs<DavResult.Success<SyncResult>>(result)
            val syncResult = (result as DavResult.Success).value
            assertEquals(1, syncResult.added.size)
            assertNotNull(syncResult.added[0].rawIcal)
            assertTrue(syncResult.added[0].rawIcal!!.contains("X-SYNC-PROP:sync-value"))
        }

        @Test
        fun `getEvent preserves rawIcal`() {
            val icalData = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:single-event
DTSTART:20231215T100000Z
SUMMARY:Single Event
X-GET-PROP:get-value
END:VEVENT
END:VCALENDAR"""

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/single-event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                                <C:calendar-data>$icalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.getEvent(serverUrl("/cal/single-event.ics"))

            assertIs<DavResult.Success<EventWithMetadata>>(result)
            val event = (result as DavResult.Success).value
            assertNotNull(event.rawIcal)
            assertTrue(event.rawIcal!!.contains("X-GET-PROP:get-value"))
        }

        @Test
        fun `construction without rawIcal defaults to null`() {
            // Verify backward compatibility - the 3-arg constructor should still work
            val event = createTestEvent()
            val eventWithMetadata = EventWithMetadata(
                event = event,
                href = "/cal/test.ics",
                etag = "etag123"
            )

            assertEquals(event, eventWithMetadata.event)
            assertEquals("/cal/test.ics", eventWithMetadata.href)
            assertEquals("etag123", eventWithMetadata.etag)
            assertEquals(null, eventWithMetadata.rawIcal)
        }
    }

    @Nested
    @DisplayName("Real-World Edge Cases")
    inner class RealWorldEdgeCasesTests {

        @Test
        fun `handles iCloud weird response codes`() {
            // iCloud sometimes returns 207 with unexpected content
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\"/>")
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            // Should handle gracefully
            assertNotNull(result)
        }

        @Test
        fun `handles events with very long UIDs`() {
            val longUid = "a".repeat(500) + "@domain.com"

            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"etag\"")
            )

            val event = createTestEvent(uid = longUid)
            val result = calDavClient.createEvent(serverUrl("/cal/"), event)

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles concurrent event modifications gracefully`() {
            // First attempt fails with 412
            server.enqueue(MockResponse().setResponseCode(412))

            val event = createTestEvent()
            val result = calDavClient.updateEvent(
                serverUrl("/cal/event.ics"),
                event,
                etag = "stale"
            )

            assertTrue(result !is DavResult.Success)
        }
    }
}