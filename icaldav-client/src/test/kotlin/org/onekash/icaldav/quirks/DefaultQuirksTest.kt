package org.onekash.icaldav.quirks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for DefaultQuirks - generic RFC-compliant CalDAV server behaviors.
 *
 * Tests cover standard XML namespace formats (d:, D:) for:
 * - Nextcloud, Radicale, Baïkal, and other compliant servers
 */
class DefaultQuirksTest {

    private val quirks = DefaultQuirks(
        providerId = "generic",
        displayName = "Test Server",
        baseUrl = "https://caldav.example.com"
    )

    // ========== Provider Properties ==========

    @Test
    fun `providerId matches constructor value`() {
        assertEquals("generic", quirks.providerId)
    }

    @Test
    fun `displayName matches constructor value`() {
        assertEquals("Test Server", quirks.displayName)
    }

    @Test
    fun `requiresAppSpecificPassword is false for generic servers`() {
        assertFalse(quirks.requiresAppSpecificPassword)
    }

    @Test
    fun `baseUrl matches constructor value`() {
        assertEquals("https://caldav.example.com", quirks.baseUrl)
    }

    // ========== Principal URL Extraction ==========

    @Test
    fun `extractPrincipalUrl with d prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:current-user-principal>
                        <d:href>/principals/users/john/</d:href>
                    </d:current-user-principal>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/principals/users/john/", result)
    }

    @Test
    fun `extractPrincipalUrl with D prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:current-user-principal>
                        <D:href>/principals/jane/</D:href>
                    </D:current-user-principal>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/principals/jane/", result)
    }

    @Test
    fun `extractPrincipalUrl with no prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
                <response>
                    <current-user-principal>
                        <href>/user/principal/</href>
                    </current-user-principal>
                </response>
            </multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/user/principal/", result)
    }

    @Test
    fun `extractPrincipalUrl returns null when not present`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/other/</d:href>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertNull(result)
    }

    // ========== Calendar Home URL Extraction ==========

    @Test
    fun `extractCalendarHomeUrl with c and d prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <c:calendar-home-set>
                        <d:href>/calendars/john/</d:href>
                    </c:calendar-home-set>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("/calendars/john/", result)
    }

    @Test
    fun `extractCalendarHomeUrl with C and D prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <C:calendar-home-set>
                        <D:href>/calendars/jane/</D:href>
                    </C:calendar-home-set>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("/calendars/jane/", result)
    }

    // ========== Calendar Extraction ==========

    @Test
    fun `extractCalendars parses displayname and color`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:ic="http://apple.com/ns/ical/">
                <d:response>
                    <d:href>/calendars/john/personal/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype>
                                <d:collection/>
                                <c:calendar/>
                            </d:resourcetype>
                            <d:displayname>Personal Calendar</d:displayname>
                            <ic:calendar-color>#3366FFFF</ic:calendar-color>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.example.com")

        assertEquals(1, calendars.size)
        assertEquals("/calendars/john/personal/", calendars[0].href)
        assertEquals("Personal Calendar", calendars[0].displayName)
        assertEquals("#3366FFFF", calendars[0].color)
        assertFalse(calendars[0].isReadOnly)
    }

    @Test
    fun `extractCalendars skips inbox and outbox`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/inbox/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>Inbox</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/calendars/john/outbox/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>Outbox</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/calendars/john/work/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>Work</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.example.com")

        assertEquals(1, calendars.size)
        assertEquals("Work", calendars[0].displayName)
    }

    @Test
    fun `extractCalendars skips freebusy`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/freebusy/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>FreeBusy</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.example.com")

        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars skips todo lists`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/tasks/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>My Todo List</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.example.com")

        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars extracts ctag`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:href>/calendars/john/personal/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>Personal</d:displayname>
                            <cs:getctag>ctag-abc123</cs:getctag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.example.com")

        assertEquals(1, calendars.size)
        assertEquals("ctag-abc123", calendars[0].ctag)
    }

    // ========== iCal Data Extraction ==========

    @Test
    fun `extractICalData handles standard format`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/personal/event.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-standard"</d:getetag>
                            <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:event-123@example.com
DTSTART:20240201T090000Z
DTEND:20240201T100000Z
SUMMARY:Standard Event
END:VEVENT
END:VCALENDAR</c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertEquals("/calendars/john/personal/event.ics", events[0].href)
        assertEquals("etag-standard", events[0].etag)
        assertTrue(events[0].icalData.contains("Standard Event"))
    }

    @Test
    fun `extractICalData handles CDATA format`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/personal/cdata-event.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-cdata"</d:getetag>
                            <c:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:cdata-123@example.com
SUMMARY:CDATA Event
END:VEVENT
END:VCALENDAR]]></c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("CDATA Event"))
    }

    @Test
    fun `extractICalData decodes XML entities`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/personal/entities.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-ent"</d:getetag>
                            <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:entities@example.com
SUMMARY:Q&amp;A Session &lt;Important&gt;
END:VEVENT
END:VCALENDAR</c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("Q&A Session <Important>"))
    }

    // ========== Sync Token Extraction ==========

    @Test
    fun `extractSyncToken from response`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:sync-token>https://caldav.example.com/sync/12345</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val token = quirks.extractSyncToken(response)
        assertEquals("https://caldav.example.com/sync/12345", token)
    }

    @Test
    fun `extractSyncToken trims whitespace`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:sync-token>
                    sync-token-with-whitespace
                </d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val token = quirks.extractSyncToken(response)
        assertEquals("sync-token-with-whitespace", token)
    }

    // ========== CTag Extraction ==========

    @Test
    fun `extractCtag with cs prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:propstat>
                        <d:prop>
                            <cs:getctag>ctag-value-123</cs:getctag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val ctag = quirks.extractCtag(response)
        assertEquals("ctag-value-123", ctag)
    }

    @Test
    fun `extractCtag with CS prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                <D:response>
                    <D:propstat>
                        <D:prop>
                            <CS:getctag>CTAG-UPPER</CS:getctag>
                        </D:prop>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val ctag = quirks.extractCtag(response)
        assertEquals("CTAG-UPPER", ctag)
    }

    // ========== Changed Items Extraction ==========

    @Test
    fun `extractChangedItems returns hrefs with etags`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/john/personal/event1.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"changed-etag-1"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/calendars/john/personal/event1.ics", items[0].first)
        assertEquals("changed-etag-1", items[0].second)
    }

    @Test
    fun `extractChangedItems skips 404 responses`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/john/personal/deleted.ics</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(0, items.size)
    }

    // ========== Deleted Hrefs Extraction ==========

    @Test
    fun `extractDeletedHrefs detects 404 status`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/john/personal/deleted1.ics</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)

        assertEquals(1, deleted.size)
        assertEquals("/calendars/john/personal/deleted1.ics", deleted[0])
    }

    @Test
    fun `extractDeletedHrefs detects status in propstat`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/calendars/john/personal/deleted2.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)

        assertEquals(1, deleted.size)
        assertEquals("/calendars/john/personal/deleted2.ics", deleted[0])
    }

    // ========== Sync Token Validation ==========

    @Test
    fun `isSyncTokenInvalid detects 403`() {
        assertTrue(quirks.isSyncTokenInvalid(403, ""))
    }

    @Test
    fun `isSyncTokenInvalid detects 412 Precondition Failed`() {
        assertTrue(quirks.isSyncTokenInvalid(412, ""))
    }

    @Test
    fun `isSyncTokenInvalid detects valid-sync-token error in body`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:error xmlns:d="DAV:">
                <d:valid-sync-token/>
            </d:error>
        """.trimIndent()

        assertTrue(quirks.isSyncTokenInvalid(200, body))
    }

    @Test
    fun `isSyncTokenInvalid returns false for success`() {
        assertFalse(quirks.isSyncTokenInvalid(200, "<multistatus/>"))
        assertFalse(quirks.isSyncTokenInvalid(207, "<multistatus/>"))
    }

    // ========== URL Building ==========

    @Test
    fun `buildCalendarUrl with relative href`() {
        val url = quirks.buildCalendarUrl("/calendars/john/personal/", "https://caldav.example.com")

        assertEquals("https://caldav.example.com/calendars/john/personal/", url)
    }

    @Test
    fun `buildCalendarUrl preserves absolute url`() {
        val url = quirks.buildCalendarUrl(
            "https://other.example.com/calendars/john/",
            "https://caldav.example.com"
        )

        assertEquals("https://other.example.com/calendars/john/", url)
    }

    @Test
    fun `buildCalendarUrl handles trailing slash in baseHost`() {
        val url = quirks.buildCalendarUrl("/calendars/john/", "https://caldav.example.com/")

        assertEquals("https://caldav.example.com/calendars/john/", url)
    }

    @Test
    fun `buildEventUrl resolves relative href`() {
        val url = quirks.buildEventUrl(
            "/calendars/john/personal/event.ics",
            "https://caldav.example.com/calendars/john/personal/"
        )

        assertEquals("https://caldav.example.com/calendars/john/personal/event.ics", url)
    }

    @Test
    fun `buildEventUrl preserves absolute url`() {
        val url = quirks.buildEventUrl(
            "https://other.example.com/calendars/event.ics",
            "https://caldav.example.com/calendars/john/"
        )

        assertEquals("https://other.example.com/calendars/event.ics", url)
    }

    // ========== Calendar Filtering ==========

    @Test
    fun `shouldSkipCalendar returns true for inbox`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/inbox/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for outbox`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/outbox/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for notification`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/notification/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for freebusy`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/freebusy/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for tasks by name`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/list/", "My Tasks"))
    }

    @Test
    fun `shouldSkipCalendar returns true for reminders by name`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/list/", "Reminders"))
    }

    @Test
    fun `shouldSkipCalendar returns true for todo by name`() {
        assertTrue(quirks.shouldSkipCalendar("/calendars/john/list/", "Todo"))
    }

    @Test
    fun `shouldSkipCalendar returns false for regular calendar`() {
        assertFalse(quirks.shouldSkipCalendar("/calendars/john/personal/", "Personal"))
        assertFalse(quirks.shouldSkipCalendar("/calendars/john/work/", "Work"))
    }

    // ========== Date Formatting ==========

    @Test
    fun `formatDateForQuery produces correct format`() {
        // Feb 28, 2024 15:45:30 UTC in millis
        val millis = 1709135130000L

        val result = quirks.formatDateForQuery(millis)

        assertEquals("20240228T000000Z", result)
    }

    // ========== Additional Headers ==========

    @Test
    fun `getAdditionalHeaders includes User-Agent`() {
        val headers = quirks.getAdditionalHeaders()

        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers["User-Agent"]!!.contains("iCalDAV"))
    }

    // ========== Default Sync Range ==========

    @Test
    fun `getDefaultSyncRangeBack returns one year in millis`() {
        val oneYearMs = 365L * 24 * 60 * 60 * 1000
        assertEquals(oneYearMs, quirks.getDefaultSyncRangeBack())
    }

    @Test
    fun `getDefaultSyncRangeForward returns far future timestamp`() {
        // Should be Jan 1, 2100 UTC (4102444800000L)
        val future = quirks.getDefaultSyncRangeForward()
        assertTrue(future > System.currentTimeMillis())
        assertEquals(4102444800000L, future)
    }
}
