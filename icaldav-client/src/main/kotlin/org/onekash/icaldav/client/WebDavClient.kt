package org.onekash.icaldav.client

import org.onekash.icaldav.model.*
import org.onekash.icaldav.xml.MultiStatusParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException

/**
 * WebDAV HTTP client built on OkHttp.
 *
 * Provides low-level WebDAV operations (PROPFIND, REPORT, GET, PUT, DELETE)
 * with support for Basic authentication.
 *
 * Uses raw HTTP approach which works reliably with iCloud
 * and other CalDAV servers.
 */
class WebDavClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val auth: DavAuth? = null
) {
    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()
    private val icalMediaType = "text/calendar; charset=utf-8".toMediaType()
    private val parser = MultiStatusParser.INSTANCE

    companion object {
        private val logger = Logger.getLogger(WebDavClient::class.java.name)

        // Retry configuration
        private const val MAX_RETRIES = 2
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 2.0

        // Response size limit (10MB) to prevent OOM
        private const val MAX_RESPONSE_SIZE_BYTES = 10L * 1024 * 1024

        /**
         * Create default OkHttpClient with sensible timeout settings.
         * Note: Uses followRedirects(false) - callers should use withAuth() for proper redirect handling.
         */
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // 5 min for large calendars
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)  // Handle redirects manually to preserve auth
                .build()
        }

        /**
         * Create OkHttpClient with short timeouts for testing.
         * Use this in unit tests to avoid hanging on missing mock responses.
         */
        fun testHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        }

        /**
         * Create OkHttpClient with authentication that handles redirects properly.
         *
         * This is critical for iCloud and other CalDAV servers that redirect to
         * partition servers (e.g., caldav.icloud.com → p180-caldav.icloud.com).
         * Standard OkHttp redirect handling strips Authorization headers on cross-host
         * redirects for security. This method uses a network interceptor to preserve
         * auth headers on all requests including redirects.
         *
         * @param auth Authentication credentials to use
         * @return OkHttpClient configured for CalDAV with proper redirect handling
         */
        fun withAuth(auth: DavAuth): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // 5 min for large calendars
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)  // Handle redirects manually
                .addNetworkInterceptor { chain ->
                    // Add auth header to ALL requests including redirects
                    val request = chain.request().newBuilder()
                        .header("Authorization", auth.toAuthHeader())
                        .header("User-Agent", "iCalDAV/1.0")
                        .build()

                    var response = chain.proceed(request)

                    // Handle redirects manually to preserve auth headers
                    var redirectCount = 0
                    while (response.code in listOf(301, 302, 303, 307, 308) && redirectCount < 5) {
                        val location = response.header("Location") ?: break

                        // Resolve relative URLs
                        val newUrl = request.url.resolve(location) ?: break

                        response.close()
                        val redirectRequest = request.newBuilder()
                            .url(newUrl)
                            .build()
                        response = chain.proceed(redirectRequest)
                        redirectCount++
                    }

                    response
                }
                .build()
        }

        /**
         * Normalize ETag by removing surrounding quotes if present.
         * ETags should be stored without quotes and quoted when sent in If-Match headers.
         */
        fun normalizeEtag(etag: String?): String? {
            if (etag == null) return null
            return etag.trim().removeSurrounding("\"")
        }

        /**
         * Format ETag for If-Match header (adds quotes if not present).
         */
        fun formatEtagForHeader(etag: String): String {
            val normalized = etag.trim().removeSurrounding("\"")
            return "\"$normalized\""
        }
    }

    /**
     * Perform PROPFIND request.
     *
     * @param url Target URL
     * @param body XML request body
     * @param depth Depth header (0, 1, or infinity)
     * @param preferMinimal If true, adds Prefer: return=minimal header to reduce response size
     * @return Parsed multistatus response
     */
    fun propfind(
        url: String,
        body: String,
        depth: DavDepth = DavDepth.ZERO,
        preferMinimal: Boolean = false
    ): DavResult<MultiStatus> {
        val requestBuilder = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(xmlMediaType))
            .header("Depth", depth.value)
            .header("Content-Type", "application/xml; charset=utf-8")
            .applyAuth()

        if (preferMinimal) {
            requestBuilder.header("Prefer", "return=minimal")
        }

        return executeAndParse(requestBuilder.build())
    }

    /**
     * Perform REPORT request (used for calendar-query, sync-collection, etc.).
     *
     * @param url Target URL
     * @param body XML request body
     * @param depth Depth header
     * @param preferMinimal If true, adds Prefer: return=minimal header to reduce response size
     * @return Parsed multistatus response
     */
    fun report(
        url: String,
        body: String,
        depth: DavDepth = DavDepth.ONE,
        preferMinimal: Boolean = false
    ): DavResult<MultiStatus> {
        val requestBuilder = Request.Builder()
            .url(url)
            .method("REPORT", body.toRequestBody(xmlMediaType))
            .header("Depth", depth.value)
            .header("Content-Type", "application/xml; charset=utf-8")
            .applyAuth()

        if (preferMinimal) {
            requestBuilder.header("Prefer", "return=minimal")
        }

        return executeAndParse(requestBuilder.build())
    }

    /**
     * Perform OPTIONS request to discover server capabilities.
     *
     * Returns the DAV compliance classes and allowed methods for the resource.
     * Use this to determine which features are supported before using them.
     *
     * @param url Target URL (usually server root or calendar collection)
     * @return Server capabilities parsed from response headers
     */
    fun options(url: String): DavResult<ServerCapabilities> {
        val request = Request.Builder()
            .url(url)
            .method("OPTIONS", null)
            .applyAuth()
            .build()

        return executeWithRetry(request) { response ->
            when {
                response.isSuccessful -> {
                    val davHeader = response.header("DAV")
                    val allowHeader = response.header("Allow")
                    DavResult.success(ServerCapabilities.fromHeaders(davHeader, allowHeader))
                }
                response.code == 405 -> {
                    // Method Not Allowed - server doesn't support OPTIONS
                    // Return UNKNOWN instead of error for graceful degradation
                    DavResult.success(ServerCapabilities.UNKNOWN)
                }
                else -> {
                    DavResult.httpError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Perform GET request to fetch a resource.
     * Uses retry logic and response size limiting.
     *
     * @param url Resource URL
     * @return Response body as string
     */
    fun get(url: String): DavResult<String> {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyAuth()
            .build()

        return executeWithRetry(request) { response ->
            if (response.isSuccessful) {
                try {
                    DavResult.success(response.bodyWithLimit())
                } catch (e: IOException) {
                    DavResult.networkError(e)
                }
            } else {
                DavResult.httpError(response.code, response.message)
            }
        }
    }

    /**
     * Perform PUT request to create or update a resource.
     * Uses retry logic for transient failures.
     *
     * @param url Resource URL
     * @param body Content to upload (iCal data for events)
     * @param etag ETag for conditional update (If-Match)
     * @param ifNoneMatch If true, adds If-None-Match: * header (fails if resource exists)
     * @param contentType Content type (defaults to text/calendar)
     * @return Response with new ETag if successful
     */
    fun put(
        url: String,
        body: String,
        etag: String? = null,
        ifNoneMatch: Boolean = false,
        contentType: MediaType = icalMediaType
    ): DavResult<PutResponse> {
        val requestBuilder = Request.Builder()
            .url(url)
            .put(body.toRequestBody(contentType))
            .header("Content-Type", contentType.toString())
            .applyAuth()

        // Conflict detection headers
        when {
            ifNoneMatch -> {
                // Fail if resource already exists - used for CREATE operations
                requestBuilder.header("If-None-Match", "*")
            }
            etag != null -> {
                // Fail if ETag doesn't match - used for UPDATE operations
                requestBuilder.header("If-Match", formatEtagForHeader(etag))
            }
        }

        val request = requestBuilder.build()

        return executeWithRetry(request) { response ->
            when {
                response.isSuccessful -> {
                    val newEtag = normalizeEtag(response.header("ETag"))
                    DavResult.success(PutResponse(response.code, newEtag))
                }
                response.code == 412 -> {
                    // Precondition Failed - ETag mismatch (conflict)
                    DavResult.httpError(412, "ETag conflict - resource was modified")
                }
                else -> {
                    DavResult.httpError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Perform DELETE request to remove a resource.
     * Uses retry logic for transient failures.
     *
     * @param url Resource URL
     * @param etag ETag for conditional delete (If-Match)
     * @return Success or error
     */
    fun delete(
        url: String,
        etag: String? = null
    ): DavResult<Unit> {
        val requestBuilder = Request.Builder()
            .url(url)
            .delete()
            .applyAuth()

        // Conditional delete with ETag (normalize to handle both quoted and unquoted)
        etag?.let {
            requestBuilder.header("If-Match", formatEtagForHeader(it))
        }

        val request = requestBuilder.build()

        return executeWithRetry(request) { response ->
            when {
                response.isSuccessful || response.code == 204 -> DavResult.success(Unit)
                response.code == 404 -> DavResult.success(Unit) // Already deleted
                response.code == 412 -> DavResult.httpError(412, "ETag conflict")
                else -> DavResult.httpError(response.code, response.message)
            }
        }
    }

    /**
     * Perform MKCALENDAR request to create a new calendar.
     * Uses retry logic for transient failures.
     *
     * @param url Calendar collection URL
     * @param body XML request body with calendar properties
     * @return Success or error
     */
    fun mkcalendar(url: String, body: String): DavResult<Unit> {
        val request = Request.Builder()
            .url(url)
            .method("MKCALENDAR", body.toRequestBody(xmlMediaType))
            .header("Content-Type", "application/xml; charset=utf-8")
            .applyAuth()
            .build()

        return executeWithRetry(request) { response ->
            if (response.isSuccessful || response.code == 201) {
                DavResult.success(Unit)
            } else {
                DavResult.httpError(response.code, response.message)
            }
        }
    }

    /**
     * Perform ACL request to modify access control list per RFC 3744 Section 8.1.
     *
     * HTTP Details:
     * - Method: ACL (custom WebDAV method)
     * - Content-Type: application/xml; charset=utf-8
     * - Request body: <d:acl> element with <d:ace> children
     * - Success: 200 OK
     * - Errors: 403 Forbidden, 409 Conflict (protected ACE)
     *
     * @param url Resource URL to modify ACL for
     * @param body XML request body (from RequestBuilder.acl())
     * @return Success or error
     */
    fun acl(url: String, body: String): DavResult<Unit> {
        val request = Request.Builder()
            .url(url)
            .method("ACL", body.toRequestBody(xmlMediaType))
            .header("Content-Type", "application/xml; charset=utf-8")
            .applyAuth()
            .build()

        return executeWithRetry(request) { response ->
            when {
                response.isSuccessful -> DavResult.success(Unit)
                response.code == 403 -> DavResult.httpError(403, "ACL modification forbidden")
                response.code == 409 -> DavResult.httpError(409, "Protected ACE conflict")
                else -> DavResult.httpError(response.code, response.message)
            }
        }
    }

    /**
     * Perform POST request for CalDAV scheduling (RFC 6638).
     *
     * Used to send iTIP messages to the schedule-outbox for delivery
     * to attendees or to query free/busy information.
     *
     * @param url The schedule-outbox URL
     * @param body iTIP message (iCalendar with METHOD)
     * @param recipients List of recipient email addresses
     * @return Raw XML response body for parsing by ScheduleResponseParser
     */
    fun post(
        url: String,
        body: String,
        recipients: List<String>
    ): DavResult<String> {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(icalMediaType))
            .header("Content-Type", "text/calendar; charset=utf-8")
            .applyAuth()

        // Add Recipient headers for each attendee
        recipients.forEach { recipient ->
            requestBuilder.addHeader("Recipient", "mailto:$recipient")
        }

        // Originator header - the calendar user making the request
        auth?.let { credentials ->
            if (credentials is DavAuth.Basic) {
                requestBuilder.header("Originator", "mailto:${credentials.username}")
            }
        }

        return executeWithRetry(requestBuilder.build()) { response ->
            when {
                response.isSuccessful -> {
                    try {
                        DavResult.success(response.bodyWithLimit())
                    } catch (e: IOException) {
                        DavResult.networkError(e)
                    }
                }
                response.code == 507 -> {
                    // Insufficient Storage - schedule-outbox full
                    DavResult.httpError(507, "Schedule outbox full")
                }
                response.code == 403 -> {
                    // Forbidden - not authorized to schedule
                    DavResult.httpError(403, "Not authorized to schedule")
                }
                else -> {
                    DavResult.httpError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Execute request and parse multistatus response.
     * Uses retry logic and response size limiting.
     */
    private fun executeAndParse(request: Request): DavResult<MultiStatus> {
        return executeWithRetry(request) { response ->
            when {
                response.isSuccessful -> {
                    try {
                        val responseBody = response.bodyWithLimit()
                        if (responseBody.isNotEmpty()) {
                            parser.parse(responseBody)
                        } else {
                            DavResult.success(MultiStatus.EMPTY)
                        }
                    } catch (e: IOException) {
                        DavResult.networkError(e)
                    }
                }
                else -> {
                    DavResult.httpError(response.code, response.message)
                }
            }
        }
    }

    /**
     * Apply authentication to request builder.
     */
    private fun Request.Builder.applyAuth(): Request.Builder {
        auth?.let { credentials ->
            header("Authorization", credentials.toAuthHeader())
        }
        return this
    }

    /**
     * Execute a request with automatic retry on transient failures.
     *
     * Retry behavior:
     * - Retries on: socket timeout, unknown host, connection errors, 429 rate limit, 5xx server errors
     * - Never retries: SSL errors (security issue), 4xx client errors (except 429)
     * - Uses exponential backoff between retries
     * - Respects Retry-After header for 429 responses
     *
     * @param request The HTTP request to execute
     * @param handler Function to process the response and return a DavResult
     * @return DavResult from the handler, or NetworkError if all retries fail
     */
    private fun <T> executeWithRetry(
        request: Request,
        handler: (Response) -> DavResult<T>
    ): DavResult<T> {
        var lastException: IOException? = null
        var currentBackoff = INITIAL_BACKOFF_MS

        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()

                // Handle 429 rate limiting
                if (response.code == 429 && attempt < MAX_RETRIES) {
                    val retryAfter = parseRetryAfterHeader(response)
                    logger.fine("Rate limited (429), waiting ${retryAfter}ms before retry")
                    response.close()
                    Thread.sleep(retryAfter)
                    return@repeat // Retry
                }

                // Handle 5xx server errors
                if (response.code in 500..599 && attempt < MAX_RETRIES) {
                    logger.fine("Server error ${response.code}, retry after ${currentBackoff}ms")
                    response.close()
                    Thread.sleep(currentBackoff)
                    currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER)
                        .toLong()
                        .coerceIn(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS)
                    return@repeat // Retry
                }

                return handler(response)

            } catch (e: SSLHandshakeException) {
                // NEVER retry SSL errors - indicates security issue (cert problem, MITM)
                logger.warning("SSL error (not retrying): ${e.message}")
                return DavResult.networkError(e)
            } catch (e: SocketTimeoutException) {
                logger.fine("Socket timeout, attempt ${attempt + 1}")
                lastException = e
            } catch (e: UnknownHostException) {
                logger.fine("Unknown host, attempt ${attempt + 1}")
                lastException = e
            } catch (e: ConnectException) {
                logger.fine("Connection failed, attempt ${attempt + 1}")
                lastException = e
            } catch (e: IOException) {
                if (!isRetryable(e)) {
                    return DavResult.networkError(e)
                }
                logger.fine("Retryable IO error, attempt ${attempt + 1}: ${e.message}")
                lastException = e
            }

            if (attempt < MAX_RETRIES) {
                Thread.sleep(currentBackoff)
                currentBackoff = (currentBackoff * BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceIn(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS)
            }
        }

        return DavResult.networkError(lastException ?: IOException("Max retries exceeded"))
    }

    /**
     * Check if an IOException is retryable.
     */
    private fun isRetryable(e: IOException): Boolean {
        return e.message?.contains("reset", ignoreCase = true) == true ||
            e.message?.contains("connection", ignoreCase = true) == true
    }

    /**
     * Parse Retry-After header for 429 responses.
     *
     * @param response HTTP response containing Retry-After header
     * @return Wait time in milliseconds (defaults to 30 seconds if not specified)
     */
    private fun parseRetryAfterHeader(response: Response): Long {
        val retryAfter = response.header("Retry-After") ?: return 30_000L
        return retryAfter.toLongOrNull()?.times(1000) ?: 30_000L
    }

    /**
     * Read response body with size limit to prevent OOM.
     *
     * @throws IOException if response exceeds MAX_RESPONSE_SIZE_BYTES
     */
    private fun Response.bodyWithLimit(): String {
        val body = this.body ?: return ""
        return body.use { b ->
            val source = b.source()
            val contentLength = b.contentLength()

            if (contentLength > MAX_RESPONSE_SIZE_BYTES) {
                logger.warning("Response rejected: Content-Length $contentLength exceeds limit")
                throw IOException("Response too large: $contentLength bytes")
            }

            source.request(MAX_RESPONSE_SIZE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_SIZE_BYTES) {
                logger.warning("Response rejected: buffered ${source.buffer.size} bytes exceeds limit")
                throw IOException("Response too large: ${source.buffer.size} bytes")
            }

            source.buffer.readUtf8()
        }
    }
}

/**
 * Response from PUT operation.
 */
data class PutResponse(
    val statusCode: Int,
    val etag: String?
)

/**
 * WebDAV authentication credentials.
 *
 * SECURITY NOTE: Credentials are stored in memory. For production use,
 * consider using secure credential storage mechanisms provided by the
 * platform (e.g., Android Keystore, macOS Keychain).
 */
sealed class DavAuth {
    abstract fun toAuthHeader(): String

    /**
     * HTTP Basic authentication.
     *
     * @property username The username for authentication
     * @property password The password (sensitive - masked in toString())
     */
    data class Basic(
        val username: String,
        val password: String
    ) : DavAuth() {
        override fun toAuthHeader(): String {
            val credentials = "$username:$password"
            val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
            return "Basic $encoded"
        }

        /**
         * Override toString to prevent accidental credential leakage in logs.
         */
        override fun toString(): String = "Basic(username=$username, password=****)"

        /**
         * Create a copy with cleared password for safe logging.
         */
        fun masked(): String = "$username:****"
    }

    /**
     * Bearer token authentication (for OAuth).
     *
     * @property token The bearer token (sensitive - masked in toString())
     */
    data class Bearer(val token: String) : DavAuth() {
        override fun toAuthHeader(): String = "Bearer $token"

        /**
         * Override toString to prevent accidental token leakage in logs.
         */
        override fun toString(): String = "Bearer(token=****)"
    }
}
