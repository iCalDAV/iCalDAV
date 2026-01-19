package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.Events
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CalendarContractMapper].
 *
 * Tests verify ContentValues mapping using Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class CalendarContractMapperTest {

    // ==================== Basic Field Mapping ====================

    @Test
    fun `toContentValues maps all basic fields correctly`() {
        val event = createTestEvent(
            uid = "test-uid-123",
            summary = "Team Meeting",
            description = "Weekly sync",
            location = "Conference Room A"
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.TITLE)).isEqualTo("Team Meeting")
        assertThat(values.getAsString(Events.DESCRIPTION)).isEqualTo("Weekly sync")
        assertThat(values.getAsString(Events.EVENT_LOCATION)).isEqualTo("Conference Room A")
        assertThat(values.getAsString(Events._SYNC_ID)).isEqualTo("test-uid-123")
        assertThat(values.getAsLong(Events.CALENDAR_ID)).isEqualTo(1L)
    }

    @Test
    fun `toContentValues handles null optional fields`() {
        val event = createTestEvent(
            summary = null,
            description = null,
            location = null
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.containsKey(Events.TITLE)).isTrue()
        assertThat(values.getAsString(Events.TITLE)).isNull()
    }

    // ==================== DURATION vs DTEND ====================

    @Test
    fun `recurring event uses DURATION, not DTEND`() {
        val event = createTestEvent(
            rrule = RRule.parse("FREQ=WEEKLY;COUNT=10"),
            duration = Duration.ofHours(1)
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.DURATION)).isEqualTo("PT1H")
        assertThat(values.containsKey(Events.DTEND)).isTrue()
        assertThat(values.getAsLong(Events.DTEND)).isNull() // Must be null for recurring
    }

    @Test
    fun `non-recurring event uses DTEND, not DURATION`() {
        val now = System.currentTimeMillis()
        val event = createTestEvent(
            rrule = null,
            dtEnd = ICalDateTime.fromTimestamp(
                now + 3600000,
                ZoneId.of("America/New_York")
            )
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsLong(Events.DTEND)).isNotNull()
        assertThat(values.containsKey(Events.DURATION)).isTrue()
        assertThat(values.getAsString(Events.DURATION)).isNull() // Must be null for non-recurring
    }

    @Test
    fun `duration format PT1H30M is correct`() {
        val event = createTestEvent(
            rrule = RRule.parse("FREQ=DAILY"),
            duration = Duration.ofMinutes(90)
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.DURATION)).isEqualTo("PT1H30M")
    }

    @Test
    fun `duration format P1D for full day recurring event`() {
        val event = createTestEvent(
            rrule = RRule.parse("FREQ=WEEKLY"),
            duration = Duration.ofDays(1)
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.DURATION)).isEqualTo("P1D")
    }

    // ==================== All-Day Events ====================

    @Test
    fun `all-day event stored with ALL_DAY flag`() {
        val event = createTestEvent(
            isAllDay = true,
            dtStart = ICalDateTime.fromTimestamp(
                System.currentTimeMillis(),
                timezone = ZoneOffset.UTC,
                isDate = true
            )
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsInteger(Events.ALL_DAY)).isEqualTo(1)
        assertThat(values.getAsString(Events.EVENT_TIMEZONE)).isEqualTo("UTC")
    }

    @Test
    fun `timed event preserves timezone`() {
        val event = createTestEvent(
            isAllDay = false,
            dtStart = ICalDateTime.fromTimestamp(
                System.currentTimeMillis(),
                ZoneId.of("America/Los_Angeles")
            )
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsInteger(Events.ALL_DAY)).isEqualTo(0)
        assertThat(values.getAsString(Events.EVENT_TIMEZONE)).isEqualTo("America/Los_Angeles")
    }

    // ==================== RECURRENCE-ID (Exceptions) ====================

    @Test
    fun `exception event has ORIGINAL_SYNC_ID and ORIGINAL_INSTANCE_TIME`() {
        val masterUid = "master-uid-123"
        val originalTime = 1705312800000L // 2024-01-15T10:00:00Z

        val event = createTestEvent(
            uid = masterUid,
            recurrenceId = ICalDateTime.fromTimestamp(originalTime, ZoneOffset.UTC)
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.ORIGINAL_SYNC_ID)).isEqualTo(masterUid)
        assertThat(values.getAsLong(Events.ORIGINAL_INSTANCE_TIME)).isEqualTo(originalTime)
    }

    @Test
    fun `non-exception event has no ORIGINAL fields`() {
        val event = createTestEvent(recurrenceId = null)

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.containsKey(Events.ORIGINAL_SYNC_ID)).isFalse()
        assertThat(values.containsKey(Events.ORIGINAL_INSTANCE_TIME)).isFalse()
    }

    // ==================== EXDATE ====================

    @Test
    fun `exdates formatted as comma-separated list`() {
        val event = createTestEvent(
            exdates = listOf(
                ICalDateTime.fromTimestamp(1704067200000, ZoneOffset.UTC), // 2024-01-01
                ICalDateTime.fromTimestamp(1704153600000, ZoneOffset.UTC)  // 2024-01-02
            )
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        val exdate = values.getAsString(Events.EXDATE)
        assertThat(exdate).isNotNull()
        assertThat(exdate).contains(",")
        assertThat(exdate!!.split(",")).hasSize(2)
    }

    @Test
    fun `empty exdates not written`() {
        val event = createTestEvent(exdates = emptyList())

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.containsKey(Events.EXDATE)).isFalse()
    }

    // ==================== Status & Transparency ====================

    @Test
    fun `status CONFIRMED mapped correctly`() {
        val event = createTestEvent(status = EventStatus.CONFIRMED)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.STATUS)).isEqualTo(Events.STATUS_CONFIRMED)
    }

    @Test
    fun `status TENTATIVE mapped correctly`() {
        val event = createTestEvent(status = EventStatus.TENTATIVE)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.STATUS)).isEqualTo(Events.STATUS_TENTATIVE)
    }

    @Test
    fun `status CANCELLED mapped correctly`() {
        val event = createTestEvent(status = EventStatus.CANCELLED)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.STATUS)).isEqualTo(Events.STATUS_CANCELED)
    }

    @Test
    fun `transparency OPAQUE means BUSY`() {
        val event = createTestEvent(transparency = Transparency.OPAQUE)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.AVAILABILITY)).isEqualTo(Events.AVAILABILITY_BUSY)
    }

    @Test
    fun `transparency TRANSPARENT means FREE`() {
        val event = createTestEvent(transparency = Transparency.TRANSPARENT)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.AVAILABILITY)).isEqualTo(Events.AVAILABILITY_FREE)
    }

    // ==================== RRULE ====================

    @Test
    fun `RRULE preserved in string format`() {
        // Input RRULE - property order may differ after parsing
        val rruleStr = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10"
        val event = createTestEvent(rrule = RRule.parse(rruleStr))

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        // RRule.toICalString() outputs in canonical order: FREQ, COUNT, BYDAY
        // RFC 5545 doesn't mandate property order, so this is acceptable
        val rruleValue = values.getAsString(Events.RRULE)!!
        assertThat(rruleValue).contains("FREQ=WEEKLY")
        assertThat(rruleValue).contains("BYDAY=MO,WE,FR")
        assertThat(rruleValue).contains("COUNT=10")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles very long description`() {
        val longDescription = "A".repeat(100_000)
        val event = createTestEvent(description = longDescription)

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.DESCRIPTION)).isEqualTo(longDescription)
    }

    @Test
    fun `handles special characters in title`() {
        val title = "Meeting: \"Important\" <CEO> & CFO"
        val event = createTestEvent(summary = title)

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.TITLE)).isEqualTo(title)
    }

    @Test
    fun `handles unicode in location`() {
        val location = "会议室 A (北京)"
        val event = createTestEvent(location = location)

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.EVENT_LOCATION)).isEqualTo(location)
    }

    @Test
    fun `sequence stored in SYNC_DATA3`() {
        val event = createTestEvent(sequence = 5)

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.SYNC_DATA3)).isEqualTo("5")
    }

    @Test
    fun `organizer email mapped correctly`() {
        val event = createTestEvent(
            organizer = Organizer(email = "organizer@example.com", name = "John Doe", sentBy = null)
        )

        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

        assertThat(values.getAsString(Events.ORGANIZER)).isEqualTo("organizer@example.com")
    }

    // ==================== Helpers ====================

    private fun createTestEvent(
        uid: String = UUID.randomUUID().toString(),
        summary: String? = "Test Event",
        description: String? = null,
        location: String? = null,
        dtStart: ICalDateTime = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
        dtEnd: ICalDateTime? = null,
        duration: Duration? = null,
        isAllDay: Boolean = false,
        status: EventStatus = EventStatus.CONFIRMED,
        transparency: Transparency = Transparency.OPAQUE,
        rrule: RRule? = null,
        exdates: List<ICalDateTime> = emptyList(),
        recurrenceId: ICalDateTime? = null,
        sequence: Int = 0,
        organizer: Organizer? = null
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = ICalEvent.generateImportId(uid, recurrenceId),
            summary = summary,
            description = description,
            location = location,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = duration,
            isAllDay = isAllDay,
            status = status,
            sequence = sequence,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrenceId,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = organizer,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = transparency,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
