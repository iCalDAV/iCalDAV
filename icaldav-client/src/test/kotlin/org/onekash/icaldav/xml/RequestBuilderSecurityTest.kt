package org.onekash.icaldav.xml

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Security tests for RequestBuilder XML generation.
 *
 * Tests XML injection prevention and proper escaping of user input.
 */
@DisplayName("RequestBuilder Security Tests")
class RequestBuilderSecurityTest {

    @Nested
    @DisplayName("XML Injection Prevention - MKCALENDAR")
    inner class XmlInjectionTests {

        @Test
        fun `displayName with angle brackets is escaped`() {
            val malicious = "<script>alert('xss')</script>"
            val xml = RequestBuilder.mkcalendar(displayName = malicious)

            // Should NOT contain unescaped angle brackets in content
            assertFalse(xml.contains("<script>"), "Unescaped < found")
            assertFalse(xml.contains("</script>"), "Unescaped </ found")

            // Should contain escaped versions
            assertTrue(xml.contains("&lt;script&gt;"), "Should contain escaped <")
            assertTrue(xml.contains("&lt;/script&gt;"), "Should contain escaped </")
        }

        @Test
        fun `displayName with ampersand is escaped`() {
            val input = "Work & Personal"
            val xml = RequestBuilder.mkcalendar(displayName = input)

            // The & should be escaped as &amp;
            assertTrue(xml.contains("&amp;"), "Ampersand should be escaped")
            // Should not have bare & followed by something other than amp/lt/gt/quot/apos
            assertFalse(xml.contains("& "), "Bare ampersand found")
        }

        @Test
        fun `displayName with quotes is escaped`() {
            val input = """Calendar "Main" Test"""
            val xml = RequestBuilder.mkcalendar(displayName = input)

            // Quotes should be escaped
            assertTrue(xml.contains("&quot;"), "Quote should be escaped")
        }

        @Test
        fun `displayName with XML closing tag injection attempt`() {
            // Attempt to close the displayname tag and inject new elements
            val malicious = "</D:displayname><D:evil>injected</D:evil><D:displayname>"
            val xml = RequestBuilder.mkcalendar(displayName = malicious)

            // Should NOT contain the injected element
            assertFalse(xml.contains("<D:evil>"), "XML injection succeeded")

            // The malicious content should be escaped
            assertTrue(xml.contains("&lt;/D:displayname&gt;"), "Closing tag should be escaped")
        }

        @Test
        fun `description with malicious content is escaped`() {
            val malicious = "<![CDATA[<script>evil()</script>]]>"
            val xml = RequestBuilder.mkcalendar(
                displayName = "Test",
                description = malicious
            )

            // CDATA markers should be escaped
            assertFalse(xml.contains("<![CDATA["), "CDATA not escaped")
        }

        @Test
        fun `color with malicious content is escaped`() {
            val malicious = "#FF0000\"/><evil/><foo attr=\""
            val xml = RequestBuilder.mkcalendar(
                displayName = "Test",
                color = malicious
            )

            // Should not contain injected elements
            assertFalse(xml.contains("<evil/>"), "XML injection in color")
        }

        @Test
        fun `all special XML characters are escaped`() {
            val input = "Test < > & \" ' chars"
            val xml = RequestBuilder.mkcalendar(displayName = input)

            assertTrue(xml.contains("&lt;"), "< should be escaped")
            assertTrue(xml.contains("&gt;"), "> should be escaped")
            assertTrue(xml.contains("&amp;"), "& should be escaped")
            assertTrue(xml.contains("&quot;"), "\" should be escaped")
            assertTrue(xml.contains("&apos;"), "' should be escaped")
        }

        @Test
        fun `normal input is preserved correctly`() {
            val input = "My Work Calendar"
            val xml = RequestBuilder.mkcalendar(displayName = input)

            assertTrue(xml.contains(input), "Normal input should be preserved")
        }

        @Test
        fun `unicode characters are preserved`() {
            val input = "Kalender \u00e4\u00f6\u00fc \u4e2d\u6587 \ud83d\udcc5"
            val xml = RequestBuilder.mkcalendar(displayName = input)

            assertTrue(xml.contains(input), "Unicode should be preserved")
        }

        @Test
        fun `newlines in displayName do not break XML`() {
            val input = "Line 1\nLine 2\rLine 3"
            val xml = RequestBuilder.mkcalendar(displayName = input)

            // XML should still be well-formed (basic check)
            assertTrue(xml.contains("<?xml"), "XML declaration missing")
            assertTrue(xml.contains("</C:mkcalendar>"), "Closing tag missing")
        }
    }

    @Nested
    @DisplayName("XML Structure Integrity")
    inner class XmlStructureTests {

        @Test
        fun `mkcalendar produces valid XML structure`() {
            val xml = RequestBuilder.mkcalendar(displayName = "Test")

            // Check basic XML structure
            assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertTrue(xml.contains("<C:mkcalendar"))
            assertTrue(xml.contains("</C:mkcalendar>"))
            assertTrue(xml.contains("xmlns:D=\"DAV:\""))
            assertTrue(xml.contains("xmlns:C=\"urn:ietf:params:xml:ns:caldav\""))
        }

        @Test
        fun `propfind produces valid XML structure`() {
            val xml = RequestBuilder.propfind("displayname", "resourcetype")

            assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertTrue(xml.contains("<D:propfind"))
            assertTrue(xml.contains("</D:propfind>"))
        }

        @Test
        fun `calendarQuery produces valid XML structure`() {
            val xml = RequestBuilder.calendarQuery(
                start = "20240101T000000Z",
                end = "20241231T235959Z"
            )

            // Uses lowercase prefixes (c:, d:) for iCloud compatibility
            assertTrue(xml.contains("<c:calendar-query"))
            assertTrue(xml.contains("</c:calendar-query>"))
            assertTrue(xml.contains("<c:time-range"))
        }

        @Test
        fun `calendarMultiget produces valid XML structure`() {
            val urls = listOf("/cal/event1.ics", "/cal/event2.ics")
            val xml = RequestBuilder.calendarMultiget(urls)

            assertTrue(xml.contains("<c:calendar-multiget"))
            assertTrue(xml.contains("</c:calendar-multiget>"))
            urls.forEach { url ->
                assertTrue(xml.contains("<d:href>$url</d:href>"))
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `empty displayName is handled`() {
            val xml = RequestBuilder.mkcalendar(displayName = "")
            assertTrue(xml.contains("<D:displayname></D:displayname>"))
        }

        @Test
        fun `very long displayName is handled`() {
            val longName = "A".repeat(10000)
            val xml = RequestBuilder.mkcalendar(displayName = longName)

            assertTrue(xml.contains(longName))
        }

        @Test
        fun `null description omits element`() {
            val xml = RequestBuilder.mkcalendar(
                displayName = "Test",
                description = null
            )

            assertFalse(xml.contains("calendar-description"))
        }

        @Test
        fun `null color omits element`() {
            val xml = RequestBuilder.mkcalendar(
                displayName = "Test",
                color = null
            )

            assertFalse(xml.contains("calendar-color"))
        }

        @Test
        fun `empty URL list in multiget`() {
            val xml = RequestBuilder.calendarMultiget(emptyList())

            assertTrue(xml.contains("<c:calendar-multiget"))
            assertFalse(xml.contains("<d:href>"))
        }
    }
}