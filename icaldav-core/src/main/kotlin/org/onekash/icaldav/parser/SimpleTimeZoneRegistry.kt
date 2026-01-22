package org.onekash.icaldav.parser

import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import java.time.ZoneId
import java.time.zone.ZoneRules

/**
 * A minimal TimeZoneRegistry implementation that avoids ZoneRulesProvider.
 *
 * ical4j 4.x's TimeZoneRegistryImpl uses ZoneRulesProvider which is not available
 * on Android via desugaring. This simple implementation:
 * - Returns null for getTimeZone() - ical4j will use embedded VTIMEZONE definitions
 * - Uses ZoneId.of() for getZoneId() which is supported on Android
 * - No-ops for register/clear operations
 *
 * This allows iCalendar parsing to work on Android without the full timezone
 * registry infrastructure.
 */
class SimpleTimeZoneRegistry : TimeZoneRegistry {

    /**
     * Returns null - ical4j will handle dates using embedded VTIMEZONE definitions
     * in the iCalendar data or fall back to system timezone handling.
     */
    override fun getTimeZone(id: String?): TimeZone? = null

    /**
     * No-op - we don't maintain a registry of custom timezones.
     */
    override fun register(timezone: TimeZone?) {
        // No-op: We don't store custom timezone definitions
    }

    /**
     * No-op - we don't maintain a registry of custom timezones.
     */
    override fun register(timezone: TimeZone?, update: Boolean) {
        // No-op: We don't store custom timezone definitions
    }

    /**
     * No-op - nothing to clear.
     */
    override fun clear() {
        // No-op: Nothing to clear
    }

    /**
     * Returns empty map - we don't maintain zone rules.
     * Avoids ZoneRulesProvider which is not available on Android.
     */
    override fun getZoneRules(): Map<String, ZoneRules> = emptyMap()

    /**
     * Converts timezone ID string to ZoneId using Java's built-in support.
     * ZoneId.of() is supported on Android via desugaring.
     *
     * Returns null if the timezone ID is invalid or not recognized.
     */
    override fun getZoneId(tzId: String?): ZoneId? {
        if (tzId.isNullOrBlank()) return null
        return try {
            ZoneId.of(tzId)
        } catch (e: Exception) {
            // Try common aliases
            try {
                ZoneId.of(normalizeTimezoneId(tzId))
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Returns the timezone ID string as-is.
     */
    override fun getTzId(zoneId: String?): String? = zoneId

    /**
     * Normalizes common timezone ID variations to standard IANA format.
     */
    private fun normalizeTimezoneId(tzId: String): String {
        // Handle common Windows timezone names
        return when {
            tzId.equals("Pacific Standard Time", ignoreCase = true) -> "America/Los_Angeles"
            tzId.equals("Eastern Standard Time", ignoreCase = true) -> "America/New_York"
            tzId.equals("Central Standard Time", ignoreCase = true) -> "America/Chicago"
            tzId.equals("Mountain Standard Time", ignoreCase = true) -> "America/Denver"
            tzId.equals("GMT", ignoreCase = true) -> "UTC"
            else -> tzId
        }
    }
}
