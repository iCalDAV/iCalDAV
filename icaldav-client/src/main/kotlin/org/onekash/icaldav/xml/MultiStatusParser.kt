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
     * Extract all properties from response, preserving per-property status.
     *
     * WebDAV servers return properties in propstat elements, where each propstat
     * has its own status. A single response may have multiple propstat elements
     * (e.g., one with 200 OK for found properties, one with 404 for not found).
     *
     * XML structure:
     * ```xml
     * <response>
     *   <propstat>
     *     <prop><displayname>Name</displayname></prop>
     *     <status>HTTP/1.1 200 OK</status>
     *   </propstat>
     *   <propstat>
     *     <prop><calendar-color/></prop>
     *     <status>HTTP/1.1 404 Not Found</status>
     *   </propstat>
     * </response>
     * ```
     */
    private fun extractProperties(xml: String): DavProperties {
        val props = mutableMapOf<String, String?>()
        val propStatus = mutableMapOf<String, PropertyStatus>()

        // Find all propstat blocks (non-greedy)
        val propstatPattern = Regex(
            """<(?:[a-zA-Z]+:)?propstat[^>]*>(.*?)</(?:[a-zA-Z]+:)?propstat>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val propstatMatches = propstatPattern.findAll(xml).toList()

        // If no propstat elements found, fall back to direct prop extraction
        if (propstatMatches.isEmpty()) {
            return extractPropertiesLegacy(xml)
        }

        for (propstatMatch in propstatMatches) {
            val propstatXml = propstatMatch.groupValues[1]

            // Extract status for this propstat block
            val statusCode = extractStatus(propstatXml)
            val statusText = extractStatusText(propstatXml)
            val status = PropertyStatus(statusCode, statusText)

            // Extract all properties from this propstat's prop block
            val propBlockPattern = Regex(
                """<(?:[a-zA-Z]+:)?prop[^>]*>(.*?)</(?:[a-zA-Z]+:)?prop>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val propContent = propBlockPattern.find(propstatXml)?.groupValues?.get(1) ?: continue

            // Extract property values for successful status, track status for all
            extractPropertiesFromBlock(propContent, props, propStatus, status)
        }

        return DavProperties.from(props, propStatus)
    }

    /**
     * Extract properties from a single prop block, recording status for each.
     */
    private fun extractPropertiesFromBlock(
        propContent: String,
        props: MutableMap<String, String?>,
        propStatus: MutableMap<String, PropertyStatus>,
        status: PropertyStatus
    ) {
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
            // Check if this property exists in the block (even if empty/self-closing)
            if (hasPropertyInBlock(propContent, propName)) {
                propStatus[propName] = status

                // Only extract values for successful properties
                if (status.isSuccess) {
                    val value = extractPropertyValue(propContent, propName)
                    if (value != null) {
                        props[propName] = value
                    }
                }
            }
        }

        // Special handling for resourcetype which contains child elements
        if (hasPropertyInBlock(propContent, "resourcetype")) {
            propStatus["resourcetype"] = status
            if (status.isSuccess) {
                val resourceTypePattern = Regex(
                    """<(?:[a-zA-Z]+:)?resourcetype[^>]*>(.*?)</(?:[a-zA-Z]+:)?resourcetype>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                )
                resourceTypePattern.find(propContent)?.groupValues?.get(1)?.let { rtContent ->
                    props["resourcetype"] = rtContent
                }
            }
        }

        // Special handling for href inside current-user-principal
        if (hasPropertyInBlock(propContent, "current-user-principal")) {
            propStatus["current-user-principal"] = status
            if (status.isSuccess) {
                val principalPattern = Regex(
                    """<(?:[a-zA-Z]+:)?current-user-principal[^>]*>.*?<(?:[a-zA-Z]+:)?href[^>]*>(.*?)</(?:[a-zA-Z]+:)?href>.*?</(?:[a-zA-Z]+:)?current-user-principal>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                )
                principalPattern.find(propContent)?.groupValues?.get(1)?.trim()?.let { href ->
                    props["current-user-principal"] = decodeHref(href)
                }
            }
        }

        // Special handling for href inside calendar-home-set
        if (hasPropertyInBlock(propContent, "calendar-home-set")) {
            propStatus["calendar-home-set"] = status
            if (status.isSuccess) {
                val homeSetPattern = Regex(
                    """<(?:[a-zA-Z]+:)?calendar-home-set[^>]*>.*?<(?:[a-zA-Z]+:)?href[^>]*>(.*?)</(?:[a-zA-Z]+:)?href>.*?</(?:[a-zA-Z]+:)?calendar-home-set>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                )
                homeSetPattern.find(propContent)?.groupValues?.get(1)?.trim()?.let { href ->
                    props["calendar-home-set"] = decodeHref(href)
                }
            }
        }
    }

    /**
     * Check if a property exists in the prop block (handles self-closing tags).
     */
    private fun hasPropertyInBlock(propContent: String, propName: String): Boolean {
        // Match opening tag (with content) or self-closing tag
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?$propName(?:\s[^>]*)?(?:>|/>)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(propContent)
    }

    /**
     * Extract status text from propstat status element (e.g., "OK", "Not Found").
     */
    private fun extractStatusText(xml: String): String {
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?status[^>]*>HTTP/[\d.]+\s+\d+\s+([^<]+)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim() ?: "OK"
    }

    /**
     * Legacy property extraction for backward compatibility.
     * Used when no propstat elements are found (non-standard response).
     */
    private fun extractPropertiesLegacy(xml: String): DavProperties {
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