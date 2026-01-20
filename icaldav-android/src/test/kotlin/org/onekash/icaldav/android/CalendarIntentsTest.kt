package org.onekash.icaldav.android

import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Unit tests for [CalendarIntents].
 *
 * Tests verify intent structure, action types, and extras mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class CalendarIntentsTest {

    // ==================== View Event Tests ====================

    @Test
    fun `viewEvent creates VIEW intent with correct action`() {
        val intent = CalendarIntents.viewEvent(123L)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
    }

    @Test
    fun `viewEvent creates intent with correct event URI`() {
        val eventId = 456L
        val intent = CalendarIntents.viewEvent(eventId)

        assertThat(intent.data).isNotNull()
        assertThat(intent.data.toString()).contains(eventId.toString())
        assertThat(intent.data.toString()).contains("events")
    }

    // ==================== View Calendar Tests ====================

    @Test
    fun `viewCalendar creates VIEW intent with time in path`() {
        val timeMs = 1704067200000L
        val intent = CalendarIntents.viewCalendar(timeMs)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isNotNull()
        assertThat(intent.data.toString()).contains("time")
        assertThat(intent.data.toString()).contains(timeMs.toString())
    }

    @Test
    fun `viewCalendar with calendarId includes calendar ID extra`() {
        val calendarId = 5L
        val timeMs = 1704067200000L
        val intent = CalendarIntents.viewCalendar(calendarId, timeMs)

        assertThat(intent.getLongExtra(Events.CALENDAR_ID, -1)).isEqualTo(calendarId)
    }

    // ==================== Edit Event Tests ====================

    @Test
    fun `editEvent creates EDIT intent`() {
        val intent = CalendarIntents.editEvent(789L)

        assertThat(intent.action).isEqualTo(Intent.ACTION_EDIT)
    }

    @Test
    fun `editEvent creates intent with correct event URI`() {
        val eventId = 321L
        val intent = CalendarIntents.editEvent(eventId)

        assertThat(intent.data).isNotNull()
        assertThat(intent.data.toString()).contains(eventId.toString())
    }

    // ==================== Insert Event Tests ====================

    @Test
    fun `insertEvent creates INSERT intent`() {
        val intent = CalendarIntents.insertEvent(title = "Test")

        assertThat(intent.action).isEqualTo(Intent.ACTION_INSERT)
    }

    @Test
    fun `insertEvent sets Events CONTENT_URI as data`() {
        val intent = CalendarIntents.insertEvent(title = "Test")

        assertThat(intent.data).isEqualTo(Events.CONTENT_URI)
    }

    @Test
    fun `insertEvent includes title extra`() {
        val title = "Team Meeting"
        val intent = CalendarIntents.insertEvent(title = title)

        assertThat(intent.getStringExtra(Events.TITLE)).isEqualTo(title)
    }

    @Test
    fun `insertEvent includes description extra`() {
        val description = "Weekly sync meeting"
        val intent = CalendarIntents.insertEvent(description = description)

        assertThat(intent.getStringExtra(Events.DESCRIPTION)).isEqualTo(description)
    }

    @Test
    fun `insertEvent includes location extra`() {
        val location = "Conference Room A"
        val intent = CalendarIntents.insertEvent(location = location)

        assertThat(intent.getStringExtra(Events.EVENT_LOCATION)).isEqualTo(location)
    }

    @Test
    fun `insertEvent includes start time extra`() {
        val startMs = 1704067200000L
        val intent = CalendarIntents.insertEvent(startMs = startMs)

        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
            .isEqualTo(startMs)
    }

    @Test
    fun `insertEvent includes end time extra`() {
        val endMs = 1704070800000L
        val intent = CalendarIntents.insertEvent(endMs = endMs)

        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
            .isEqualTo(endMs)
    }

    @Test
    fun `insertEvent includes all-day flag`() {
        val intent = CalendarIntents.insertEvent(allDay = true)

        assertThat(intent.getBooleanExtra(Events.ALL_DAY, false)).isTrue()
    }

    @Test
    fun `insertEvent includes calendar ID extra when specified`() {
        val calendarId = 7L
        val intent = CalendarIntents.insertEvent(calendarId = calendarId)

        assertThat(intent.getLongExtra(Events.CALENDAR_ID, -1)).isEqualTo(calendarId)
    }

    @Test
    fun `insertEvent from ICalEvent copies all basic fields`() {
        val event = createTestEvent(
            summary = "Important Meeting",
            description = "Discuss Q4 goals",
            location = "Room B"
        )

        val intent = CalendarIntents.insertEvent(event)

        assertThat(intent.getStringExtra(Events.TITLE)).isEqualTo("Important Meeting")
        assertThat(intent.getStringExtra(Events.DESCRIPTION)).isEqualTo("Discuss Q4 goals")
        assertThat(intent.getStringExtra(Events.EVENT_LOCATION)).isEqualTo("Room B")
    }

    @Test
    fun `insertEvent from ICalEvent includes times`() {
        val startMs = 1704067200000L
        val endMs = startMs + 3600000
        val event = ICalEvent(
            uid = "test-uid",
            importId = ICalEvent.generateImportId("test-uid", null),
            summary = "Test",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(startMs, ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(endMs, ZoneOffset.UTC),
            duration = null,
            isAllDay = false,
            recurrenceId = null,
            rrule = null,
            exdates = emptyList(),
            status = EventStatus.CONFIRMED,
            sequence = 0,
            transparency = Transparency.OPAQUE,
            organizer = null,
            alarms = emptyList(),
            categories = emptyList(),
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            url = null,
            rawProperties = emptyMap()
        )

        val intent = CalendarIntents.insertEvent(event)

        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
            .isEqualTo(startMs)
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
            .isEqualTo(endMs)
    }

    @Test
    fun `insertEvent from ICalEvent with RRULE includes rule extra`() {
        val event = createTestEvent(
            rrule = RRule.parse("FREQ=WEEKLY;COUNT=10")
        )

        val intent = CalendarIntents.insertEvent(event)

        val rrule = intent.getStringExtra(Events.RRULE)
        assertThat(rrule).isNotNull()
        assertThat(rrule).contains("FREQ=WEEKLY")
    }

    @Test
    fun `insertEvent from ICalEvent maps OPAQUE to AVAILABILITY_BUSY`() {
        val event = createTestEvent(transparency = Transparency.OPAQUE)

        val intent = CalendarIntents.insertEvent(event)

        assertThat(intent.getIntExtra(Events.AVAILABILITY, -1))
            .isEqualTo(Events.AVAILABILITY_BUSY)
    }

    @Test
    fun `insertEvent from ICalEvent maps TRANSPARENT to AVAILABILITY_FREE`() {
        val event = createTestEvent(transparency = Transparency.TRANSPARENT)

        val intent = CalendarIntents.insertEvent(event)

        assertThat(intent.getIntExtra(Events.AVAILABILITY, -1))
            .isEqualTo(Events.AVAILABILITY_FREE)
    }

    // ==================== Insert All-Day Event Tests ====================

    @Test
    fun `insertAllDayEvent sets allDay to true`() {
        val intent = CalendarIntents.insertAllDayEvent(
            startMs = 1704067200000L,
            endMs = 1704153600000L
        )

        assertThat(intent.getBooleanExtra(Events.ALL_DAY, false)).isTrue()
    }

    @Test
    fun `insertAllDayEvent includes all fields`() {
        val intent = CalendarIntents.insertAllDayEvent(
            title = "All Day Event",
            description = "Full day meeting",
            location = "Offsite",
            startMs = 1704067200000L,
            endMs = 1704153600000L,
            calendarId = 3L
        )

        assertThat(intent.getStringExtra(Events.TITLE)).isEqualTo("All Day Event")
        assertThat(intent.getStringExtra(Events.DESCRIPTION)).isEqualTo("Full day meeting")
        assertThat(intent.getStringExtra(Events.EVENT_LOCATION)).isEqualTo("Offsite")
        assertThat(intent.getLongExtra(Events.CALENDAR_ID, -1)).isEqualTo(3L)
    }

    // ==================== Helpers ====================

    private fun createTestEvent(
        summary: String? = "Test Event",
        description: String? = null,
        location: String? = null,
        rrule: RRule? = null,
        transparency: Transparency = Transparency.OPAQUE
    ): ICalEvent {
        return ICalEvent(
            uid = "test-uid-${System.currentTimeMillis()}",
            importId = ICalEvent.generateImportId("test-uid", null),
            summary = summary,
            description = description,
            location = location,
            dtStart = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(System.currentTimeMillis() + 3600000, ZoneOffset.UTC),
            duration = null,
            isAllDay = false,
            recurrenceId = null,
            rrule = rrule,
            exdates = emptyList(),
            status = EventStatus.CONFIRMED,
            sequence = 0,
            transparency = transparency,
            organizer = null,
            alarms = emptyList(),
            categories = emptyList(),
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
