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
 * Unit tests for ACCESS_LEVEL ↔ CLASS property mapping in [CalendarContractMapper].
 *
 * Tests verify round-trip fidelity between iCal CLASS property (RFC 5545)
 * and Android CalendarContract ACCESS_LEVEL column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AccessLevelMappingTest {

    // ==================== Classification → ACCESS_LEVEL Tests ====================

    @Test
    fun `CLASS PUBLIC maps to ACCESS_PUBLIC`() {
        val event = createTestEvent(classification = Classification.PUBLIC)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_PUBLIC)
    }

    @Test
    fun `CLASS PRIVATE maps to ACCESS_PRIVATE`() {
        val event = createTestEvent(classification = Classification.PRIVATE)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_PRIVATE)
    }

    @Test
    fun `CLASS CONFIDENTIAL maps to ACCESS_CONFIDENTIAL`() {
        val event = createTestEvent(classification = Classification.CONFIDENTIAL)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_CONFIDENTIAL)
    }

    @Test
    fun `missing CLASS maps to ACCESS_DEFAULT`() {
        val event = createTestEvent(classification = null)
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_DEFAULT)
    }

    // ==================== ACCESS_LEVEL → Classification Tests ====================

    @Test
    fun `ACCESS_PUBLIC maps to Classification PUBLIC`() {
        val result = CalendarContractMapper.mapClassification(Events.ACCESS_PUBLIC)
        assertThat(result).isEqualTo(Classification.PUBLIC)
    }

    @Test
    fun `ACCESS_PRIVATE maps to Classification PRIVATE`() {
        val result = CalendarContractMapper.mapClassification(Events.ACCESS_PRIVATE)
        assertThat(result).isEqualTo(Classification.PRIVATE)
    }

    @Test
    fun `ACCESS_CONFIDENTIAL maps to Classification CONFIDENTIAL`() {
        val result = CalendarContractMapper.mapClassification(Events.ACCESS_CONFIDENTIAL)
        assertThat(result).isEqualTo(Classification.CONFIDENTIAL)
    }

    @Test
    fun `ACCESS_DEFAULT maps to null Classification`() {
        val result = CalendarContractMapper.mapClassification(Events.ACCESS_DEFAULT)
        assertThat(result).isNull()
    }

    // ==================== Helpers ====================

    private fun createTestEvent(
        uid: String = UUID.randomUUID().toString(),
        classification: Classification? = null
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
            rdates = emptyList(),
            classification = classification,
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
