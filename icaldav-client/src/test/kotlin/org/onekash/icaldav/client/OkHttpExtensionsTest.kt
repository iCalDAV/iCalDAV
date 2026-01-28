package org.onekash.icaldav.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * Unit tests for OkHttp Call.await() suspend extension.
 */
class OkHttpExtensionsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `await returns response on success`() = runTest {
        server.enqueue(MockResponse().setBody("OK").setResponseCode(200))

        val request = Request.Builder()
            .url(server.url("/"))
            .build()

        val response = client.newCall(request).await()
        response.use { r ->
            assertEquals(200, r.code)
            assertEquals("OK", r.body?.string())
        }
    }

    @Test
    fun `await returns response for error status codes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val request = Request.Builder()
            .url(server.url("/missing"))
            .build()

        val response = client.newCall(request).await()
        response.use { r ->
            assertEquals(404, r.code)
        }
    }

    @Test
    fun `await throws IOException on network error`() = runTest {
        // Use unreachable IP to trigger network error
        val unreachableClient = OkHttpClient.Builder()
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("http://10.255.255.1/")
            .build()

        assertFailsWith<IOException> {
            unreachableClient.newCall(request).await()
        }
    }

    @Test
    fun `await cancels HTTP call when coroutine is cancelled`() = runTest {
        // Don't enqueue any response - the request will hang waiting

        val request = Request.Builder()
            .url(server.url("/slow"))
            .build()

        val call = client.newCall(request)

        val job = launch {
            try {
                call.await()
            } catch (e: Exception) {
                // Expected to be cancelled
            }
        }

        // Give the request time to start
        delay(50)

        // Cancel the coroutine
        job.cancel()

        // Wait for cancellation to propagate
        delay(50)

        // Verify the HTTP call was cancelled
        assertTrue(call.isCanceled(), "HTTP call should be cancelled when coroutine is cancelled")
    }

    @Test
    fun `response can be used with use pattern`() = runTest {
        server.enqueue(MockResponse().setBody("Test Body"))

        val request = Request.Builder()
            .url(server.url("/"))
            .build()

        val body = client.newCall(request).await().use { response ->
            response.body?.string()
        }

        assertEquals("Test Body", body)
    }

    @Test
    fun `await works with POST request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("Created"))

        val request = Request.Builder()
            .url(server.url("/create"))
            .post("data".toRequestBody(null))
            .build()

        val response = client.newCall(request).await()
        response.use { r ->
            assertEquals(201, r.code)
        }

        // Verify the request was received
        val recordedRequest = server.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("data", recordedRequest.body.readUtf8())
    }
}
