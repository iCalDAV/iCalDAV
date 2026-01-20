package org.onekash.icaldav.android

import android.database.MatrixCursor
import android.os.Build
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Instances
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.PartStat
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [InstancesMapper].
 *
 * Tests verify cursor parsing, URI building, and EventInstance data class behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class InstancesMapperTest {

    // ==================== URI Building ====================

    @Test
    fun `buildInstancesUri creates correct URI with time range`() {
        val startMs = 1704067200000L // 2024-01-01 00:00:00 UTC
        val endMs = 1704153600000L   // 2024-01-02 00:00:00 UTC

        val uri = InstancesMapper.buildInstancesUri(startMs, endMs)

        assertThat(uri.toString()).contains(startMs.toString())
        assertThat(uri.toString()).contains(endMs.toString())
        assertThat(uri.authority).isEqualTo("com.android.calendar")
    }

    @Test
    fun `buildSearchUri includes search query in path`() {
        val startMs = 1704067200000L
        val endMs = 1704153600000L
        val searchQuery = "meeting"

        val uri = InstancesMapper.buildSearchUri(startMs, endMs, searchQuery)

        assertThat(uri.toString()).contains(startMs.toString())
        assertThat(uri.toString()).contains(endMs.toString())
        assertThat(uri.toString()).contains(searchQuery)
    }

    @Test
    fun `buildSearchUri handles special characters in search query`() {
        val uri = InstancesMapper.buildSearchUri(1000L, 2000L, "team & project")

        // URI should properly encode the search term
        assertThat(uri.pathSegments).isNotEmpty()
    }

    // ==================== Cursor Parsing ====================

    @Test
    fun `fromCursor parses all fields correctly`() {
        val cursor = createTestCursor(
            instanceId = 100L,
            eventId = 1L,
            calendarId = 2L,
            begin = 1704067200000L,
            end = 1704070800000L,
            title = "Team Meeting",
            location = "Room A",
            allDay = false,
            status = Instances.STATUS_CONFIRMED,
            selfAttendeeStatus = Attendees.ATTENDEE_STATUS_ACCEPTED,
            calendarColor = 0xFF0000FF.toInt(),
            eventColor = 0xFFFF0000.toInt()
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.instanceId).isEqualTo(100L)
        assertThat(instance.eventId).isEqualTo(1L)
        assertThat(instance.calendarId).isEqualTo(2L)
        assertThat(instance.begin).isEqualTo(1704067200000L)
        assertThat(instance.end).isEqualTo(1704070800000L)
        assertThat(instance.title).isEqualTo("Team Meeting")
        assertThat(instance.location).isEqualTo("Room A")
        assertThat(instance.allDay).isFalse()
        assertThat(instance.status).isEqualTo(EventStatus.CONFIRMED)
        assertThat(instance.selfAttendeeStatus).isEqualTo(PartStat.ACCEPTED)
        assertThat(instance.calendarColor).isEqualTo(0xFF0000FF.toInt())
        assertThat(instance.eventColor).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `fromCursor handles null optional fields`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 2L,
            calendarId = 3L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = null,
            calendarColor = null,
            eventColor = null
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.title).isNull()
        assertThat(instance.location).isNull()
        assertThat(instance.status).isNull()
        assertThat(instance.selfAttendeeStatus).isNull()
        assertThat(instance.calendarColor).isNull()
        assertThat(instance.eventColor).isNull()
    }

    @Test
    fun `fromCursor handles all-day event`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1704067200000L,
            end = 1704153600000L,
            allDay = true
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.allDay).isTrue()
    }

    @Test
    fun `fromCursor maps STATUS_TENTATIVE correctly`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            status = Instances.STATUS_TENTATIVE
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.status).isEqualTo(EventStatus.TENTATIVE)
    }

    @Test
    fun `fromCursor maps STATUS_CANCELED correctly`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            status = Instances.STATUS_CANCELED
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.status).isEqualTo(EventStatus.CANCELLED)
    }

    @Test
    fun `fromCursor maps ATTENDEE_STATUS_DECLINED correctly`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            selfAttendeeStatus = Attendees.ATTENDEE_STATUS_DECLINED
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.selfAttendeeStatus).isEqualTo(PartStat.DECLINED)
    }

    @Test
    fun `fromCursor maps ATTENDEE_STATUS_TENTATIVE correctly`() {
        val cursor = createTestCursor(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            selfAttendeeStatus = Attendees.ATTENDEE_STATUS_TENTATIVE
        )
        cursor.moveToFirst()

        val instance = InstancesMapper.fromCursor(cursor)

        assertThat(instance.selfAttendeeStatus).isEqualTo(PartStat.TENTATIVE)
    }

    // ==================== EventInstance Data Class ====================

    @Test
    fun `EventInstance durationMs calculated correctly`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 4600L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = null
        )

        assertThat(instance.durationMs).isEqualTo(3600L)
    }

    @Test
    fun `EventInstance displayColor prefers eventColor over calendarColor`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = null,
            calendarColor = 0xFF0000FF.toInt(),
            eventColor = 0xFFFF0000.toInt()
        )

        assertThat(instance.displayColor).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `EventInstance displayColor falls back to calendarColor when eventColor is null`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = null,
            calendarColor = 0xFF0000FF.toInt(),
            eventColor = null
        )

        assertThat(instance.displayColor).isEqualTo(0xFF0000FF.toInt())
    }

    @Test
    fun `EventInstance hasResponded true when accepted`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = PartStat.ACCEPTED
        )

        assertThat(instance.hasResponded).isTrue()
    }

    @Test
    fun `EventInstance hasResponded false when NEEDS_ACTION`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = PartStat.NEEDS_ACTION
        )

        assertThat(instance.hasResponded).isFalse()
    }

    @Test
    fun `EventInstance hasResponded false when null`() {
        val instance = EventInstance(
            instanceId = 1L,
            eventId = 1L,
            calendarId = 1L,
            begin = 1000L,
            end = 2000L,
            title = null,
            location = null,
            allDay = false,
            status = null,
            selfAttendeeStatus = null
        )

        assertThat(instance.hasResponded).isFalse()
    }

    // ==================== Helpers ====================

    private fun createTestCursor(
        instanceId: Long,
        eventId: Long,
        calendarId: Long,
        begin: Long,
        end: Long,
        title: String? = "Test Event",
        location: String? = null,
        allDay: Boolean = false,
        status: Int? = Instances.STATUS_CONFIRMED,
        selfAttendeeStatus: Int? = null,
        calendarColor: Int? = null,
        eventColor: Int? = null
    ): MatrixCursor {
        val cursor = MatrixCursor(InstancesMapper.PROJECTION)
        cursor.addRow(
            arrayOf(
                instanceId,
                eventId,
                begin,
                end,
                title,
                if (allDay) 1 else 0,
                calendarId,
                location,
                status,
                selfAttendeeStatus,
                calendarColor,
                eventColor
            )
        )
        return cursor
    }
}
