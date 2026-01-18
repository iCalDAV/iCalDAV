package org.onekash.icaldav.recurrence

import org.onekash.icaldav.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class RRuleExpanderTest {

    private val expander = RRuleExpander()
    private val zone = ZoneId.of("America/New_York")

    @Test
    fun `non-recurring event returns itself`() {
        val event = createTestEvent(rrule = null)

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        assertEquals(1, occurrences.size)
        assertEquals(event.uid, occurrences[0].uid)
    }

    @Test
    fun `daily recurrence generates correct occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val event = createTestEvent(
            startDate = ZonedDateTime.of(2023, 12, 1, 10, 0, 0, 0, zone),
            rrule = rrule
        )

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        assertEquals(5, occurrences.size)

        // Verify each occurrence is one day apart
        val dayCodes = occurrences.map { it.dtStart.toDayCode() }
        assertEquals(listOf("20231201", "20231202", "20231203", "20231204", "20231205"), dayCodes)
    }

    @Test
    fun `weekly recurrence on specific days`() {
        val rrule = RRule(
            freq = Frequency.WEEKLY,
            interval = 1,
            count = 6,
            byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.WEDNESDAY),
                WeekdayNum(DayOfWeek.FRIDAY)
            )
        )
        // Start on Monday Dec 4, 2023
        val event = createTestEvent(
            startDate = ZonedDateTime.of(2023, 12, 4, 10, 0, 0, 0, zone),
            rrule = rrule
        )

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        assertEquals(6, occurrences.size)

        // Should get Mon, Wed, Fri for 2 weeks
        val dayCodes = occurrences.map { it.dtStart.toDayCode() }
        assertTrue(dayCodes.contains("20231204"))  // Monday
        assertTrue(dayCodes.contains("20231206"))  // Wednesday
        assertTrue(dayCodes.contains("20231208"))  // Friday
    }

    @Test
    fun `EXDATE excludes occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val event = createTestEvent(
            startDate = ZonedDateTime.of(2023, 12, 1, 10, 0, 0, 0, zone),
            rrule = rrule,
            exdates = listOf(
                ICalDateTime.parse("20231202T150000Z"),  // Dec 2
                ICalDateTime.parse("20231204T150000Z")   // Dec 4
            )
        )

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        // 5 occurrences minus 2 excluded = 3
        assertEquals(3, occurrences.size)

        val dayCodes = occurrences.map { it.dtStart.toDayCode() }
        assertFalse(dayCodes.contains("20231202"))
        assertFalse(dayCodes.contains("20231204"))
        assertTrue(dayCodes.contains("20231201"))
        assertTrue(dayCodes.contains("20231203"))
        assertTrue(dayCodes.contains("20231205"))
    }

    @Test
    fun `overrides replace original occurrences`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val masterEvent = createTestEvent(
            uid = "recurring-123",
            startDate = ZonedDateTime.of(2023, 12, 1, 10, 0, 0, 0, zone),
            rrule = rrule,
            summary = "Daily Standup"
        )

        // Create an override that moved Dec 3 occurrence to a different time
        val overrideEvent = createTestEvent(
            uid = "recurring-123",
            summary = "Daily Standup (Rescheduled)",
            startDate = ZonedDateTime.of(2023, 12, 3, 15, 0, 0, 0, zone),
            recurrenceId = ICalDateTime.parse("20231203T150000Z")
        )

        val overrides = RRuleExpander.buildOverrideMap(listOf(overrideEvent))

        val occurrences = expander.expand(
            masterEvent,
            TimeRange.forMonth(2023, 12, zone),
            overrides
        )

        assertEquals(5, occurrences.size)

        // Find Dec 3 occurrence - should be the override
        val dec3 = occurrences.find { it.dtStart.toDayCode() == "20231203" }
        assertNotNull(dec3)
        assertEquals("Daily Standup (Rescheduled)", dec3!!.summary)
    }

    @Test
    fun `occurrence importIds are unique`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 5
        )
        val event = createTestEvent(
            uid = "test-uid-123",
            startDate = ZonedDateTime.of(2023, 12, 1, 10, 0, 0, 0, zone),
            rrule = rrule
        )

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        // Each occurrence should have a unique importId
        val importIds = occurrences.map { it.importId }
        assertEquals(importIds.size, importIds.toSet().size)

        // Format should be uid:OCC:daycode
        assertTrue(importIds.all { it.startsWith("test-uid-123:OCC:") })
    }

    @Test
    fun `occurrences preserve event duration`() {
        val rrule = RRule(
            freq = Frequency.DAILY,
            interval = 1,
            count = 3
        )
        val startDate = ZonedDateTime.of(2023, 12, 1, 10, 0, 0, 0, zone)
        val endDate = ZonedDateTime.of(2023, 12, 1, 11, 30, 0, 0, zone)  // 1.5 hour duration

        val event = createTestEvent(
            startDate = startDate,
            endDate = endDate,
            rrule = rrule
        )

        val occurrences = expander.expand(
            event,
            TimeRange.forMonth(2023, 12, zone)
        )

        for (occ in occurrences) {
            val duration = occ.dtEnd!!.timestamp - occ.dtStart.timestamp
            assertEquals(90 * 60 * 1000L, duration)  // 90 minutes in millis
        }
    }

    @Test
    fun `TimeRange forMonth creates correct range`() {
        val range = TimeRange.forMonth(2023, 12, zone)

        val startZdt = ZonedDateTime.ofInstant(range.start, zone)
        val endZdt = ZonedDateTime.ofInstant(range.end, zone)

        assertEquals(12, startZdt.monthValue)
        assertEquals(1, startZdt.dayOfMonth)
        assertEquals(1, endZdt.monthValue)  // January 1 (exclusive end)
        assertEquals(2024, endZdt.year)
    }

    @Test
    fun `buildOverrideMap creates correct mapping`() {
        val override1 = createTestEvent(
            uid = "event-1",
            recurrenceId = ICalDateTime.parse("20231201T100000Z")
        )
        val override2 = createTestEvent(
            uid = "event-2",
            recurrenceId = ICalDateTime.parse("20231215T100000Z")
        )
        val nonOverride = createTestEvent(
            uid = "event-3",
            recurrenceId = null
        )

        val map = RRuleExpander.buildOverrideMap(listOf(override1, override2, nonOverride))

        assertEquals(2, map.size)
        assertTrue(map.containsKey("20231201"))
        assertTrue(map.containsKey("20231215"))
    }

    @Test
    fun `monthly recurrence on specific day`() {
        val rrule = RRule(
            freq = Frequency.MONTHLY,
            interval = 1,
            count = 3,
            byMonthDay = listOf(15)
        )
        val event = createTestEvent(
            startDate = ZonedDateTime.of(2023, 10, 15, 10, 0, 0, 0, zone),
            rrule = rrule
        )

        val occurrences = expander.expand(
            event,
            TimeRange.aroundNow(100, 100, zone)  // Wide range
        )

        // Should get Oct 15, Nov 15, Dec 15
        val dayCodes = occurrences.map { it.dtStart.toDayCode() }
        assertTrue(dayCodes.all { it.endsWith("15") })
    }

    private fun createTestEvent(
        uid: String = "test-uid-123",
        summary: String = "Test Event",
        startDate: ZonedDateTime = ZonedDateTime.of(2023, 12, 15, 10, 0, 0, 0, zone),
        endDate: ZonedDateTime? = null,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        recurrenceId: ICalDateTime? = null
    ): ICalEvent {
        val actualEnd = endDate ?: startDate.plusHours(1)

        return ICalEvent(
            uid = uid,
            importId = if (recurrenceId != null) "$uid:RECID:${recurrenceId.toICalString()}" else uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(startDate, false),
            dtEnd = ICalDateTime.fromZonedDateTime(actualEnd, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrenceId,
            alarms = emptyList(),
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
