package org.onekash.icaldav.quirks

import org.onekash.icaldav.xml.XmlParserUtils
import java.util.Calendar
import java.util.TimeZone

/**
 * Default CalDAV quirks for generic RFC-compliant servers.
 *
 * This implementation follows standard CalDAV/WebDAV specifications:
 * - RFC 4791 (CalDAV)
 * - RFC 6578 (WebDAV Sync)
 * - RFC 4918 (WebDAV)
 *
 * Use this for servers like Nextcloud, Radicale, Baïkal, and other
 * standards-compliant CalDAV implementations.
 *
 * Uses XmlPullParser for efficient parsing with automatic entity decoding.
 *
 * @param providerId Unique identifier for this provider
 * @param displayName Human-readable name
 * @param baseUrl Base CalDAV URL
 *
 * @see CalDavProvider for the recommended replacement
 */
@Deprecated(
    message = "Use CalDavProvider.generic(baseUrl) instead",
    replaceWith = ReplaceWith("CalDavProvider.generic(baseUrl)", "org.onekash.icaldav.quirks.CalDavProvider")
)
@Suppress("DEPRECATION")  // We need to implement the deprecated interface
class DefaultQuirks(
    override val providerId: String = "generic",
    override val displayName: String = "CalDAV Server",
    override val baseUrl: String
) : CalDavQuirks {

    override val requiresAppSpecificPassword = false

    override fun extractPrincipalUrl(responseBody: String): String? {
        return XmlParserUtils.extractHrefFromContainer(responseBody, "current-user-principal")
    }

    override fun extractCalendarHomeUrl(responseBody: String): String? {
        return XmlParserUtils.extractHrefFromContainer(responseBody, "calendar-home-set")
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        return XmlParserUtils.parseResponses(responseBody)
            .filter { it.hasCalendarResourceType }
            .filterNot { shouldSkipCalendar(it.href, it.displayName) }
            .map { response ->
                CalDavQuirks.ParsedCalendar(
                    href = response.href,
                    displayName = response.displayName ?: "Unnamed",
                    color = response.calendarColor,
                    ctag = response.ctag,
                    isReadOnly = response.isReadOnly
                )
            }
    }

    override fun extractICalData(responseBody: String): List<CalDavQuirks.ParsedEventData> {
        return XmlParserUtils.parseResponses(responseBody)
            .filter { it.calendarData?.contains("BEGIN:VCALENDAR") == true }
            .map { response ->
                CalDavQuirks.ParsedEventData(
                    href = response.href,
                    etag = response.etag,
                    icalData = response.calendarData!!
                )
            }
    }

    override fun extractSyncToken(responseBody: String): String? {
        return XmlParserUtils.extractElementText(responseBody, "sync-token")
    }

    override fun extractCtag(responseBody: String): String? {
        return XmlParserUtils.extractElementText(responseBody, "getctag")
    }

    override fun buildCalendarUrl(href: String, baseHost: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            "${baseHost.trimEnd('/')}$href"
        }
    }

    override fun buildEventUrl(href: String, calendarUrl: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            val baseHost = extractBaseHost(calendarUrl)
            "$baseHost$href"
        }
    }

    override fun getAdditionalHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "iCalDAV/1.0 (Kotlin)"
        )
    }

    override fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean {
        return responseCode == 403 ||
            responseCode == 412 ||
            responseBody.contains("valid-sync-token", ignoreCase = true)
    }

    override fun extractDeletedHrefs(responseBody: String): List<String> {
        return XmlParserUtils.parseResponses(responseBody)
            .filter { it.status == 404 }
            .map { it.href }
    }

    override fun extractChangedItems(responseBody: String): List<Pair<String, String?>> {
        return XmlParserUtils.parseResponses(responseBody)
            .filter { it.status != 404 && it.href.endsWith(".ics") }
            .map { Pair(it.href, it.etag) }
    }

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
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

    override fun formatDateForQuery(epochMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return String.format(
            "%04d%02d%02dT000000Z",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun extractBaseHost(url: String): String {
        return if (url.contains("://")) {
            val afterProtocol = url.substringAfter("://")
            val host = afterProtocol.substringBefore("/")
            url.substringBefore("://") + "://" + host
        } else {
            url.substringBefore("/")
        }
    }
}
