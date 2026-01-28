package org.onekash.icaldav.xml

import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.model.MultiStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertIs

/**
 * Regression tests for XML entity decoding in CalDAV responses.
 *
 * These tests verify that XML entities (&amp; &lt; &gt; &quot; &apos;) are
 * correctly decoded in all property values, not just etag.
 *
 * ## Bug Being Fixed
 *
 * MultiStatusParser.extractPropertyValue() does NOT decode XML entities,
 * while extractEtag() DOES decode them. This causes displayname and other
 * properties containing & < > etc. to display incorrectly.
 *
 * ## Expected Behavior After Migration
 *
 * All tests in this file should PASS after XmlPullParser migration.
 * XmlPullParser automatically decodes XML entities.
 */
@DisplayName("Entity Decoding Regression Tests")
class EntityDecodingRegressionTest {

    private val parser = MultiStatusParser()

    private fun parseSuccess(xml: String): MultiStatus {
        val result = parser.parse(xml)
        assertIs<DavResult.Success<MultiStatus>>(result)
        return result.value
    }

    // ============================================================================
    // Displayname Entity Decoding
    // ============================================================================

    @Nested
    @DisplayName("Displayname Entity Decoding")
    inner class DisplaynameEntityTests {

        @Test
        fun `displayname with ampersand should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/special/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Work &amp; Personal</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val displayName = multiStatus.responses[0].properties.displayName

            // After XmlPullParser migration, this should pass
            assertEquals("Work & Personal", displayName,
                "Ampersand entity &amp; should be decoded to &")
        }

        @Test
        fun `displayname with lt and gt should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/year/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Team &lt;2024&gt;</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val displayName = multiStatus.responses[0].properties.displayName

            assertEquals("Team <2024>", displayName,
                "Lt/gt entities &lt; &gt; should be decoded to < >")
        }

        @Test
        fun `displayname with all entity types should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/complex/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>&quot;Tom &amp; Jerry&apos;s&quot; &lt;Show&gt;</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val displayName = multiStatus.responses[0].properties.displayName

            assertEquals("\"Tom & Jerry's\" <Show>", displayName,
                "All XML entities should be decoded")
        }
    }

    // ============================================================================
    // Calendar Description Entity Decoding
    // ============================================================================

    @Nested
    @DisplayName("Calendar Description Entity Decoding")
    inner class CalendarDescriptionEntityTests {

        @Test
        fun `calendar-description with ampersand should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendars/user/main/</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-description>Events for work &amp; personal use</C:calendar-description>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val description = multiStatus.responses[0].properties.calendarDescription

            assertEquals("Events for work & personal use", description,
                "Calendar description entities should be decoded")
        }
    }

    // ============================================================================
    // Etag Entity Decoding (Should Already Work)
    // ============================================================================

    @Nested
    @DisplayName("Etag Entity Decoding (Baseline)")
    inner class EtagEntityTests {

        @Test
        fun `etag with quot entities should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>&quot;abc123def&quot;</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val etag = multiStatus.responses[0].etag

            // This should already pass - etag decoding works
            assertEquals("abc123def", etag,
                "Etag entities should be decoded (this already works)")
        }
    }

    // ============================================================================
    // CDATA Handling
    // ============================================================================

    @Nested
    @DisplayName("CDATA Handling")
    inner class CdataTests {

        @Test
        fun `calendar-data in CDATA should be extracted correctly`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendars/user/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR]]></C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val calendarData = multiStatus.responses[0].calendarData

            assertNotNull(calendarData, "Calendar data should be extracted from CDATA")
            assertTrue(calendarData.contains("BEGIN:VCALENDAR"), "Should contain VCALENDAR")
            assertTrue(calendarData.contains("SUMMARY:Test Event"), "Should contain event summary")
        }
    }

    // ============================================================================
    // Multiple Propstat Per Response
    // ============================================================================

    @Nested
    @DisplayName("Multiple Propstat Handling")
    inner class MultiplePropstatTests {

        @Test
        fun `response with 200 and 404 propstat should track both`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendars/user/personal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Personal Calendar</D:displayname>
                                <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                        <D:propstat>
                            <D:prop>
                                <D:calendar-color/>
                            </D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val response = multiStatus.responses[0]

            // 200 OK properties should be extracted
            assertEquals("Personal Calendar", response.properties.displayName)
            assertNotNull(response.properties.resourceType)

            // 404 property should be tracked as not found
            val colorStatus = response.properties.getStatus("calendar-color")
            assertNotNull(colorStatus, "Property status should be tracked for 404 properties")
            assertEquals(404, colorStatus.statusCode, "Calendar-color should have 404 status")
        }
    }

    // ============================================================================
    // URL-Encoded Href Handling
    // ============================================================================

    @Nested
    @DisplayName("URL-Encoded Href Handling")
    inner class UrlEncodedHrefTests {

        @Test
        fun `href with plus sign should preserve plus`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/event+test.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Event</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val href = multiStatus.responses[0].href

            // Plus should be preserved (not converted to space)
            assertEquals("/calendars/user/event+test.ics", href,
                "Plus sign in href should be preserved")
        }

        @Test
        fun `href with percent-encoded characters should be decoded`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/event%20with%20spaces.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Event</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val href = multiStatus.responses[0].href

            assertEquals("/calendars/user/event with spaces.ics", href,
                "Percent-encoded spaces should be decoded")
        }
    }

    // ============================================================================
    // Namespace Variations
    // ============================================================================

    @Nested
    @DisplayName("Namespace Variations")
    inner class NamespaceVariationTests {

        @Test
        fun `lowercase d prefix should work`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/calendars/user/</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:displayname>Test &amp; Calendar</d:displayname>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("Test & Calendar", multiStatus.responses[0].properties.displayName)
        }

        @Test
        fun `no namespace prefix should work`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <multistatus xmlns="DAV:">
                    <response>
                        <href>/calendars/user/</href>
                        <propstat>
                            <prop>
                                <displayname>Test &amp; Calendar</displayname>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("Test & Calendar", multiStatus.responses[0].properties.displayName)
        }
    }
}
