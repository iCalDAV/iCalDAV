package org.onekash.icaldav.quirks

import org.onekash.icaldav.xml.XmlParserUtils
import java.util.Calendar
import java.util.TimeZone

/**
 * CalDAV provider configuration and parsing utilities.
 *
 * This is the recommended way to configure provider-specific behavior.
 * All parsing methods delegate to [XmlParserUtils] for consistent
 * XML handling with proper entity decoding.
 *
 * Note: User-Agent is configured on CalDavClient.forProvider(), not here.
 * This class represents the server provider, not the client application.
 *
 * @property id Provider identifier (e.g., "icloud", "generic")
 * @property displayName Human-readable name
 * @property baseUrl Base CalDAV URL
 * @property requiresAppPassword Whether provider requires app-specific passwords
 * @property invalidSyncTokenCodes HTTP codes indicating invalid sync token
 */
data class CalDavProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val requiresAppPassword: Boolean = false,
    val invalidSyncTokenCodes: Set<Int> = setOf(403, 412)
) {
    /**
     * Extract principal URL from PROPFIND response.
     *
     * @param xml XML response from PROPFIND on base URL
     * @return Principal URL path or null if not found
     */
    fun extractPrincipalUrl(xml: String): String? {
        return XmlParserUtils.extractHrefFromContainer(xml, "current-user-principal")
    }

    /**
     * Extract calendar home URL from principal PROPFIND response.
     *
     * @param xml XML response from PROPFIND on principal URL
     * @return Calendar home URL path or null if not found
     */
    fun extractCalendarHomeUrl(xml: String): String? {
        return XmlParserUtils.extractHrefFromContainer(xml, "calendar-home-set")
    }

    /**
     * Extract calendar list from calendar-home PROPFIND response.
     *
     * @param xml XML response from PROPFIND on calendar home
     * @param baseHost Base host URL (e.g., "https://caldav.icloud.com")
     * @return List of parsed calendars
     */
    fun extractCalendars(xml: String, baseHost: String): List<ParsedCalendar> {
        return XmlParserUtils.parseResponses(xml)
            .filter { it.hasCalendarResourceType }
            .filterNot { shouldSkipCalendar(it.href, it.displayName) }
            .map { response ->
                ParsedCalendar(
                    href = response.href,
                    displayName = response.displayName ?: "Unnamed",
                    color = response.calendarColor,
                    ctag = response.ctag,
                    isReadOnly = response.isReadOnly
                )
            }
    }

    /**
     * Extract iCal data from REPORT response.
     *
     * @param xml XML response from calendar-query or calendar-multiget
     * @return List of parsed event data
     */
    fun extractICalData(xml: String): List<ParsedEventData> {
        return XmlParserUtils.parseResponses(xml)
            .filter { it.calendarData?.contains("BEGIN:VCALENDAR") == true }
            .map { response ->
                ParsedEventData(
                    href = response.href,
                    etag = response.etag,
                    icalData = response.calendarData!!
                )
            }
    }

    /**
     * Extract sync-token from response for incremental sync (RFC 6578).
     *
     * @param xml XML response containing sync-token
     * @return Sync token string or null if not present
     */
    fun extractSyncToken(xml: String): String? {
        return XmlParserUtils.extractElementText(xml, "sync-token")
    }

    /**
     * Extract ctag (collection tag) for change detection.
     *
     * @param xml XML response from PROPFIND
     * @return Ctag string or null if not present
     */
    fun extractCtag(xml: String): String? {
        return XmlParserUtils.extractElementText(xml, "getctag")
    }

    /**
     * Build the full URL for a calendar given its href.
     *
     * @param href Calendar href from response (may be relative)
     * @param baseHost Base host URL
     * @return Full calendar URL
     */
    fun buildCalendarUrl(href: String, baseHost: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            "${baseHost.trimEnd('/')}$href"
        }
    }

    /**
     * Build the full URL for an event given its href.
     *
     * @param href Event href from response (may be relative)
     * @param calendarUrl Calendar collection URL
     * @return Full event URL
     */
    fun buildEventUrl(href: String, calendarUrl: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            val baseHost = extractBaseHost(calendarUrl)
            "$baseHost$href"
        }
    }

    /**
     * Check if a response indicates the sync-token is invalid/expired.
     *
     * @param responseCode HTTP response code
     * @param responseBody Response body
     * @return true if sync token is invalid and full sync is needed
     */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean {
        return responseCode in invalidSyncTokenCodes ||
            responseBody.contains("valid-sync-token", ignoreCase = true)
    }

    /**
     * Extract deleted resource hrefs from sync-collection response.
     *
     * @param xml XML response from sync-collection
     * @return List of hrefs for deleted resources
     */
    fun extractDeletedHrefs(xml: String): List<String> {
        return XmlParserUtils.parseResponses(xml)
            .filter { it.status == 404 }
            .map { it.href }
    }

    /**
     * Extract changed item hrefs and etags from sync-collection response.
     *
     * @param xml XML response from sync-collection
     * @return List of (href, etag) pairs for changed/added resources
     */
    fun extractChangedItems(xml: String): List<Pair<String, String?>> {
        return XmlParserUtils.parseResponses(xml)
            .filter { it.status != 404 && it.href.endsWith(".ics") }
            .map { Pair(it.href, it.etag) }
    }

    /**
     * Check if a calendar href should be skipped (inbox, outbox, etc).
     *
     * @param href Calendar href
     * @param displayName Calendar display name (may be null)
     * @return true if this calendar should be skipped
     */
    fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase() ?: ""

        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            hrefLower.contains("freebusy") ||
            nameLower.contains("tasks") ||
            nameLower.contains("reminders") ||
            nameLower.contains("todo")
    }

    /**
     * Format date for time-range filter in REPORT query.
     *
     * @param epochMillis Timestamp in milliseconds since epoch
     * @return Formatted date string (e.g., "20240101T000000Z")
     */
    fun formatDateForQuery(epochMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return String.format(
            "%04d%02d%02dT000000Z",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

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

    private fun extractBaseHost(url: String): String {
        return if (url.contains("://")) {
            val afterProtocol = url.substringAfter("://")
            val host = afterProtocol.substringBefore("/")
            url.substringBefore("://") + "://" + host
        } else {
            url.substringBefore("/")
        }
    }

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
         * Pre-configured provider for iCloud.
         *
         * iCloud CalDAV has unique behaviors:
         * - Requires app-specific passwords for third-party apps
         * - Uses 403 (not 412) for invalid sync tokens
         * - Redirects to regional partition servers
         */
        val ICLOUD = CalDavProvider(
            id = "icloud",
            displayName = "iCloud",
            baseUrl = "https://caldav.icloud.com",
            requiresAppPassword = true,
            invalidSyncTokenCodes = setOf(403)
        )

        /**
         * Create a generic CalDAV provider.
         *
         * @param baseUrl Base CalDAV URL
         * @return CalDavProvider configured for generic servers
         */
        fun generic(baseUrl: String) = CalDavProvider(
            id = "generic",
            displayName = "CalDAV Server",
            baseUrl = baseUrl
        )

        /**
         * Detect the appropriate provider for a given server URL.
         *
         * @param serverUrl CalDAV server URL
         * @return CalDavProvider configured for the detected provider
         */
        fun forServer(serverUrl: String): CalDavProvider {
            return when {
                serverUrl.contains("icloud.com", ignoreCase = true) -> ICLOUD
                serverUrl.contains("google.com", ignoreCase = true) -> CalDavProvider(
                    id = "google",
                    displayName = "Google Calendar",
                    baseUrl = serverUrl
                )
                serverUrl.contains("fastmail.com", ignoreCase = true) -> CalDavProvider(
                    id = "fastmail",
                    displayName = "Fastmail",
                    baseUrl = serverUrl
                )
                serverUrl.contains(":5232", ignoreCase = true) -> CalDavProvider(
                    id = "radicale",
                    displayName = "Radicale",
                    baseUrl = serverUrl
                )
                else -> generic(serverUrl)
            }
        }
    }
}
