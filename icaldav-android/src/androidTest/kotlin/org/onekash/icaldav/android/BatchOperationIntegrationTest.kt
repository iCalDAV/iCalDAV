package org.onekash.icaldav.android

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for [CalendarBatchBuilder] with real CalendarProvider.
 *
 * Tests verify that batch operations work correctly with the Android
 * CalendarContract provider, including back-references for related tables.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BatchOperationIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private lateinit var context: Context
    private val contentResolver by lazy { context.contentResolver }

    private val accountName = "batch-test@test.local"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL

    private var testCalendarId: Long = -1
    private val testRunId = UUID.randomUUID().toString().take(8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testCalendarId = createTestCalendar()
    }

    @After
    fun cleanup() {
        // Delete all events from test calendar
        if (testCalendarId > 0) {
            contentResolver.delete(
                asSyncAdapter(Events.CONTENT_URI),
                "${Events.CALENDAR_ID} = ?",
                arrayOf(testCalendarId.toString())
            )

            // Delete test calendar
            contentResolver.delete(
                asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, testCalendarId)),
                null, null
            )
        }
    }

    // ==================== Batch Insert Tests ====================

    @Test
    fun batchInsert_multipleEvents_atomic() {
        val builder = CalendarBatchBuilder(accountName, accountType)
        val events = (1..5).map { createTestEvent("Event $it") }

        events.forEach { event ->
            builder.insertEvent(event, testCalendarId)
        }

        val operations = builder.build()
        assertThat(operations).hasSize(5)

        // Apply batch
        val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        assertThat(results.size).isEqualTo(5)

        // Verify all inserted
        val count = queryEventCount()
        assertThat(count).isEqualTo(5)
    }

    @Test
    fun batchInsert_eventWithReminders_backReference() {
        val builder = CalendarBatchBuilder(accountName, accountType)
        val event = createTestEvent("Event with Reminders")
        val alarm1 = createTestAlarm(15)
        val alarm2 = createTestAlarm(60)

        builder.insertEvent(event, testCalendarId)
        val eventRef = builder.operationCount - 1
        builder.insertReminderWithBackRef(alarm1, eventRef)
        builder.insertReminderWithBackRef(alarm2, eventRef)

        val operations = builder.build()
        assertThat(operations).hasSize(3) // 1 event + 2 reminders

        // Apply batch
        val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        assertThat(results.size).isEqualTo(3)

        // Get event ID from first result
        val eventId = ContentUris.parseId(results[0].uri!!)
        assertThat(eventId).isGreaterThan(0L)

        // Query reminders for this event
        val remindersCursor = contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        )

        remindersCursor?.use {
            assertThat(it.count).isEqualTo(2)
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(15)
            it.moveToNext()
            assertThat(it.getInt(0)).isEqualTo(60)
        }
    }

    @Test
    fun batchInsert_eventWithAttendees_backReference() {
        val builder = CalendarBatchBuilder(accountName, accountType)
        val event = createTestEvent("Meeting")
        val attendee1 = createTestAttendee("alice@example.com", "Alice")
        val attendee2 = createTestAttendee("bob@example.com", "Bob")

        builder.insertEvent(event, testCalendarId)
        val eventRef = builder.operationCount - 1
        builder.insertAttendeeWithBackRef(attendee1, eventRef)
        builder.insertAttendeeWithBackRef(attendee2, eventRef)

        val operations = builder.build()
        val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)

        val eventId = ContentUris.parseId(results[0].uri!!)

        // Query attendees
        val attendeesCursor = contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )

        attendeesCursor?.use {
            assertThat(it.count).isEqualTo(2)
        }
    }

    // ==================== Batch Update Tests ====================

    @Test
    fun batchUpdate_multipleEvents() {
        // First insert events individually
        val event1Id = insertEvent(createTestEvent("Event 1"))
        val event2Id = insertEvent(createTestEvent("Event 2"))

        // Now batch update
        val builder = CalendarBatchBuilder(accountName, accountType)
        val updatedEvent1 = createTestEvent("Updated Event 1")
        val updatedEvent2 = createTestEvent("Updated Event 2")

        builder.updateEvent(event1Id, updatedEvent1, testCalendarId)
        builder.updateEvent(event2Id, updatedEvent2, testCalendarId)

        val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, builder.build())
        assertThat(results.size).isEqualTo(2)

        // Verify updates
        val cursor1 = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, event1Id),
            arrayOf(Events.TITLE), null, null, null
        )
        cursor1?.use {
            it.moveToFirst()
            assertThat(it.getString(0)).isEqualTo("Updated Event 1")
        }
    }

    // ==================== Batch Delete Tests ====================

    @Test
    fun batchDelete_multipleEvents() {
        // Insert events
        val ids = (1..3).map { insertEvent(createTestEvent("Delete Me $it")) }
        assertThat(queryEventCount()).isEqualTo(3)

        // Batch delete
        val builder = CalendarBatchBuilder(accountName, accountType)
        ids.forEach { builder.deleteEvent(it) }

        val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, builder.build())
        assertThat(results.size).isEqualTo(3)

        // Verify deleted
        assertThat(queryEventCount()).isEqualTo(0)
    }

    // ==================== Clear Operations Tests ====================

    @Test
    fun batchClearReminders_removesAllForEvent() {
        // Insert event with reminders manually
        val eventId = insertEvent(createTestEvent("Event with Reminders"))
        insertReminder(eventId, 15)
        insertReminder(eventId, 30)
        insertReminder(eventId, 60)

        // Verify reminders exist
        assertThat(queryReminderCount(eventId)).isEqualTo(3)

        // Clear reminders via batch
        val builder = CalendarBatchBuilder(accountName, accountType)
        builder.clearReminders(eventId)

        contentResolver.applyBatch(CalendarContract.AUTHORITY, builder.build())

        // Verify cleared
        assertThat(queryReminderCount(eventId)).isEqualTo(0)
    }

    @Test
    fun batchClearAttendees_removesAllForEvent() {
        val eventId = insertEvent(createTestEvent("Meeting"))
        insertAttendee(eventId, "alice@example.com")
        insertAttendee(eventId, "bob@example.com")

        assertThat(queryAttendeeCount(eventId)).isEqualTo(2)

        val builder = CalendarBatchBuilder(accountName, accountType)
        builder.clearAttendees(eventId)

        contentResolver.applyBatch(CalendarContract.AUTHORITY, builder.build())

        assertThat(queryAttendeeCount(eventId)).isEqualTo(0)
    }

    // ==================== Helper Integration Test ====================

    @Test
    fun calendarProviderHelper_applyBatch_works() {
        val helper = CalendarProviderHelper(contentResolver, accountName, accountType)
        val builder = CalendarBatchBuilder(accountName, accountType)

        val event = createTestEvent("Helper Test Event")
        builder.insertEvent(event, testCalendarId)

        val results = helper.applyBatch(builder.build())
        assertThat(results.size).isEqualTo(1)
        assertThat(results[0].uri).isNotNull()
    }

    // ==================== Helpers ====================

    private fun createTestCalendar(): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, "batch-test-$testRunId")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Batch Test Calendar")
            put(Calendars.CALENDAR_COLOR, 0xFF00FF00.toInt())
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        }

        val uri = contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun createTestEvent(summary: String): ICalEvent {
        val uid = UUID.randomUUID().toString()
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(System.currentTimeMillis() + 3600000, ZoneOffset.UTC),
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

    private fun createTestAlarm(minutesBefore: Int): ICalAlarm {
        return ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-minutesBefore.toLong()),
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = null,
            summary = null
        )
    }

    private fun createTestAttendee(email: String, name: String): Attendee {
        return Attendee(
            email = email,
            name = name,
            partStat = PartStat.ACCEPTED,
            role = AttendeeRole.REQ_PARTICIPANT,
            rsvp = false
        )
    }

    private fun insertEvent(event: ICalEvent): Long {
        val values = CalendarContractMapper.toContentValues(event, testCalendarId)
        val uri = contentResolver.insert(asSyncAdapter(Events.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun insertReminder(eventId: Long, minutes: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        contentResolver.insert(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI), values)
    }

    private fun insertAttendee(eventId: Long, email: String) {
        val values = ContentValues().apply {
            put(CalendarContract.Attendees.EVENT_ID, eventId)
            put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
            put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
        }
        contentResolver.insert(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI), values)
    }

    private fun queryEventCount(): Int {
        val cursor = contentResolver.query(
            Events.CONTENT_URI,
            arrayOf("COUNT(*)"),
            "${Events.CALENDAR_ID} = ?",
            arrayOf(testCalendarId.toString()),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }

    private fun queryReminderCount(eventId: Long): Int {
        val cursor = contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf("COUNT(*)"),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }

    private fun queryAttendeeCount(eventId: Long): Int {
        val cursor = contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf("COUNT(*)"),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }

    private fun asSyncAdapter(uri: android.net.Uri): android.net.Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }
}
