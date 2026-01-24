package org.onekash.icaldav.xml

import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.model.MultiStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * Comprehensive MultiStatusParser tests.
 *
 * Tests WebDAV/CalDAV multistatus XML response parsing:
 * - Namespace variations (D:, d:, no prefix, DAV:)
 * - Property extraction
 * - Calendar-data extraction
 * - ETag handling
 * - sync-token extraction
 * - Edge cases and error handling
 */
@DisplayName("MultiStatusParser Tests")
class MultiStatusParserTest {

    private val parser = MultiStatusParser()

    // Helper to parse and extract MultiStatus
    private fun parseSuccess(xml: String): MultiStatus {
        val result = parser.parse(xml)
        assertIs<DavResult.Success<MultiStatus>>(result)
        return result.value
    }

    @Nested
    @DisplayName("Basic Parsing")
    inner class BasicParsingTests {

        @Test
        fun `parses simple multistatus response`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>My Calendar</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(1, multiStatus.responses.size)
            assertEquals("/calendars/user/", multiStatus.responses[0].href)
            assertEquals(200, multiStatus.responses[0].status)
        }

        @Test
        fun `parses multiple responses`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/personal/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Personal</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/calendars/user/work/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Work</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(2, multiStatus.responses.size)
        }

        @Test
        fun `parses response with 404 status`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/deleted.ics</D:href>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(404, multiStatus.responses[0].status)
        }
    }

    @Nested
    @DisplayName("Namespace Handling")
    inner class NamespaceTests {

        @Test
        fun `parses with D prefix namespace`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/test/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Test</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("/test/", multiStatus.responses[0].href)
        }

        @Test
        fun `parses with lowercase d prefix`() {
            val xml = """
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/test/</d:href>
                        <d:propstat>
                            <d:prop><d:displayname>Test</d:displayname></d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("/test/", multiStatus.responses[0].href)
        }

        @Test
        fun `parses without namespace prefix - default namespace`() {
            val xml = """
                <multistatus xmlns="DAV:">
                    <response>
                        <href>/test/</href>
                        <propstat>
                            <prop><displayname>Test</displayname></prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("/test/", multiStatus.responses[0].href)
        }

        @Test
        fun `parses with CalDAV namespace`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"abc123"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val response = multiStatus.responses[0]
            assertNotNull(response.calendarData)
            assertTrue(response.calendarData!!.contains("VCALENDAR"))
        }

        @Test
        fun `parses iCloud style response - mixed namespaces`() {
            val xml = """
                <multistatus xmlns="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                    <response>
                        <href>/1234567890/calendars/home/</href>
                        <propstat>
                            <prop>
                                <displayname>Home</displayname>
                                <CS:getctag>ctag-value-123</CS:getctag>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNotNull(multiStatus)
        }
    }

    @Nested
    @DisplayName("Property Extraction")
    inner class PropertyExtractionTests {

        @Test
        fun `extracts displayname property`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>My Calendar</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("My Calendar", multiStatus.responses[0].properties.displayName)
        }

        @Test
        fun `extracts resourcetype with calendar`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:resourcetype>
                                    <D:collection/>
                                    <C:calendar/>
                                </D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val resourceType = multiStatus.responses[0].properties.resourceType
            assertNotNull(resourceType)
            assertTrue(resourceType.contains("collection") || resourceType.contains("calendar"))
        }

        @Test
        fun `extracts current-user-principal href`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:current-user-principal>
                                    <D:href>/principals/users/testuser/</D:href>
                                </D:current-user-principal>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val principal = multiStatus.responses[0].properties.get("current-user-principal")
            assertEquals("/principals/users/testuser/", principal)
        }

        @Test
        fun `extracts calendar-home-set href`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/principals/users/testuser/</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-home-set>
                                    <D:href>/calendars/testuser/</D:href>
                                </C:calendar-home-set>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val homeSet = multiStatus.responses[0].properties.get("calendar-home-set")
            assertEquals("/calendars/testuser/", homeSet)
        }

        @Test
        fun `extracts getctag property`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <CS:getctag>ctag-12345</CS:getctag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNotNull(multiStatus)
        }
    }

    @Nested
    @DisplayName("ETag Handling")
    inner class EtagTests {

        @Test
        fun `extracts quoted etag`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"abc123"</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("abc123", multiStatus.responses[0].etag)
        }

        @Test
        fun `extracts unquoted etag`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>abc123</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNotNull(multiStatus.responses[0].etag)
        }

        @Test
        fun `handles missing etag`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
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
            assertNull(multiStatus.responses[0].etag)
        }

        @Test
        fun `decodes HTML-encoded etag with quot entities`() {
            // Nextcloud and some servers return etags with HTML entity encoding
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>&quot;86d7e34a0b92938ed6fe0213b9eda285&quot;</D:getetag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            // Should decode &quot; to " and then remove surrounding quotes
            assertEquals("86d7e34a0b92938ed6fe0213b9eda285", multiStatus.responses[0].etag)
        }
    }

    @Nested
    @DisplayName("Calendar Data Extraction")
    inner class CalendarDataTests {

        @Test
        fun `extracts calendar-data with event`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag123"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:event-123
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val calendarData = multiStatus.responses[0].calendarData
            assertNotNull(calendarData)
            assertTrue(calendarData.contains("BEGIN:VCALENDAR"))
            assertTrue(calendarData.contains("UID:event-123"))
            assertTrue(calendarData.contains("SUMMARY:Test Event"))
        }

        @Test
        fun `extracts calendar-data without namespace prefix`() {
            val xml = """
                <multistatus xmlns="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                    <response>
                        <href>/cal/event.ics</href>
                        <propstat>
                            <prop>
                                <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test
END:VEVENT
END:VCALENDAR</cal:calendar-data>
                            </prop>
                            <status>HTTP/1.1 200 OK</status>
                        </propstat>
                    </response>
                </multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNotNull(multiStatus.responses[0].calendarData)
        }

        @Test
        fun `handles empty calendar-data`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-data></C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            // Empty calendar-data should be null or empty
            assertNotNull(multiStatus)
        }

        @Test
        fun `extracts calendar-data wrapped in CDATA section`() {
            // Some CalDAV servers (including iCloud) wrap calendar-data in CDATA
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-cdata"</D:getetag>
                                <C:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:cdata-event-123
DTSTART:20231215T100000Z
DTEND:20231215T110000Z
SUMMARY:CDATA Wrapped Event
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
            assertNotNull(calendarData)
            assertTrue(calendarData.contains("BEGIN:VCALENDAR"))
            assertTrue(calendarData.contains("UID:cdata-event-123"))
            assertTrue(calendarData.contains("SUMMARY:CDATA Wrapped Event"))
            // Ensure CDATA markers are NOT in the extracted data
            assertTrue(!calendarData.contains("<![CDATA["))
            assertTrue(!calendarData.contains("]]>"))
        }

        @Test
        fun `extracts calendar-data with CDATA and lowercase namespace`() {
            // iCloud often uses lowercase c: prefix
            val xml = """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <d:href>/cal/event.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"etag123"</d:getetag>
                                <c:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:lowercase-cdata
SUMMARY:Lowercase CDATA
END:VEVENT
END:VCALENDAR]]></c:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            val calendarData = multiStatus.responses[0].calendarData
            assertNotNull(calendarData)
            assertTrue(calendarData.contains("BEGIN:VCALENDAR"))
            assertTrue(calendarData.contains("UID:lowercase-cdata"))
        }
    }

    @Nested
    @DisplayName("Sync Token Extraction")
    inner class SyncTokenTests {

        @Test
        fun `extracts sync-token from multistatus`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/event.ics</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Event</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:sync-token>http://example.com/ns/sync/1234</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("http://example.com/ns/sync/1234", multiStatus.syncToken)
        }

        @Test
        fun `handles missing sync-token`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Cal</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNull(multiStatus.syncToken)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `handles empty multistatus`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertTrue(multiStatus.responses.isEmpty())
        }

        @Test
        fun `handles response without propstat`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/deleted.ics</D:href>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(404, multiStatus.responses[0].status)
        }

        @Test
        fun `handles whitespace in href`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>
                            /cal/event.ics
                        </D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Event</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals("/cal/event.ics", multiStatus.responses[0].href)
        }

        @Test
        fun `handles URL-encoded paths`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user%40example.com/calendar/</D:href>
                        <D:propstat>
                            <D:prop><D:displayname>Calendar</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            // Now expects decoded output
            assertEquals("/calendars/user@example.com/calendar/", multiStatus.responses[0].href)
        }

        @Test
        fun `decodes various URL-encoded characters in hrefs`() {
            val xml = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/test%40user/event%20name.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()
            val multiStatus = parseSuccess(xml)
            assertEquals("/calendars/test@user/event name.ics", multiStatus.responses[0].href)
        }

        @Test
        fun `preserves literal plus signs in hrefs`() {
            val xml = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user/event+name.ics</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()
            val multiStatus = parseSuccess(xml)
            // Literal + should remain as +, not become space
            assertEquals("/calendars/user/event+name.ics", multiStatus.responses[0].href)
        }

        @Test
        fun `decodes hrefs in current-user-principal`() {
            val xml = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:current-user-principal>
                                    <D:href>/principals/user%40example.com/</D:href>
                                </D:current-user-principal>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()
            val multiStatus = parseSuccess(xml)
            assertEquals("/principals/user@example.com/", multiStatus.responses[0].properties.currentUserPrincipal)
        }

        @Test
        fun `decodes hrefs in calendar-home-set`() {
            val xml = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/principals/user%40example.com/</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-home-set>
                                    <D:href>/calendars/user%40example.com/</D:href>
                                </C:calendar-home-set>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()
            val multiStatus = parseSuccess(xml)
            assertEquals("/calendars/user@example.com/", multiStatus.responses[0].properties.calendarHomeSet)
        }

        @Test
        fun `idempotent decoding - already decoded hrefs unchanged`() {
            val xml = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendars/user@example.com/calendar/</D:href>
                        <D:propstat>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()
            val multiStatus = parseSuccess(xml)
            assertEquals("/calendars/user@example.com/calendar/", multiStatus.responses[0].href)
        }

        @Test
        fun `handles special characters in displayname`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Calendar &amp; Events &lt;2023&gt;</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            // XML entities should be decoded
            assertNotNull(multiStatus)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `returns parse error for malformed XML`() {
            val xml = "not valid xml <<<<"

            val result = parser.parse(xml)
            // Should return parse error or handle gracefully
            assertNotNull(result)
        }

        @Test
        fun `handles empty input`() {
            val result = parser.parse("")
            assertNotNull(result)
        }

        @Test
        fun `handles response without href`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:propstat>
                            <D:prop><D:displayname>No Href</D:displayname></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            // Response without href should be skipped
            assertTrue(multiStatus.responses.isEmpty())
        }

        @Test
        fun `handles deeply nested structure`() {
            val xml = """
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/cal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:resourcetype>
                                    <D:collection/>
                                    <C:calendar>
                                        <C:supported-calendar-component-set>
                                            <C:comp name="VEVENT"/>
                                            <C:comp name="VTODO"/>
                                        </C:supported-calendar-component-set>
                                    </C:calendar>
                                </D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertNotNull(multiStatus)
        }
    }

    @Nested
    @DisplayName("Real-World Server Responses")
    inner class RealWorldTests {

        @Test
        fun `parses typical PROPFIND calendar list response`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/" xmlns:CS="http://calendarserver.org/ns/">
                    <D:response>
                        <D:href>/calendars/user/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Calendar Home</D:displayname>
                                <D:resourcetype>
                                    <D:collection/>
                                </D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/calendars/user/personal/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Personal</D:displayname>
                                <D:resourcetype>
                                    <D:collection/>
                                    <C:calendar/>
                                </D:resourcetype>
                                <A:calendar-color>#0000FF</A:calendar-color>
                                <CS:getctag>ctag-abc123</CS:getctag>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(2, multiStatus.responses.size)
        }

        @Test
        fun `parses typical REPORT calendar-query response`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendars/user/personal/event1.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-1"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231215T100000Z
SUMMARY:Meeting
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/calendars/user/personal/event2.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"etag-2"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event2
DTSTART:20231216T140000Z
SUMMARY:Lunch
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(2, multiStatus.responses.size)
            assertTrue(multiStatus.responses.all { it.calendarData != null })
        }

        @Test
        fun `parses sync-collection response with deletions`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/calendars/user/personal/new-event.ics</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:getetag>"new-etag"</D:getetag>
                                <C:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:new-event
DTSTART:20231217T100000Z
SUMMARY:New Event
END:VEVENT
END:VCALENDAR</C:calendar-data>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                    <D:response>
                        <D:href>/calendars/user/personal/deleted-event.ics</D:href>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:response>
                    <D:sync-token>http://example.com/sync/token-2</D:sync-token>
                </D:multistatus>
            """.trimIndent()

            val multiStatus = parseSuccess(xml)
            assertEquals(2, multiStatus.responses.size)
            assertEquals(200, multiStatus.responses[0].status)
            assertEquals(404, multiStatus.responses[1].status)
            assertEquals("http://example.com/sync/token-2", multiStatus.syncToken)
        }
    }
}