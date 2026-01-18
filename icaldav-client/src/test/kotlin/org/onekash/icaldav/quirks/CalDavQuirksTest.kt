package org.onekash.icaldav.quirks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for CalDavQuirks companion object factory method.
 *
 * Tests the auto-detection of CalDAV provider from server URL.
 */
class CalDavQuirksTest {

    @Test
    fun `forServer detects icloud from caldav icloud com`() {
        val quirks = CalDavQuirks.forServer("https://caldav.icloud.com")

        assertTrue(quirks is ICloudQuirks)
        assertEquals("icloud", quirks.providerId)
    }

    @Test
    fun `forServer detects icloud from partition URL`() {
        val quirks = CalDavQuirks.forServer("https://p180-caldav.icloud.com:443/123456/calendars/")

        assertTrue(quirks is ICloudQuirks)
        assertEquals("icloud", quirks.providerId)
    }

    @Test
    fun `forServer detects icloud case insensitively`() {
        val quirks = CalDavQuirks.forServer("https://CALDAV.ICLOUD.COM")

        assertTrue(quirks is ICloudQuirks)
    }

    @Test
    fun `forServer detects google from google com URL`() {
        val quirks = CalDavQuirks.forServer("https://www.google.com/calendar/dav/user@gmail.com/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("google", quirks.providerId)
        assertEquals("Google Calendar", quirks.displayName)
    }

    @Test
    fun `forServer detects google from calendar google com`() {
        val quirks = CalDavQuirks.forServer("https://calendar.google.com/caldav/v2/user@gmail.com/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("google", quirks.providerId)
    }

    @Test
    fun `forServer detects fastmail`() {
        val quirks = CalDavQuirks.forServer("https://caldav.fastmail.com/dav/calendars/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("fastmail", quirks.providerId)
        assertEquals("Fastmail", quirks.displayName)
    }

    @Test
    fun `forServer returns generic for nextcloud`() {
        val quirks = CalDavQuirks.forServer("https://cloud.example.com/remote.php/dav/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("generic", quirks.providerId)
        assertEquals("CalDAV Server", quirks.displayName)
    }

    @Test
    fun `forServer returns generic for radicale`() {
        val quirks = CalDavQuirks.forServer("https://radicale.example.com/john/calendar.ics/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("generic", quirks.providerId)
    }

    @Test
    fun `forServer returns generic for baikal`() {
        val quirks = CalDavQuirks.forServer("https://dav.example.com/dav.php/calendars/john/default/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("generic", quirks.providerId)
    }

    @Test
    fun `forServer returns generic for unknown server`() {
        val quirks = CalDavQuirks.forServer("https://custom-caldav.mycompany.internal/caldav/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("generic", quirks.providerId)
    }

    @Test
    fun `forServer preserves baseUrl in DefaultQuirks`() {
        val serverUrl = "https://caldav.myserver.com:8443/dav/"
        val quirks = CalDavQuirks.forServer(serverUrl)

        assertTrue(quirks is DefaultQuirks)
        assertEquals(serverUrl, quirks.baseUrl)
    }

    // ========== Interface Contract Tests ==========

    @Test
    fun `ParsedCalendar data class holds all fields`() {
        val calendar = CalDavQuirks.ParsedCalendar(
            href = "/calendars/test/",
            displayName = "Test Calendar",
            color = "#FF0000",
            ctag = "ctag-123",
            isReadOnly = true
        )

        assertEquals("/calendars/test/", calendar.href)
        assertEquals("Test Calendar", calendar.displayName)
        assertEquals("#FF0000", calendar.color)
        assertEquals("ctag-123", calendar.ctag)
        assertTrue(calendar.isReadOnly)
    }

    @Test
    fun `ParsedCalendar defaults isReadOnly to false`() {
        val calendar = CalDavQuirks.ParsedCalendar(
            href = "/calendars/test/",
            displayName = "Test",
            color = null,
            ctag = null
        )

        assertFalse(calendar.isReadOnly)
    }

    @Test
    fun `ParsedEventData data class holds all fields`() {
        val event = CalDavQuirks.ParsedEventData(
            href = "/calendars/test/event.ics",
            etag = "etag-123",
            icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"
        )

        assertEquals("/calendars/test/event.ics", event.href)
        assertEquals("etag-123", event.etag)
        assertTrue(event.icalData.contains("VCALENDAR"))
    }

    @Test
    fun `ParsedEventData allows null etag`() {
        val event = CalDavQuirks.ParsedEventData(
            href = "/calendars/test/event.ics",
            etag = null,
            icalData = "BEGIN:VCALENDAR\nEND:VCALENDAR"
        )

        assertNull(event.etag)
    }
}
