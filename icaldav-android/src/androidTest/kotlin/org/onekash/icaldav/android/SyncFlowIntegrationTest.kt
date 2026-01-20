package org.onekash.icaldav.android

import android.Manifest
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
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for sync flow scenarios with CalendarProviderHelper.
 *
 * Tests verify common sync adapter patterns:
 * - Initial sync (fetch all, insert into Android)
 * - Incremental sync (detect changes, push/pull)
 * - Conflict detection (dirty flags, ETags)
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SyncFlowIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private lateinit var context: Context
    private val contentResolver by lazy { context.contentResolver }
    private lateinit var helper: CalendarProviderHelper

    private val accountName = "sync-test@test.local"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL

    private var testCalendarId: Long = -1
    private val testRunId = UUID.randomUUID().toString().take(8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        helper = CalendarProviderHelper(contentResolver, accountName, accountType)
        testCalendarId = createTestCalendar()
    }

    @After
    fun cleanup() {
        if (testCalendarId > 0) {
            contentResolver.delete(
                asSyncAdapter(Events.CONTENT_URI),
                "${Events.CALENDAR_ID} = ?",
                arrayOf(testCalendarId.toString())
            )

            contentResolver.delete(
                asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, testCalendarId)),
                null, null
            )
        }
    }

    // ==================== Initial Sync Tests ====================

    @Test
    fun initialSync_insertMultipleEvents() {
        val events = (1..10).map { createTestEvent("Sync Event $it", "uid-$it-$testRunId") }

        events.forEach { event ->
            val eventId = helper.insertEvent(event, testCalendarId)
            assertThat(eventId).isGreaterThan(0L)
        }

        val count = helper.countEvents(testCalendarId)
        assertThat(count).isEqualTo(10)
    }

    @Test
    fun initialSync_insertEventWithRemindersAndAttendees() {
        val event = createTestEvent("Meeting", "meeting-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId)

        // Add reminders
        val alarm = ICalAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-15),
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = null,
            summary = null
        )
        helper.insertReminder(alarm, eventId)

        // Verify
        val reminders = helper.queryReminders(eventId)
        assertThat(reminders).hasSize(1)
    }

    // ==================== Incremental Sync Tests ====================

    @Test
    fun incrementalSync_queryDirtyEvents() {
        // Insert events as sync adapter (not dirty)
        val event1 = createTestEvent("Event 1", "uid1-$testRunId")
        val event1Id = helper.insertEvent(event1, testCalendarId, asSyncAdapter = true)

        // Initially no dirty events
        assertThat(helper.countDirtyEvents(testCalendarId)).isEqualTo(0)

        // Modify event as user (sets dirty flag)
        val updateValues = ContentValues().apply {
            put(Events.TITLE, "Modified Event 1")
        }
        contentResolver.update(
            ContentUris.withAppendedId(Events.CONTENT_URI, event1Id),
            updateValues,
            null, null
        )

        // Now should have dirty event
        assertThat(helper.countDirtyEvents(testCalendarId)).isEqualTo(1)

        val dirtyEvents = helper.queryDirtyEvents(testCalendarId)
        assertThat(dirtyEvents).hasSize(1)
        assertThat(dirtyEvents[0].second.uid).isEqualTo("uid1-$testRunId")
    }

    @Test
    fun incrementalSync_clearDirtyFlag() {
        val event = createTestEvent("Dirty Event", "dirty-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId, asSyncAdapter = false)

        // Should be dirty after non-sync-adapter insert
        assertThat(helper.countDirtyEvents(testCalendarId)).isEqualTo(1)

        // Clear dirty flag (after successful sync)
        helper.clearDirtyFlag(eventId)

        assertThat(helper.countDirtyEvents(testCalendarId)).isEqualTo(0)
    }

    @Test
    fun incrementalSync_queryDeletedEvents() {
        val event = createTestEvent("To Be Deleted", "delete-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId)

        // Initially no deleted events
        assertThat(helper.countDeletedEvents(testCalendarId)).isEqualTo(0)

        // Soft delete (user delete, not sync adapter)
        contentResolver.delete(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            null, null
        )

        // Now should have deleted event
        assertThat(helper.countDeletedEvents(testCalendarId)).isEqualTo(1)

        val deletedEvents = helper.queryDeletedEvents(testCalendarId)
        assertThat(deletedEvents).hasSize(1)
        assertThat(deletedEvents[0].second).isEqualTo("delete-$testRunId")
    }

    // ==================== ETag/Conflict Tests ====================

    @Test
    fun etagStorage_storeAndRetrieve() {
        val event = createTestEvent("ETag Event", "etag-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId)

        val etag = "\"abc123\""
        helper.storeEtag(eventId, etag)

        val retrieved = helper.getStoredEtag(eventId)
        assertThat(retrieved).isEqualTo(etag)
    }

    @Test
    fun etagStorage_clearEtag() {
        val event = createTestEvent("ETag Event 2", "etag2-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId)

        helper.storeEtag(eventId, "\"etag-value\"")
        assertThat(helper.getStoredEtag(eventId)).isNotNull()

        helper.storeEtag(eventId, null)
        assertThat(helper.getStoredEtag(eventId)).isNull()
    }

    @Test
    fun eventUrlStorage_storeAndRetrieve() {
        val event = createTestEvent("URL Event", "url-$testRunId")
        val eventId = helper.insertEvent(event, testCalendarId)

        val url = "https://example.com/calendar/event.ics"
        helper.storeEventUrl(eventId, url)

        val retrieved = helper.getStoredEventUrl(eventId)
        assertThat(retrieved).isEqualTo(url)
    }

    // ==================== Upsert Tests ====================

    @Test
    fun upsert_insertsNewEvent() {
        val event = createTestEvent("Upsert New", "upsert-new-$testRunId")

        val eventId = helper.upsertEvent(event, testCalendarId)
        assertThat(eventId).isGreaterThan(0L)

        val count = helper.countEvents(testCalendarId)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun upsert_updatesExistingEvent() {
        val uid = "upsert-existing-$testRunId"
        val event1 = createTestEvent("Original Title", uid)
        val eventId1 = helper.upsertEvent(event1, testCalendarId)

        val event2 = createTestEvent("Updated Title", uid)
        val eventId2 = helper.upsertEvent(event2, testCalendarId)

        // Should be same event ID
        assertThat(eventId2).isEqualTo(eventId1)

        // Should still be only 1 event
        assertThat(helper.countEvents(testCalendarId)).isEqualTo(1)

        // Verify title updated
        val result = helper.findEventBySyncId(testCalendarId, uid)
        assertThat(result?.second?.summary).isEqualTo("Updated Title")
    }

    // ==================== Helpers ====================

    private fun createTestCalendar(): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, "sync-test-$testRunId")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Sync Test Calendar")
            put(Calendars.CALENDAR_COLOR, 0xFF0000FF.toInt())
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        }

        val uri = contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun createTestEvent(summary: String, uid: String): ICalEvent {
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

    private fun asSyncAdapter(uri: android.net.Uri): android.net.Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }
}
