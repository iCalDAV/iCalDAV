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

    // ==================== CLASS → ACCESS_LEVEL Tests ====================

    @Test
    fun `CLASS PUBLIC maps to ACCESS_PUBLIC`() {
        val event = createTestEvent(rawProperties = mapOf("CLASS" to "PUBLIC"))
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_PUBLIC)
    }

    @Test
    fun `CLASS PRIVATE maps to ACCESS_PRIVATE`() {
        val event = createTestEvent(rawProperties = mapOf("CLASS" to "PRIVATE"))
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_PRIVATE)
    }

    @Test
    fun `CLASS CONFIDENTIAL maps to ACCESS_CONFIDENTIAL`() {
        val event = createTestEvent(rawProperties = mapOf("CLASS" to "CONFIDENTIAL"))
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_CONFIDENTIAL)
    }

    @Test
    fun `missing CLASS maps to ACCESS_DEFAULT`() {
        val event = createTestEvent(rawProperties = emptyMap())
        val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_DEFAULT)
    }

    // ==================== ACCESS_LEVEL → CLASS Tests (mapClassificationString) ====================

    @Test
    fun `ACCESS_PUBLIC maps to CLASS PUBLIC`() {
        val result = CalendarContractMapper.mapClassificationString(Events.ACCESS_PUBLIC)
        assertThat(result).isEqualTo("PUBLIC")
    }

    @Test
    fun `ACCESS_PRIVATE maps to CLASS PRIVATE`() {
        val result = CalendarContractMapper.mapClassificationString(Events.ACCESS_PRIVATE)
        assertThat(result).isEqualTo("PRIVATE")
    }

    @Test
    fun `ACCESS_CONFIDENTIAL maps to CLASS CONFIDENTIAL`() {
        val result = CalendarContractMapper.mapClassificationString(Events.ACCESS_CONFIDENTIAL)
        assertThat(result).isEqualTo("CONFIDENTIAL")
    }

    @Test
    fun `ACCESS_DEFAULT maps to null CLASS`() {
        val result = CalendarContractMapper.mapClassificationString(Events.ACCESS_DEFAULT)
        assertThat(result).isNull()
    }

    // ==================== Helpers ====================

    private fun createTestEvent(
        uid: String = UUID.randomUUID().toString(),
        rawProperties: Map<String, String> = emptyMap()
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
            rawProperties = rawProperties
        )
    }
}
