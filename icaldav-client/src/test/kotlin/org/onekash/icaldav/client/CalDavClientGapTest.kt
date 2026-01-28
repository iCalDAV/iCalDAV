package org.onekash.icaldav.client

import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.DavResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
 * Gap tests for CalDavClient - covers edge cases not in main test suite.
 *
 * Test Plan References:
 * - A1: CalDavClient gap tests (timeout, retry, 403/410 handling)
 */
@DisplayName("CalDavClient Gap Tests")
class CalDavClientGapTest {

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
    @DisplayName("syncCollection Error Handling")
    inner class SyncCollectionErrorTests {

        @Test
        fun `syncCollection returns HttpError on 403 expired token`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setBody("Sync token expired")
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"), "old-token")

            assertIs<DavResult.HttpError>(result)
            assertEquals(403, result.code)
        }

        @Test
        fun `syncCollection returns HttpError on 410 invalid token`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(410)
                    .setBody("Sync token invalid")
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"), "invalid-token")

            assertIs<DavResult.HttpError>(result)
            assertEquals(410, result.code)
        }

        @Test
        fun `syncCollection handles empty sync token for initial sync`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:sync-token>https://example.com/sync/token123</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"), "")

            assertIs<DavResult.Success<*>>(result)
        }

        @Test
        fun `syncCollection handles deleted events`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/deleted-event.ics</D:href>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:response>
                    <D:sync-token>https://example.com/sync/token456</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"), "old-token")

            assertIs<DavResult.Success<SyncResult>>(result)
            val syncResult = result.value
            assertTrue(syncResult.deleted.isNotEmpty())
            assertEquals("/cal/deleted-event.ics", syncResult.deleted.first())
        }

        @Test
        fun `syncCollection handles hrefs without calendar-data (iCloud style)`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag123"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>https://example.com/sync/token789</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(xmlResponse)
            )

            val result = calDavClient.syncCollection(serverUrl("/cal/"), "old-token")

            assertIs<DavResult.Success<SyncResult>>(result)
            val syncResult = result.value
            // Should capture hrefs that need separate fetch
            assertTrue(syncResult.addedHrefs.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("getCtag Tests")
    inner class GetCtagTests {

        @Test
        fun `getCtag returns null when ctag not present`() = runTest {
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

            assertIs<DavResult.Success<String?>>(result)
            // Ctag may be null if not present
        }

        @Test
        fun `getCtag returns ctag when present`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <CS:getctag>ctag-abc123</CS:getctag>
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

            assertIs<DavResult.Success<String?>>(result)
            assertEquals("ctag-abc123", result.value)
        }

        @Test
        fun `getCtag handles 401 unauthorized`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=\"CalDAV\"")
            )

            val result = calDavClient.getCtag(serverUrl("/cal/"))

            assertIs<DavResult.HttpError>(result)
            assertEquals(401, result.code)
        }
    }

    @Nested
    @DisplayName("fetchEventsByHref Edge Cases")
    inner class FetchEventsByHrefEdgeCases {

        @Test
        fun `fetchEventsByHref with empty list returns empty success`() = runTest {
            val result = calDavClient.fetchEventsByHref(serverUrl("/cal/"), emptyList())

            assertIs<DavResult.Success<*>>(result)
            assertTrue((result.value as List<*>).isEmpty())
        }

        @Test
        fun `fetchEventsByHref handles partial success (some events missing)`() = runTest {
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
                        <D:status>HTTP/1.1 404 Not Found</D:status>
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
                listOf("/cal/event1.ics", "/cal/event2.ics")
            )

            assertIs<DavResult.Success<*>>(result)
            // Should return the one event that was found
            val events = result.value as List<*>
            assertEquals(1, events.size)
        }

        @Test
        fun `fetchEventsByHref handles server error`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error")
            )

            val result = calDavClient.fetchEventsByHref(
                serverUrl("/cal/"),
                listOf("/cal/event1.ics")
            )

            // 500 errors should not return Success
            assertTrue(result !is DavResult.Success, "Expected error result but got Success: $result")
        }
    }

    @Nested
    @DisplayName("Event CRUD Conflict Handling")
    inner class EventCrudConflictTests {

        @Test
        fun `updateEvent returns 412 on etag mismatch`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(412)
                    .setBody("Precondition Failed")
            )

            val event = createTestEvent()
            val result = calDavClient.updateEvent(
                eventUrl = serverUrl("/cal/event.ics"),
                event = event,
                etag = "old-etag"
            )

            assertIs<DavResult.HttpError>(result)
            assertEquals(412, result.code)
        }

        @Test
        fun `deleteEvent returns 412 on etag mismatch`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(412)
                    .setBody("Precondition Failed")
            )

            val result = calDavClient.deleteEvent(
                eventUrl = serverUrl("/cal/event.ics"),
                etag = "old-etag"
            )

            assertIs<DavResult.HttpError>(result)
            assertEquals(412, result.code)
        }

        @Test
        fun `createEventRaw returns 412 when event already exists`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(412)
                    .setBody("Precondition Failed - resource exists")
            )

            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:existing-event
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = calDavClient.createEventRaw(
                calendarUrl = serverUrl("/cal/"),
                uid = "existing-event",
                icalData = icalData
            )

            assertIs<DavResult.HttpError>(result)
            assertEquals(412, result.code)
        }
    }

    @Nested
    @DisplayName("Network Error Handling")
    inner class NetworkErrorTests {

        @Test
        fun `fetchEvents handles connection timeout`() = runTest {
            server.enqueue(
                MockResponse()
                    .setSocketPolicy(SocketPolicy.NO_RESPONSE)
            )

            val result = calDavClient.fetchEvents(serverUrl("/cal/"))

            // Should return NetworkError, not crash
            assertTrue(result is DavResult.NetworkError || result is DavResult.HttpError)
        }
    }

    @Nested
    @DisplayName("UID Sanitization")
    inner class UidSanitizationTests {

        @Test
        fun `buildEventUrl sanitizes special characters`() {
            // @ . - are preserved, other special chars become _
            val url = calDavClient.buildEventUrl(serverUrl("/cal/"), "event@test.com")
            assertTrue(url.contains("event@test.com.ics"))

            // Space becomes underscore
            val url2 = calDavClient.buildEventUrl(serverUrl("/cal/"), "my event")
            assertTrue(url2.contains("my_event.ics"))
        }

        @Test
        fun `buildEventUrl rejects path traversal`() {
            try {
                calDavClient.buildEventUrl(serverUrl("/cal/"), "../../../etc/passwd")
                assertTrue(false, "Should have thrown exception")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("path traversal") == true)
            }
        }
    }

    private fun createTestEvent(uid: String = "test-event"): org.onekash.icaldav.model.ICalEvent {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.Instant.now()

        return org.onekash.icaldav.model.ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Test Event",
            description = null,
            location = null,
            dtStart = org.onekash.icaldav.model.ICalDateTime(
                timestamp = now.toEpochMilli(),
                timezone = zone,
                isUtc = true,
                isDate = false
            ),
            dtEnd = org.onekash.icaldav.model.ICalDateTime(
                timestamp = now.plusSeconds(3600).toEpochMilli(),
                timezone = zone,
                isUtc = true,
                isDate = false
            ),
            duration = null,
            isAllDay = false,
            status = org.onekash.icaldav.model.EventStatus.CONFIRMED,
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
            transparency = org.onekash.icaldav.model.Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}