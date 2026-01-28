package org.onekash.icaldav.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest
import org.onekash.icaldav.model.DavResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for OPTIONS-based server capability discovery.
 *
 * Tests WebDavClient.options() and CalDavClient.getCapabilities() including
 * caching behavior and graceful degradation.
 */
@DisplayName("Server Capabilities Tests")
class ServerCapabilitiesTest {

    private lateinit var server: MockWebServer
    private lateinit var webDavClient: WebDavClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        webDavClient = WebDavClient(WebDavClient.testHttpClient())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun serverUrl(path: String = "/"): String {
        return server.url(path).toString()
    }

    @Nested
    @DisplayName("WebDavClient.options() Tests")
    inner class WebDavClientOptionsTests {

        @Test
        fun `parses DAV header with calendar-access`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, calendar-access, addressbook")
                .setHeader("Allow", "OPTIONS, GET, PUT, DELETE, PROPFIND, REPORT"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.davClasses.contains("calendar-access"))
            assertTrue(caps.davClasses.contains("1"))
            assertTrue(caps.davClasses.contains("2"))
        }

        @Test
        fun `parses DAV header with sync-collection`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, calendar-access, sync-collection")
                .setHeader("Allow", "OPTIONS, PROPFIND, REPORT"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsSyncCollection)
        }

        @Test
        fun `detects sync-collection from DAV class 3`() = runTest {
            // Some servers advertise class 3 instead of sync-collection
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, calendar-access")
                .setHeader("Allow", "OPTIONS, PROPFIND, REPORT"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsSyncCollection)
        }

        @Test
        fun `parses Allow header for methods`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "OPTIONS, GET, PUT, DELETE, PROPFIND, REPORT, MKCALENDAR"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsPropfind)
            assertTrue(caps.supportsReport)
            assertTrue(caps.supportsMkcalendar)
            assertTrue(caps.allowedMethods.contains("GET"))
            assertTrue(caps.allowedMethods.contains("PUT"))
            assertTrue(caps.allowedMethods.contains("DELETE"))
        }

        @Test
        fun `handles 405 Method Not Allowed gracefully`() = runTest {
            // Some servers don't support OPTIONS
            server.enqueue(MockResponse().setResponseCode(405))

            val result = webDavClient.options(serverUrl())

            // Should return UNKNOWN, not error
            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertEquals(ServerCapabilities.UNKNOWN, caps)
            assertTrue(caps.davClasses.isEmpty())
            assertTrue(caps.allowedMethods.isEmpty())
        }

        @Test
        fun `returns error for auth failures`() = runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = webDavClient.options(serverUrl())

            assertFalse(result.isSuccess)
            assertTrue(result is DavResult.HttpError)
            assertEquals(401, (result as DavResult.HttpError).code)
        }

        @Test
        fun `handles missing DAV header`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Allow", "GET, PUT"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.davClasses.isEmpty())
            assertFalse(caps.supportsCalDav)
            assertTrue(caps.allowedMethods.contains("GET"))
        }

        @Test
        fun `handles missing Allow header`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.allowedMethods.isEmpty())
        }

        @Test
        fun `preserves raw DAV header for debugging`() = runTest {
            val rawDav = "1, 2, calendar-access, extended-mkcol"
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", rawDav)
                .setHeader("Allow", "OPTIONS"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertEquals(rawDav, caps.rawDavHeader)
        }

        @Test
        fun `handles angle brackets in DAV header`() = runTest {
            // Some servers wrap values in angle brackets
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "<1>, <calendar-access>")
                .setHeader("Allow", "OPTIONS"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.davClasses.contains("1"))
            assertTrue(caps.davClasses.contains("calendar-access"))
        }
    }

    @Nested
    @DisplayName("ServerCapabilities Model Tests")
    inner class ServerCapabilitiesModelTests {

        @Test
        fun `UNKNOWN has empty collections`() {
            val unknown = ServerCapabilities.UNKNOWN
            assertTrue(unknown.davClasses.isEmpty())
            assertTrue(unknown.allowedMethods.isEmpty())
            assertFalse(unknown.supportsCalDav)
            assertFalse(unknown.supportsSyncCollection)
        }

        @Test
        fun `fromHeaders parses correctly`() {
            val caps = ServerCapabilities.fromHeaders(
                davHeader = "1, 2, calendar-access",
                allowHeader = "GET, PUT, PROPFIND"
            )

            assertEquals(3, caps.davClasses.size)
            assertEquals(3, caps.allowedMethods.size)
            assertTrue(caps.supportsCalDav)
        }

        @Test
        fun `fromHeaders handles null inputs`() {
            val caps = ServerCapabilities.fromHeaders(null, null)
            assertTrue(caps.davClasses.isEmpty())
            assertTrue(caps.allowedMethods.isEmpty())
        }

        @Test
        fun `supportsExtendedMkcol detection`() {
            val caps = ServerCapabilities.fromHeaders(
                "1, extended-mkcol, calendar-access",
                null
            )
            assertTrue(caps.supportsExtendedMkcol)
        }

        @Test
        fun `supportsAutoSchedule detection`() {
            val caps = ServerCapabilities.fromHeaders(
                "1, calendar-access, calendar-auto-schedule",
                null
            )
            assertTrue(caps.supportsAutoSchedule)
        }

        @Test
        fun `case insensitive DAV class matching`() {
            val caps = ServerCapabilities.fromHeaders(
                "CALENDAR-ACCESS, Sync-Collection",
                null
            )
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.supportsSyncCollection)
        }

        @Test
        fun `case insensitive method matching`() {
            val caps = ServerCapabilities.fromHeaders(
                null,
                "propfind, REPORT, MkCalendar"
            )
            assertTrue(caps.supportsPropfind)
            assertTrue(caps.supportsReport)
            assertTrue(caps.supportsMkcalendar)
        }

        @Test
        fun `discoveredAt timestamp is set`() {
            val before = System.currentTimeMillis()
            val caps = ServerCapabilities.fromHeaders("1", "GET")
            val after = System.currentTimeMillis()

            assertTrue(caps.discoveredAt >= before)
            assertTrue(caps.discoveredAt <= after)
        }
    }

    @Nested
    @DisplayName("CalDavClient.getCapabilities() Caching Tests")
    inner class CalDavClientCachingTests {

        @Test
        fun `caches capabilities on first call`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "PROPFIND"))

            val calDavClient = CalDavClient(webDavClient)

            // First call - hits server
            val result1 = calDavClient.getCapabilities(serverUrl())
            assertTrue(result1.isSuccess)
            assertEquals(1, server.requestCount)

            // Second call - should use cache
            val result2 = calDavClient.getCapabilities(serverUrl())
            assertTrue(result2.isSuccess)
            assertEquals(1, server.requestCount) // No additional request
        }

        @Test
        fun `forceRefresh bypasses cache`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "PROPFIND"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access, sync-collection")
                .setHeader("Allow", "PROPFIND, REPORT"))

            val calDavClient = CalDavClient(webDavClient)

            // First call
            val result1 = calDavClient.getCapabilities(serverUrl())
            assertTrue(result1.isSuccess)
            assertFalse((result1 as DavResult.Success).value.supportsSyncCollection)

            // Second call with forceRefresh
            val result2 = calDavClient.getCapabilities(serverUrl(), forceRefresh = true)
            assertTrue(result2.isSuccess)
            assertTrue((result2 as DavResult.Success).value.supportsSyncCollection)
            assertEquals(2, server.requestCount)
        }

        @Test
        fun `clearCapabilitiesCache removes cached entries`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1")
                .setHeader("Allow", "GET"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "GET, PROPFIND"))

            val calDavClient = CalDavClient(webDavClient)

            // First call
            calDavClient.getCapabilities(serverUrl())
            assertEquals(1, server.requestCount)

            // Clear cache
            calDavClient.clearCapabilitiesCache()

            // Next call should hit server again
            val result = calDavClient.getCapabilities(serverUrl())
            assertEquals(2, server.requestCount)
            assertTrue((result as DavResult.Success).value.supportsCalDav)
        }

        @Test
        fun `caches per URL`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "PROPFIND"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1")
                .setHeader("Allow", "GET"))

            val calDavClient = CalDavClient(webDavClient)

            // Different URLs should have separate cache entries
            val result1 = calDavClient.getCapabilities(serverUrl("/server1"))
            val result2 = calDavClient.getCapabilities(serverUrl("/server2"))

            assertEquals(2, server.requestCount)
            assertTrue((result1 as DavResult.Success).value.supportsCalDav)
            assertFalse((result2 as DavResult.Success).value.supportsCalDav)
        }
    }

    @Nested
    @DisplayName("CalDavClient.syncCollectionIfSupported() Tests")
    inner class SyncCollectionIfSupportedTests {

        @Test
        fun `returns null when sync-collection not supported`() = runTest {
            // OPTIONS response without sync-collection
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "PROPFIND"))

            val calDavClient = CalDavClient(webDavClient)
            val result = calDavClient.syncCollectionIfSupported(serverUrl("/calendar/"))

            assertTrue(result.isSuccess)
            val syncResult = (result as DavResult.Success).value
            assertEquals(null, syncResult)
        }

        @Test
        fun `calls syncCollection when supported`() = runTest {
            // OPTIONS response with sync-collection
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access, sync-collection")
                .setHeader("Allow", "PROPFIND, REPORT"))

            // Sync-collection REPORT response
            server.enqueue(MockResponse()
                .setResponseCode(207)
                .setBody("""<?xml version="1.0" encoding="UTF-8"?>
                    <D:multistatus xmlns:D="DAV:">
                        <D:sync-token>http://example.com/sync/1234</D:sync-token>
                    </D:multistatus>"""))

            val calDavClient = CalDavClient(webDavClient)
            val result = calDavClient.syncCollectionIfSupported(serverUrl("/calendar/"))

            assertTrue(result.isSuccess)
            val syncResult = (result as DavResult.Success).value
            assertNotNull(syncResult)
            assertEquals("http://example.com/sync/1234", syncResult.newSyncToken)
        }
    }

    @Nested
    @DisplayName("Real Server Response Scenarios")
    inner class RealServerResponseTests {

        @Test
        fun `parses iCloud-style DAV header`() = runTest {
            // iCloud returns this format
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 2, 3, access-control, calendar-access, calendar-schedule, calendar-auto-schedule, calendar-availability, inbox-availability, calendar-proxy, calendarserver-private-events, calendarserver-private-comments, calendarserver-sharing, calendarserver-sharing-no-scheduling, calendar-query-extended, calendar-default-alarms, calendar-managed-attachments, calendarserver-partstat-changes, calendarserver-group-sharee, calendar-no-timezone, calendarserver-home-sync")
                .setHeader("Allow", "OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK, REPORT, ACL, MKCALENDAR, MKCOL"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.supportsSyncCollection) // DAV class 3
            assertTrue(caps.supportsAutoSchedule)
            assertTrue(caps.supportsPropfind)
            assertTrue(caps.supportsReport)
            assertTrue(caps.supportsMkcalendar)
        }

        @Test
        fun `parses Nextcloud-style DAV header`() = runTest {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, 3, extended-mkcol, addressbook, calendar-access, calendar-auto-schedule, calendarserver-subscribed, calendar-availability, nc-calendar-webcal-cache, calendarserver-sharing, calendarserver-principal-property-search, calendar-proxy")
                .setHeader("Allow", "OPTIONS, GET, HEAD, DELETE, PROPFIND, PUT, PROPPATCH, COPY, MOVE, REPORT, PATCH"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertTrue(caps.supportsSyncCollection)
            assertTrue(caps.supportsExtendedMkcol)
            assertTrue(caps.supportsAutoSchedule)
        }

        @Test
        fun `parses minimal CalDAV server`() = runTest {
            // Minimal server with basic CalDAV support
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("DAV", "1, calendar-access")
                .setHeader("Allow", "OPTIONS, GET, PUT, DELETE, PROPFIND"))

            val result = webDavClient.options(serverUrl())

            assertTrue(result.isSuccess)
            val caps = (result as DavResult.Success).value
            assertTrue(caps.supportsCalDav)
            assertFalse(caps.supportsSyncCollection)
            assertFalse(caps.supportsReport)
            assertFalse(caps.supportsMkcalendar)
        }
    }
}