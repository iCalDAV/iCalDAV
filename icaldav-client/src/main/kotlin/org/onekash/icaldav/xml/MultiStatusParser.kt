package org.onekash.icaldav.xml

import org.onekash.icaldav.model.*

/**
 * Parser for WebDAV/CalDAV multistatus XML responses.
 *
 * Uses regex-based parsing for simplicity and reliability with namespace
 * variations (D:, d:, DAV:, etc.) that different servers use.
 *
 * Production-tested with iCloud namespace handling variations.
 */
class MultiStatusParser {

    /**
     * Parse multistatus XML response into structured data.
     *
     * @param xml Raw XML string from server
     * @return Parsed MultiStatus with all responses
     */
    fun parse(xml: String): DavResult<MultiStatus> {
        return try {
            val responses = parseResponses(xml)
            val syncToken = extractSyncToken(xml)
            DavResult.success(MultiStatus(responses, syncToken))
        } catch (e: Exception) {
            DavResult.parseError("Failed to parse multistatus: ${e.message}", xml)
        }
    }

    /**
     * Parse all <response> elements from multistatus.
     */
    private fun parseResponses(xml: String): List<DavResponse> {
        // Match <response> or <D:response> or <d:response> elements
        val responsePattern = Regex(
            """<(?:[a-zA-Z]+:)?response[^>]*>(.*?)</(?:[a-zA-Z]+:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        return responsePattern.findAll(xml).mapNotNull { match ->
            parseResponse(match.groupValues[1])
        }.toList()
    }

    /**
     * Parse a single <response> element.
     */
    private fun parseResponse(responseXml: String): DavResponse? {
        val href = extractHref(responseXml) ?: return null
        val status = extractStatus(responseXml)
        val properties = extractProperties(responseXml)
        val etag = extractEtag(responseXml)
        val calendarData = extractCalendarData(responseXml)

        return DavResponse(
            href = href,
            status = status,
            properties = properties,
            etag = etag,
            calendarData = calendarData
        )
    }

    /**
     * Extract href from response element.
     */
    private fun extractHref(xml: String): String? {
        // <href>/path/to/resource</href> or <D:href>...</D:href>
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?href[^>]*>(.*?)</(?:[a-zA-Z]+:)?href>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()?.let { decodeHref(it) }
    }

    /**
     * Extract HTTP status code from propstat or response.
     */
    private fun extractStatus(xml: String): Int {
        // <status>HTTP/1.1 200 OK</status>
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?status[^>]*>HTTP/\d+\.\d+\s+(\d+)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 200
    }

    /**
     * Extract all properties from response.
     */
    private fun extractProperties(xml: String): DavProperties {
        val props = mutableMapOf<String, String?>()

        // Find all property elements inside <prop> or <D:prop>
        val propPattern = Regex(
            """<(?:[a-zA-Z]+:)?prop[^>]*>(.*?)</(?:[a-zA-Z]+:)?prop>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val propContent = propPattern.find(xml)?.groupValues?.get(1) ?: return DavProperties.EMPTY

        // Common properties to extract
        val propertiesToExtract = listOf(
            "displayname",
            "resourcetype",
            "getetag",
            "getlastmodified",
            "getcontenttype",
            "getcontentlength",
            "current-user-principal",
            "calendar-home-set",
            "calendar-color",
            "calendar-description",
            "getctag",
            "sync-token",
            "supported-calendar-component-set"
        )

        for (propName in propertiesToExtract) {
            val value = extractPropertyValue(propContent, propName)
            if (value != null) {
                props[propName] = value
            }
        }

        // Special handling for resourcetype which contains child elements
        val resourceTypePattern = Regex(
            """<(?:[a-zA-Z]+:)?resourcetype[^>]*>(.*?)</(?:[a-zA-Z]+:)?resourcetype>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        resourceTypePattern.find(propContent)?.groupValues?.get(1)?.let { rtContent ->
            props["resourcetype"] = rtContent
        }

        // Special handling for href inside current-user-principal
        val principalPattern = Regex(
            """<(?:[a-zA-Z]+:)?current-user-principal[^>]*>.*?<(?:[a-zA-Z]+:)?href[^>]*>(.*?)</(?:[a-zA-Z]+:)?href>.*?</(?:[a-zA-Z]+:)?current-user-principal>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        principalPattern.find(propContent)?.groupValues?.get(1)?.trim()?.let { href ->
            props["current-user-principal"] = decodeHref(href)
        }

        // Special handling for href inside calendar-home-set
        val homeSetPattern = Regex(
            """<(?:[a-zA-Z]+:)?calendar-home-set[^>]*>.*?<(?:[a-zA-Z]+:)?href[^>]*>(.*?)</(?:[a-zA-Z]+:)?href>.*?</(?:[a-zA-Z]+:)?calendar-home-set>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        homeSetPattern.find(propContent)?.groupValues?.get(1)?.trim()?.let { href ->
            props["calendar-home-set"] = decodeHref(href)
        }

        return DavProperties.from(props)
    }

    /**
     * Extract a single property value by name.
     */
    private fun extractPropertyValue(propContent: String, propName: String): String? {
        // Handle namespaced property names (e.g., cs:getctag, C:calendar-data)
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?$propName[^/>]*>([^<]*)</(?:[a-zA-Z]+:)?$propName>""",
            setOf(RegexOption.IGNORE_CASE)
        )
        return pattern.find(propContent)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Extract ETag from response (handles quoted and unquoted).
     */
    private fun extractEtag(xml: String): String? {
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?getetag[^>]*>"?([^"<]+)"?</(?:[a-zA-Z]+:)?getetag>""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }

    /**
     * Extract calendar-data (iCal content) from response.
     * This is CalDAV-specific (RFC 4791).
     *
     * Handles CDATA sections which some servers (including iCloud) use to wrap
     * calendar data: <C:calendar-data><![CDATA[BEGIN:VCALENDAR...]]></C:calendar-data>
     */
    private fun extractCalendarData(xml: String): String? {
        // <C:calendar-data>BEGIN:VCALENDAR...</C:calendar-data>
        // or <calendar-data>...</calendar-data>
        // Also handles CDATA: <C:calendar-data><![CDATA[BEGIN:VCALENDAR...]]></C:calendar-data>
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?calendar-data[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</(?:[a-zA-Z]+:)?calendar-data>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Extract sync-token from multistatus root element.
     */
    private fun extractSyncToken(xml: String): String? {
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?sync-token[^>]*>([^<]+)</(?:[a-zA-Z]+:)?sync-token>""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }

    /**
     * URL-decode an href, preserving literal + signs.
     * URLDecoder treats + as space (query string convention), but in paths + should remain +.
     */
    private fun decodeHref(href: String): String {
        return try {
            // Preserve literal + by temporarily encoding it
            java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            href // Return as-is if decoding fails
        }
    }

    companion object {
        /** Shared instance for convenience */
        val INSTANCE = MultiStatusParser()
    }
}