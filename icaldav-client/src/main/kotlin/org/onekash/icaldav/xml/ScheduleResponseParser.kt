package org.onekash.icaldav.xml

import org.onekash.icaldav.model.RequestStatus
import org.onekash.icaldav.model.ScheduleStatus
import org.onekash.icaldav.model.SchedulingResult

/**
 * Parser for schedule-response XML (RFC 6638 Section 3.2.11).
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

        // Pattern to find all <response> elements (handles namespace prefixes)
        val responsePattern = Regex(
            """<(?:\w+:)?response[^>]*>(.*?)</(?:\w+:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responsePattern.findAll(xml)) {
            val responseXml = match.groupValues[1]

            // Extract recipient (inside <href>)
            val recipientPattern = Regex(
                """<(?:\w+:)?recipient[^>]*>.*?<(?:\w+:)?href[^>]*>([^<]+)</(?:\w+:)?href>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val recipient = recipientPattern.find(responseXml)
                ?.groupValues?.get(1)
                ?.trim()
                ?.removePrefix("mailto:")
                ?: continue

            // Extract request-status (e.g., "2.0;Success")
            val statusPattern = Regex(
                """<(?:\w+:)?request-status[^>]*>([^<]+)</(?:\w+:)?request-status>""",
                RegexOption.IGNORE_CASE
            )
            val statusStr = statusPattern.find(responseXml)?.groupValues?.get(1)?.trim()
                ?: "5.0;Unknown error"
            val status = ScheduleStatus.fromString(statusStr)
            val requestStatus = RequestStatus.fromCode(status.code)

            // Extract calendar-data (optional, for free-busy responses)
            val calDataPattern = Regex(
                """<(?:\w+:)?calendar-data[^>]*>(.*?)</(?:\w+:)?calendar-data>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val calendarData = calDataPattern.find(responseXml)?.groupValues?.get(1)?.trim()
                ?.let { unescapeXml(it) }

            recipientResults.add(
                SchedulingResult.RecipientResult(
                    recipient = recipient,
                    status = status,
                    requestStatus = requestStatus,
                    calendarData = calendarData
                )
            )
        }

        return SchedulingResult(
            success = recipientResults.all { it.status.isSuccess || it.status.isPending },
            recipientResults = recipientResults,
            rawResponse = xml
        )
    }

    /**
     * Unescape XML entities in calendar data.
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
