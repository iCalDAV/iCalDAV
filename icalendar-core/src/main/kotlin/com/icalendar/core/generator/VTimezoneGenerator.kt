package com.icalendar.core.generator

import com.icalendar.core.model.ICalDateTime
import com.icalendar.core.model.ICalEvent
import java.time.*
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale

/**
 * Generates RFC 5545 compliant VTIMEZONE components.
 *
 * This generator creates VTIMEZONE definitions for timezone IDs found in events,
 * enabling interoperability with calendar clients that don't recognize IANA timezone IDs.
 *
 * The generated VTIMEZONEs include:
 * - STANDARD component for non-DST periods (or fixed-offset timezones)
 * - DAYLIGHT component for DST periods (if applicable)
 * - RRULE for recurring transitions
 *
 * Implementation notes:
 * - UTC timezones are skipped (no VTIMEZONE needed)
 * - Invalid timezone IDs return empty string
 * - Uses RRULE-based recurring transitions for DST rules
 */
class VTimezoneGenerator {

    /**
     * Generate VTIMEZONE component for a single timezone ID.
     *
     * @param tzid The timezone ID (e.g., "America/New_York")
     * @return VTIMEZONE component string, or empty if invalid/UTC
     */
    fun generate(tzid: String): String {
        // Skip UTC - no VTIMEZONE needed
        if (tzid == "UTC" || tzid == "Z" || tzid == "Etc/UTC" || tzid == "GMT") {
            return ""
        }

        return buildString {
            appendTimezone(this, tzid)
        }
    }

    /**
     * Generate VTIMEZONE components for multiple timezone IDs.
     *
     * @param tzids Set of timezone IDs to generate
     * @return Concatenated VTIMEZONE components
     */
    fun generate(tzids: Set<String>): String {
        return buildString {
            tzids.forEach { tzid ->
                append(generate(tzid))
            }
        }
    }

    /**
     * Collect unique timezone IDs from a list of events.
     *
     * Extracts TZIDs from DTSTART, DTEND, and other datetime properties.
     * Excludes UTC timezones as they don't require VTIMEZONE.
     *
     * @param events List of events to scan
     * @return Set of unique non-UTC timezone IDs
     */
    fun collectTimezones(events: List<ICalEvent>): Set<String> {
        val tzids = mutableSetOf<String>()

        for (event in events) {
            collectFromDateTime(event.dtStart, tzids)
            event.dtEnd?.let { collectFromDateTime(it, tzids) }
            event.recurrenceId?.let { collectFromDateTime(it, tzids) }
            event.exdates.forEach { collectFromDateTime(it, tzids) }
        }

        return tzids
    }

    /**
     * Extract timezone ID from a datetime and add to set if not UTC.
     */
    private fun collectFromDateTime(dt: ICalDateTime, tzids: MutableSet<String>) {
        if (!dt.isUtc && !dt.isDate && dt.timezone != null) {
            val tzid = dt.timezone.id
            if (tzid != "UTC" && tzid != "Z" && tzid != "Etc/UTC" && tzid != "GMT") {
                tzids.add(tzid)
            }
        }
    }

    /**
     * Append VTIMEZONE component for a timezone ID.
     */
    private fun appendTimezone(builder: StringBuilder, tzid: String) {
        try {
            val zoneId = ZoneId.of(tzid)
            val rules = zoneId.rules

            builder.appendLine("BEGIN:VTIMEZONE")
            builder.appendLine("TZID:$tzid")

            // Get transition rules for repeating DST patterns
            val transitionRules = rules.transitionRules

            if (transitionRules.isEmpty()) {
                // No DST - single STANDARD component with fixed offset
                val offset = rules.getOffset(Instant.now())
                appendFixedTimezoneComponent(builder, offset, tzid)
            } else {
                // Has DST - generate STANDARD and DAYLIGHT components from rules
                for (rule in transitionRules) {
                    appendTimezoneComponent(builder, rule, zoneId)
                }
            }

            builder.appendLine("END:VTIMEZONE")
        } catch (e: Exception) {
            // Skip invalid timezone IDs - return empty content
        }
    }

    /**
     * Append a fixed-offset timezone component (no DST).
     */
    private fun appendFixedTimezoneComponent(builder: StringBuilder, offset: ZoneOffset, tzid: String) {
        val offsetStr = formatOffset(offset)
        val abbrev = tzid.substringAfterLast("/").take(4).uppercase()

        builder.appendLine("BEGIN:STANDARD")
        builder.appendLine("DTSTART:19700101T000000")
        builder.appendLine("TZOFFSETFROM:$offsetStr")
        builder.appendLine("TZOFFSETTO:$offsetStr")
        builder.appendLine("TZNAME:$abbrev")
        builder.appendLine("END:STANDARD")
    }

    /**
     * Append a STANDARD or DAYLIGHT component from a transition rule.
     */
    private fun appendTimezoneComponent(builder: StringBuilder, rule: ZoneOffsetTransitionRule, zoneId: ZoneId) {
        // Determine if transitioning TO daylight time (clocks spring forward)
        // Use totalSeconds because ZoneOffset comparison is non-intuitive (-05:00 < -06:00)
        val isDst = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
        val componentType = if (isDst) "DAYLIGHT" else "STANDARD"

        builder.appendLine("BEGIN:$componentType")

        // DTSTART: Use 1970 as base year per common practice
        val month = rule.month.value
        val time = rule.localTime

        // Format DTSTART as YYYYMMDDTHHMMSS
        val dtstart = String.format(
            "1970%02d%02dT%02d%02d%02d",
            month,
            calculateDtstartDay(rule),
            time.hour,
            time.minute,
            time.second
        )
        builder.appendLine("DTSTART:$dtstart")

        // RRULE for recurring transition
        val rrule = buildRrule(rule)
        builder.appendLine("RRULE:$rrule")

        // Offsets
        builder.appendLine("TZOFFSETFROM:${formatOffset(rule.offsetBefore)}")
        builder.appendLine("TZOFFSETTO:${formatOffset(rule.offsetAfter)}")

        // Timezone abbreviation - use standard Java API to get proper name
        val abbrev = getTimezoneAbbreviation(zoneId, rule.offsetAfter, isDst)
        builder.appendLine("TZNAME:$abbrev")

        builder.appendLine("END:$componentType")
    }

    /**
     * Get timezone abbreviation using standard Java time API.
     * Falls back to offset-based format if unavailable.
     */
    private fun getTimezoneAbbreviation(zoneId: ZoneId, offset: ZoneOffset, isDst: Boolean): String {
        return try {
            // Create a sample instant in the target offset period to get correct abbreviation
            // Use a date in the middle of summer (July) for DST, winter (January) for standard
            val sampleYear = 2024
            val sampleMonth = if (isDst) 7 else 1
            val sampleInstant = LocalDateTime.of(sampleYear, sampleMonth, 15, 12, 0)
                .toInstant(offset)
            val zdt = sampleInstant.atZone(zoneId)

            // Use DateTimeFormatter to get proper abbreviation (e.g., "CST", "CDT", "JST")
            val formatter = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US)
            zdt.format(formatter)
        } catch (e: Exception) {
            // Fallback to offset string format
            formatOffset(offset)
        }
    }

    /**
     * Calculate DTSTART day for a transition rule.
     * Returns a day in 1970 that matches the rule pattern.
     */
    private fun calculateDtstartDay(rule: ZoneOffsetTransitionRule): Int {
        val dayOfMonthIndicator = rule.dayOfMonthIndicator
        val dayOfWeek = rule.dayOfWeek

        return if (dayOfWeek == null) {
            // Fixed day of month
            if (dayOfMonthIndicator > 0) dayOfMonthIndicator else 28 + dayOfMonthIndicator
        } else {
            // Day of week in month (e.g., 2nd Sunday)
            // For DTSTART, we just need a valid date - RRULE handles the pattern
            when {
                dayOfMonthIndicator > 0 -> dayOfMonthIndicator.coerceAtMost(28)
                dayOfMonthIndicator < 0 -> 28 + dayOfMonthIndicator
                else -> 1
            }
        }
    }

    /**
     * Build RRULE string for a transition rule.
     */
    private fun buildRrule(rule: ZoneOffsetTransitionRule): String {
        val parts = mutableListOf("FREQ=YEARLY")
        parts.add("BYMONTH=${rule.month.value}")

        val dayOfWeek = rule.dayOfWeek
        val dayOfMonthIndicator = rule.dayOfMonthIndicator

        if (dayOfWeek != null) {
            val weekNum = when {
                dayOfMonthIndicator >= 8 && dayOfMonthIndicator <= 14 -> 2
                dayOfMonthIndicator >= 15 && dayOfMonthIndicator <= 21 -> 3
                dayOfMonthIndicator >= 22 && dayOfMonthIndicator <= 28 -> 4
                dayOfMonthIndicator < 0 -> -1  // Last occurrence
                else -> 1
            }
            val dayAbbrev = dayOfWeekToIcal(dayOfWeek)
            parts.add("BYDAY=$weekNum$dayAbbrev")
        } else {
            parts.add("BYMONTHDAY=$dayOfMonthIndicator")
        }

        return parts.joinToString(";")
    }

    /**
     * Convert DayOfWeek to iCal abbreviation.
     */
    private fun dayOfWeekToIcal(dow: DayOfWeek): String {
        return when (dow) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }
    }

    /**
     * Format ZoneOffset as iCal offset string (e.g., "-0500", "+0900", "+0530").
     */
    fun formatOffset(offset: ZoneOffset): String {
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return String.format("%s%02d%02d", sign, hours, minutes)
    }
}
