package com.icalendar.webdav.quirks

/**
 * Abstraction for CalDAV provider-specific behaviors.
 *
 * Different CalDAV servers (iCloud, Google, FastMail, Nextcloud) have quirks
 * in their XML responses, authentication flows, and supported features.
 * This interface allows the library to handle these differences cleanly.
 *
 * Implementations:
 * - [ICloudQuirks] - Apple iCloud CalDAV
 * - [DefaultQuirks] - Generic CalDAV servers
 *
 * Based on battle-tested patterns from KashCal.
 */
interface CalDavQuirks {

    /** Provider identifier (e.g., "icloud", "google", "fastmail") */
    val providerId: String

    /** Human-readable provider name */
    val displayName: String

    /** Base CalDAV URL for this provider */
    val baseUrl: String

    /** Whether this provider requires app-specific passwords */
    val requiresAppSpecificPassword: Boolean

    /**
     * Extract principal URL from PROPFIND response.
     * Different providers use different XML namespace formats.
     *
     * @param responseBody XML response from PROPFIND on base URL
     * @return Principal URL path or null if not found
     */
    fun extractPrincipalUrl(responseBody: String): String?

    /**
     * Extract calendar home URL from principal PROPFIND response.
     *
     * @param responseBody XML response from PROPFIND on principal URL
     * @return Calendar home URL path or null if not found
     */
    fun extractCalendarHomeUrl(responseBody: String): String?

    /**
     * Extract calendar list from calendar-home PROPFIND response.
     *
     * @param responseBody XML response from PROPFIND on calendar home
     * @param baseHost Base host URL (e.g., "https://caldav.icloud.com")
     * @return List of parsed calendars
     */
    fun extractCalendars(responseBody: String, baseHost: String): List<ParsedCalendar>

    /**
     * Extract iCal data from REPORT response.
     * Some providers wrap in CDATA, others use XML entities.
     *
     * @param responseBody XML response from calendar-query or calendar-multiget
     * @return List of parsed event data
     */
    fun extractICalData(responseBody: String): List<ParsedEventData>

    /**
     * Extract sync-token from response for incremental sync (RFC 6578).
     *
     * @param responseBody XML response containing sync-token
     * @return Sync token string or null if not present
     */
    fun extractSyncToken(responseBody: String): String?

    /**
     * Extract ctag (collection tag) for change detection.
     *
     * @param responseBody XML response from PROPFIND
     * @return Ctag string or null if not present
     */
    fun extractCtag(responseBody: String): String?

    /**
     * Build the full URL for a calendar given its href.
     *
     * @param href Calendar href from response (may be relative)
     * @param baseHost Base host URL
     * @return Full calendar URL
     */
    fun buildCalendarUrl(href: String, baseHost: String): String

    /**
     * Build the full URL for an event given its href.
     *
     * @param href Event href from response (may be relative)
     * @param calendarUrl Calendar collection URL
     * @return Full event URL
     */
    fun buildEventUrl(href: String, calendarUrl: String): String

    /**
     * Get additional headers required by this provider.
     *
     * @return Map of header name to value
     */
    fun getAdditionalHeaders(): Map<String, String>

    /**
     * Check if a response indicates the sync-token is invalid/expired.
     *
     * @param responseCode HTTP response code
     * @param responseBody Response body
     * @return true if sync token is invalid and full sync is needed
     */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean

    /**
     * Extract deleted resource hrefs from sync-collection response.
     * In CalDAV, deleted items are indicated by 404 status in the response.
     *
     * @param responseBody XML response from sync-collection
     * @return List of hrefs for deleted resources
     */
    fun extractDeletedHrefs(responseBody: String): List<String>

    /**
     * Extract changed item hrefs and etags from sync-collection response.
     * Unlike [extractICalData], this does NOT require calendar-data to be present.
     *
     * Used for incremental sync (RFC 6578) where sync-collection returns hrefs/etags,
     * and we then fetch full event data via calendar-multiget.
     *
     * @param responseBody XML response from sync-collection
     * @return List of (href, etag) pairs for changed/added resources
     */
    fun extractChangedItems(responseBody: String): List<Pair<String, String?>>

    /**
     * Check if a calendar href should be skipped (inbox, outbox, etc).
     *
     * @param href Calendar href
     * @param displayName Calendar display name (may be null)
     * @return true if this calendar should be skipped
     */
    fun shouldSkipCalendar(href: String, displayName: String?): Boolean

    /**
     * Format date for time-range filter in REPORT query.
     *
     * @param epochMillis Timestamp in milliseconds since epoch
     * @return Formatted date string (e.g., "20240101T000000Z")
     */
    fun formatDateForQuery(epochMillis: Long): String

    /**
     * Default sync range - how far back to sync.
     *
     * @return Duration in milliseconds (default: 1 year)
     */
    fun getDefaultSyncRangeBack(): Long = 365L * 24 * 60 * 60 * 1000

    /**
     * Default sync range - how far forward to sync.
     *
     * @return Duration in milliseconds (default: Jan 1, 2100 UTC)
     */
    fun getDefaultSyncRangeForward(): Long = 4102444800000L

    /**
     * Parsed calendar info from PROPFIND response.
     */
    data class ParsedCalendar(
        val href: String,
        val displayName: String,
        val color: String?,
        val ctag: String?,
        val isReadOnly: Boolean = false
    )

    /**
     * Parsed event data from REPORT response.
     */
    data class ParsedEventData(
        val href: String,
        val etag: String?,
        val icalData: String
    )

    companion object {
        /**
         * Detect the appropriate quirks implementation for a given server URL.
         *
         * @param serverUrl CalDAV server URL
         * @return Appropriate [CalDavQuirks] implementation
         */
        fun forServer(serverUrl: String): CalDavQuirks {
            return when {
                serverUrl.contains("icloud.com", ignoreCase = true) -> ICloudQuirks()
                serverUrl.contains("google.com", ignoreCase = true) -> DefaultQuirks("google", "Google Calendar", serverUrl)
                serverUrl.contains("fastmail.com", ignoreCase = true) -> DefaultQuirks("fastmail", "Fastmail", serverUrl)
                // Radicale: uses simple URL structure, skip discovery and use direct URLs
                // URL pattern: http://host:5232/{user}/{calendar}/
                serverUrl.contains(":5232", ignoreCase = true) -> DefaultQuirks("radicale", "Radicale", serverUrl)
                else -> DefaultQuirks("generic", "CalDAV Server", serverUrl)
            }
        }
    }
}
