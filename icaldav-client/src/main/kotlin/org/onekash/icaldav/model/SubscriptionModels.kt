package org.onekash.icaldav.model

import org.onekash.icaldav.model.ICalEvent
import java.time.Duration

/**
 * Represents an ICS calendar subscription.
 *
 * ICS subscriptions are read-only calendar feeds fetched via HTTP.
 * Common sources: webcal:// URLs, TripIt, holiday calendars, sports schedules.
 */
data class Subscription(
    /** Unique identifier for this subscription */
    val id: String,

    /** Display name for the subscription */
    val name: String,

    /** Original URL (may be webcal://) */
    val originalUrl: String,

    /** Normalized HTTP(S) URL */
    val httpUrl: String,

    /** Calendar color for display */
    val color: Int? = null,

    /** Whether this subscription is enabled */
    val enabled: Boolean = true,

    /** Cached state for efficient fetching */
    val cacheState: CacheState = CacheState.empty()
)

/**
 * HTTP caching state for conditional requests.
 *
 * Supports:
 * - ETag-based caching (If-None-Match)
 * - Last-Modified caching (If-Modified-Since)
 * - REFRESH-INTERVAL from RFC 7986
 */
data class CacheState(
    /** ETag from last response */
    val etag: String? = null,

    /** Last-Modified header from last response */
    val lastModified: String? = null,

    /** REFRESH-INTERVAL from iCal file (RFC 7986) */
    val refreshInterval: Duration? = null,

    /** Cache-Control max-age from HTTP response */
    val maxAge: Duration? = null,

    /** Timestamp of last successful fetch */
    val lastFetch: Long = 0,

    /** Timestamp when cache expires */
    val expiresAt: Long = 0
) {
    /** Check if cache is still valid */
    fun isValid(): Boolean = System.currentTimeMillis() < expiresAt

    /** Check if we should use conditional request */
    fun hasValidators(): Boolean = etag != null || lastModified != null

    companion object {
        fun empty() = CacheState()
    }
}

/**
 * Result of fetching a subscription.
 */
sealed class FetchResult {
    /**
     * Successful fetch with new events.
     */
    data class Success(
        val events: List<ICalEvent>,
        val calendarName: String?,
        val calendarColor: Int?,
        val newCacheState: CacheState
    ) : FetchResult()

    /**
     * Content not modified (304 response).
     * Use cached events.
     */
    data class NotModified(
        val cacheState: CacheState
    ) : FetchResult()

    /**
     * HTTP error occurred.
     */
    data class HttpError(
        val code: Int,
        val message: String
    ) : FetchResult()

    /**
     * Network error occurred.
     */
    data class NetworkError(
        val exception: Exception
    ) : FetchResult()

    /**
     * Parse error in ICS content.
     */
    data class ParseError(
        val message: String,
        val exception: Exception? = null
    ) : FetchResult()
}

/**
 * Configuration for subscription fetching.
 */
data class FetchConfig(
    /** Connection timeout in milliseconds */
    val connectTimeoutMs: Long = 30_000,

    /** Read timeout in milliseconds */
    val readTimeoutMs: Long = 60_000,

    /** Default refresh interval when none specified */
    val defaultRefreshInterval: Duration = Duration.ofHours(6),

    /** Minimum refresh interval (to prevent abuse) */
    val minRefreshInterval: Duration = Duration.ofMinutes(15),

    /** User-Agent header */
    val userAgent: String = "iCalDAV/1.0"
)

/**
 * Subscription refresh schedule.
 */
data class RefreshSchedule(
    /** Subscription ID */
    val subscriptionId: String,

    /** When to next refresh */
    val nextRefreshAt: Long,

    /** How the interval was determined */
    val source: RefreshSource
)

/**
 * How the refresh interval was determined.
 */
enum class RefreshSource {
    /** From REFRESH-INTERVAL property in iCal (RFC 7986) */
    ICAL_PROPERTY,

    /** From HTTP Cache-Control header */
    HTTP_CACHE_CONTROL,

    /** From HTTP Expires header */
    HTTP_EXPIRES,

    /** Default interval from config */
    DEFAULT,

    /** User-specified interval */
    USER_CONFIGURED
}