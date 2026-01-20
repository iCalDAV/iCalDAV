package org.onekash.icaldav.xml

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.ScheduleStatus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Schedule Response Parser Tests")
class ScheduleResponseParserTest {

    @Test
    fun `parses single recipient response`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <C:schedule-response xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <C:response>
                    <C:recipient><D:href>mailto:user@example.com</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status>
                </C:response>
            </C:schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertTrue(result.success)
        assertEquals(1, result.recipientResults.size)
        assertEquals("user@example.com", result.recipientResults[0].recipient)
        assertEquals("2.0", result.recipientResults[0].status.code)
        assertEquals("Success", result.recipientResults[0].status.description)
    }

    @Test
    fun `parses multiple recipient responses`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <C:schedule-response xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <C:response>
                    <C:recipient><D:href>mailto:user1@example.com</D:href></C:recipient>
                    <C:request-status>2.0;Success</C:request-status>
                </C:response>
                <C:response>
                    <C:recipient><D:href>mailto:user2@example.com</D:href></C:recipient>
                    <C:request-status>3.7;Invalid calendar user</C:request-status>
                </C:response>
                <C:response>
                    <C:recipient><D:href>mailto:user3@example.com</D:href></C:recipient>
                    <C:request-status>5.1;Could not deliver</C:request-status>
                </C:response>
            </C:schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertEquals(3, result.recipientResults.size)
        assertEquals("user1@example.com", result.recipientResults[0].recipient)
        assertEquals("user2@example.com", result.recipientResults[1].recipient)
        assertEquals("user3@example.com", result.recipientResults[2].recipient)

        assertTrue(result.recipientResults[0].status.isSuccess)
        assertEquals(ScheduleStatus.StatusCategory.PERMISSION_ERROR, result.recipientResults[1].status.category)
        assertEquals(ScheduleStatus.StatusCategory.DELIVERY_ERROR, result.recipientResults[2].status.category)
    }

    @Test
    fun `extracts request-status code and description`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:test@example.com</D:href></recipient>
                    <request-status>2.0;Scheduling message successfully delivered</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertEquals("2.0", result.recipientResults[0].status.code)
        assertEquals("Scheduling message successfully delivered", result.recipientResults[0].status.description)
    }

    @Test
    fun `extracts calendar-data for free-busy`() {
        val calendarData = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VFREEBUSY
UID:fb-123
DTSTART:20231215T000000Z
DTEND:20231222T000000Z
FREEBUSY:20231215T090000Z/20231215T100000Z
END:VFREEBUSY
END:VCALENDAR"""

        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                    <calendar-data>$calendarData</calendar-data>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertEquals(calendarData, result.recipientResults[0].calendarData)
    }

    @Test
    fun `handles missing calendar-data`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertNull(result.recipientResults[0].calendarData)
    }

    @Test
    fun `handles namespace prefixes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <cal:schedule-response xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:dav="DAV:">
                <cal:response>
                    <cal:recipient><dav:href>mailto:user@example.com</dav:href></cal:recipient>
                    <cal:request-status>2.0;Success</cal:request-status>
                </cal:response>
            </cal:schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertEquals(1, result.recipientResults.size)
        assertEquals("user@example.com", result.recipientResults[0].recipient)
    }

    @Test
    fun `success is true when all recipients succeed`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user1@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
                <response>
                    <recipient><D:href>mailto:user2@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertTrue(result.success)
    }

    @Test
    fun `success is true when all recipients are pending or success`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user1@example.com</D:href></recipient>
                    <request-status>1.0;Request pending</request-status>
                </response>
                <response>
                    <recipient><D:href>mailto:user2@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertTrue(result.success)
    }

    @Test
    fun `success is false when any recipient fails`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user1@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
                <response>
                    <recipient><D:href>mailto:user2@example.com</D:href></recipient>
                    <request-status>5.1;Could not deliver</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertTrue(!result.success)
    }

    @Test
    fun `stores raw response`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <response>
                    <recipient><D:href>mailto:user@example.com</D:href></recipient>
                    <request-status>2.0;Success</request-status>
                </response>
            </schedule-response>
        """.trimIndent()

        val result = ScheduleResponseParser.parse(xml)

        assertEquals(xml, result.rawResponse)
    }

    @Nested
    @DisplayName("XML Entity Unescaping Tests")
    inner class XmlEntityUnescapingTests {
        @Test
        fun `unescapes XML entities in calendar-data`() {
            val xml = """
                <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                    <response>
                        <recipient><D:href>mailto:user@example.com</D:href></recipient>
                        <request-status>2.0;Success</request-status>
                        <calendar-data>&lt;test&gt;&amp;data&lt;/test&gt;</calendar-data>
                    </response>
                </schedule-response>
            """.trimIndent()

            val result = ScheduleResponseParser.parse(xml)

            assertEquals("<test>&data</test>", result.recipientResults[0].calendarData)
        }
    }
}
