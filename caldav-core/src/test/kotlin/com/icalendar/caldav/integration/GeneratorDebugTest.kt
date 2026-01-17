package com.icalendar.caldav.integration

import com.icalendar.core.generator.ICalGenerator
import com.icalendar.core.model.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class GeneratorDebugTest {
    @Test
    fun `compare raw vs generated iCal`() {
        val generator = ICalGenerator()

        // Raw iCal (same format as working raw API tests)
        val rawIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:debug-test-uid
            DTSTAMP:20260118T010000Z
            DTSTART:20260119T010000Z
            DTEND:20260119T020000Z
            SUMMARY:Raw Test Event
            STATUS:CONFIRMED
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // Generated iCal
        val startTime = Instant.parse("2026-01-19T01:00:00Z")
        val endTime = Instant.parse("2026-01-19T02:00:00Z")

        val event = ICalEvent(
            uid = "debug-test-uid",
            importId = "debug-test-uid",
            summary = "Generated Test Event",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(startTime.toEpochMilli(), null, false),
            dtEnd = ICalDateTime.fromTimestamp(endTime.toEpochMilli(), null, false),
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
            dtstamp = ICalDateTime.fromTimestamp(Instant.parse("2026-01-18T01:00:00Z").toEpochMilli(), null, false),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )

        val generatedIcal = generator.generate(event)

        println("=== RAW iCal ===")
        println(rawIcal)
        println()
        println("=== GENERATED iCal ===")
        println(generatedIcal)
        println()
        println("=== COMPARISON ===")
        println("Raw length: ${rawIcal.length} chars, ${rawIcal.toByteArray().size} bytes")
        println("Generated length: ${generatedIcal.length} chars, ${generatedIcal.toByteArray().size} bytes")
        println()
        println("Raw first 50 bytes hex:")
        rawIcal.toByteArray().take(50).forEach { print("%02X ".format(it)) }
        println()
        println("Generated first 50 bytes hex:")
        generatedIcal.toByteArray().take(50).forEach { print("%02X ".format(it)) }
        println()

        // Key difference checks
        println()
        println("=== KEY CHECKS ===")
        println("Raw has CALSCALE: ${rawIcal.contains("CALSCALE")}")
        println("Generated has CALSCALE: ${generatedIcal.contains("CALSCALE")}")
        println("Raw has METHOD: ${rawIcal.contains("METHOD")}")
        println("Generated has METHOD: ${generatedIcal.contains("METHOD")}")
    }
}
