package org.onekash.icaldav.xml

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.model.PropertyStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for per-property status parsing in MultiStatusParser.
 *
 * WebDAV servers return per-property status in propstat elements, where
 * each propstat can have a different status (200 OK, 404 Not Found, etc.).
 */
@DisplayName("Per-Property Status Parsing Tests")
class PropstatStatusTest {

    private val parser = MultiStatusParser()

    @Nested
    @DisplayName("Single Propstat Parsing")
    inner class SinglePropstatTests {

        @Test
        fun `parses single propstat with 200 OK`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>My Calendar</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val response = multiStatus.responses.first()
            val props = response.properties

            assertEquals("My Calendar", props.displayName)
            assertEquals(200, props.getStatus("displayname")?.statusCode)
            assertTrue(props.getStatus("displayname")?.isSuccess == true)
            assertTrue(props.failedProperties.isEmpty())
        }

        @Test
        fun `parses single propstat with 404 Not Found`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname/>
                            </D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val response = multiStatus.responses.first()
            val props = response.properties

            assertNull(props.displayName)
            assertEquals(404, props.getStatus("displayname")?.statusCode)
            assertTrue(props.getStatus("displayname")?.isNotFound == true)
            assertTrue(props.notFoundProperties.contains("displayname"))
        }
    }

    @Nested
    @DisplayName("Multiple Propstat Parsing")
    inner class MultiplePropstatTests {

        @Test
        fun `parses multiple propstat elements (200 + 404)`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Work Calendar</D:displayname>
                                <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                        <D:propstat>
                            <D:prop>
                                <A:calendar-color/>
                                <C:calendar-description/>
                            </D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val response = multiStatus.responses.first()
            val props = response.properties

            // 200 OK properties
            assertEquals("Work Calendar", props.displayName)
            assertEquals(200, props.getStatus("displayname")?.statusCode)
            assertTrue(props.getStatus("resourcetype")?.isSuccess == true)

            // 404 Not Found properties
            assertNull(props.calendarColor)
            assertEquals(404, props.getStatus("calendar-color")?.statusCode)
            assertTrue(props.getStatus("calendar-color")?.isNotFound == true)

            assertEquals(404, props.getStatus("calendar-description")?.statusCode)
            assertTrue(props.getStatus("calendar-description")?.isNotFound == true)

            // Check failed properties aggregation
            assertEquals(2, props.failedProperties.size)
            assertTrue(props.failedProperties.containsKey("calendar-color"))
            assertTrue(props.failedProperties.containsKey("calendar-description"))
        }

        @Test
        fun `parses 403 Forbidden property`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname>Calendar</D:displayname>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                        <D:propstat>
                            <D:prop>
                                <CS:getctag/>
                            </D:prop>
                            <D:status>HTTP/1.1 403 Forbidden</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val response = multiStatus.responses.first()
            val props = response.properties

            assertEquals(403, props.getStatus("getctag")?.statusCode)
            assertTrue(props.getStatus("getctag")?.isForbidden == true)
            assertFalse(props.getStatus("getctag")?.isNotFound == true)
        }
    }

    @Nested
    @DisplayName("Namespace Variations")
    inner class NamespaceTests {

        @Test
        fun `handles mixed namespace prefixes`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <d:href>/calendar/</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:displayname>Calendar</d:displayname>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                        <d:propstat>
                            <d:prop>
                                <cal:calendar-description/>
                            </d:prop>
                            <d:status>HTTP/1.1 404 Not Found</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val props = multiStatus.responses.first().properties

            assertEquals(200, props.getStatus("displayname")?.statusCode)
            assertEquals(404, props.getStatus("calendar-description")?.statusCode)
        }

        @Test
        fun `handles self-closing property tags`() {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:">
                    <D:response>
                        <D:href>/calendar/</D:href>
                        <D:propstat>
                            <D:prop>
                                <D:displayname/>
                                <D:getetag />
                            </D:prop>
                            <D:status>HTTP/1.1 404 Not Found</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val props = multiStatus.responses.first().properties

            assertEquals(404, props.getStatus("displayname")?.statusCode)
            assertEquals(404, props.getStatus("getetag")?.statusCode)
        }
    }

    @Nested
    @DisplayName("Backward Compatibility")
    inner class BackwardCompatTests {

        @Test
        fun `empty propertyStatus when using legacy parsing`() {
            // DavProperties created without status info (legacy)
            val props = org.onekash.icaldav.model.DavProperties.from(
                mapOf("displayname" to "Test")
            )

            // Should not fail - propertyStatus defaults to empty
            assertTrue(props.propertyStatus.isEmpty())
            assertNull(props.getStatus("displayname"))
            // hasProperty returns true when no status tracking
            assertTrue(props.hasProperty("displayname"))
        }

        @Test
        fun `standard single propstat response still works`() {
            // Most common case: single propstat with all 200 OK
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response>
                        <D:href>/principals/user/</D:href>
                        <D:propstat>
                            <D:prop>
                                <C:calendar-home-set>
                                    <D:href>/calendars/user/</D:href>
                                </C:calendar-home-set>
                            </D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status>
                        </D:propstat>
                    </D:response>
                </D:multistatus>"""

            val result = parser.parse(xml)
            assertTrue(result.isSuccess)
            val multiStatus = result.getOrNull()!!

            val props = multiStatus.responses.first().properties
            assertEquals("/calendars/user/", props.calendarHomeSet)
            assertTrue(props.getStatus("calendar-home-set")?.isSuccess == true)
        }
    }

    @Nested
    @DisplayName("PropertyStatus Model")
    inner class PropertyStatusModelTests {

        @Test
        fun `PropertyStatus constants`() {
            assertEquals(200, PropertyStatus.OK.statusCode)
            assertEquals("OK", PropertyStatus.OK.statusText)
            assertTrue(PropertyStatus.OK.isSuccess)

            assertEquals(404, PropertyStatus.NOT_FOUND.statusCode)
            assertTrue(PropertyStatus.NOT_FOUND.isNotFound)

            assertEquals(403, PropertyStatus.FORBIDDEN.statusCode)
            assertTrue(PropertyStatus.FORBIDDEN.isForbidden)
        }

        @Test
        fun `PropertyStatus fromStatusLine parsing`() {
            val status = PropertyStatus.fromStatusLine("HTTP/1.1 207 Multi-Status")
            assertEquals(207, status.statusCode)
            assertEquals("Multi-Status", status.statusText)
            assertTrue(status.isSuccess)
        }

        @Test
        fun `PropertyStatus success range`() {
            assertTrue(PropertyStatus(200, "OK").isSuccess)
            assertTrue(PropertyStatus(201, "Created").isSuccess)
            assertTrue(PropertyStatus(204, "No Content").isSuccess)
            assertTrue(PropertyStatus(207, "Multi-Status").isSuccess)
            assertFalse(PropertyStatus(404, "Not Found").isSuccess)
            assertFalse(PropertyStatus(500, "Internal Server Error").isSuccess)
        }
    }

    @Nested
    @DisplayName("DavProperties Helpers")
    inner class DavPropertiesHelpersTests {

        @Test
        fun `failedProperties returns only non-200`() {
            val props = org.onekash.icaldav.model.DavProperties(
                properties = mapOf("displayname" to "Test"),
                propertyStatus = mapOf(
                    "displayname" to PropertyStatus.OK,
                    "calendar-color" to PropertyStatus.NOT_FOUND,
                    "getctag" to PropertyStatus.FORBIDDEN
                )
            )

            assertEquals(2, props.failedProperties.size)
            assertTrue(props.failedProperties.containsKey("calendar-color"))
            assertTrue(props.failedProperties.containsKey("getctag"))
            assertFalse(props.failedProperties.containsKey("displayname"))
        }

        @Test
        fun `notFoundProperties returns only 404`() {
            val props = org.onekash.icaldav.model.DavProperties(
                properties = mapOf("displayname" to "Test"),
                propertyStatus = mapOf(
                    "displayname" to PropertyStatus.OK,
                    "calendar-color" to PropertyStatus.NOT_FOUND,
                    "getctag" to PropertyStatus.FORBIDDEN
                )
            )

            assertEquals(1, props.notFoundProperties.size)
            assertTrue(props.notFoundProperties.contains("calendar-color"))
            assertFalse(props.notFoundProperties.contains("getctag"))
        }

        @Test
        fun `hasProperty returns correct value based on status`() {
            val props = org.onekash.icaldav.model.DavProperties(
                properties = mapOf("displayname" to "Test"),
                propertyStatus = mapOf(
                    "displayname" to PropertyStatus.OK,
                    "calendar-color" to PropertyStatus.NOT_FOUND
                )
            )

            assertTrue(props.hasProperty("displayname"))
            assertFalse(props.hasProperty("calendar-color"))
            // No status tracking - returns true (backward compat)
            assertTrue(props.hasProperty("unknown-prop"))
        }
    }
}
