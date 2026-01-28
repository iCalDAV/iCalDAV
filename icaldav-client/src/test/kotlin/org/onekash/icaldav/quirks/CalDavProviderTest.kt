package org.onekash.icaldav.quirks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for CalDavProvider - the recommended way to configure
 * provider-specific CalDAV behavior.
 */
class CalDavProviderTest {

    private val provider = CalDavProvider.generic("https://caldav.example.com")

    // ========== Provider Properties ==========

    @Test
    fun `generic provider has correct defaults`() {
        val p = CalDavProvider.generic("https://caldav.example.com")
        assertEquals("generic", p.id)
        assertEquals("CalDAV Server", p.displayName)
        assertEquals("https://caldav.example.com", p.baseUrl)
        assertFalse(p.requiresAppPassword)
        assertEquals(setOf(403, 412), p.invalidSyncTokenCodes)
    }

    @Test
    fun `ICLOUD has correct configuration`() {
        val p = CalDavProvider.ICLOUD
        assertEquals("icloud", p.id)
        assertEquals("iCloud", p.displayName)
        assertEquals("https://caldav.icloud.com", p.baseUrl)
        assertTrue(p.requiresAppPassword)
        assertEquals(setOf(403), p.invalidSyncTokenCodes)
    }

    @Test
    fun `custom invalidSyncTokenCodes supported`() {
        val p = CalDavProvider(
            id = "custom",
            displayName = "Custom Server",
            baseUrl = "https://custom.example.com",
            invalidSyncTokenCodes = setOf(403, 410, 412)
        )
        assertEquals(setOf(403, 410, 412), p.invalidSyncTokenCodes)
    }

    // ========== forServer() Detection ==========

    @Test
    fun `forServer detects iCloud`() {
        val p = CalDavProvider.forServer("https://caldav.icloud.com")
        assertEquals("icloud", p.id)
        assertTrue(p.requiresAppPassword)
    }

    @Test
    fun `forServer detects Google`() {
        val p = CalDavProvider.forServer("https://www.google.com/calendar/dav/")
        assertEquals("google", p.id)
    }

    @Test
    fun `forServer detects Fastmail`() {
        val p = CalDavProvider.forServer("https://caldav.fastmail.com/")
        assertEquals("fastmail", p.id)
    }

    @Test
    fun `forServer detects Radicale`() {
        val p = CalDavProvider.forServer("http://localhost:5232/user/calendar/")
        assertEquals("radicale", p.id)
    }

    @Test
    fun `forServer returns generic for unknown`() {
        val p = CalDavProvider.forServer("https://caldav.nextcloud.example.com/")
        assertEquals("generic", p.id)
        assertFalse(p.requiresAppPassword)
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

        val result = provider.extractPrincipalUrl(response)
        assertEquals("/principals/users/john/", result)
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

        val result = provider.extractPrincipalUrl(response)
        assertNull(result)
    }

    @Test
    fun `extractPrincipalUrl handles malformed XML gracefully`() {
        val result = provider.extractPrincipalUrl("not xml at all")
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

        val result = provider.extractCalendarHomeUrl(response)
        assertEquals("/calendars/john/", result)
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

        val calendars = provider.extractCalendars(response, "https://caldav.example.com")

        assertEquals(1, calendars.size)
        assertEquals("/calendars/john/personal/", calendars[0].href)
        assertEquals("Personal Calendar", calendars[0].displayName)
        assertEquals("#3366FFFF", calendars[0].color)
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

        val calendars = provider.extractCalendars(response, "https://caldav.example.com")

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

        val calendars = provider.extractCalendars(response, "https://caldav.example.com")
        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars skips todo lists by name`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/calendars/john/list/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                            <d:displayname>My Todo List</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = provider.extractCalendars(response, "https://caldav.example.com")
        assertEquals(0, calendars.size)
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
BEGIN:VEVENT
UID:event-123@example.com
SUMMARY:Standard Event
END:VEVENT
END:VCALENDAR</c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = provider.extractICalData(response)

        assertEquals(1, events.size)
        assertEquals("/calendars/john/personal/event.ics", events[0].href)
        assertEquals("etag-standard", events[0].etag)
        assertTrue(events[0].icalData.contains("Standard Event"))
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

        val events = provider.extractICalData(response)

        assertEquals(1, events.size)
        // Entity decoding: &amp; -> &, &lt; -> <, &gt; -> >
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

        val token = provider.extractSyncToken(response)
        assertEquals("https://caldav.example.com/sync/12345", token)
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

        val ctag = provider.extractCtag(response)
        assertEquals("ctag-value-123", ctag)
    }

    // ========== Sync Token Validation ==========

    @Test
    fun `isSyncTokenInvalid detects configured codes`() {
        // Default codes: 403, 412
        assertTrue(provider.isSyncTokenInvalid(403, ""))
        assertTrue(provider.isSyncTokenInvalid(412, ""))
        assertFalse(provider.isSyncTokenInvalid(200, ""))
        assertFalse(provider.isSyncTokenInvalid(207, ""))
    }

    @Test
    fun `isSyncTokenInvalid with custom codes`() {
        val customProvider = CalDavProvider(
            id = "custom",
            displayName = "Custom",
            baseUrl = "https://custom.example.com",
            invalidSyncTokenCodes = setOf(403, 410, 412)
        )
        assertTrue(customProvider.isSyncTokenInvalid(410, ""))
    }

    @Test
    fun `isSyncTokenInvalid detects valid-sync-token in body`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:error xmlns:d="DAV:">
                <d:valid-sync-token/>
            </d:error>
        """.trimIndent()

        assertTrue(provider.isSyncTokenInvalid(200, body))
    }

    // ========== URL Building ==========

    @Test
    fun `buildCalendarUrl with relative href`() {
        val url = provider.buildCalendarUrl("/calendars/john/personal/", "https://caldav.example.com")
        assertEquals("https://caldav.example.com/calendars/john/personal/", url)
    }

    @Test
    fun `buildCalendarUrl preserves absolute url`() {
        val url = provider.buildCalendarUrl(
            "https://other.example.com/calendars/john/",
            "https://caldav.example.com"
        )
        assertEquals("https://other.example.com/calendars/john/", url)
    }

    @Test
    fun `buildCalendarUrl handles trailing slash in baseHost`() {
        val url = provider.buildCalendarUrl("/calendars/john/", "https://caldav.example.com/")
        assertEquals("https://caldav.example.com/calendars/john/", url)
    }

    @Test
    fun `buildEventUrl resolves relative href`() {
        val url = provider.buildEventUrl(
            "/calendars/john/personal/event.ics",
            "https://caldav.example.com/calendars/john/personal/"
        )
        assertEquals("https://caldav.example.com/calendars/john/personal/event.ics", url)
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

        val items = provider.extractChangedItems(response)

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

        val items = provider.extractChangedItems(response)
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

        val deleted = provider.extractDeletedHrefs(response)

        assertEquals(1, deleted.size)
        assertEquals("/calendars/john/personal/deleted1.ics", deleted[0])
    }

    // ========== Calendar Filtering ==========

    @Test
    fun `shouldSkipCalendar returns true for inbox`() {
        assertTrue(provider.shouldSkipCalendar("/calendars/john/inbox/", null))
    }

    @Test
    fun `shouldSkipCalendar returns true for tasks by name`() {
        assertTrue(provider.shouldSkipCalendar("/calendars/john/list/", "My Tasks"))
    }

    @Test
    fun `shouldSkipCalendar returns false for regular calendar`() {
        assertFalse(provider.shouldSkipCalendar("/calendars/john/personal/", "Personal"))
        assertFalse(provider.shouldSkipCalendar("/calendars/john/work/", "Work"))
    }

    // ========== Date Formatting ==========

    @Test
    fun `formatDateForQuery produces correct format`() {
        // Feb 28, 2024 15:45:30 UTC in millis
        val millis = 1709135130000L

        val result = provider.formatDateForQuery(millis)

        assertEquals("20240228T000000Z", result)
    }

    // ========== Default Sync Range ==========

    @Test
    fun `getDefaultSyncRangeBack returns one year in millis`() {
        val oneYearMs = 365L * 24 * 60 * 60 * 1000
        assertEquals(oneYearMs, provider.getDefaultSyncRangeBack())
    }

    @Test
    fun `getDefaultSyncRangeForward returns far future timestamp`() {
        val future = provider.getDefaultSyncRangeForward()
        assertTrue(future > System.currentTimeMillis())
        assertEquals(4102444800000L, future)
    }

    // ========== Data Class Equality ==========

    @Test
    fun `CalDavProvider is a data class with proper equals`() {
        val p1 = CalDavProvider("test", "Test", "https://test.com")
        val p2 = CalDavProvider("test", "Test", "https://test.com")
        val p3 = CalDavProvider("other", "Test", "https://test.com")

        assertEquals(p1, p2)
        assertNotEquals(p1, p3)
    }

    @Test
    fun `CalDavProvider copy works correctly`() {
        val original = CalDavProvider.ICLOUD
        val modified = original.copy(baseUrl = "https://custom.icloud.com")

        assertEquals("icloud", modified.id)
        assertEquals("https://custom.icloud.com", modified.baseUrl)
        assertTrue(modified.requiresAppPassword)
    }
}
