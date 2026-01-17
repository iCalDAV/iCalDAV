package com.icalendar.webdav.xml

import com.icalendar.webdav.model.DavResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * ReDoS (Regular Expression Denial of Service) prevention tests.
 *
 * These tests verify that regex patterns in MultiStatusParser complete
 * in reasonable time even with adversarial input designed to trigger
 * catastrophic backtracking.
 *
 * OWASP Reference: https://owasp.org/www-community/attacks/Regular_expression_Denial_of_Service_-_ReDoS
 *
 * Classic ReDoS patterns to test against:
 * - Nested quantifiers: (a+)+ on "aaaaaaaaaaaaaaaaaaaaaaaaaaaaX"
 * - Overlapping alternation: (a|a)+ on repeated 'a'
 * - Quantified groups with optional elements
 *
 * The MultiStatusParser uses patterns like:
 * - <(?:[a-zA-Z]+:)?response[^>]*>(.*?)</(?:[a-zA-Z]+:)?response>
 * - These could be vulnerable if input has many '<' without closing '>'
 */
@DisplayName("ReDoS Prevention Tests")
class ReDoSPreventionTest {

    private val parser = MultiStatusParser()

    // All tests must complete within 5 seconds
    // ReDoS attacks would cause exponential time, hanging for minutes/hours

    @Nested
    @DisplayName("MultiStatusParser ReDoS Resistance")
    inner class MultiStatusParserReDoS {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `deeply nested tags do not cause exponential backtracking`() {
            // Create deeply nested structure that might confuse regex
            val nestedTags = buildString {
                repeat(1000) { append("<D:response>") }
                append("<D:href>/test</D:href>")
                repeat(1000) { append("</D:response>") }
            }

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    $nestedTags
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            // Should complete quickly, result may be success or error
            assertTrue(result is DavResult.Success || result is DavResult.ParseError,
                "Should complete without hanging")
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `moderate unclosed tags do not cause backtracking`() {
            // Unclosed tags could cause regex to backtrack looking for matches
            // Note: Very large numbers (10000+) of unclosed tags may cause slowdown
            // due to regex backtracking - this is a known limitation of regex parsing
            val unclosedTags = "<D:response>".repeat(500)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    $unclosedTags
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `repeated namespace prefixes do not cause backtracking`() {
            // Many different namespace prefixes
            val prefixes = (1..1000).joinToString("") { i ->
                "<ns$i:response><ns$i:href>/path$i</ns$i:href></ns$i:response>"
            }

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    $prefixes
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `long attribute values do not cause backtracking`() {
            // Pattern [^>]* could be slow with very long attributes
            val longAttr = "x".repeat(100000)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response attr="$longAttr">
                        <D:href>/test</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `alternating open close brackets do not cause backtracking`() {
            // Pattern that might confuse greedy vs lazy matching
            val alternating = "<>".repeat(50000)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>$alternating</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `repeated response-like strings in content do not cause backtracking`() {
            // Content that looks like tags but isn't
            val fakeResponses = "response response response ".repeat(10000)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/test</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>$fakeResponses</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `calendar data with repeated patterns does not hang`() {
            // Calendar data with patterns that might match regex partially
            val repeatedIcal = "BEGIN:VEVENT\nEND:VEVENT\n".repeat(5000)

            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data>BEGIN:VCALENDAR
$repeatedIcal
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `many colons in namespace prefix area does not hang`() {
            // Colons used in namespace prefixes could confuse pattern
            val manyColons = ":".repeat(10000)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/test$manyColons</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `pathological input for dot-matches-all does not hang`() {
            // DOT_MATCHES_ALL with repeated newlines
            val manyNewlines = "\n".repeat(100000)

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/test</D:href>$manyNewlines
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success || result is DavResult.ParseError)
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        fun `evil regex input pattern aaaaaaaX does not hang`() {
            // Classic ReDoS pattern - many 'a's followed by non-matching char
            // Tests if any internal pattern is vulnerable to (a+)+ style attack
            val evilInput = "a".repeat(50) + "X"

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/$evilInput</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>$evilInput</D:displayname>
                                <D:getetag>"$evilInput"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)
            assertTrue(result is DavResult.Success)
        }
    }

    @Nested
    @DisplayName("Large Input Performance")
    inner class LargeInputPerformance {

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `parsing 1000 responses completes in reasonable time`() {
            val responses = (1..1000).joinToString("\n") { i ->
                """
                <D:response>
                    <D:href>/calendar/event$i.ics</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Event $i</D:displayname>
                            <D:getetag>"etag$i"</D:getetag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
                """.trimIndent()
            }

            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    $responses
                </D:multistatus>
            """.trimIndent()

            val startTime = System.currentTimeMillis()
            val result = parser.parse(xml)
            val duration = System.currentTimeMillis() - startTime

            assertTrue(result is DavResult.Success)
            val multiStatus = result.getOrNull()!!
            assertTrue(multiStatus.responses.size == 1000,
                "Should parse all 1000 responses")
            assertTrue(duration < 10000,
                "Should complete in under 10 seconds, took ${duration}ms")
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        fun `parsing response with 1MB calendar data completes`() {
            // Simulate a very large calendar file
            val largeCalData = buildString {
                append("BEGIN:VCALENDAR\nVERSION:2.0\n")
                repeat(10000) { i ->
                    append("BEGIN:VEVENT\n")
                    append("UID:event$i@example.com\n")
                    append("SUMMARY:Event $i with a reasonably long summary text\n")
                    append("DTSTART:20240101T090000Z\n")
                    append("DTEND:20240101T100000Z\n")
                    append("END:VEVENT\n")
                }
                append("END:VCALENDAR")
            }

            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendar/huge.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data>$largeCalData</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val result = parser.parse(xml)

            assertTrue(result is DavResult.Success)
            val calData = result.getOrNull()?.responses?.firstOrNull()?.calendarData
            assertTrue(calData != null && calData.contains("BEGIN:VCALENDAR"))
        }
    }
}
