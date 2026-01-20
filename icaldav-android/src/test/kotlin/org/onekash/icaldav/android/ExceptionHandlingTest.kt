package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.Events
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for exception handling in [CalendarContractMapper] and [CalendarBatchBuilder].
 *
 * Tests verify RECURRENCE-ID mapping, ORIGINAL_SYNC_ID handling, and batch operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ExceptionHandlingTest {

    // ==================== ContentValues Mapping ====================

    @Test
    fun `exception event has ORIGINAL_SYNC_ID set to master UID`() {
        val masterUid = "master-uid-123"
        val recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC)

        val exception = createExceptionEvent(
            uid = masterUid,
            recurrenceId = recurrenceId,
            summary = "Modified Meeting"
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsString(Events.ORIGINAL_SYNC_ID)).isEqualTo(masterUid)
    }

    @Test
    fun `exception event has ORIGINAL_INSTANCE_TIME set`() {
        val originalTime = 1704067200000L
        val recurrenceId = ICalDateTime.fromTimestamp(originalTime, ZoneOffset.UTC)

        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = recurrenceId
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsLong(Events.ORIGINAL_INSTANCE_TIME)).isEqualTo(originalTime)
    }

    @Test
    fun `exception event for all-day has ORIGINAL_ALL_DAY set to 1`() {
        val recurrenceId = ICalDateTime(
            timestamp = 1704067200000L,
            timezone = ZoneOffset.UTC,
            isUtc = true,
            isDate = true
        )

        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = recurrenceId
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsInteger(Events.ORIGINAL_ALL_DAY)).isEqualTo(1)
    }

    @Test
    fun `exception event for timed event has ORIGINAL_ALL_DAY set to 0`() {
        val recurrenceId = ICalDateTime(
            timestamp = 1704067200000L,
            timezone = ZoneOffset.UTC,
            isUtc = true,
            isDate = false
        )

        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = recurrenceId
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsInteger(Events.ORIGINAL_ALL_DAY)).isEqualTo(0)
    }

    @Test
    fun `non-exception event has no ORIGINAL fields`() {
        val normalEvent = createTestEvent(
            uid = "normal-uid",
            summary = "Normal Event"
        )

        val values = CalendarContractMapper.toContentValues(normalEvent, calendarId = 1L)

        assertThat(values.containsKey(Events.ORIGINAL_SYNC_ID)).isFalse()
        assertThat(values.containsKey(Events.ORIGINAL_INSTANCE_TIME)).isFalse()
    }

    // ==================== CalendarBatchBuilder Exception Tests ====================

    @Test
    fun `insertException adds operation with correct values`() {
        val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")
        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC),
            summary = "Modified"
        )

        builder.insertException(exception, calendarId = 1L)

        assertThat(builder.operationCount).isEqualTo(1)
    }

    @Test
    fun `insertException requires recurrenceId`() {
        val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")
        val eventWithoutRecurrenceId = createTestEvent(
            uid = "test-uid",
            summary = "Normal Event"
        )

        var exceptionThrown = false
        try {
            builder.insertException(eventWithoutRecurrenceId, calendarId = 1L)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
            assertThat(e.message).contains("recurrenceId")
        }

        assertThat(exceptionThrown).isTrue()
    }

    @Test
    fun `deleteExceptionsForMaster adds delete operation`() {
        val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")

        builder.deleteExceptionsForMaster("master-uid-123")

        assertThat(builder.operationCount).isEqualTo(1)
    }

    @Test
    fun `deleteExceptionByRecurrenceId adds delete operation`() {
        val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")

        builder.deleteExceptionByRecurrenceId("master-uid-123", 1704067200000L)

        assertThat(builder.operationCount).isEqualTo(1)
    }

    @Test
    fun `chaining multiple exception operations works`() {
        val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")

        val exception1 = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC),
            summary = "Modified 1"
        )
        val exception2 = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = ICalDateTime.fromTimestamp(1704153600000L, ZoneOffset.UTC),
            summary = "Modified 2"
        )

        builder
            .insertException(exception1, calendarId = 1L)
            .insertException(exception2, calendarId = 1L)
            .deleteExceptionsForMaster("old-master-uid")

        assertThat(builder.operationCount).isEqualTo(3)
    }

    // ==================== Exception Event Characteristics ====================

    @Test
    fun `exception inherits UID from master`() {
        val masterUid = "shared-uid-123"

        val exception = createExceptionEvent(
            uid = masterUid,
            recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC)
        )

        assertThat(exception.uid).isEqualTo(masterUid)
    }

    @Test
    fun `exception can have different summary than master`() {
        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC),
            summary = "Rescheduled Meeting"
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsString(Events.TITLE)).isEqualTo("Rescheduled Meeting")
    }

    @Test
    fun `exception can have different location than master`() {
        val exception = createExceptionEvent(
            uid = "master-uid",
            recurrenceId = ICalDateTime.fromTimestamp(1704067200000L, ZoneOffset.UTC),
            location = "Room B"
        )

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        assertThat(values.getAsString(Events.EVENT_LOCATION)).isEqualTo("Room B")
    }

    @Test
    fun `exception can have different time than original occurrence`() {
        // Original occurrence was at 10:00 AM
        val originalTime = 1704067200000L // 2024-01-01 00:00:00 UTC

        // Exception is rescheduled to 2:00 PM (4 hours later)
        val newStartTime = originalTime + (4 * 3600 * 1000)

        val exception = ICalEvent(
            uid = "master-uid",
            importId = ICalEvent.generateImportId("master-uid", ICalDateTime.fromTimestamp(originalTime, ZoneOffset.UTC)),
            summary = "Rescheduled Meeting",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(newStartTime, ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(newStartTime + 3600000, ZoneOffset.UTC),
            duration = null,
            isAllDay = false,
            recurrenceId = ICalDateTime.fromTimestamp(originalTime, ZoneOffset.UTC),
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

        val values = CalendarContractMapper.toContentValues(exception, calendarId = 1L)

        // DTSTART should be the new time
        assertThat(values.getAsLong(Events.DTSTART)).isEqualTo(newStartTime)
        // ORIGINAL_INSTANCE_TIME should be the original occurrence time
        assertThat(values.getAsLong(Events.ORIGINAL_INSTANCE_TIME)).isEqualTo(originalTime)
    }

    // ==================== Helpers ====================

    private fun createExceptionEvent(
        uid: String,
        recurrenceId: ICalDateTime,
        summary: String = "Exception Event",
        location: String? = null
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = ICalEvent.generateImportId(uid, recurrenceId),
            summary = summary,
            description = null,
            location = location,
            dtStart = ICalDateTime.fromTimestamp(recurrenceId.timestamp, ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(recurrenceId.timestamp + 3600000, ZoneOffset.UTC),
            duration = null,
            isAllDay = false,
            recurrenceId = recurrenceId,
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
    }

    private fun createTestEvent(
        uid: String = UUID.randomUUID().toString(),
        summary: String? = "Test Event"
    ): ICalEvent {
        return ICalEvent(
            uid = uid,
            importId = ICalEvent.generateImportId(uid, null),
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(System.currentTimeMillis() + 3600000, ZoneOffset.UTC),
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
    }
}
