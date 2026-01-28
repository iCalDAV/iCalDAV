package org.onekash.icaldav.xml

import org.onekash.icaldav.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser for WebDAV/CalDAV multistatus XML responses.
 *
 * Uses XmlPullParser for efficient streaming parsing with:
 * - Automatic XML entity decoding (&amp; → &, &lt; → <, etc.)
 * - Proper namespace handling (d:, D:, c:, cs:, unprefixed)
 * - Per-propstat status tracking
 * - CDATA section support
 *
 * Production-tested with iCloud, Nextcloud, Baikal, and Radicale servers.
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
            val result = parseXml(xml)
            DavResult.success(MultiStatus(result.responses, result.syncToken))
        } catch (e: Exception) {
            DavResult.parseError("Failed to parse multistatus: ${e.message}", xml)
        }
    }

    private data class ParseResult(
        val responses: List<DavResponse>,
        val syncToken: String?
    )

    /**
     * Parse XML using XmlPullParser.
     */
    private fun parseXml(xml: String): ParseResult {
        // Strip XML declaration and DOCTYPE if present
        // - kxml2 doesn't handle XML declarations well
        // - DOCTYPE stripping is a security measure against XXE attacks
        val cleanXml = stripXmlProlog(xml)

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(cleanXml))

        val responses = mutableListOf<DavResponse>()
        var rootSyncToken: String? = null

        // Response-level state
        var currentResponse: ResponseBuilder? = null

        // Propstat-level state
        var currentPropstat: PropstatBuilder? = null

        // Nested element tracking
        var inCurrentUserPrincipal = false
        var inCalendarHomeSet = false
        var inResourceType = false
        var resourceTypeContent = StringBuilder()

        // Track depth for response elements to handle nested responses correctly
        var responseDepth = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name.lowercase()

                    when (localName) {
                        "response" -> {
                            responseDepth++
                            if (responseDepth == 1) {
                                currentResponse = ResponseBuilder()
                            }
                        }
                        "propstat" -> {
                            if (currentResponse != null) {
                                currentPropstat = PropstatBuilder()
                            }
                        }
                        "prop" -> {
                            // Just marks entering prop block, properties extracted inside
                        }
                        "href" -> {
                            val text = parser.nextText().trim()
                            when {
                                inCalendarHomeSet && currentPropstat != null -> {
                                    currentPropstat.calendarHomeSet = decodeHref(text)
                                }
                                inCurrentUserPrincipal && currentPropstat != null -> {
                                    currentPropstat.currentUserPrincipal = decodeHref(text)
                                }
                                currentResponse != null && currentResponse.href == null -> {
                                    currentResponse.href = decodeHref(text)
                                }
                            }
                        }
                        "status" -> {
                            val statusText = parser.nextText()
                            val (code, text) = parseStatus(statusText)
                            if (currentPropstat != null) {
                                currentPropstat.statusCode = code
                                currentPropstat.statusText = text
                            } else if (currentResponse != null) {
                                // Response-level status (e.g., 404 for deleted items)
                                currentResponse.status = code
                            }
                        }
                        "displayname" -> {
                            if (currentPropstat != null) {
                                // Use readTextContent to handle potential nested elements defensively
                                currentPropstat.displayName = readTextContent(parser).takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("displayname")
                            }
                        }
                        "resourcetype" -> {
                            if (currentPropstat != null) {
                                inResourceType = true
                                resourceTypeContent.clear()
                                currentPropstat.addPropertyPresent("resourcetype")
                            }
                        }
                        "getetag" -> {
                            if (currentPropstat != null) {
                                val etag = parser.nextText().trim().removeSurrounding("\"")
                                currentPropstat.etag = etag
                                currentPropstat.addPropertyPresent("getetag")
                            }
                        }
                        "getctag" -> {
                            if (currentPropstat != null) {
                                currentPropstat.ctag = parser.nextText().trim()
                                currentPropstat.addPropertyPresent("getctag")
                            }
                        }
                        "sync-token" -> {
                            val token = parser.nextText().trim()
                            if (currentPropstat != null) {
                                currentPropstat.syncToken = token
                                currentPropstat.addPropertyPresent("sync-token")
                            } else if (currentResponse == null) {
                                // Root-level sync-token
                                rootSyncToken = token
                            }
                        }
                        "calendar-color" -> {
                            if (currentPropstat != null) {
                                currentPropstat.calendarColor = parser.nextText().trim().takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("calendar-color")
                            }
                        }
                        "calendar-description" -> {
                            if (currentPropstat != null) {
                                currentPropstat.calendarDescription = parser.nextText().trim().takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("calendar-description")
                            }
                        }
                        "current-user-principal" -> {
                            if (currentPropstat != null) {
                                inCurrentUserPrincipal = true
                                currentPropstat.addPropertyPresent("current-user-principal")
                            }
                        }
                        "calendar-home-set" -> {
                            if (currentPropstat != null) {
                                inCalendarHomeSet = true
                                currentPropstat.addPropertyPresent("calendar-home-set")
                            }
                        }
                        "calendar-data" -> {
                            if (currentPropstat != null) {
                                // Handle both plain text and CDATA sections
                                currentPropstat.calendarData = readTextContent(parser).takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("calendar-data")
                            }
                        }
                        "getlastmodified" -> {
                            if (currentPropstat != null) {
                                currentPropstat.getLastModified = parser.nextText().trim().takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("getlastmodified")
                            }
                        }
                        "getcontenttype" -> {
                            if (currentPropstat != null) {
                                currentPropstat.getContentType = parser.nextText().trim().takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("getcontenttype")
                            }
                        }
                        "getcontentlength" -> {
                            if (currentPropstat != null) {
                                currentPropstat.getContentLength = parser.nextText().trim().takeIf { it.isNotEmpty() }
                                currentPropstat.addPropertyPresent("getcontentlength")
                            }
                        }
                        "supported-calendar-component-set" -> {
                            if (currentPropstat != null) {
                                // For now, just track presence; could extract VEVENT/VTODO support
                                currentPropstat.addPropertyPresent("supported-calendar-component-set")
                            }
                        }
                        // Track resourcetype children
                        "collection", "calendar", "schedule-inbox", "schedule-outbox" -> {
                            if (inResourceType) {
                                resourceTypeContent.append("<$localName/>")
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val localName = parser.name.lowercase()

                    when (localName) {
                        "response" -> {
                            if (responseDepth == 1 && currentResponse != null) {
                                // Only add response if it has an href
                                val response = currentResponse.build()
                                if (response.href.isNotEmpty()) {
                                    responses.add(response)
                                }
                                currentResponse = null
                            }
                            responseDepth--
                        }
                        "propstat" -> {
                            if (currentPropstat != null && currentResponse != null) {
                                currentResponse.mergePropstat(currentPropstat)
                                currentPropstat = null
                            }
                        }
                        "resourcetype" -> {
                            if (currentPropstat != null && inResourceType) {
                                currentPropstat.resourceType = resourceTypeContent.toString()
                            }
                            inResourceType = false
                        }
                        "current-user-principal" -> {
                            inCurrentUserPrincipal = false
                        }
                        "calendar-home-set" -> {
                            inCalendarHomeSet = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return ParseResult(responses, rootSyncToken)
    }

    /**
     * Strip XML declaration and DOCTYPE from the input.
     * - XML declaration (<?xml ...?>) causes issues with kxml2
     * - DOCTYPE stripping prevents XXE (XML External Entity) attacks
     */
    private fun stripXmlProlog(xml: String): String {
        var result = xml.trim()

        // Strip XML declaration: <?xml version="1.0" encoding="UTF-8"?>
        if (result.startsWith("<?xml")) {
            result = result.substringAfter("?>").trim()
        }

        // Strip DOCTYPE (security: prevents XXE attacks)
        // Handles multi-line DOCTYPE with internal subset: <!DOCTYPE foo [ ... ]>
        if (result.startsWith("<!DOCTYPE", ignoreCase = true)) {
            val bracketIndex = result.indexOf('[')
            val gtIndex = result.indexOf('>')

            if (bracketIndex != -1 && bracketIndex < gtIndex) {
                // DOCTYPE with internal subset: <!DOCTYPE foo [ ... ]>
                // Find the closing ]>
                val closeIndex = result.indexOf("]>")
                if (closeIndex != -1) {
                    result = result.substring(closeIndex + 2).trim()
                }
            } else if (gtIndex != -1) {
                // Simple DOCTYPE: <!DOCTYPE foo>
                result = result.substring(gtIndex + 1).trim()
            }
        }

        return result
    }

    /**
     * Read text content from current element, handling:
     * - Plain text
     * - CDATA sections
     * - Unexpected nested elements (treated as text for security/compatibility)
     *
     * After this method, the parser will be positioned at the END_TAG.
     */
    private fun readTextContent(parser: XmlPullParser): String {
        val content = StringBuilder()
        var depth = 1  // We're inside the starting element
        var eventType = parser.next()

        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> content.append(parser.text)
                XmlPullParser.CDSECT -> content.append(parser.text)
                XmlPullParser.START_TAG -> {
                    // Unexpected nested element - include as text for compatibility
                    content.append("<").append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        content.append(" ").append(parser.getAttributeName(i))
                            .append("=\"").append(parser.getAttributeValue(i)).append("\"")
                    }
                    content.append(">")
                    depth++
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth > 0) {
                        // Closing an unexpected nested element
                        content.append("</").append(parser.name).append(">")
                    }
                }
            }
            if (depth > 0) {
                eventType = parser.next()
            }
        }

        return content.toString().trim()
    }

    /**
     * Parse HTTP status line into code and text.
     */
    private fun parseStatus(statusLine: String): Pair<Int, String> {
        val match = Regex("""HTTP/[\d.]+\s+(\d+)\s*(.*)""").find(statusLine)
        val code = match?.groupValues?.get(1)?.toIntOrNull() ?: 200
        val text = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: "OK"
        return Pair(code, text)
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

    /**
     * Builder for accumulating response data.
     */
    private class ResponseBuilder {
        var href: String? = null
        var status: Int = 200

        // Properties accumulated from all propstats
        private val props = mutableMapOf<String, String?>()
        private val propStatus = mutableMapOf<String, PropertyStatus>()

        // Direct properties from propstats
        var etag: String? = null
        var calendarData: String? = null

        fun mergePropstat(propstat: PropstatBuilder) {
            val status = PropertyStatus(propstat.statusCode, propstat.statusText)

            // Track status for all present properties
            for (propName in propstat.propertiesPresent) {
                propStatus[propName] = status
            }

            // Only merge values for successful propstats
            if (status.isSuccess) {
                propstat.displayName?.let { props["displayname"] = it }
                propstat.resourceType?.let { props["resourcetype"] = it }
                propstat.ctag?.let { props["getctag"] = it }
                propstat.syncToken?.let { props["sync-token"] = it }
                propstat.calendarColor?.let { props["calendar-color"] = it }
                propstat.calendarDescription?.let { props["calendar-description"] = it }
                propstat.currentUserPrincipal?.let { props["current-user-principal"] = it }
                propstat.calendarHomeSet?.let { props["calendar-home-set"] = it }
                propstat.getLastModified?.let { props["getlastmodified"] = it }
                propstat.getContentType?.let { props["getcontenttype"] = it }
                propstat.getContentLength?.let { props["getcontentlength"] = it }

                // etag and calendarData are also set on response directly
                propstat.etag?.let {
                    props["getetag"] = it
                    etag = it
                }
                propstat.calendarData?.let { calendarData = it }
            }
        }

        fun build(): DavResponse {
            return DavResponse(
                href = href ?: "",
                status = status,
                properties = DavProperties.from(props, propStatus),
                etag = etag,
                calendarData = calendarData
            )
        }
    }

    /**
     * Builder for accumulating propstat data.
     */
    private class PropstatBuilder {
        var statusCode: Int = 200
        var statusText: String = "OK"

        var displayName: String? = null
        var resourceType: String? = null
        var etag: String? = null
        var ctag: String? = null
        var syncToken: String? = null
        var calendarColor: String? = null
        var calendarDescription: String? = null
        var currentUserPrincipal: String? = null
        var calendarHomeSet: String? = null
        var calendarData: String? = null
        var getLastModified: String? = null
        var getContentType: String? = null
        var getContentLength: String? = null

        // Track which properties are present (even if empty or 404)
        val propertiesPresent = mutableSetOf<String>()

        fun addPropertyPresent(name: String) {
            propertiesPresent.add(name)
        }
    }

    companion object {
        /** Shared instance for convenience */
        val INSTANCE = MultiStatusParser()
    }
}
