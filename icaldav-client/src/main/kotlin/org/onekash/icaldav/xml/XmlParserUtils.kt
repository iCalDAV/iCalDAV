package org.onekash.icaldav.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Utility class for XmlPullParser-based XML extraction.
 *
 * Provides common XML parsing operations used by CalDavQuirks implementations
 * with automatic entity decoding and namespace handling.
 */
object XmlParserUtils {

    /**
     * Strip XML declaration and DOCTYPE from input.
     * - XML declaration causes issues with kxml2
     * - DOCTYPE stripping prevents XXE attacks
     */
    fun stripXmlProlog(xml: String): String {
        var result = xml.trim()

        // Strip XML declaration
        if (result.startsWith("<?xml")) {
            result = result.substringAfter("?>").trim()
        }

        // Strip DOCTYPE (security: prevents XXE attacks)
        if (result.startsWith("<!DOCTYPE", ignoreCase = true)) {
            val bracketIndex = result.indexOf('[')
            val gtIndex = result.indexOf('>')

            if (bracketIndex != -1 && bracketIndex < gtIndex) {
                val closeIndex = result.indexOf("]>")
                if (closeIndex != -1) {
                    result = result.substring(closeIndex + 2).trim()
                }
            } else if (gtIndex != -1) {
                result = result.substring(gtIndex + 1).trim()
            }
        }

        return result
    }

    /**
     * Create a namespace-aware XmlPullParser for the given XML.
     */
    fun createParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(stripXmlProlog(xml)))
        return parser
    }

    /**
     * Extract the first occurrence of an element's text content.
     *
     * @param xml XML string to search
     * @param elementName Local name of element (without namespace prefix)
     * @return Text content or null if not found
     */
    fun extractElementText(xml: String, elementName: String): String? {
        return try {
            val parser = createParser(xml)
            val targetName = elementName.lowercase()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.lowercase() == targetName) {
                    return readTextContent(parser).takeIf { it.isNotEmpty() }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract href from inside a container element (e.g., current-user-principal/href).
     *
     * @param xml XML string to search
     * @param containerName Container element name
     * @return href value or null
     */
    fun extractHrefFromContainer(xml: String, containerName: String): String? {
        return try {
            val parser = createParser(xml)
            val targetContainer = containerName.lowercase()
            var inContainer = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase()
                        when {
                            name == targetContainer -> inContainer = true
                            name == "href" && inContainer -> {
                                val href = readTextContent(parser).trim()
                                if (href.isNotEmpty()) return decodeHref(href)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == targetContainer) {
                            inContainer = false
                        }
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract all response elements and their properties from multistatus XML.
     */
    data class ResponseData(
        val href: String,
        val status: Int,
        val displayName: String?,
        val etag: String?,
        val ctag: String?,
        val calendarColor: String?,
        val calendarData: String?,
        val hasCalendarResourceType: Boolean,
        val isReadOnly: Boolean
    )

    /**
     * Parse all response elements from multistatus XML.
     */
    fun parseResponses(xml: String): List<ResponseData> {
        val responses = mutableListOf<ResponseData>()

        try {
            val parser = createParser(xml)
            var currentResponse: ResponseBuilder? = null
            var inResourceType = false
            var resourceTypeContent = StringBuilder()
            var responseDepth = 0

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase()
                        when (name) {
                            "response" -> {
                                responseDepth++
                                if (responseDepth == 1) {
                                    currentResponse = ResponseBuilder()
                                }
                            }
                            "href" -> {
                                if (currentResponse != null && currentResponse.href == null && !inResourceType) {
                                    currentResponse.href = decodeHref(readTextContent(parser))
                                }
                            }
                            "status" -> {
                                if (currentResponse != null) {
                                    val statusText = readTextContent(parser)
                                    currentResponse.status = parseStatusCode(statusText)
                                }
                            }
                            "displayname" -> {
                                if (currentResponse != null) {
                                    currentResponse.displayName = readTextContent(parser).takeIf { it.isNotEmpty() }
                                }
                            }
                            "getetag" -> {
                                if (currentResponse != null) {
                                    currentResponse.etag = readTextContent(parser).trim().removeSurrounding("\"")
                                }
                            }
                            "getctag" -> {
                                if (currentResponse != null) {
                                    currentResponse.ctag = readTextContent(parser).takeIf { it.isNotEmpty() }
                                }
                            }
                            "calendar-color" -> {
                                if (currentResponse != null) {
                                    currentResponse.calendarColor = readTextContent(parser).takeIf { it.isNotEmpty() }
                                }
                            }
                            "calendar-data" -> {
                                if (currentResponse != null) {
                                    currentResponse.calendarData = readTextContent(parser).takeIf { it.isNotEmpty() }
                                }
                            }
                            "resourcetype" -> {
                                inResourceType = true
                                resourceTypeContent.clear()
                            }
                            "calendar" -> {
                                if (inResourceType && currentResponse != null) {
                                    currentResponse.hasCalendarResourceType = true
                                }
                            }
                            "read-only" -> {
                                if (currentResponse != null) {
                                    currentResponse.isReadOnly = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase()
                        when (name) {
                            "response" -> {
                                if (responseDepth == 1 && currentResponse != null) {
                                    val href = currentResponse.href
                                    if (href != null && href.isNotEmpty()) {
                                        responses.add(currentResponse.build())
                                    }
                                    currentResponse = null
                                }
                                responseDepth--
                            }
                            "resourcetype" -> {
                                inResourceType = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever we parsed before the error
        }

        return responses
    }

    /**
     * Read text content from current element, handling TEXT, CDATA, and nested elements.
     */
    fun readTextContent(parser: XmlPullParser): String {
        val content = StringBuilder()
        var depth = 1

        var eventType = parser.next()
        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> content.append(parser.text)
                XmlPullParser.CDSECT -> content.append(parser.text)
                XmlPullParser.START_TAG -> {
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
     * URL-decode an href, preserving literal + signs.
     */
    fun decodeHref(href: String): String {
        return try {
            java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            href
        }
    }

    /**
     * Parse HTTP status code from status line.
     */
    private fun parseStatusCode(statusLine: String): Int {
        val match = Regex("""HTTP/[\d.]+\s+(\d+)""").find(statusLine)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 200
    }

    private class ResponseBuilder {
        var href: String? = null
        var status: Int = 200
        var displayName: String? = null
        var etag: String? = null
        var ctag: String? = null
        var calendarColor: String? = null
        var calendarData: String? = null
        var hasCalendarResourceType: Boolean = false
        var isReadOnly: Boolean = false

        fun build() = ResponseData(
            href = href ?: "",
            status = status,
            displayName = displayName,
            etag = etag,
            ctag = ctag,
            calendarColor = calendarColor,
            calendarData = calendarData,
            hasCalendarResourceType = hasCalendarResourceType,
            isReadOnly = isReadOnly
        )
    }
}
