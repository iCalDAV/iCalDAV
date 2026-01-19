package org.onekash.icaldav.quirks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for ICloudQuirks - iCloud-specific CalDAV behaviors.
 *
 * Tests cover:
 * - XML namespace variations (d:, D:, no prefix)
 * - CDATA handling for calendar data
 * - Calendar filtering (inbox, outbox, tasks)
 * - Sync token extraction and validation
 * - URL building for server-returned hrefs
 */
class ICloudQuirksTest {

    private val quirks = ICloudQuirks()

    // ========== Provider Properties ==========

    @Test
    fun `providerId is icloud`() {
        assertEquals("icloud", quirks.providerId)
    }

    @Test
    fun `displayName is iCloud`() {
        assertEquals("iCloud", quirks.displayName)
    }

    @Test
    fun `requiresAppSpecificPassword is true`() {
        assertTrue(quirks.requiresAppSpecificPassword)
    }

    @Test
    fun `baseUrl is caldav icloud com`() {
        assertEquals("https://caldav.icloud.com", quirks.baseUrl)
    }

    // ========== Principal URL Extraction ==========

    @Test
    fun `extractPrincipalUrl with d prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:current-user-principal>
                        <d:href>/123456789/principal/</d:href>
                    </d:current-user-principal>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/123456789/principal/", result)
    }

    @Test
    fun `extractPrincipalUrl with D prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:current-user-principal>
                        <D:href>/user/principal/</D:href>
                    </D:current-user-principal>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/user/principal/", result)
    }

    @Test
    fun `extractPrincipalUrl with no prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
                <response>
                    <current-user-principal>
                        <href>/no-prefix/principal/</href>
                    </current-user-principal>
                </response>
            </multistatus>
        """.trimIndent()

        val result = quirks.extractPrincipalUrl(response)
        assertEquals("/no-prefix/principal/", result)
    }

    @Test
    fun `extractPrincipalUrl returns null when not present`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:displayname>Some Resource</d:displayname>
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
                        <d:href>/123/calendars/</d:href>
                    </c:calendar-home-set>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("/123/calendars/", result)
    }

    @Test
    fun `extractCalendarHomeUrl with no prefix`() {
        val response = """
            <multistatus xmlns="DAV:">
                <response>
                    <calendar-home-set>
                        <href>/home/calendars/</href>
                    </calendar-home-set>
                </response>
            </multistatus>
        """.trimIndent()

        val result = quirks.extractCalendarHomeUrl(response)
        assertEquals("/home/calendars/", result)
    }

    // ========== Calendar Extraction ==========

    @Test
    fun `extractCalendars parses displayname and color`() {
        // Note: iCloud returns <calendar xmlns="urn:ietf:params:xml:ns:caldav"/> in resourcetype
        // The implementation checks for "<calendar" and "caldav" substrings
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:ic="http://apple.com/ns/ical/">
                <d:response>
                    <d:href>/123/calendars/home/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype>
                                <d:collection/>
                                <calendar xmlns="urn:ietf:params:xml:ns:caldav"/>
                            </d:resourcetype>
                            <d:displayname>Personal</d:displayname>
                            <ic:calendar-color>#FF5733FF</ic:calendar-color>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("/123/calendars/home/", calendars[0].href)
        assertEquals("Personal", calendars[0].displayName)
        assertEquals("#FF5733FF", calendars[0].color)
    }

    @Test
    fun `extractCalendars skips inbox`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/inbox/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>Inbox</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/home/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>Personal</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("Personal", calendars[0].displayName)
    }

    @Test
    fun `extractCalendars skips outbox`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/outbox/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>Outbox</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars skips tasks and reminders`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/tasks-list/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>My Tasks</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/reminders/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>Reminders</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(0, calendars.size)
    }

    @Test
    fun `extractCalendars extracts ctag`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:href>/123/calendars/home/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></d:resourcetype>
                            <d:displayname>Work</d:displayname>
                            <cs:getctag>ctag-12345</cs:getctag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val calendars = quirks.extractCalendars(response, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("ctag-12345", calendars[0].ctag)
    }

    // ========== iCal Data Extraction ==========

    @Test
    fun `extractICalData handles CDATA format`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/123/calendars/home/event.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-123"</d:getetag>
                            <c:calendar-data><![CDATA[BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-uid@example.com
DTSTART:20240101T100000Z
DTEND:20240101T110000Z
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR]]></c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertEquals("/123/calendars/home/event.ics", events[0].href)
        assertEquals("etag-123", events[0].etag)
        assertTrue(events[0].icalData.contains("BEGIN:VCALENDAR"))
        assertTrue(events[0].icalData.contains("Test Event"))
    }

    @Test
    fun `extractICalData handles standard format without CDATA`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/123/calendars/home/event2.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-456"</d:getetag>
                            <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-uid-2@example.com
SUMMARY:Another Event
END:VEVENT
END:VCALENDAR</c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("Another Event"))
    }

    @Test
    fun `extractICalData handles XML entities`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:response>
                    <d:href>/123/calendars/home/event3.ics</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getetag>"etag-789"</d:getetag>
                            <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-uid-3@example.com
SUMMARY:Meeting &amp; Discussion
END:VEVENT
END:VCALENDAR</c:calendar-data>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val events = quirks.extractICalData(response)

        assertEquals(1, events.size)
        assertTrue(events[0].icalData.contains("Meeting & Discussion"))
    }

    // ========== Sync Token Extraction ==========

    @Test
    fun `extractSyncToken from response with d prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:sync-token>http://example.com/sync/token-123</d:sync-token>
            </d:multistatus>
        """.trimIndent()

        val token = quirks.extractSyncToken(response)
        assertEquals("http://example.com/sync/token-123", token)
    }

    @Test
    fun `extractSyncToken from response without prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
                <sync-token>sync-token-456</sync-token>
            </multistatus>
        """.trimIndent()

        val token = quirks.extractSyncToken(response)
        assertEquals("sync-token-456", token)
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
                            <cs:getctag>FT=-@RU=12345@S=7890</cs:getctag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val ctag = quirks.extractCtag(response)
        assertEquals("FT=-@RU=12345@S=7890", ctag)
    }

    @Test
    fun `extractCtag without prefix`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <multistatus xmlns="DAV:">
                <response>
                    <propstat>
                        <prop>
                            <getctag>simple-ctag</getctag>
                        </prop>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val ctag = quirks.extractCtag(response)
        assertEquals("simple-ctag", ctag)
    }

    // ========== Changed Items Extraction ==========

    @Test
    fun `extractChangedItems returns hrefs with etags`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/home/event1.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"etag-aaa"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/home/event2.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"etag-bbb"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(2, items.size)
        assertEquals("/123/calendars/home/event1.ics", items[0].first)
        assertEquals("etag-aaa", items[0].second)
        assertEquals("/123/calendars/home/event2.ics", items[1].first)
        assertEquals("etag-bbb", items[1].second)
    }

    @Test
    fun `extractChangedItems skips 404 items`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/home/existing.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"etag-exist"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/home/deleted.ics</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/123/calendars/home/existing.ics", items[0].first)
    }

    @Test
    fun `extractChangedItems skips non-ics files`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/home/</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"collection-etag"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/home/event.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                        <d:prop>
                            <d:getetag>"event-etag"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = quirks.extractChangedItems(response)

        assertEquals(1, items.size)
        assertEquals("/123/calendars/home/event.ics", items[0].first)
    }

    // ========== Deleted Hrefs Extraction ==========

    @Test
    fun `extractDeletedHrefs detects 404 status`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/home/deleted1.ics</d:href>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:response>
                <d:response>
                    <d:href>/123/calendars/home/deleted2.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)

        assertEquals(2, deleted.size)
        assertTrue(deleted.contains("/123/calendars/home/deleted1.ics"))
        assertTrue(deleted.contains("/123/calendars/home/deleted2.ics"))
    }

    @Test
    fun `extractDeletedHrefs ignores 200 status`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/123/calendars/home/exists.ics</d:href>
                    <d:propstat>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val deleted = quirks.extractDeletedHrefs(response)

        assertEquals(0, deleted.size)
    }

    // ========== Sync Token Validation ==========

    @Test
    fun `isSyncTokenInvalid detects 403`() {
        assertTrue(quirks.isSyncTokenInvalid(403, ""))
    }

    @Test
    fun `isSyncTokenInvalid detects valid-sync-token error`() {
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
        assertFalse(quirks.isSyncTokenInvalid(200, "<multistatus></multistatus>"))
        assertFalse(quirks.isSyncTokenInvalid(207, "<multistatus></multistatus>"))
    }

    // ========== URL Building ==========

    @Test
    fun `buildCalendarUrl with relative href`() {
        val url = quirks.buildCalendarUrl("/123/calendars/home/", "https://p180-caldav.icloud.com:443")

        assertEquals("https://p180-caldav.icloud.com:443/123/calendars/home/", url)
    }

    @Test
    fun `buildCalendarUrl preserves absolute url`() {
        val url = quirks.buildCalendarUrl(
            "https://p180-caldav.icloud.com:443/123/calendars/home/",
            "https://caldav.icloud.com"
        )

        assertEquals("https://p180-caldav.icloud.com:443/123/calendars/home/", url)
    }

    @Test
    fun `buildEventUrl resolves relative href`() {
        val url = quirks.buildEventUrl(
            "/123/calendars/home/event.ics",
            "https://p180-caldav.icloud.com:443/123/calendars/home/"
        )

        assertEquals("https://p180-caldav.icloud.com:443/123/calendars/home/event.ics", url)
    }

    @Test
    fun `buildEventUrl preserves absolute url`() {
        val url = quirks.buildEventUrl(
            "https://p180-caldav.icloud.com:443/123/calendars/home/event.ics",
            "https://caldav.icloud.com/something/"
        )

        assertEquals("https://p180-caldav.icloud.com:443/123/calendars/home/event.ics", url)
    }

    // ========== Calendar Filtering ==========

    @Test
    fun `shouldSkipCalendar returns true for inbox`() {
        assertTrue(quirks.shouldSkipCalendar("/123/inbox/", "Inbox"))
    }

    @Test
    fun `shouldSkipCalendar returns true for outbox`() {
        assertTrue(quirks.shouldSkipCalendar("/123/outbox/", "Outbox"))
    }

    @Test
    fun `shouldSkipCalendar returns true for notification`() {
        assertTrue(quirks.shouldSkipCalendar("/123/notification/", "Notifications"))
    }

    @Test
    fun `shouldSkipCalendar returns true for tasks by name`() {
        assertTrue(quirks.shouldSkipCalendar("/123/calendars/todo/", "My Tasks"))
    }

    @Test
    fun `shouldSkipCalendar returns true for reminders by name`() {
        assertTrue(quirks.shouldSkipCalendar("/123/calendars/list/", "Reminders"))
    }

    @Test
    fun `shouldSkipCalendar returns false for regular calendar`() {
        assertFalse(quirks.shouldSkipCalendar("/123/calendars/home/", "Personal"))
        assertFalse(quirks.shouldSkipCalendar("/123/calendars/work/", "Work"))
    }

    // ========== Date Formatting ==========

    @Test
    fun `formatDateForQuery produces correct format`() {
        // Jan 15, 2024 12:30:00 UTC in millis
        val millis = 1705321800000L

        val result = quirks.formatDateForQuery(millis)

        assertEquals("20240115T000000Z", result)
    }

    @Test
    fun `formatDateForQuery handles epoch`() {
        val result = quirks.formatDateForQuery(0L)
        assertEquals("19700101T000000Z", result)
    }

    // ========== Additional Headers ==========

    @Test
    fun `getAdditionalHeaders includes User-Agent`() {
        val headers = quirks.getAdditionalHeaders()

        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers["User-Agent"]!!.contains("iCalDAV"))
    }
}
