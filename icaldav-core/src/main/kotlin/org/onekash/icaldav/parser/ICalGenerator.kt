package org.onekash.icaldav.parser

import org.onekash.icaldav.model.*
import org.onekash.icaldav.model.ImageDisplay
import org.onekash.icaldav.model.ICalImage
import org.onekash.icaldav.model.ICalConference
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates RFC 5545 compliant iCalendar strings.
 *
 * Handles all required fields for iCloud compatibility:
 * Missing CALSCALE, METHOD, STATUS, or SEQUENCE → HTTP 400
 *
 * VTIMEZONE generation is enabled by default for better interoperability
 * with calendar clients that don't recognize IANA timezone IDs.
 */
class ICalGenerator(
    private val prodId: String = "-//iCalDAV//EN",
    /**
     * Include Apple-specific extensions for better iCloud compatibility.
     * When true, adds X-WR-ALARMUID and X-APPLE-DEFAULT-ALARM to VALARM.
     */
    private val includeAppleExtensions: Boolean = true
) {
    private val vtimezoneGenerator = VTimezoneGenerator()
    /**
     * Generate iCal string for a single event.
     *
     * @param event The event to generate
     * @param includeMethod Include METHOD:PUBLISH (some CalDAV servers like Nextcloud
     *                      reject this for PUT operations - set to false for CalDAV)
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     *                         (enabled by default for better interoperability)
     * @return Complete VCALENDAR string
     */
    fun generate(
        event: ICalEvent,
        includeMethod: Boolean = false,
        includeVTimezone: Boolean = true
    ): String {
        return buildString {
            // VCALENDAR header
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:$prodId")
            appendLine("CALSCALE:GREGORIAN")
            if (includeMethod) {
                appendLine("METHOD:PUBLISH")
            }

            // VTIMEZONE components (before VEVENT per RFC 5545)
            if (includeVTimezone) {
                val tzids = vtimezoneGenerator.collectTimezones(listOf(event))
                tzids.forEach { tzid ->
                    append(vtimezoneGenerator.generate(tzid))
                }
            }

            // VEVENT
            appendVEvent(event)

            appendLine("END:VCALENDAR")
        }
    }

    /**
     * Generate iCal string for multiple events (batch).
     *
     * @param events List of events to generate
     * @param includeMethod Include METHOD:PUBLISH
     * @param includeVTimezone Include VTIMEZONE components for referenced timezones
     *                         (enabled by default, deduplicates across all events)
     * @return Complete VCALENDAR string with all events
     */
    fun generateBatch(
        events: List<ICalEvent>,
        includeMethod: Boolean = true,
        includeVTimezone: Boolean = true
    ): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:$prodId")
            appendLine("CALSCALE:GREGORIAN")
            if (includeMethod) {
                appendLine("METHOD:PUBLISH")
            }

            // VTIMEZONE components (deduplicated across all events)
            if (includeVTimezone) {
                val tzids = vtimezoneGenerator.collectTimezones(events)
                tzids.forEach { tzid ->
                    append(vtimezoneGenerator.generate(tzid))
                }
            }

            events.forEach { event ->
                appendVEvent(event)
            }

            appendLine("END:VCALENDAR")
        }
    }

    private fun StringBuilder.appendVEvent(event: ICalEvent) {
        appendLine("BEGIN:VEVENT")

        // Required properties
        appendLine("UID:${event.uid}")
        appendLine("DTSTAMP:${formatDtStamp()}")

        // DTSTART with timezone
        appendDateTimeProperty("DTSTART", event.dtStart)

        // DTEND or DURATION
        event.dtEnd?.let { dtend ->
            appendDateTimeProperty("DTEND", dtend)
        } ?: event.duration?.let { dur ->
            appendLine("DURATION:${ICalAlarm.formatDuration(dur)}")
        }

        // RECURRENCE-ID for modified instances
        event.recurrenceId?.let { recid ->
            appendDateTimeProperty("RECURRENCE-ID", recid)
        }

        // RRULE (only for master events, NOT modified instances)
        if (event.recurrenceId == null) {
            event.rrule?.let { rrule ->
                appendLine("RRULE:${rrule.toICalString()}")
            }
        }

        // EXDATE list
        event.exdates.forEach { exdate ->
            appendDateTimeProperty("EXDATE", exdate)
        }

        // Summary (title)
        event.summary?.let {
            appendFoldedLine("SUMMARY:${escapeICalText(it)}")
        }

        // Description
        event.description?.let {
            appendFoldedLine("DESCRIPTION:${escapeICalText(it)}")
        }

        // Location
        event.location?.let {
            appendFoldedLine("LOCATION:${escapeICalText(it)}")
        }

        // Status (required for iCloud)
        appendLine("STATUS:${event.status.toICalString()}")

        // Sequence (required for iCloud, increment on updates)
        appendLine("SEQUENCE:${event.sequence}")

        // Transparency
        if (event.transparency != Transparency.OPAQUE) {
            appendLine("TRANSP:${event.transparency.toICalString()}")
        }

        // Categories
        if (event.categories.isNotEmpty()) {
            appendLine("CATEGORIES:${event.categories.joinToString(",") { escapeICalText(it) }}")
        }

        // Color (RFC 7986)
        event.color?.let {
            appendLine("COLOR:$it")
        }

        // IMAGE properties (RFC 7986)
        event.images.forEach { image ->
            appendImageProperty(image)
        }

        // CONFERENCE properties (RFC 7986)
        event.conferences.forEach { conference ->
            appendConferenceProperty(conference)
        }

        // LINK properties (RFC 9253)
        event.links.forEach { link ->
            appendLine(link.toICalString())
        }

        // RELATED-TO properties (RFC 9253)
        event.relations.forEach { relation ->
            appendLine(relation.toICalString())
        }

        // URL
        event.url?.let {
            appendLine("URL:$it")
        }

        // Organizer (for scheduling)
        event.organizer?.let { org ->
            val params = buildString {
                org.name?.let { append(";CN=${escapeParamValue(it)}") }
                org.sentBy?.let { append(";SENT-BY=\"$it\"") }
            }
            appendLine("ORGANIZER${params}:mailto:${org.email}")
        }

        // Attendees (for scheduling)
        event.attendees.forEach { att ->
            val params = buildString {
                att.name?.let { append(";CN=${escapeParamValue(it)}") }
                append(";PARTSTAT=${att.partStat.toICalString()}")
                append(";ROLE=${att.role.toICalString()}")
                if (att.rsvp) append(";RSVP=TRUE")
            }
            appendLine("ATTENDEE${params}:mailto:${att.email}")
        }

        // VALARMs
        event.alarms.forEach { alarm ->
            appendVAlarm(alarm)
        }

        // Created/Last-Modified
        event.created?.let {
            appendLine("CREATED:${it.toICalString()}")
        }
        event.lastModified?.let {
            appendLine("LAST-MODIFIED:${it.toICalString()}")
        }

        // Raw properties (X-*, CLASS, and other unhandled properties for round-trip)
        event.rawProperties.forEach { (key, value) ->
            // Key may contain parameters: "X-APPLE-STRUCTURED-LOCATION;VALUE=URI"
            // In that case, output as-is with the value
            appendFoldedLine("$key:$value")
        }

        appendLine("END:VEVENT")
    }

    private fun StringBuilder.appendVAlarm(alarm: ICalAlarm) {
        appendLine("BEGIN:VALARM")

        // RFC 9074: UID for alarm identification
        // Generate a UID if not provided (needed for Apple extensions)
        val alarmUid = alarm.uid ?: java.util.UUID.randomUUID().toString().uppercase()
        appendLine("UID:$alarmUid")

        // Apple-specific extensions for better iCloud compatibility
        if (includeAppleExtensions) {
            // X-WR-ALARMUID: iCloud alarm identifier (same as UID)
            appendLine("X-WR-ALARMUID:$alarmUid")
            // X-APPLE-DEFAULT-ALARM: Prevents iPhone from treating this as a
            // "default" alarm that can be merged with calendar defaults
            if (!alarm.defaultAlarm) {
                appendLine("X-APPLE-DEFAULT-ALARM:FALSE")
            }
        }

        appendLine("ACTION:${alarm.action.name}")

        // Trigger
        alarm.trigger?.let { dur ->
            val related = if (alarm.triggerRelatedToEnd) ";RELATED=END" else ""
            appendLine("TRIGGER${related}:${ICalAlarm.formatDuration(dur)}")
        } ?: alarm.triggerAbsolute?.let { dt ->
            appendLine("TRIGGER;VALUE=DATE-TIME:${dt.toICalString()}")
        }

        // Description (required for DISPLAY)
        if (alarm.action == AlarmAction.DISPLAY) {
            appendLine("DESCRIPTION:${alarm.description ?: "Reminder"}")
        }

        // Summary (for EMAIL action)
        alarm.summary?.let {
            appendLine("SUMMARY:$it")
        }

        // Repeat
        if (alarm.repeatCount > 0) {
            appendLine("REPEAT:${alarm.repeatCount}")
            alarm.repeatDuration?.let { dur ->
                appendLine("DURATION:${ICalAlarm.formatDuration(dur)}")
            }
        }

        // RFC 9074 extensions
        alarm.acknowledged?.let {
            appendLine("ACKNOWLEDGED:${it.toICalString()}")
        }

        alarm.relatedTo?.let {
            appendLine("RELATED-TO:$it")
        }

        // RFC 9074: DEFAULT-ALARM
        if (alarm.defaultAlarm) {
            appendLine("DEFAULT-ALARM:TRUE")
        }

        alarm.proximity?.let {
            appendLine("PROXIMITY:${it.toICalString()}")
        }

        appendLine("END:VALARM")
    }

    /**
     * Append datetime property with proper formatting.
     */
    private fun StringBuilder.appendDateTimeProperty(name: String, dt: ICalDateTime) {
        if (dt.isDate) {
            // DATE format for all-day
            appendLine("$name;VALUE=DATE:${dt.toICalString()}")
        } else if (dt.isUtc) {
            // UTC format
            appendLine("$name:${dt.toICalString()}")
        } else if (dt.timezone != null) {
            // Local with TZID
            val tzid = dt.timezone.id
            appendLine("$name;TZID=$tzid:${dt.toICalString()}")
        } else {
            // Floating (no timezone)
            appendLine("$name:${dt.toICalString()}")
        }
    }

    /**
     * Append a line with folding if > 75 octets (bytes).
     *
     * RFC 5545 Section 3.1: Lines SHOULD NOT be longer than 75 octets,
     * excluding the line break.
     *
     * IMPORTANT: This counts octets (UTF-8 bytes), not characters.
     * A single character may be 1-4 bytes in UTF-8.
     * Handles surrogate pairs (emoji) correctly by using code points.
     */
    private fun StringBuilder.appendFoldedLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)

        if (bytes.size <= 75) {
            appendLine(line)
            return
        }

        // Use code points instead of chars to handle surrogate pairs correctly
        val codePoints = line.codePoints().toArray()
        var cpIndex = 0
        var isFirst = true

        while (cpIndex < codePoints.size) {
            val maxBytes = if (isFirst) 75 else 74  // 74 for continuation (after space)

            // Find how many code points fit in maxBytes
            var usedBytes = 0
            val startCpIndex = cpIndex

            while (cpIndex < codePoints.size) {
                val cp = codePoints[cpIndex]
                val cpBytes = Character.toString(cp).toByteArray(Charsets.UTF_8).size

                if (usedBytes + cpBytes > maxBytes) {
                    break
                }

                usedBytes += cpBytes
                cpIndex++
            }

            // Handle edge case: if no code points fit, force at least one
            if (cpIndex == startCpIndex && cpIndex < codePoints.size) {
                cpIndex++
            }

            if (!isFirst) {
                append(" ")  // RFC 5545: continuation lines start with space or tab
            }

            // Convert code points back to string
            val segment = codePoints.sliceArray(startCpIndex until cpIndex)
                .map { Character.toString(it) }
                .joinToString("")
            append(segment)
            appendLine()

            isFirst = false
        }
    }

    /**
     * Format DTSTAMP as UTC timestamp.
     */
    private fun formatDtStamp(): String {
        val now = Instant.now().atZone(ZoneOffset.UTC)
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(now)
    }

    /**
     * Escape text values per RFC 5545 Section 3.3.11.
     */
    private fun escapeICalText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    /**
     * Escape parameter values (may need quoting).
     */
    private fun escapeParamValue(value: String): String {
        return if (value.contains(":") || value.contains(";") || value.contains(",")) {
            "\"$value\""
        } else {
            value
        }
    }

    // ============ RFC 7986 Property Generation ============

    /**
     * Append IMAGE property (RFC 7986).
     *
     * Format: IMAGE;VALUE=URI;DISPLAY=BADGE;FMTTYPE=image/png:https://example.com/logo.png
     */
    private fun StringBuilder.appendImageProperty(image: ICalImage) {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (image.display != ImageDisplay.GRAPHIC) {
            params.add("DISPLAY=${image.display.name}")
        }
        image.mediaType?.let { params.add("FMTTYPE=$it") }
        image.altText?.let { params.add("ALTREP=\"${escapeICalText(it)}\"") }

        appendLine("IMAGE;${params.joinToString(";")}:${image.uri}")
    }

    /**
     * Append CONFERENCE property (RFC 7986).
     *
     * Format: CONFERENCE;VALUE=URI;FEATURE=VIDEO,AUDIO;LABEL=Join:https://zoom.us/j/123
     */
    private fun StringBuilder.appendConferenceProperty(conference: ICalConference) {
        val params = mutableListOf<String>()
        params.add("VALUE=URI")

        if (conference.features.isNotEmpty()) {
            params.add("FEATURE=${conference.features.joinToString(",") { it.name }}")
        }
        conference.label?.let { params.add("LABEL=${escapeParamValue(it)}") }
        conference.language?.let { params.add("LANGUAGE=$it") }

        appendLine("CONFERENCE;${params.joinToString(";")}:${conference.uri}")
    }
}