package org.onekash.icaldav.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.ZoneId

class ICalDateTimeTest {

    @Test
    fun `parse UTC datetime`() {
        val dt = ICalDateTime.parse("20231215T140000Z")

        assertTrue(dt.isUtc)
        assertFalse(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(2023, zdt.year)
        assertEquals(12, zdt.monthValue)
        assertEquals(15, zdt.dayOfMonth)
        assertEquals(14, zdt.hour)
        assertEquals(0, zdt.minute)
    }

    @Test
    fun `parse local datetime with timezone`() {
        val dt = ICalDateTime.parse("20231215T090000", "America/New_York")

        assertEquals(ZoneId.of("America/New_York"), dt.timezone)
        assertFalse(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(9, zdt.hour)
    }

    @Test
    fun `parse all-day date`() {
        val dt = ICalDateTime.parse("20231215")

        assertTrue(dt.isDate)

        val zdt = dt.toZonedDateTime()
        assertEquals(2023, zdt.year)
        assertEquals(12, zdt.monthValue)
        assertEquals(15, zdt.dayOfMonth)
        assertEquals(0, zdt.hour)  // All-day starts at midnight
    }

    @Test
    fun `parse floating datetime`() {
        val dt = ICalDateTime.parse("20231215T140000")

        assertNotNull(dt.timezone)  // Uses system default
        assertFalse(dt.isDate)
        assertFalse(dt.isUtc)
    }

    @Test
    fun `toDayCode returns correct format`() {
        val dt = ICalDateTime.parse("20231215T140000Z")
        assertEquals("20231215", dt.toDayCode())

        val dt2 = ICalDateTime.parse("20240101")
        assertEquals("20240101", dt2.toDayCode())
    }

    @Test
    fun `toICalString for UTC datetime`() {
        val dt = ICalDateTime.parse("20231215T140000Z")
        assertEquals("20231215T140000Z", dt.toICalString())
    }

    @Test
    fun `toICalString for all-day date`() {
        val dt = ICalDateTime.parse("20231215")
        assertEquals("20231215", dt.toICalString())
    }

    @Test
    fun `fromZonedDateTime creates correct instance`() {
        val zdt = java.time.ZonedDateTime.of(2023, 12, 15, 14, 30, 0, 0, ZoneId.of("UTC"))
        val dt = ICalDateTime.fromZonedDateTime(zdt, false)

        assertEquals(ZoneId.of("UTC"), dt.timezone)
        assertFalse(dt.isDate)

        val roundTripped = dt.toZonedDateTime()
        assertEquals(zdt.toInstant().toEpochMilli(), roundTripped.toInstant().toEpochMilli())
    }

    @Test
    fun `fromZonedDateTime for all-day event`() {
        val zdt = java.time.ZonedDateTime.of(2023, 12, 15, 0, 0, 0, 0, ZoneId.systemDefault())
        val dt = ICalDateTime.fromZonedDateTime(zdt, true)

        assertTrue(dt.isDate)
        assertEquals("20231215", dt.toICalString())
    }

    @Test
    fun `timezone conversion preserves instant`() {
        // Parse UTC time
        val dt = ICalDateTime.parse("20231215T140000Z")
        val utcInstant = dt.timestamp

        // Create same instant in different timezone (should be same underlying time)
        val nyZdt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(utcInstant),
            ZoneId.of("America/New_York")
        )
        val dtNY = ICalDateTime.fromZonedDateTime(nyZdt, false)

        // Timestamps should be equal
        assertEquals(dt.timestamp, dtNY.timestamp)
    }
}
