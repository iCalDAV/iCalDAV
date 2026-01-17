package com.icalendar.core.generator

import com.icalendar.core.model.*
import com.icalendar.core.parser.ICalParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ICalGeneratorTest {

    private val generator = ICalGenerator()

    @Test
    fun `generate simple event produces valid iCal`() {
        val event = createTestEvent()

        val icalString = generator.generate(event)

        assertTrue(icalString.contains("BEGIN:VCALENDAR"))
        assertTrue(icalString.contains("END:VCALENDAR"))
        assertTrue(icalString.contains("BEGIN:VEVENT"))
        assertTrue(icalString.contains("END:VEVENT"))
        assertTrue(icalString.contains("UID:test-uid-123"))
        assertTrue(icalString.contains("SUMMARY:Test Event"))
    }

    @Test
    fun `generated iCal has required CalDAV properties`() {
        val event = createTestEvent()

        val icalString = generator.generate(event)

        // Required properties for CalDAV
        assertTrue(icalString.contains("VERSION:2.0"))
        assertTrue(icalString.contains("PRODID:"))
        assertTrue(icalString.contains("CALSCALE:GREGORIAN"))
        // METHOD is excluded by default for CalDAV PUT (some servers reject it)
        assertFalse(icalString.contains("METHOD:PUBLISH"))
        assertTrue(icalString.contains("SEQUENCE:"))
        assertTrue(icalString.contains("STATUS:"))
    }

    @Test
    fun `generated iCal with includeMethod has METHOD property`() {
        val event = createTestEvent()

        val icalString = generator.generate(event, includeMethod = true)

        assertTrue(icalString.contains("METHOD:PUBLISH"))
    }

    @Test
    fun `generate all-day event uses DATE format`() {
        val event = createTestEvent(isAllDay = true)

        val icalString = generator.generate(event)

        // All-day events should use VALUE=DATE format
        assertTrue(icalString.contains("DTSTART;VALUE=DATE:"))
    }

    @Test
    fun `generate timed event includes datetime`() {
        val event = createTestEvent(isAllDay = false)

        val icalString = generator.generate(event)

        // Timed events should have DTSTART
        assertTrue(icalString.contains("DTSTART"))
    }

    @Test
    fun `generate event with alarm includes VALARM`() {
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = java.time.Duration.ofMinutes(-15),
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = "Reminder",
            summary = null,
            repeatCount = 0,
            repeatDuration = null
        )
        val event = createTestEvent(alarms = listOf(alarm))

        val icalString = generator.generate(event)

        assertTrue(icalString.contains("BEGIN:VALARM"))
        assertTrue(icalString.contains("END:VALARM"))
        assertTrue(icalString.contains("ACTION:DISPLAY"))
        assertTrue(icalString.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun `generate event with RECURRENCE-ID includes property`() {
        val recurrenceId = ICalDateTime.parse("20231208T140000Z")
        val event = createTestEvent(recurrenceId = recurrenceId)

        val icalString = generator.generate(event)

        assertTrue(icalString.contains("RECURRENCE-ID"))
        assertTrue(icalString.contains("20231208"))
    }

    @Test
    fun `generate event escapes special characters`() {
        val event = createTestEvent(
            summary = "Meeting, Important",
            description = "Line 1\nLine 2\nWith; semicolon"
        )

        val icalString = generator.generate(event)

        assertTrue(icalString.contains("Meeting\\, Important"))
        assertTrue(icalString.contains("\\n"))
        assertTrue(icalString.contains("\\;"))
    }

    @Test
    fun `generated iCal has line endings`() {
        val event = createTestEvent()

        val icalString = generator.generate(event)

        // iCal should have proper line endings
        assertTrue(icalString.contains("\n"))
    }

    @Test
    fun `generate event with RRULE includes recurrence rule`() {
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 10,
            byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.WEDNESDAY)
            )
        )
        val event = createTestEvent(rrule = rrule)

        val icalString = generator.generate(event)

        assertTrue(icalString.contains("RRULE:"))
        assertTrue(icalString.contains("FREQ=WEEKLY"))
        assertTrue(icalString.contains("COUNT=10"))
        assertTrue(icalString.contains("BYDAY="))
    }

    @Test
    fun `round trip parsing generates equivalent event`() {
        val original = createTestEvent(
            summary = "Round Trip Test",
            description = "Testing parse and generate",
            location = "Office"
        )

        val icalString = generator.generate(original)
        val parser = ICalParser()
        val result = parser.parseAllEvents(icalString)

        assertTrue(result is ParseResult.Success)
        val parsed = result.getOrNull()!![0]

        assertEquals(original.uid, parsed.uid)
        assertEquals(original.summary, parsed.summary)
        assertEquals(original.description, parsed.description)
        assertEquals(original.location, parsed.location)
    }

    private fun createTestEvent(
        uid: String = "test-uid-123",
        summary: String = "Test Event",
        description: String? = "Test description",
        location: String? = "Test location",
        isAllDay: Boolean = false,
        alarms: List<ICalAlarm> = emptyList(),
        recurrenceId: ICalDateTime? = null,
        rrule: RRule? = null
    ): ICalEvent {
        val zone = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(2023, 12, 15, 14, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2023, 12, 15, 15, 0, 0, 0, zone)

        return ICalEvent(
            uid = uid,
            importId = if (recurrenceId != null) "$uid:RECID:${recurrenceId.toICalString()}" else uid,
            summary = summary,
            description = description,
            location = location,
            dtStart = ICalDateTime.fromZonedDateTime(start, isAllDay),
            dtEnd = ICalDateTime.fromZonedDateTime(end, isAllDay),
            duration = null,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = emptyList(),
            recurrenceId = recurrenceId,
            alarms = alarms,
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
