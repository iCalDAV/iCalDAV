package org.onekash.icaldav.client

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
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Server Compatibility Mock Tests
 *
 * Tests CalDAV client behavior with responses that simulate different
 * CalDAV server implementations and their quirks.
 *
 * Servers tested:
 * - Google Calendar (OAuth, specific XML patterns)
 * - Apple iCloud (lowercase prefixes, CDATA)
 * - Fastmail (standard WebDAV)
 * - Nextcloud (standard WebDAV with extensions)
 */
@DisplayName("Server Compatibility Mock Tests")
class ServerCompatibilityMockTest {

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

    @Nested
    @DisplayName("Google Calendar Compatibility")
    inner class GoogleCalendarTests {

        @Test
        fun `handles Google Calendar XML namespacing style`() = runTest {
            // Google uses specific namespace patterns
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <multistatus xmlns="DAV:" xmlns:caldav="urn:ietf:params:xml:ns:caldav"
                             xmlns:cs="http://calendarserver.org/ns/">
                    <response>
                        <href>/calendar/v3/calendars/primary/events/event1.ics</href>
                        <propstat>
                            <prop>
                                <getetag>"event1-etag"</getetag>
                                <caldav:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Google Inc//Google Calendar//EN
BEGIN:VEVENT
UID:google-event-1@google.com
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Google Event
ORGANIZER:mailto:user@gmail.com
END:VEVENT
END:VCALENDAR</caldav:calendar-data>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/calendar/v3/calendars/primary/events/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles Google Calendar color property`() = runTest {
            // Google Calendar returns color in custom namespace
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <multistatus xmlns="DAV:" xmlns:caldav="urn:ietf:params:xml:ns:caldav"
                             xmlns:apple="http://apple.com/ns/ical/">
                    <response>
                        <href>/calendar/event.ics</href>
                        <propstat>
                            <prop>
                                <getetag>"etag"</getetag>
                                <apple:calendar-color>#0000FFFF</apple:calendar-color>
                                <caldav:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:color-event@google.com
DTSTART:20231215T100000Z
SUMMARY:Colored Event
COLOR:blue
END:VEVENT
END:VCALENDAR</caldav:calendar-data>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/calendar/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles Google Calendar discovery response`() = runTest {
            // Google uses specific principal and calendar-home patterns
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <multistatus xmlns="DAV:" xmlns:caldav="urn:ietf:params:xml:ns:caldav">
                    <response>
                        <href>/calendar/v3/</href>
                        <propstat>
                            <prop>
                                <current-user-principal>
                                    <href>/calendar/v3/users/me/</href>
                                </current-user-principal>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            // Just verify no parsing errors
            val result = calDavClient.fetchEvents(serverUrl("/calendar/v3/"))
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Apple iCloud Compatibility")
    inner class ICloudTests {

        @Test
        fun `handles iCloud lowercase namespace prefixes`() = runTest {
            // iCloud uses lowercase 'd:' prefix
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                               xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/calendars/user/calendar/event.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"icloud-etag"</d:getetag>
                                <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Apple Inc.//iCloud//EN
BEGIN:VEVENT
UID:icloud-event@icloud.com
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:iCloud Event
END:VEVENT
END:VCALENDAR</cal:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/calendars/user/calendar/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles iCloud CDATA wrapped calendar data`() = runTest {
            // iCloud sometimes wraps calendar-data in CDATA sections
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <d:href>/calendars/event.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"cdata-etag"</d:getetag>
                                <cal:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Apple Inc.//iCloud//EN
BEGIN:VEVENT
UID:cdata-event@icloud.com
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:CDATA Event <with special chars>
DESCRIPTION:Event with & symbols
END:VEVENT
END:VCALENDAR]]></cal:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/calendars/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles iCloud ctag in calendarserver namespace`() = runTest {
            // iCloud puts getctag in http://calendarserver.org/ns/
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/calendars/user/calendar/</d:href>
                        <d:propstat>
                            <d:prop>
                                <cs:getctag>icloud-ctag-123456789</cs:getctag>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.getCtag(serverUrl("/calendars/user/calendar/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles iCloud sync-token format`() = runTest {
            // iCloud sync tokens are URLs
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/calendars/user/calendar/event.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"etag"</d:getetag>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                    <d:sync-token>https://p01-caldav.icloud.com/sync/token123</d:sync-token>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/calendars/user/calendar/"))

            assertIs<DavResult.Success<SyncResult>>(result)
        }
    }

    @Nested
    @DisplayName("Fastmail Compatibility")
    inner class FastmailTests {

        @Test
        fun `handles Fastmail standard DAV response`() = runTest {
            // Fastmail uses standard DAV prefixes
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/dav/calendars/user/default/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"fastmail-etag"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Fastmail//Calendar//EN
BEGIN:VEVENT
UID:fastmail-event@fastmail.com
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Fastmail Event
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
                    .setHeader("DAV", "1, 2, calendar-access")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/dav/calendars/user/default/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles Fastmail JMAP-style UIDs`() = runTest {
            // Fastmail UIDs can be JMAP-style
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/dav/calendars/user/default/Mxxxx.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"jmap-etag"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:Mxxxxxxxxxxxxxxxxxxxxxxxxxxxx
DTSTART:20231215T100000Z
SUMMARY:JMAP UID Event
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

            val result = calDavClient.fetchEvents(serverUrl("/dav/calendars/user/default/"))

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("Nextcloud Compatibility")
    inner class NextcloudTests {

        @Test
        fun `handles Nextcloud response format`() = runTest {
            // Nextcloud uses standard DAV but with specific property extensions
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                               xmlns:oc="http://owncloud.org/ns"
                               xmlns:nc="http://nextcloud.org/ns">
                    <d:response>
                        <d:href>/remote.php/dav/calendars/user/personal/event.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"nextcloud-etag"</d:getetag>
                                <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Nextcloud calendar//EN
BEGIN:VEVENT
UID:nextcloud-event@nextcloud.local
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Nextcloud Event
END:VEVENT
END:VCALENDAR</cal:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/remote.php/dav/calendars/user/personal/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles Nextcloud calendar list with oc namespace`() = runTest {
            // Nextcloud discovery response
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                               xmlns:oc="http://owncloud.org/ns"
                               xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/remote.php/dav/calendars/user/personal/</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:displayname>Personal</d:displayname>
                                <cs:getctag>nextcloud-ctag-12345</cs:getctag>
                                <cal:supported-calendar-component-set>
                                    <cal:comp name="VEVENT"/>
                                    <cal:comp name="VTODO"/>
                                </cal:supported-calendar-component-set>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.getCtag(serverUrl("/remote.php/dav/calendars/user/personal/"))

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("Edge Cases - Multiple Server Quirks")
    inner class EdgeCasesTests {

        @Test
        fun `handles no namespace prefix at all`() = runTest {
            // Some minimal implementations use no prefix
            val xmlResponse = """
                <?xml version="1.0"?>
                <multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <response>
                        <href>/cal/event.ics</href>
                        <propstat>
                            <prop>
                                <getetag>"no-prefix-etag"</getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:no-prefix-event
DTSTART:20231215T100000Z
SUMMARY:No Prefix Event
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
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
        fun `handles mixed case in status`() = runTest {
            // Some servers might have different case in HTTP status
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                            </D:prop>
                            <D:status>http/1.1 200 ok</D:status>
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

            // Should not crash on case variations
            assertNotNull(result)
        }

        @Test
        fun `handles empty calendar-data element`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/empty.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"empty-etag"</D:getetag>
                                <C:calendar-data></C:calendar-data>
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

            // Should handle empty calendar data gracefully
            assertNotNull(result)
        }

        @Test
        fun `handles self-closing calendar-data element`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/selfclose.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"selfclose-etag"</D:getetag>
                                <C:calendar-data/>
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

            assertNotNull(result)
        }

        @Test
        fun `handles Unicode in calendar data`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/unicode.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"unicode-etag"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:unicode-event@test.com
DTSTART:20231215T100000Z
SUMMARY:日本語イベント
DESCRIPTION:Événement avec accents éàù
LOCATION:東京 · Tokyo · トーキョー
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
                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `handles multiple events in single response`() = runTest {
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
                    <D:response>
                        <D:href>/cal/event3.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag3"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event3
DTSTART:20231217T100000Z
SUMMARY:Event 3
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
            val events = (result as DavResult.Success).value
            assertEquals(3, events.size, "Should parse all 3 events")
        }

        @Test
        fun `handles response with mixed 200 and 404 propstats`() = runTest {
            // Some servers return mixed success/error in same response
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                        <D:propstat>
                            <D:prop>
                                <D:displayname/>
                            </D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status>
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

            // Should handle mixed propstats gracefully
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("HTTP Response Codes")
    inner class HttpResponseCodeTests {

        @Test
        fun `handles 503 Service Unavailable`() = runTest {
            // Server temporarily unavailable
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Retry-After", "120")
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `handles 429 Too Many Requests`() = runTest {
            // Rate limiting - use short retry (1 second) to avoid test timeout
            // Enqueue multiple 429s to exhaust retry attempts
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "1")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "1")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "1")
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            assertTrue(result !is DavResult.Success, "Should fail after retry attempts exhausted")
        }

        @Test
        fun `handles 301 redirect`() = runTest {
            // Permanent redirect - need to enqueue both the redirect and the final response
            server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .setHeader("Location", serverUrl("/new-location/"))
            )
            // Response at the redirected location
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml")
                    .setBody("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <D:multistatus xmlns:D="DAV:">
                            <D:response>
                                <D:href>/new-location/</D:href>
                                <D:propstat>
                                    <D:status>HTTP/1.1 200 OK</D:status>
                                </D:propstat>
                            </D:response>
                        </D:multistatus>
                    """.trimIndent())
            )

            val result = calDavClient.fetchEvents(serverUrl("/old-location/"))

            // OkHttp follows redirects by default
            assertNotNull(result)
        }
    }
}
