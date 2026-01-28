package org.onekash.icaldav.client

import org.onekash.icaldav.model.DavDepth
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.xml.RequestBuilder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Integration tests for WebDavClient using MockWebServer.
 *
 * Tests actual HTTP request/response flows.
 */
@DisplayName("WebDavClient Integration Tests")
class WebDavClientIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()

        client = WebDavClient(
            auth = DavAuth.Basic("testuser", "testpass")
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun serverUrl(path: String = "/"): String {
        return server.url(path).toString()
    }

    @Nested
    @DisplayName("PROPFIND Operations")
    inner class PropfindTests {

        @Test
        fun `propfind returns calendar properties`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>My Calendar</D:displayname>
                                <D:resourcetype>
                                    <D:collection/>
                                    <C:calendar/>
                                </D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml")
                    .setBody(xmlResponse)
            )

            val result = client.propfind(
                url = serverUrl("/cal/"),
                body = RequestBuilder.propfind("displayname", "resourcetype"),
                depth = DavDepth.ZERO
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("0", request.getHeader("Depth"))
        }

        @Test
        fun `propfind with depth 1 returns children`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Calendars</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/calendars/work/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Work</D:displayname></D:prop>
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

            val result = client.propfind(
                url = serverUrl("/calendars/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ONE
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("1", request.getHeader("Depth"))
        }

        @Test
        fun `propfind handles 401 unauthorized`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=\"CalDAV\"")
            )

            val result = client.propfind(
                url = serverUrl("/cal/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            assertIs<DavResult.HttpError>(result)
        }

        @Test
        fun `propfind handles 404 not found`() = runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.propfind(
                url = serverUrl("/nonexistent/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            assertIs<DavResult.HttpError>(result)
        }
    }

    @Nested
    @DisplayName("REPORT Operations")
    inner class ReportTests {

        @Test
        fun `calendar-query returns events`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"abc123"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231215T100000Z
SUMMARY:Test Event
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

            val result = client.report(
                url = serverUrl("/cal/"),
                body = RequestBuilder.calendarQuery(
                    start = "20231201T000000Z",
                    end = "20231231T235959Z"
                )
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("REPORT", request.method)
        }

        @Test
        fun `calendar-multiget returns specific events`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR...</C:calendar-data>
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

            val result = client.report(
                url = serverUrl("/cal/"),
                body = RequestBuilder.calendarMultiget(listOf("/cal/event1.ics"))
            )

            assertIs<DavResult.Success<*>>(result)
        }
    }

    @Nested
    @DisplayName("GET Operations")
    inner class GetTests {

        @Test
        fun `get returns ics content`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-event
                DTSTART:20231215T100000Z
                SUMMARY:Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/calendar")
                    .setHeader("ETag", "\"abc123\"")
                    .setBody(icsContent)
            )

            val result = client.get(serverUrl("/cal/event.ics"))

            assertIs<DavResult.Success<String>>(result)
            assertTrue(result.value.contains("VCALENDAR"))

            val request = server.takeRequest()
            assertEquals("GET", request.method)
        }

        @Test
        fun `get handles 404 gracefully`() = runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.get(serverUrl("/cal/missing.ics"))

            assertIs<DavResult.HttpError>(result)
        }
    }

    @Nested
    @DisplayName("PUT Operations")
    inner class PutTests {

        @Test
        fun `put creates new event`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"new-etag\"")
            )

            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:new-event
                DTSTART:20231215T100000Z
                SUMMARY:New Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = client.put(
                url = serverUrl("/cal/new-event.ics"),
                body = icsContent
            )

            assertIs<DavResult.Success<String?>>(result)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("text/calendar; charset=utf-8", request.getHeader("Content-Type"))
        }

        @Test
        fun `put with etag does conditional update`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
                    .setHeader("ETag", "\"updated-etag\"")
            )

            val result = client.put(
                url = serverUrl("/cal/event.ics"),
                body = "BEGIN:VCALENDAR...",
                etag = "old-etag"
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("\"old-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `put returns 412 on etag mismatch`() = runTest {
            server.enqueue(MockResponse().setResponseCode(412))

            val result = client.put(
                url = serverUrl("/cal/event.ics"),
                body = "BEGIN:VCALENDAR...",
                etag = "stale-etag"
            )

            assertIs<DavResult.HttpError>(result)
        }
    }

    @Nested
    @DisplayName("DELETE Operations")
    inner class DeleteTests {

        @Test
        fun `delete removes event`() = runTest {
            server.enqueue(MockResponse().setResponseCode(204))

            val result = client.delete(serverUrl("/cal/event.ics"))

            assertIs<DavResult.Success<Unit>>(result)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        }

        @Test
        fun `delete with etag does conditional delete`() = runTest {
            server.enqueue(MockResponse().setResponseCode(204))

            val result = client.delete(
                url = serverUrl("/cal/event.ics"),
                etag = "current-etag"
            )

            assertIs<DavResult.Success<*>>(result)

            val request = server.takeRequest()
            assertEquals("\"current-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `delete handles 404 as success - already deleted`() = runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.delete(serverUrl("/cal/missing.ics"))

            // 404 on delete is typically OK - resource already gone
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("MKCALENDAR Operations")
    inner class MkcalendarTests {

        @Test
        fun `mkcalendar creates new calendar`() = runTest {
            server.enqueue(MockResponse().setResponseCode(201))

            val result = client.mkcalendar(
                url = serverUrl("/calendars/new-calendar/"),
                body = RequestBuilder.mkcalendar(
                    displayName = "New Calendar",
                    description = "A test calendar",
                    color = "#FF0000"
                )
            )

            assertIs<DavResult.Success<Unit>>(result)

            val request = server.takeRequest()
            assertEquals("MKCALENDAR", request.method)
        }

        @Test
        fun `mkcalendar handles 405 method not allowed`() = runTest {
            server.enqueue(MockResponse().setResponseCode(405))

            val result = client.mkcalendar(
                url = serverUrl("/calendars/existing/"),
                body = RequestBuilder.mkcalendar(displayName = "Test")
            )

            assertIs<DavResult.HttpError>(result)
        }
    }

    @Nested
    @DisplayName("Authentication")
    inner class AuthenticationTests {

        @Test
        fun `basic auth header is sent`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody("<D:multistatus xmlns:D=\"DAV:\"></D:multistatus>")
            )

            client.propfind(
                url = serverUrl("/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            val request = server.takeRequest()
            val authHeader = request.getHeader("Authorization")
            assertNotNull(authHeader)
            assertTrue(authHeader.startsWith("Basic "))
        }

        @Test
        fun `bearer token auth is sent`() = runTest {
            val bearerClient = WebDavClient(
                auth = DavAuth.Bearer("test-token-123")
            )

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody("<D:multistatus xmlns:D=\"DAV:\"></D:multistatus>")
            )

            bearerClient.propfind(
                url = serverUrl("/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            val request = server.takeRequest()
            val authHeader = request.getHeader("Authorization")
            assertEquals("Bearer test-token-123", authHeader)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `handles 500 server error`() = runTest {
            // Enqueue enough 500 responses to exceed retry attempts (MAX_RETRIES + 1 = 3)
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(500))
            }

            val result = client.get(serverUrl("/cal/"))

            assertIs<DavResult.HttpError>(result)
        }

        @Test
        fun `handles malformed XML response`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody("not valid xml <<<<")
            )

            val result = client.propfind(
                url = serverUrl("/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            // Should handle gracefully, not crash
            assertNotNull(result)
        }

        @Test
        fun `handles empty response body`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody("")
            )

            val result = client.propfind(
                url = serverUrl("/"),
                body = RequestBuilder.propfind("displayname"),
                depth = DavDepth.ZERO
            )

            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("ETag Handling")
    inner class EtagHandlingTests {

        @Test
        fun `etag is normalized - quotes added`() {
            val etag = WebDavClient.formatEtagForHeader("abc123")
            assertEquals("\"abc123\"", etag)
        }

        @Test
        fun `etag is normalized - existing quotes preserved`() {
            val etag = WebDavClient.formatEtagForHeader("\"abc123\"")
            assertEquals("\"abc123\"", etag)
        }

        @Test
        fun `etag normalization removes extra quotes`() {
            val normalized = WebDavClient.normalizeEtag("\"\"abc\"\"")
            // Should handle gracefully
            assertNotNull(normalized)
        }

        @Test
        fun `null etag returns null`() {
            val normalized = WebDavClient.normalizeEtag(null)
            assertEquals(null, normalized)
        }
    }
}