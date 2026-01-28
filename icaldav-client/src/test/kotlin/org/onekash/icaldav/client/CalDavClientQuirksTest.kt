package org.onekash.icaldav.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.test.runTest
import org.onekash.icaldav.quirks.CalDavProvider

/**
 * Unit tests for CalDavClient factory methods and CalDavProvider integration.
 */
class CalDavClientQuirksTest {

    private lateinit var mockServer: MockWebServer

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun `forProvider creates client for iCloud`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://caldav.icloud.com",
            username = "test@icloud.com",
            password = "test-password"
        )

        assertNotNull(client)
    }

    @Test
    fun `forProvider creates client for Google`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://www.google.com/calendar/dav/test@gmail.com/",
            username = "test@gmail.com",
            password = "test-password"
        )

        assertNotNull(client)
    }

    @Test
    fun `forProvider creates client for generic server`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://caldav.myserver.com/dav/",
            username = "user",
            password = "pass"
        )

        assertNotNull(client)
    }

    @Test
    fun `forProvider accepts custom userAgent`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://caldav.example.com",
            username = "user",
            password = "pass",
            userAgent = "MyApp/1.0 (Android)"
        )

        assertNotNull(client)
    }

    @Test
    fun `withBasicAuth creates client`() {
        val client = CalDavClient.withBasicAuth(
            username = "user",
            password = "pass"
        )

        assertNotNull(client)
    }

    @Test
    fun `withBasicAuth accepts custom userAgent`() {
        val client = CalDavClient.withBasicAuth(
            username = "user",
            password = "pass",
            userAgent = "KashCal/2.0 (Android)"
        )

        assertNotNull(client)
    }

    // ========== User-Agent Verification Tests ==========

    @Test
    fun `forProvider passes userAgent to HTTP client`() = runTest {
        // Enqueue a response for the PROPFIND request
        mockServer.enqueue(MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml")
            .setBody("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/calendar/</d:href>
                        <d:propstat>
                            <d:prop>
                                <cs:getctag>ctag-123</cs:getctag>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()))

        val client = CalDavClient.forProvider(
            serverUrl = mockServer.url("/").toString(),
            username = "user",
            password = "pass",
            userAgent = "TestApp/1.0"
        )

        // Trigger a request
        client.getCtag(mockServer.url("/calendar/").toString())

        // Verify User-Agent header was sent
        val request = mockServer.takeRequest()
        assertEquals("TestApp/1.0", request.getHeader("User-Agent"))
    }

    @Test
    fun `withBasicAuth passes userAgent to HTTP client`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml")
            .setBody("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/calendar/</d:href>
                        <d:propstat>
                            <d:prop>
                                <cs:getctag>ctag-456</cs:getctag>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()))

        val client = CalDavClient.withBasicAuth(
            username = "user",
            password = "pass",
            userAgent = "KashCal/2.0 (Android)"
        )

        client.getCtag(mockServer.url("/calendar/").toString())

        val request = mockServer.takeRequest()
        assertEquals("KashCal/2.0 (Android)", request.getHeader("User-Agent"))
    }

    @Test
    fun `default userAgent is set when not specified`() = runTest {
        mockServer.enqueue(MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml")
            .setBody("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/calendar/</d:href>
                    </d:response>
                </d:multistatus>
            """.trimIndent()))

        val client = CalDavClient.withBasicAuth(
            username = "user",
            password = "pass"
            // No userAgent specified - should use default
        )

        client.getCtag(mockServer.url("/calendar/").toString())

        val request = mockServer.takeRequest()
        assertEquals("iCalDAV/1.0 (Kotlin)", request.getHeader("User-Agent"))
    }

    @Test
    fun `constructor creates client with webDavClient`() {
        val webDavClient = WebDavClient(WebDavClient.defaultHttpClient())

        val client = CalDavClient(webDavClient)

        assertNotNull(client)
    }

    // ========== CalDavProvider Tests ==========

    @Test
    fun `CalDavProvider forServer detects iCloud`() {
        val provider = CalDavProvider.forServer("https://caldav.icloud.com")

        assertEquals("icloud", provider.id)
        assertTrue(provider.requiresAppPassword)
        assertEquals(setOf(403), provider.invalidSyncTokenCodes)
    }

    @Test
    fun `CalDavProvider forServer returns generic for unknown`() {
        val provider = CalDavProvider.forServer("https://nextcloud.myserver.com/remote.php/dav/")

        assertEquals("generic", provider.id)
        assertFalse(provider.requiresAppPassword)
        assertEquals(setOf(403, 412), provider.invalidSyncTokenCodes)
    }

    @Test
    fun `CalDavProvider ICLOUD constant is configured correctly`() {
        val provider = CalDavProvider.ICLOUD

        assertEquals("icloud", provider.id)
        assertEquals("iCloud", provider.displayName)
        assertEquals("https://caldav.icloud.com", provider.baseUrl)
        assertTrue(provider.requiresAppPassword)
    }

    @Test
    fun `CalDavProvider generic factory creates correct provider`() {
        val provider = CalDavProvider.generic("https://caldav.example.com")

        assertEquals("generic", provider.id)
        assertEquals("CalDAV Server", provider.displayName)
        assertEquals("https://caldav.example.com", provider.baseUrl)
        assertFalse(provider.requiresAppPassword)
    }

    // ========== Legacy API Tests (with deprecation) ==========

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated CalDavQuirks forServer still works`() {
        // Ensure backward compatibility
        val quirks = org.onekash.icaldav.quirks.CalDavQuirks.forServer("https://caldav.icloud.com")

        assertTrue(quirks is org.onekash.icaldav.quirks.ICloudQuirks)
        assertEquals("icloud", quirks.providerId)
    }
}
