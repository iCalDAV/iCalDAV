package org.onekash.icaldav.xml

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.onekash.icaldav.model.Ace
import org.onekash.icaldav.model.Principal
import org.onekash.icaldav.model.Privilege

/**
 * Tests for RequestBuilder XML generation.
 *
 * Verifies that all request builders generate valid, properly-namespaced XML
 * that conforms to WebDAV/CalDAV specifications.
 */
class RequestBuilderTest {

    // ==================== PROPFIND Tests ====================

    @Nested
    inner class PropfindTests {

        @Test
        fun `propfind generates valid XML with requested properties`() {
            val xml = RequestBuilder.propfind("displayname", "getetag")

            assertTrue(xml.contains("<?xml version=\"1.0\""))
            assertTrue(xml.contains("<D:propfind"))
            assertTrue(xml.contains("<D:displayname/>"))
            assertTrue(xml.contains("<D:getetag/>"))
            assertTrue(xml.contains("</D:propfind>"))
        }

        @Test
        fun `propfindPrincipal requests current-user-principal`() {
            val xml = RequestBuilder.propfindPrincipal()

            assertTrue(xml.contains("<D:current-user-principal/>"))
        }

        @Test
        fun `propfindCalendarHome requests calendar-home-set`() {
            val xml = RequestBuilder.propfindCalendarHome()

            assertTrue(xml.contains("<C:calendar-home-set/>"))
            assertTrue(xml.contains("xmlns:C=\"urn:ietf:params:xml:ns:caldav\""))
        }

        @Test
        fun `propfindCalendars requests all calendar properties`() {
            val xml = RequestBuilder.propfindCalendars()

            assertTrue(xml.contains("<D:displayname/>"))
            assertTrue(xml.contains("<D:resourcetype/>"))
            assertTrue(xml.contains("<D:getetag/>"))
            assertTrue(xml.contains("<CS:getctag/>"))
            assertTrue(xml.contains("<D:sync-token/>"))
            assertTrue(xml.contains("<C:supported-calendar-component-set/>"))
            assertTrue(xml.contains("<A:calendar-color/>"))
            assertTrue(xml.contains("<C:calendar-description/>"))
        }

        @Test
        fun `propfindCtag requests ctag and sync-token`() {
            val xml = RequestBuilder.propfindCtag()

            assertTrue(xml.contains("<CS:getctag/>"))
            assertTrue(xml.contains("<D:sync-token/>"))
            assertTrue(xml.contains("xmlns:CS=\"http://calendarserver.org/ns/\""))
        }
    }

    // ==================== Calendar Query Tests ====================

    @Nested
    inner class CalendarQueryTests {

        @Test
        fun `calendarQuery generates valid VEVENT filter`() {
            val xml = RequestBuilder.calendarQuery()

            assertTrue(xml.contains("<c:calendar-query"))
            assertTrue(xml.contains("<c:comp-filter name=\"VCALENDAR\">"))
            assertTrue(xml.contains("<c:comp-filter name=\"VEVENT\">"))
            assertTrue(xml.contains("<d:getetag/>"))
            assertTrue(xml.contains("<c:calendar-data/>"))
        }

        @Test
        fun `calendarQuery with time range includes time-range element`() {
            val xml = RequestBuilder.calendarQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z"
            )

            assertTrue(xml.contains("<c:time-range"))
            assertTrue(xml.contains("start=\"20231201T000000Z\""))
            assertTrue(xml.contains("end=\"20231231T235959Z\""))
        }

        @Test
        fun `calendarQuery without time range omits time-range element`() {
            val xml = RequestBuilder.calendarQuery()

            assertFalse(xml.contains("<c:time-range"))
        }

        @Test
        fun `calendarQueryEtagOnly requests only etag`() {
            val xml = RequestBuilder.calendarQueryEtagOnly(
                start = "20231201T000000Z",
                end = "20231231T235959Z"
            )

            assertTrue(xml.contains("<d:getetag/>"))
            assertFalse(xml.contains("<c:calendar-data/>"))
        }
    }

    // ==================== Todo Query Tests ====================

    @Nested
    inner class TodoQueryTests {

        @Test
        fun `todoQuery generates valid VTODO filter`() {
            val xml = RequestBuilder.todoQuery()

            assertTrue(xml.contains("<c:comp-filter name=\"VTODO\">"))
        }

        @Test
        fun `todoQuery with time range includes time-range element`() {
            val xml = RequestBuilder.todoQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z"
            )

            assertTrue(xml.contains("<c:time-range"))
        }
    }

    // ==================== Journal Query Tests ====================

    @Nested
    inner class JournalQueryTests {

        @Test
        fun `journalQuery generates valid VJOURNAL filter`() {
            val xml = RequestBuilder.journalQuery()

            assertTrue(xml.contains("<c:comp-filter name=\"VJOURNAL\">"))
        }
    }

    // ==================== Calendar Multiget Tests ====================

    @Nested
    inner class CalendarMultigetTests {

        @Test
        fun `calendarMultiget includes all hrefs`() {
            val hrefs = listOf(
                "/calendars/user/default/event1.ics",
                "/calendars/user/default/event2.ics",
                "/calendars/user/default/event3.ics"
            )

            val xml = RequestBuilder.calendarMultiget(hrefs)

            assertTrue(xml.contains("<c:calendar-multiget"))
            hrefs.forEach { href ->
                assertTrue(xml.contains("<d:href>$href</d:href>"))
            }
            assertTrue(xml.contains("<d:getetag/>"))
            assertTrue(xml.contains("<c:calendar-data/>"))
        }

        @Test
        fun `calendarMultiget handles empty list`() {
            val xml = RequestBuilder.calendarMultiget(emptyList())

            assertTrue(xml.contains("<c:calendar-multiget"))
            assertFalse(xml.contains("<d:href>"))
        }
    }

    // ==================== Sync Collection Tests ====================

    @Nested
    inner class SyncCollectionTests {

        @Test
        fun `syncCollection with empty token generates initial sync request`() {
            val xml = RequestBuilder.syncCollection("")

            assertTrue(xml.contains("<D:sync-collection"))
            assertTrue(xml.contains("<D:sync-token></D:sync-token>"))
            assertTrue(xml.contains("<D:sync-level>1</D:sync-level>"))
        }

        @Test
        fun `syncCollection with token generates delta sync request`() {
            val token = "http://server.com/sync/token123"
            val xml = RequestBuilder.syncCollection(token)

            assertTrue(xml.contains("<D:sync-token>$token</D:sync-token>"))
        }
    }

    // ==================== Free/Busy Query Tests ====================

    @Nested
    inner class FreeBusyQueryTests {

        @Test
        fun `freeBusyQuery generates valid XML`() {
            val xml = RequestBuilder.freeBusyQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z",
                organizer = "organizer@example.com",
                attendees = listOf("attendee1@example.com", "attendee2@example.com")
            )

            assertTrue(xml.contains("<C:free-busy-query"))
            assertTrue(xml.contains("<C:time-range start=\"20231201T000000Z\" end=\"20231231T235959Z\"/>"))
            assertTrue(xml.contains("<C:organizer>mailto:organizer@example.com</C:organizer>"))
            assertTrue(xml.contains("<C:attendee>mailto:attendee1@example.com</C:attendee>"))
            assertTrue(xml.contains("<C:attendee>mailto:attendee2@example.com</C:attendee>"))
        }

        @Test
        fun `freeBusyQuery with single attendee`() {
            val xml = RequestBuilder.freeBusyQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z",
                organizer = "organizer@example.com",
                attendees = listOf("solo@example.com")
            )

            assertTrue(xml.contains("<C:attendee>mailto:solo@example.com</C:attendee>"))
        }
    }

    // ==================== MKCALENDAR Tests ====================

    @Nested
    inner class MkcalendarTests {

        @Test
        fun `mkcalendar generates valid XML with display name`() {
            val xml = RequestBuilder.mkcalendar(displayName = "My Calendar")

            assertTrue(xml.contains("<C:mkcalendar"))
            assertTrue(xml.contains("<D:displayname>My Calendar</D:displayname>"))
        }

        @Test
        fun `mkcalendar with description includes description`() {
            val xml = RequestBuilder.mkcalendar(
                displayName = "My Calendar",
                description = "A test calendar"
            )

            assertTrue(xml.contains("<C:calendar-description>A test calendar</C:calendar-description>"))
        }

        @Test
        fun `mkcalendar with color includes color property`() {
            val xml = RequestBuilder.mkcalendar(
                displayName = "My Calendar",
                color = "#FF5733"
            )

            assertTrue(xml.contains("<A:calendar-color"))
            assertTrue(xml.contains("#FF5733"))
        }

        @Test
        fun `mkcalendar escapes XML special characters`() {
            val xml = RequestBuilder.mkcalendar(
                displayName = "Cal <&> \"Test\"",
                description = "A 'quoted' description"
            )

            assertTrue(xml.contains("&lt;"))
            assertTrue(xml.contains("&gt;"))
            assertTrue(xml.contains("&amp;"))
            assertTrue(xml.contains("&quot;"))
            assertTrue(xml.contains("&apos;"))
        }
    }

    // ==================== Scheduling URL Tests ====================

    @Nested
    inner class SchedulingUrlTests {

        @Test
        fun `propfindSchedulingUrls requests inbox and outbox`() {
            val xml = RequestBuilder.propfindSchedulingUrls()

            assertTrue(xml.contains("<C:schedule-inbox-URL/>"))
            assertTrue(xml.contains("<C:schedule-outbox-URL/>"))
        }
    }

    // ==================== ACL Tests ====================

    @Nested
    inner class AclTests {

        @Test
        fun `propfindAcl requests ACL properties`() {
            val xml = RequestBuilder.propfindAcl()

            assertTrue(xml.contains("<D:acl/>"))
            assertTrue(xml.contains("<D:current-user-privilege-set/>"))
        }

        @Test
        fun `propfindCurrentUserPrivilegeSet requests only privileges`() {
            val xml = RequestBuilder.propfindCurrentUserPrivilegeSet()

            assertTrue(xml.contains("<D:current-user-privilege-set/>"))
            assertFalse(xml.contains("<D:acl/>"))
        }

        @Test
        fun `propfindPrincipalCollectionSet requests principal collection`() {
            val xml = RequestBuilder.propfindPrincipalCollectionSet()

            assertTrue(xml.contains("<D:principal-collection-set/>"))
        }

        @Test
        fun `acl generates valid ACE elements`() {
            val aces = listOf(
                Ace(
                    principal = Principal.Href("/principals/users/john"),
                    grant = setOf(Privilege.READ, Privilege.WRITE)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("<D:acl"))
            assertTrue(xml.contains("<D:ace>"))
            assertTrue(xml.contains("<D:href>/principals/users/john</D:href>"))
            assertTrue(xml.contains("<D:grant>"))
            assertTrue(xml.contains("<D:privilege><D:read/></D:privilege>"))
            assertTrue(xml.contains("<D:privilege><D:write/></D:privilege>"))
        }

        @Test
        fun `acl handles different principal types`() {
            val aces = listOf(
                Ace(principal = Principal.All, grant = setOf(Privilege.READ)),
                Ace(principal = Principal.Authenticated, grant = setOf(Privilege.READ)),
                Ace(principal = Principal.Unauthenticated, grant = emptySet(), deny = setOf(Privilege.READ)),
                Ace(principal = Principal.Self, grant = setOf(Privilege.ALL)),
                Ace(principal = Principal.Property("owner"), grant = setOf(Privilege.READ))
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("<D:all/>"))
            assertTrue(xml.contains("<D:authenticated/>"))
            assertTrue(xml.contains("<D:unauthenticated/>"))
            assertTrue(xml.contains("<D:self/>"))
            assertTrue(xml.contains("<D:property><D:owner/></D:property>"))
        }

        @Test
        fun `acl generates deny elements`() {
            val aces = listOf(
                Ace(
                    principal = Principal.All,
                    grant = emptySet(),
                    deny = setOf(Privilege.WRITE)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("<D:deny>"))
            assertTrue(xml.contains("<D:privilege><D:write/></D:privilege>"))
        }

        @Test
        fun `acl handles empty ACE list`() {
            val xml = RequestBuilder.acl(emptyList())

            assertTrue(xml.contains("<D:acl"))
            assertTrue(xml.contains("</D:acl>"))
        }
    }

    // ==================== Namespace Tests ====================

    @Nested
    inner class NamespaceTests {

        @Test
        fun `calendarQuery uses lowercase prefixes for iCloud compatibility`() {
            val xml = RequestBuilder.calendarQuery()

            // iCloud requires lowercase namespace prefixes
            assertTrue(xml.contains("xmlns:d=\"DAV:\""))
            assertTrue(xml.contains("xmlns:c=\"urn:ietf:params:xml:ns:caldav\""))
            assertTrue(xml.contains("<c:calendar-query"))
            assertTrue(xml.contains("<d:prop>"))
        }

        @Test
        fun `propfindCalendars includes Apple namespace for color`() {
            val xml = RequestBuilder.propfindCalendars()

            assertTrue(xml.contains("xmlns:A=\"http://apple.com/ns/ical/\""))
            assertTrue(xml.contains("<A:calendar-color/>"))
        }

        @Test
        fun `propfindCtag includes CalendarServer namespace`() {
            val xml = RequestBuilder.propfindCtag()

            assertTrue(xml.contains("xmlns:CS=\"http://calendarserver.org/ns/\""))
            assertTrue(xml.contains("<CS:getctag/>"))
        }
    }
}