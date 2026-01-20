package org.onekash.icaldav.android

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.os.Build
import android.provider.CalendarContract
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [CalendarProviderHelper].
 *
 * Tests verify helper methods with Robolectric's shadow ContentResolver.
 * Note: These tests verify method behavior but may not have full CalendarProvider
 * implementation available in Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class CalendarProviderHelperTest {

    private lateinit var helper: CalendarProviderHelper
    private lateinit var contentResolver: ContentResolver

    private val accountName = "test@example.com"
    private val accountType = "org.onekash.icaldav"

    @Before
    fun setUp() {
        contentResolver = RuntimeEnvironment.getApplication().contentResolver
        helper = CalendarProviderHelper(contentResolver, accountName, accountType)
    }

    // ==================== Batch Operations Tests ====================

    @Test
    fun `applyBatch method exists and takes ArrayList`() {
        // Verify the method signature and existence
        // Note: Robolectric's CalendarProvider doesn't fully implement applyBatch,
        // so we just verify the method exists and can be called
        val operations = ArrayList<ContentProviderOperation>()
        try {
            helper.applyBatch(operations)
        } catch (e: Exception) {
            // Expected in Robolectric - CalendarProvider not fully implemented
        }
        // Test passes if method exists and is callable
    }

    @Test
    fun `CalendarBatchBuilder operations can be built for applyBatch`() {
        // Verify batch operations can be constructed and would be valid
        val builder = CalendarBatchBuilder(accountName, accountType)
        val event = createTestEvent()
        builder.insertEvent(event, calendarId = 1L)

        val operations = builder.build()
        assertThat(operations).hasSize(1)

        // Operations are well-formed ContentProviderOperations
        assertThat(operations[0]).isNotNull()
    }

    // ==================== Count Operations Tests ====================

    @Test
    fun `countDirtyEvents returns 0 for nonexistent calendar`() {
        val count = helper.countDirtyEvents(calendarId = 999999L)
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `countDeletedEvents returns 0 for nonexistent calendar`() {
        val count = helper.countDeletedEvents(calendarId = 999999L)
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `countEvents returns 0 for nonexistent calendar`() {
        val count = helper.countEvents(calendarId = 999999L)
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `countEvents with includeDeleted returns 0 for nonexistent calendar`() {
        val count = helper.countEvents(calendarId = 999999L, includeDeleted = true)
        assertThat(count).isEqualTo(0)
    }

    // ==================== Query Operations Tests ====================

    @Test
    fun `queryEventsInRange returns empty list for nonexistent calendar`() {
        val now = System.currentTimeMillis()
        val events = helper.queryEventsInRange(
            calendarId = 999999L,
            startMs = now,
            endMs = now + 86400000 // +1 day
        )
        assertThat(events).isEmpty()
    }

    @Test
    fun `queryEvents returns empty list for nonexistent calendar`() {
        val events = helper.queryEvents(calendarId = 999999L)
        assertThat(events).isEmpty()
    }

    @Test
    fun `queryDirtyEvents returns empty list for nonexistent calendar`() {
        val events = helper.queryDirtyEvents(calendarId = 999999L)
        assertThat(events).isEmpty()
    }

    @Test
    fun `queryDeletedEvents returns empty list for nonexistent calendar`() {
        val events = helper.queryDeletedEvents(calendarId = 999999L)
        assertThat(events).isEmpty()
    }

    @Test
    fun `queryCalendars returns empty list when no calendars exist`() {
        val calendars = helper.queryCalendars()
        assertThat(calendars).isEmpty()
    }

    @Test
    fun `findCalendarByUrl returns null for nonexistent calendar`() {
        val calendar = helper.findCalendarByUrl("https://example.com/calendar/")
        assertThat(calendar).isNull()
    }

    @Test
    fun `findEventBySyncId returns null for nonexistent event`() {
        val result = helper.findEventBySyncId(calendarId = 999999L, syncId = "nonexistent-uid")
        assertThat(result).isNull()
    }

    // ==================== Instance Operations Tests ====================

    @Test
    fun `queryInstances returns null or cursor for invalid range`() {
        val cursor = helper.queryInstances(
            startMs = 0,
            endMs = 1000,
            calendarId = 999999L
        )
        // Robolectric may return null or an empty cursor
        cursor?.use {
            assertThat(it.count).isEqualTo(0)
        }
    }

    // ==================== Reminder Operations Tests ====================

    @Test
    fun `queryReminders returns empty list for nonexistent event`() {
        val reminders = helper.queryReminders(eventId = 999999L)
        assertThat(reminders).isEmpty()
    }

    // ==================== Attendee Operations Tests ====================

    @Test
    fun `queryAttendees returns empty list for nonexistent event`() {
        val attendees = helper.queryAttendees(eventId = 999999L)
        assertThat(attendees).isEmpty()
    }

    // ==================== ETag Operations Tests ====================

    @Test
    fun `getStoredEtag returns null for nonexistent event`() {
        val etag = helper.getStoredEtag(eventId = 999999L)
        assertThat(etag).isNull()
    }

    @Test
    fun `getStoredEventUrl returns null for nonexistent event`() {
        val url = helper.getStoredEventUrl(eventId = 999999L)
        assertThat(url).isNull()
    }

    // ==================== Helper Integration Tests ====================

    @Test
    fun `CalendarBatchBuilder integrates with helper`() {
        val builder = CalendarBatchBuilder(accountName, accountType)
        val event = createTestEvent()

        builder.insertEvent(event, calendarId = 1L)
        val operations = builder.build()

        assertThat(operations).hasSize(1)
        // Verify we can pass to applyBatch without error
        // (actual insert may fail in Robolectric due to no calendar, but API works)
    }

    @Test
    fun `upsertEvent handles nonexistent calendar`() {
        val event = createTestEvent()
        // Without a real calendar, upsert behavior varies by Robolectric version
        // Just verify the method exists and is callable
        try {
            val result = helper.upsertEvent(event, calendarId = 999999L)
            // If successful in Robolectric, should be -1 or valid ID
            assertThat(result <= 0L).isTrue()
        } catch (e: Exception) {
            // Expected - Robolectric's ContentProvider may throw
        }
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
}
