package org.onekash.icaldav.xml

/**
 * Builder for WebDAV/CalDAV XML request bodies.
 *
 * Creates properly namespaced XML for PROPFIND, REPORT, and other
 * WebDAV operations.
 */
object RequestBuilder {

    /**
     * Escape XML special characters to prevent injection attacks.
     * Must be applied to all user-provided content.
     */
    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Build PROPFIND request body for discovering properties.
     *
     * @param properties List of property names to request
     * @return XML request body
     */
    fun propfind(vararg properties: String): String {
        val propElements = properties.joinToString("\n      ") { "<D:$it/>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/">
  <D:prop>
      $propElements
  </D:prop>
</D:propfind>"""
    }

    /**
     * PROPFIND for discovering current-user-principal.
     */
    fun propfindPrincipal(): String = propfind("current-user-principal")

    /**
     * PROPFIND for discovering calendar-home-set.
     */
    fun propfindCalendarHome(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <C:calendar-home-set/>
  </D:prop>
</D:propfind>"""
    }

    /**
     * PROPFIND for listing calendars with their properties.
     */
    fun propfindCalendars(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/" xmlns:A="http://apple.com/ns/ical/">
  <D:prop>
    <D:displayname/>
    <D:resourcetype/>
    <D:getetag/>
    <CS:getctag/>
    <D:sync-token/>
    <C:supported-calendar-component-set/>
    <A:calendar-color/>
    <C:calendar-description/>
  </D:prop>
</D:propfind>"""
    }

    /**
     * PROPFIND for checking calendar ctag (change detection).
     */
    fun propfindCtag(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
  <D:prop>
    <CS:getctag/>
    <D:sync-token/>
  </D:prop>
</D:propfind>"""
    }

    /**
     * CalDAV calendar-query REPORT for fetching events in a time range.
     *
     * Uses lowercase namespace prefixes (c:, d:) which are proven to work
     * with iCloud. Some CalDAV servers are sensitive to namespace prefix casing.
     *
     * @param start ISO 8601 timestamp for range start (e.g., "20231201T000000Z")
     * @param end ISO 8601 timestamp for range end (e.g., "20241231T235959Z")
     * @return XML request body
     */
    fun calendarQuery(start: String? = null, end: String? = null): String {
        val timeRange = if (start != null && end != null) {
            """<c:time-range start="$start" end="$end"/>"""
        } else ""

        // Use lowercase prefixes (c:, d:) - proven to work with iCloud
        return """<?xml version="1.0" encoding="UTF-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <d:getetag/>
    <c:calendar-data/>
  </d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT">
        $timeRange
      </c:comp-filter>
    </c:comp-filter>
  </c:filter>
</c:calendar-query>"""
    }

    /**
     * CalDAV calendar-query REPORT for fetching only ETags (no calendar-data).
     *
     * Used for lightweight sync when comparing etags to detect changes.
     * Returns ~96% less data than full calendarQuery for large calendars.
     *
     * @param start ISO 8601 timestamp for range start (e.g., "20231201T000000Z")
     * @param end ISO 8601 timestamp for range end (e.g., "20241231T235959Z")
     * @return XML request body
     */
    fun calendarQueryEtagOnly(start: String? = null, end: String? = null): String {
        val timeRange = if (start != null && end != null) {
            """<c:time-range start="$start" end="$end"/>"""
        } else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <d:getetag/>
  </d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT">
        $timeRange
      </c:comp-filter>
    </c:comp-filter>
  </c:filter>
</c:calendar-query>"""
    }

    /**
     * CalDAV calendar-multiget REPORT for fetching specific events by URL.
     *
     * Uses lowercase namespace prefixes (c:, d:) which are proven to work
     * with iCloud. Some CalDAV servers are sensitive to namespace prefix casing.
     *
     * @param hrefs List of event URLs to fetch
     * @return XML request body
     */
    fun calendarMultiget(hrefs: List<String>): String {
        val hrefElements = hrefs.joinToString("\n    ") { "<d:href>$it</d:href>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <d:getetag/>
    <c:calendar-data/>
  </d:prop>
    $hrefElements
</c:calendar-multiget>"""
    }

    /**
     * WebDAV sync-collection REPORT for incremental sync (RFC 6578).
     *
     * @param syncToken Previous sync-token (empty for initial sync)
     * @return XML request body
     */
    fun syncCollection(syncToken: String = ""): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<D:sync-collection xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:sync-token>$syncToken</D:sync-token>
  <D:sync-level>1</D:sync-level>
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
</D:sync-collection>"""
    }

    /**
     * Free/busy query REPORT (RFC 4791 Section 7.10).
     *
     * @param start ISO 8601 timestamp
     * @param end ISO 8601 timestamp
     * @param organizer Email address of organizer
     * @param attendees List of attendee email addresses
     * @return XML request body
     */
    fun freeBusyQuery(
        start: String,
        end: String,
        organizer: String,
        attendees: List<String>
    ): String {
        val attendeeElements = attendees.joinToString("\n      ") {
            """<C:attendee>mailto:$it</C:attendee>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<C:free-busy-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <C:time-range start="$start" end="$end"/>
  <C:organizer>mailto:$organizer</C:organizer>
      $attendeeElements
</C:free-busy-query>"""
    }

    /**
     * MKCALENDAR request body for creating a new calendar.
     *
     * @param displayName Calendar display name
     * @param description Optional description
     * @param color Optional color (e.g., "#FF5733")
     * @return XML request body
     */
    fun mkcalendar(
        displayName: String,
        description: String? = null,
        color: String? = null
    ): String {
        val safeDisplayName = escapeXml(displayName)
        val descProp = description?.let { "<C:calendar-description>${escapeXml(it)}</C:calendar-description>" } ?: ""
        val colorProp = color?.let { """<A:calendar-color xmlns:A="http://apple.com/ns/ical/">${escapeXml(it)}</A:calendar-color>""" } ?: ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:set>
    <D:prop>
      <D:displayname>$safeDisplayName</D:displayname>
      $descProp
      $colorProp
    </D:prop>
  </D:set>
</C:mkcalendar>"""
    }
}