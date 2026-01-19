package org.onekash.icaldav.quirks

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
 * @param providerId Unique identifier for this provider
 * @param displayName Human-readable name
 * @param baseUrl Base CalDAV URL
 */
class DefaultQuirks(
    override val providerId: String = "generic",
    override val displayName: String = "CalDAV Server",
    override val baseUrl: String
) : CalDavQuirks {

    override val requiresAppSpecificPassword = false

    override fun extractPrincipalUrl(responseBody: String): String? {
        // Standard DAV namespace patterns
        val patterns = listOf(
            """<d:current-user-principal>\s*<d:href>([^<]+)</d:href>\s*</d:current-user-principal>""",
            """<D:current-user-principal>\s*<D:href>([^<]+)</D:href>\s*</D:current-user-principal>""",
            """<current-user-principal[^>]*>\s*<href[^>]*>([^<]+)</href>\s*</current-user-principal>""",
            """current-user-principal.*?<.*?href[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    override fun extractCalendarHomeUrl(responseBody: String): String? {
        val patterns = listOf(
            """<c:calendar-home-set>\s*<d:href>([^<]+)</d:href>\s*</c:calendar-home-set>""",
            """<C:calendar-home-set>\s*<D:href>([^<]+)</D:href>\s*</C:calendar-home-set>""",
            """<calendar-home-set[^>]*>\s*<href[^>]*>([^<]+)</href>\s*</calendar-home-set>""",
            """calendar-home-set.*?<.*?href[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        val calendars = mutableListOf<CalDavQuirks.ParsedCalendar>()

        val responseRegex = Regex(
            """<(?:d:|D:)?response[^>]*>(.*?)</(?:d:|D:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Check if it's a calendar collection
            val isCalendar = responseXml.contains("calendar", ignoreCase = true) &&
                (responseXml.contains("resourcetype", ignoreCase = true) ||
                    responseXml.contains("caldav", ignoreCase = true))

            if (isCalendar) {
                val href = extractHref(responseXml) ?: continue
                val displayName = extractDisplayName(responseXml) ?: "Unnamed"

                if (shouldSkipCalendar(href, displayName)) continue

                val color = extractCalendarColor(responseXml)
                val ctag = extractCtagFromResponse(responseXml)

                calendars.add(
                    CalDavQuirks.ParsedCalendar(
                        href = href,
                        displayName = displayName,
                        color = color,
                        ctag = ctag,
                        isReadOnly = false
                    )
                )
            }
        }

        return calendars
    }

    override fun extractICalData(responseBody: String): List<CalDavQuirks.ParsedEventData> {
        val events = mutableListOf<CalDavQuirks.ParsedEventData>()

        val responseRegex = Regex(
            """<(?:d:|D:)?response[^>]*>(.*?)</(?:d:|D:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]
            val href = extractHref(responseXml) ?: continue
            val etag = extractEtag(responseXml)
            val icalData = extractCalendarData(responseXml)

            if (icalData != null && icalData.contains("BEGIN:VCALENDAR")) {
                events.add(
                    CalDavQuirks.ParsedEventData(
                        href = href,
                        etag = etag,
                        icalData = icalData
                    )
                )
            }
        }

        return events
    }

    override fun extractSyncToken(responseBody: String): String? {
        val patterns = listOf(
            """<(?:d:|D:)?sync-token[^>]*>([^<]+)</(?:d:|D:)?sync-token>""",
            """<sync-token[^>]*>([^<]+)</sync-token>"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    override fun extractCtag(responseBody: String): String? {
        val patterns = listOf(
            """<(?:cs:|CS:)?getctag[^>]*>([^<]+)</(?:cs:|CS:)?getctag>""",
            """<getctag[^>]*>([^<]+)</getctag>"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
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
        val deletedHrefs = mutableListOf<String>()

        val responseRegex = Regex(
            """<(?:d:|D:)?response[^>]*>(.*?)</(?:d:|D:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            val isDeleted = responseXml.contains("HTTP/1.1 404", ignoreCase = true) ||
                responseXml.contains("404 Not Found", ignoreCase = true) ||
                responseXml.contains("<status>HTTP/1.1 404", ignoreCase = true)

            if (isDeleted) {
                val href = extractHref(responseXml)
                if (href != null) {
                    deletedHrefs.add(href)
                }
            }
        }

        return deletedHrefs
    }

    override fun extractChangedItems(responseBody: String): List<Pair<String, String?>> {
        val items = mutableListOf<Pair<String, String?>>()

        val responseRegex = Regex(
            """<(?:d:|D:)?response[^>]*>(.*?)</(?:d:|D:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Skip deleted items
            if (responseXml.contains("HTTP/1.1 404", ignoreCase = true) ||
                responseXml.contains("404 Not Found", ignoreCase = true)) {
                continue
            }

            val href = extractHref(responseXml) ?: continue
            if (!href.endsWith(".ics")) continue

            val etag = extractEtag(responseXml)
            items.add(Pair(href, etag))
        }

        return items
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

    // Private helper methods

    private fun extractHref(xml: String): String? {
        val regex = Regex("""<(?:d:|D:)?href[^>]*>([^<]+)</(?:d:|D:)?href>""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractDisplayName(xml: String): String? {
        val regex = Regex("""<(?:d:|D:)?displayname[^>]*>([^<]*)</(?:d:|D:)?displayname>""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractCalendarColor(xml: String): String? {
        // Try Apple and standard formats
        val patterns = listOf(
            """<(?:ic:|IC:)?calendar-color[^>]*>([^<]+)</""",
            """<(?:x-)?apple-calendar-color[^>]*>([^<]+)</"""
        )
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractCtagFromResponse(xml: String): String? {
        val regex = Regex("""<(?:cs:|CS:)?getctag[^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractEtag(xml: String): String? {
        val regex = Regex("""<(?:d:|D:)?getetag[^>]*>"?([^"<]+)"?</""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim('"')
    }

    private fun extractCalendarData(xml: String): String? {
        val patterns = listOf(
            // CDATA format
            """<(?:c:|C:|cal:)?calendar-data[^>]*><!\[CDATA\[(.*?)\]\]></(?:c:|C:|cal:)?calendar-data>""",
            // Standard format
            """<(?:c:|C:|cal:)?calendar-data[^>]*>(.*?)</(?:c:|C:|cal:)?calendar-data>"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val match = regex.find(xml)
            if (match != null) {
                val data = match.groupValues[1]
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .trim()
                if (data.contains("BEGIN:VCALENDAR")) {
                    return data
                }
            }
        }
        return null
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
