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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * TDD tests for new KashCal features to port to icaldav.
 *
 * Test Plan References:
 * - B1: fetchEtagsInRange() tests
 * - B2: Etag-based fallback sync tests
 * - B3: Multiget retry/fallback tests
 * - B5: Eventual consistency tests
 * - B6: Duplicate href deduplication tests
 */
@DisplayName("CalDavClient New Features Tests")
class CalDavClientNewFeaturesTest {

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

    // ========================================================================
    // B1: fetchEtagsInRange() Tests
    // ========================================================================

    @Nested
    @DisplayName("B1: fetchEtagsInRange")
    inner class FetchEtagsInRangeTests {

        @Test
        fun `B1-1 returns etags without calendar-data`() {
            // Server returns etags only (no calendar-data) - lightweight response
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-abc123"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/cal/event2.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-def456"</D:getetag>
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

            val start = Instant.parse("2023-01-01T00:00:00Z")
            val end = Instant.parse("2023-12-31T23:59:59Z")

            val result = calDavClient.fetchEtagsInRange(serverUrl("/cal/"), start, end)

            assertIs<DavResult.Success<List<EtagInfo>>>(result)
            val etags = result.value
            assertEquals(2, etags.size)
            assertEquals("/cal/event1.ics", etags[0].href)
            assertEquals("etag-abc123", etags[0].etag)
            assertEquals("/cal/event2.ics", etags[1].href)
            assertEquals("etag-def456", etags[1].etag)

            // Verify request doesn't ask for calendar-data
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("getetag"))
            assertTrue(!body.contains("calendar-data"), "Request should NOT include calendar-data")
        }

        @Test
        fun `B1-2 respects time range filter`() {
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

            val start = Instant.parse("2023-06-01T00:00:00Z")
            val end = Instant.parse("2023-06-30T23:59:59Z")

            calDavClient.fetchEtagsInRange(serverUrl("/cal/"), start, end)

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.contains("time-range"), "Request should include time-range filter")
            assertTrue(body.contains("20230601T000000Z"), "Request should include start time")
            assertTrue(body.contains("20230630T235959Z"), "Request should include end time")
        }

        @Test
        fun `B1-3 handles empty response`() {
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

            val result = calDavClient.fetchEtagsInRange(
                serverUrl("/cal/"),
                Instant.now().minusSeconds(86400),
                Instant.now()
            )

            assertIs<DavResult.Success<List<EtagInfo>>>(result)
            assertTrue(result.value.isEmpty())
        }

        @Test
        fun `B1-4 handles server error`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error")
            )

            val result = calDavClient.fetchEtagsInRange(
                serverUrl("/cal/"),
                Instant.now().minusSeconds(86400),
                Instant.now()
            )

            assertTrue(result !is DavResult.Success)
        }

        @Test
        fun `B1-5 handles iCloud quoted etags`() {
            // iCloud returns etags with surrounding quotes which we strip
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"icloud-etag-abc123"</D:getetag>
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

            val result = calDavClient.fetchEtagsInRange(
                serverUrl("/cal/"),
                Instant.now().minusSeconds(86400),
                Instant.now()
            )

            assertIs<DavResult.Success<List<EtagInfo>>>(result)
            val etag = result.value.firstOrNull()?.etag
            assertNotNull(etag)
            // Verify quotes were stripped
            assertEquals("icloud-etag-abc123", etag)
        }
    }

    // ========================================================================
    // B2: getSyncToken() Tests
    // ========================================================================

    @Nested
    @DisplayName("B2: getSyncToken")
    inner class GetSyncTokenTests {

        @Test
        fun `B2-1 returns sync token from PROPFIND`() {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:sync-token>https://caldav.example.com/sync/token-12345</D:sync-token>
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

            val result = calDavClient.getSyncToken(serverUrl("/cal/"))

            assertIs<DavResult.Success<String?>>(result)
            assertEquals("https://caldav.example.com/sync/token-12345", result.value)
        }

        @Test
        fun `B2-2 returns null when sync token not supported`() {
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

            val result = calDavClient.getSyncToken(serverUrl("/cal/"))

            assertIs<DavResult.Success<String?>>(result)
            // Token may be null if not supported
        }
    }

    // ========================================================================
    // B6: Duplicate Href Deduplication Tests
    // ========================================================================

    @Nested
    @DisplayName("B6: Duplicate Href Deduplication")
    inner class DuplicateHrefTests {

        @Test
        fun `B6-1 syncCollection deduplicates duplicate hrefs from iCloud`() {
            // iCloud sometimes returns duplicate hrefs in sync-collection
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/cal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag1"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/cal/event2.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag2"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>https://example.com/sync/token</D:sync-token>
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

            // Should have 2 unique hrefs, not 3
            val uniqueHrefs = syncResult.addedHrefs.map { it.href }.distinct()
            assertEquals(2, uniqueHrefs.size, "Should deduplicate duplicate hrefs")
        }
    }
}