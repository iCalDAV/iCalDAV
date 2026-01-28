package org.onekash.icaldav.xml

import org.onekash.icaldav.model.RequestStatus
import org.onekash.icaldav.model.ScheduleStatus
import org.onekash.icaldav.model.SchedulingResult
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser for schedule-response XML (RFC 6638 Section 3.2.11).
 *
 * Uses XmlPullParser for efficient parsing with automatic entity decoding.
 *
 * Response format:
 * ```xml
 * <C:schedule-response xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
 *   <C:response>
 *     <C:recipient><D:href>mailto:user@example.com</D:href></C:recipient>
 *     <C:request-status>2.0;Success</C:request-status>
 *     <C:calendar-data>BEGIN:VCALENDAR...END:VCALENDAR</C:calendar-data>
 *   </C:response>
 * </C:schedule-response>
 * ```
 */
object ScheduleResponseParser {

    /**
     * Parse schedule-response XML into SchedulingResult.
     *
     * @param xml Raw XML response from schedule-outbox POST
     * @return SchedulingResult with per-recipient status
     */
    fun parse(xml: String): SchedulingResult {
        val recipientResults = mutableListOf<SchedulingResult.RecipientResult>()

        try {
            val parser = createParser(xml)
            var currentResult: ResultBuilder? = null
            var inRecipient = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "response" -> currentResult = ResultBuilder()
                            "recipient" -> inRecipient = true
                            "href" -> {
                                if (inRecipient && currentResult != null) {
                                    val href = readTextContent(parser).trim()
                                    currentResult.recipient = href.removePrefix("mailto:")
                                }
                            }
                            "request-status" -> {
                                if (currentResult != null) {
                                    currentResult.statusStr = readTextContent(parser).trim()
                                }
                            }
                            "calendar-data" -> {
                                if (currentResult != null) {
                                    currentResult.calendarData = readTextContent(parser).takeIf { it.isNotEmpty() }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "response" -> {
                                currentResult?.build()?.let { recipientResults.add(it) }
                                currentResult = null
                            }
                            "recipient" -> inRecipient = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever we parsed
        }

        return SchedulingResult(
            success = recipientResults.all { it.status.isSuccess || it.status.isPending },
            recipientResults = recipientResults,
            rawResponse = xml
        )
    }

    private fun createParser(xml: String): XmlPullParser {
        val cleanXml = XmlParserUtils.stripXmlProlog(xml)
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(cleanXml))
        return parser
    }

    private fun readTextContent(parser: XmlPullParser): String {
        val content = StringBuilder()
        var depth = 1
        var eventType = parser.next()

        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> content.append(parser.text)
                XmlPullParser.CDSECT -> content.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) eventType = parser.next()
        }

        return content.toString().trim()
    }

    private class ResultBuilder {
        var recipient: String? = null
        var statusStr: String = "5.0;Unknown error"
        var calendarData: String? = null

        fun build(): SchedulingResult.RecipientResult? {
            val r = recipient ?: return null
            val status = ScheduleStatus.fromString(statusStr)
            val requestStatus = RequestStatus.fromCode(status.code)

            return SchedulingResult.RecipientResult(
                recipient = r,
                status = status,
                requestStatus = requestStatus,
                calendarData = calendarData
            )
        }
    }
}
