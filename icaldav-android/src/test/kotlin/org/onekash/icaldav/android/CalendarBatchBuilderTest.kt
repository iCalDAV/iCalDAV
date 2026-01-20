package org.onekash.icaldav.android

import android.content.ContentProviderOperation
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [CalendarBatchBuilder].
 *
 * Tests verify batch operation construction, back-references, and size limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class CalendarBatchBuilderTest {

    private lateinit var builder: CalendarBatchBuilder

    private val accountName = "test@example.com"
    private val accountType = "org.onekash.icaldav"

    @Before
    fun setUp() {
        builder = CalendarBatchBuilder(accountName, accountType)
    }

    // ==================== Basic Operations ====================

    @Test
    fun `insertEvent creates insert operation`() {
        val event = createTestEvent()

        builder.insertEvent(event, calendarId = 1L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains(Events.CONTENT_URI.toString())
        assertThat(operations[0].uri.toString()).contains(CalendarContract.CALLER_IS_SYNCADAPTER)
    }

    @Test
    fun `updateEvent creates update operation`() {
        val event = createTestEvent()

        builder.updateEvent(eventId = 42L, event = event, calendarId = 1L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains("42")
        assertThat(operations[0].uri.toString()).contains(CalendarContract.CALLER_IS_SYNCADAPTER)
    }

    @Test
    fun `deleteEvent creates delete operation`() {
        builder.deleteEvent(eventId = 42L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains("42")
    }

    @Test
    fun `insertReminder creates insert operation`() {
        val alarm = createTestAlarm()

        builder.insertReminder(alarm, eventId = 42L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains(Reminders.CONTENT_URI.toString())
    }

    @Test
    fun `insertAttendee creates insert operation`() {
        val attendee = createTestAttendee()

        builder.insertAttendee(attendee, eventId = 42L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains(Attendees.CONTENT_URI.toString())
    }

    // ==================== Back-Reference Operations ====================

    @Test
    fun `insertReminderWithBackRef references event operation`() {
        val event = createTestEvent()
        val alarm = createTestAlarm()

        builder.insertEvent(event, calendarId = 1L)
        val eventRef = builder.operationCount - 1
        builder.insertReminderWithBackRef(alarm, eventRef)
        val operations = builder.build()

        assertThat(operations).hasSize(2)
        // Second operation should be for reminders
        assertThat(operations[1].uri.toString()).contains(Reminders.CONTENT_URI.toString())
    }

    @Test
    fun `insertAttendeeWithBackRef references event operation`() {
        val event = createTestEvent()
        val attendee = createTestAttendee()

        builder.insertEvent(event, calendarId = 1L)
        val eventRef = builder.operationCount - 1
        builder.insertAttendeeWithBackRef(attendee, eventRef)
        val operations = builder.build()

        assertThat(operations).hasSize(2)
        assertThat(operations[1].uri.toString()).contains(Attendees.CONTENT_URI.toString())
    }

    @Test
    fun `insertOrganizerWithBackRef references event operation`() {
        val event = createTestEvent()
        val organizer = Organizer("org@example.com", "Organizer", null)

        builder.insertEvent(event, calendarId = 1L)
        val eventRef = builder.operationCount - 1
        builder.insertOrganizerWithBackRef(organizer, eventRef)
        val operations = builder.build()

        assertThat(operations).hasSize(2)
        assertThat(operations[1].uri.toString()).contains(Attendees.CONTENT_URI.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `insertReminderWithBackRef throws on invalid reference`() {
        val alarm = createTestAlarm()
        // No event inserted yet, so index 0 is invalid
        builder.insertReminderWithBackRef(alarm, eventIdRef = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `insertAttendeeWithBackRef throws on negative reference`() {
        val attendee = createTestAttendee()
        builder.insertAttendeeWithBackRef(attendee, eventIdRef = -1)
    }

    // ==================== Clear Operations ====================

    @Test
    fun `clearReminders creates delete operation with selection`() {
        builder.clearReminders(eventId = 42L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains(Reminders.CONTENT_URI.toString())
    }

    @Test
    fun `clearAttendees creates delete operation with selection`() {
        builder.clearAttendees(eventId = 42L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        assertThat(operations[0].uri.toString()).contains(Attendees.CONTENT_URI.toString())
    }

    // ==================== Batch Size Limit ====================

    @Test(expected = IllegalStateException::class)
    fun `exceeding MAX_BATCH_SIZE throws exception`() {
        val event = createTestEvent()
        // Try to add more than MAX_BATCH_SIZE operations
        repeat(CalendarBatchBuilder.MAX_BATCH_SIZE + 1) {
            builder.insertEvent(event, calendarId = 1L)
        }
    }

    @Test
    fun `MAX_BATCH_SIZE operations are allowed`() {
        val event = createTestEvent()
        repeat(CalendarBatchBuilder.MAX_BATCH_SIZE) {
            builder.insertEvent(event, calendarId = 1L)
        }
        val operations = builder.build()
        assertThat(operations).hasSize(CalendarBatchBuilder.MAX_BATCH_SIZE)
    }

    // ==================== Utility Methods ====================

    @Test
    fun `clear removes all operations`() {
        val event = createTestEvent()
        builder.insertEvent(event, calendarId = 1L)
        builder.insertEvent(event, calendarId = 1L)
        assertThat(builder.operationCount).isEqualTo(2)

        builder.clear()
        assertThat(builder.operationCount).isEqualTo(0)
        assertThat(builder.isEmpty()).isTrue()
    }

    // ==================== Helpers ====================

    private fun createTestEvent(
        uid: String = UUID.randomUUID().toString()
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Test Event",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
            dtEnd = null,
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
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

    private fun createTestAlarm(): ICalAlarm {
        return ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-15),
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = null,
            summary = null
        )
    }

    private fun createTestAttendee(): Attendee {
        return Attendee(
            email = "attendee@example.com",
            name = "Test Attendee",
            partStat = PartStat.ACCEPTED,
            role = AttendeeRole.REQ_PARTICIPANT,
            rsvp = false
        )
    }
}
