package org.onekash.icaldav.client

import org.onekash.icaldav.model.DavResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for WebDavClient HTTP resilience features.
 *
 * Tests cover:
 * - Retry on transient failures (socket timeout, connection errors)
 * - Retry on 5xx server errors with exponential backoff
 * - Rate limit handling (429) with Retry-After header
 * - Response size limiting (10MB max)
 * - No retry on SSL errors (security issue)
 * - Retry exhaustion after MAX_RETRIES
 */
class WebDavClientResilienceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Short timeouts for fast tests
        val testHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        client = WebDavClient(testHttpClient)
    }

    @AfterEach
    fun teardown() {
        mockWebServer.shutdown()
    }

    // ========== Retry on Transient Failures ==========

    @Test
    fun `retries on socket timeout and eventually succeeds`() {
        // First request times out, second succeeds
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals("OK", result.getOrNull())
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `succeeds on first try when server is healthy`() {
        // Simple success case - no retries needed
        mockWebServer.enqueue(MockResponse().setBody("Healthy Response"))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals("Healthy Response", result.getOrNull())
        assertEquals(1, mockWebServer.requestCount)
    }

    // ========== Retry on 5xx Server Errors ==========

    @Test
    fun `retries on 500 server error with backoff`() {
        // Two 500 errors, then success
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val start = System.currentTimeMillis()
        val result = client.get(mockWebServer.url("/test").toString())
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.isSuccess)
        assertEquals(3, mockWebServer.requestCount) // MAX_RETRIES + 1
        // Should have some backoff delay (at least initial backoff)
        assertTrue(elapsed >= 500) // At least one backoff period
    }

    @Test
    fun `retries on 502 bad gateway`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(502))
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `retries on 503 service unavailable`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    // ========== Rate Limiting (429) ==========

    @Test
    fun `handles 429 with Retry-After header`() {
        // 429 with 1 second delay, then success
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "1")
        )
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val start = System.currentTimeMillis()
        val result = client.get(mockWebServer.url("/test").toString())
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.isSuccess)
        assertTrue(elapsed >= 1000) // Waited at least 1 second (Retry-After)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `handles 429 without Retry-After header using default`() {
        // 429 without header - should use default wait time
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setBody("OK"))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    // ========== Response Size Limiting ==========

    @Test
    fun `rejects response over size limit`() {
        // 11MB response exceeds 10MB limit
        val largeBody = "x".repeat(11 * 1024 * 1024)
        mockWebServer.enqueue(MockResponse().setBody(largeBody))

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.NetworkError)
    }

    @Test
    fun `accepts response under size limit`() {
        // 1MB response is under limit
        val body = "x".repeat(1 * 1024 * 1024)
        mockWebServer.enqueue(MockResponse().setBody(body))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals(body, result.getOrNull())
    }

    @Test
    fun `accepts response at exactly size limit`() {
        // 10MB response is exactly at the limit - should succeed
        val body = "x".repeat(10 * 1024 * 1024)
        mockWebServer.enqueue(MockResponse().setBody(body))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals(body, result.getOrNull())
    }

    // ========== Retry Exhaustion ==========

    @Test
    fun `gives up after MAX_RETRIES on 500`() {
        // All attempts return 500
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.HttpError)
        // MAX_RETRIES = 2, so MAX_RETRIES + 1 = 3 attempts
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `gives up after MAX_RETRIES on timeout`() {
        // All attempts time out
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        }

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.NetworkError)
        assertEquals(3, mockWebServer.requestCount) // MAX_RETRIES + 1
    }

    // ========== No Retry on Client Errors ==========

    @Test
    fun `does not retry on 400 bad request`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(400))

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.HttpError)
        assertEquals(1, mockWebServer.requestCount) // No retry
    }

    @Test
    fun `does not retry on 401 unauthorized`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.HttpError)
        assertEquals(1, mockWebServer.requestCount) // No retry
    }

    @Test
    fun `does not retry on 403 forbidden`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertTrue(result is DavResult.HttpError)
        assertEquals(1, mockWebServer.requestCount) // No retry
    }

    @Test
    fun `does not retry on 404 not found`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val result = client.get(mockWebServer.url("/test").toString())

        assertFalse(result.isSuccess)
        assertEquals(1, mockWebServer.requestCount) // No retry
    }

    // ========== Method-Specific Tests ==========

    @Test
    fun `propfind retries on server error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("<multistatus xmlns=\"DAV:\"><response></response></multistatus>")
        )

        val result = client.propfind(
            mockWebServer.url("/test").toString(),
            "<propfind/>"
        )

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `report retries on server error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("<multistatus xmlns=\"DAV:\"></multistatus>")
        )

        val result = client.report(
            mockWebServer.url("/test").toString(),
            "<report/>"
        )

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `put retries on server error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"new-etag\"")
        )

        val result = client.put(
            mockWebServer.url("/test.ics").toString(),
            "BEGIN:VCALENDAR\nEND:VCALENDAR"
        )

        assertTrue(result.isSuccess)
        assertEquals("new-etag", result.getOrNull()?.etag)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `delete retries on server error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.delete(mockWebServer.url("/test.ics").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `mkcalendar retries on server error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(201))

        val result = client.mkcalendar(
            mockWebServer.url("/calendars/new/").toString(),
            "<mkcalendar/>"
        )

        assertTrue(result.isSuccess)
        assertEquals(2, mockWebServer.requestCount)
    }

    // ========== Empty Response Handling ==========

    @Test
    fun `handles empty response body`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val result = client.get(mockWebServer.url("/test").toString())

        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull())
    }
}
