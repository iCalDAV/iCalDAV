package org.onekash.icaldav.client

import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.util.DurationUtils
import org.onekash.icaldav.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Client for fetching ICS calendar subscriptions.
 *
 * Supports:
 * - webcal:// URL conversion
 * - HTTP caching with ETag and Last-Modified
 * - REFRESH-INTERVAL parsing (RFC 7986)
 * - Basic authentication via URL credentials
 *
 * Production-tested with various ICS subscription services.
 */
class IcsSubscriptionClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val parser: ICalParser = ICalParser(),
    private val config: FetchConfig = FetchConfig()
) {
    /**
     * Fetch events from an ICS subscription URL.
     *
     * @param url Calendar URL (webcal://, http://, or https://)
     * @param cacheState Previous cache state for conditional requests
     * @return Fetch result with events or status
     */
    fun fetch(url: String, cacheState: CacheState = CacheState.empty()): FetchResult {
        val httpUrl = try {
            WebCalUrl.toHttpUrl(url)
        } catch (e: IllegalArgumentException) {
            return FetchResult.ParseError("Invalid URL: ${e.message}", e)
        }

        val request = buildRequest(httpUrl, cacheState)

        return try {
            httpClient.newCall(request).execute().use { response ->
                handleResponse(response, cacheState)
            }
        } catch (e: IOException) {
            FetchResult.NetworkError(e)
        }
    }

    /**
     * Fetch subscription and update its cache state.
     *
     * @param subscription Subscription to fetch
     * @return Updated subscription with new events or same if not modified
     */
    fun fetchSubscription(subscription: Subscription): Pair<FetchResult, Subscription> {
        val result = fetch(subscription.httpUrl, subscription.cacheState)

        val updatedSubscription = when (result) {
            is FetchResult.Success -> subscription.copy(
                cacheState = result.newCacheState,
                color = result.calendarColor ?: subscription.color,
                name = result.calendarName ?: subscription.name
            )
            is FetchResult.NotModified -> subscription.copy(
                cacheState = result.cacheState
            )
            else -> subscription
        }

        return Pair(result, updatedSubscription)
    }

    /**
     * Calculate next refresh time for a subscription.
     *
     * @param cacheState Current cache state
     * @return Refresh schedule with next refresh time
     */
    fun calculateNextRefresh(subscriptionId: String, cacheState: CacheState): RefreshSchedule {
        val now = System.currentTimeMillis()

        // Priority: REFRESH-INTERVAL > Cache-Control > default
        val (interval, source) = when {
            cacheState.refreshInterval != null -> {
                val bounded = boundInterval(cacheState.refreshInterval)
                bounded to RefreshSource.ICAL_PROPERTY
            }
            cacheState.maxAge != null -> {
                val bounded = boundInterval(cacheState.maxAge)
                bounded to RefreshSource.HTTP_CACHE_CONTROL
            }
            cacheState.expiresAt > now -> {
                Duration.ofMillis(cacheState.expiresAt - now) to RefreshSource.HTTP_EXPIRES
            }
            else -> {
                config.defaultRefreshInterval to RefreshSource.DEFAULT
            }
        }

        return RefreshSchedule(
            subscriptionId = subscriptionId,
            nextRefreshAt = now + interval.toMillis(),
            source = source
        )
    }

    /**
     * Build HTTP request with caching headers.
     */
    private fun buildRequest(url: String, cacheState: CacheState): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", config.userAgent)
            .header("Accept", "text/calendar, application/calendar+xml, text/plain")

        // Add conditional headers if we have validators
        if (cacheState.hasValidators()) {
            cacheState.etag?.let { etag ->
                builder.header("If-None-Match", etag)
            }
            cacheState.lastModified?.let { lastMod ->
                builder.header("If-Modified-Since", lastMod)
            }
        }

        return builder.build()
    }

    /**
     * Handle HTTP response and parse content.
     */
    private fun handleResponse(response: Response, previousState: CacheState): FetchResult {
        return when (response.code) {
            200 -> parseSuccessResponse(response)
            304 -> handleNotModified(response, previousState)
            401, 403 -> FetchResult.HttpError(response.code, "Authentication required")
            404 -> FetchResult.HttpError(response.code, "Calendar not found")
            in 400..499 -> FetchResult.HttpError(response.code, "Client error: ${response.message}")
            in 500..599 -> FetchResult.HttpError(response.code, "Server error: ${response.message}")
            else -> FetchResult.HttpError(response.code, response.message)
        }
    }

    /**
     * Parse successful response with ICS content.
     */
    private fun parseSuccessResponse(response: Response): FetchResult {
        val body = response.body?.string()
            ?: return FetchResult.ParseError("Empty response body")

        // Parse iCal content
        val parseResult = parser.parseAllEvents(body)

        return when (parseResult) {
            is org.onekash.icaldav.model.ParseResult.Success -> {
                val newCacheState = buildCacheState(response, body)

                FetchResult.Success(
                    events = parseResult.value,
                    calendarName = extractCalendarName(body),
                    calendarColor = extractCalendarColor(body),
                    newCacheState = newCacheState
                )
            }
            is org.onekash.icaldav.model.ParseResult.Error -> {
                val cause = (parseResult.error as? org.onekash.icaldav.model.ParseError.General)?.cause
                FetchResult.ParseError(parseResult.error.message, cause as? Exception)
            }
        }
    }

    /**
     * Handle 304 Not Modified response.
     */
    private fun handleNotModified(response: Response, previousState: CacheState): FetchResult {
        // Update cache state with new expiry from headers
        val newState = previousState.copy(
            lastFetch = System.currentTimeMillis(),
            maxAge = parseMaxAge(response),
            expiresAt = calculateExpiresAt(response, previousState)
        )

        return FetchResult.NotModified(newState)
    }

    /**
     * Build new cache state from response.
     */
    private fun buildCacheState(response: Response, icsContent: String): CacheState {
        val now = System.currentTimeMillis()
        val maxAge = parseMaxAge(response)

        return CacheState(
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            refreshInterval = parseRefreshInterval(icsContent),
            maxAge = maxAge,
            lastFetch = now,
            expiresAt = calculateExpiresAt(response, CacheState.empty())
        )
    }

    /**
     * Parse Cache-Control max-age directive.
     */
    private fun parseMaxAge(response: Response): Duration? {
        val cacheControl = response.header("Cache-Control") ?: return null

        val maxAgeMatch = Regex("max-age=(\\d+)").find(cacheControl)
        val seconds = maxAgeMatch?.groupValues?.get(1)?.toLongOrNull() ?: return null

        return Duration.ofSeconds(seconds)
    }

    /**
     * Calculate expiration timestamp from HTTP headers.
     */
    private fun calculateExpiresAt(response: Response, fallback: CacheState): Long {
        val now = System.currentTimeMillis()

        // Try Cache-Control max-age first
        parseMaxAge(response)?.let { maxAge ->
            return now + maxAge.toMillis()
        }

        // Try Expires header
        response.header("Expires")?.let { expires ->
            parseHttpDate(expires)?.let { instant ->
                return instant.toEpochMilli()
            }
        }

        // Fall back to default interval
        return now + config.defaultRefreshInterval.toMillis()
    }

    /**
     * Parse HTTP date format (RFC 7231).
     *
     * Uses thread-safe DateTimeFormatter instead of SimpleDateFormat.
     * Supports the preferred format: "Sun, 06 Nov 1994 08:49:37 GMT"
     */
    private fun parseHttpDate(dateStr: String): Instant? {
        return try {
            // RFC 7231 preferred format
            ZonedDateTime.parse(dateStr, HTTP_DATE_FORMATTER).toInstant()
        } catch (e: DateTimeParseException) {
            // Try alternative formats
            tryParseHttpDateAlternatives(dateStr)
        }
    }

    /**
     * Try alternative HTTP date formats.
     */
    private fun tryParseHttpDateAlternatives(dateStr: String): Instant? {
        val alternativeFormatters = listOf(
            // RFC 850 format: "Sunday, 06-Nov-94 08:49:37 GMT"
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            // ANSI C asctime() format: "Sun Nov  6 08:49:37 1994"
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)
        )

        for (formatter in alternativeFormatters) {
            try {
                return ZonedDateTime.parse(dateStr, formatter).toInstant()
            } catch (e: DateTimeParseException) {
                // Continue to next format
            }
        }
        return null
    }

    /**
     * Parse REFRESH-INTERVAL property from iCal content (RFC 7986).
     *
     * Example: REFRESH-INTERVAL;VALUE=DURATION:PT6H
     *
     * Uses [DurationUtils] for consistent parsing across the library.
     */
    private fun parseRefreshInterval(icsContent: String): Duration? {
        val regex = Regex("REFRESH-INTERVAL[^:]*:([^\r\n]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(icsContent) ?: return null
        val value = match.groupValues[1].trim()

        return DurationUtils.parse(value)
    }

    /**
     * Extract calendar name from X-WR-CALNAME property.
     */
    private fun extractCalendarName(icsContent: String): String? {
        val regex = Regex("X-WR-CALNAME:([^\r\n]+)", RegexOption.IGNORE_CASE)
        return regex.find(icsContent)?.groupValues?.get(1)?.trim()
    }

    /**
     * Extract calendar color from X-APPLE-CALENDAR-COLOR or COLOR property.
     */
    private fun extractCalendarColor(icsContent: String): Int? {
        // Try X-APPLE-CALENDAR-COLOR first
        val appleRegex = Regex("X-APPLE-CALENDAR-COLOR:([^\r\n]+)", RegexOption.IGNORE_CASE)
        appleRegex.find(icsContent)?.groupValues?.get(1)?.let { colorStr ->
            parseColor(colorStr.trim())?.let { return it }
        }

        // Try RFC 7986 COLOR property
        val colorRegex = Regex("(?:^|\n)COLOR:([^\r\n]+)", RegexOption.IGNORE_CASE)
        colorRegex.find(icsContent)?.groupValues?.get(1)?.let { colorStr ->
            parseColor(colorStr.trim())?.let { return it }
        }

        return null
    }

    /**
     * Parse color string to integer.
     *
     * Supports: #RRGGBB, #AARRGGBB, CSS color names
     */
    private fun parseColor(colorStr: String): Int? {
        return try {
            when {
                colorStr.startsWith("#") -> {
                    val hex = colorStr.substring(1)
                    when (hex.length) {
                        6 -> (0xFF000000 or hex.toLong(16)).toInt()
                        8 -> hex.toLong(16).toInt()
                        else -> null
                    }
                }
                else -> null // Could add CSS color name mapping
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ensure interval is within bounds.
     */
    private fun boundInterval(interval: Duration): Duration {
        return when {
            interval < config.minRefreshInterval -> config.minRefreshInterval
            interval > Duration.ofDays(7) -> Duration.ofDays(7)
            else -> interval
        }
    }

    companion object {
        /**
         * Thread-safe HTTP date formatter (RFC 7231 preferred format).
         * Example: "Sun, 06 Nov 1994 08:49:37 GMT"
         */
        private val HTTP_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

        /**
         * Create default HTTP client with reasonable timeouts.
         */
        fun defaultHttpClient(config: FetchConfig = FetchConfig()): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}