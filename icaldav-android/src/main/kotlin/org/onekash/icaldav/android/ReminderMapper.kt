package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Reminders
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import java.time.Duration
import java.time.ZoneOffset
import kotlin.math.absoluteValue

/**
 * Maps between iCalDAV's [ICalAlarm] model and Android's CalendarContract.Reminders table.
 *
 * ## RFC 5545 VALARM Conversion
 *
 * ### Trigger Handling
 * - RFC 5545 uses negative durations for "before" triggers (e.g., -PT15M = 15 min before)
 * - [ICalAlarm.triggerMinutes] returns the signed value (negative for before)
 * - Android's Reminders.MINUTES expects a **positive** value
 * - This mapper uses [kotlin.math.absoluteValue] to convert
 *
 * ### RELATED=END Conversion
 * - RFC 5545 allows triggers relative to event END (TRIGGER;RELATED=END:-PT15M)
 * - Android only supports triggers relative to START
 * - This mapper converts: minutes_from_start = event_duration - minutes_before_end
 *
 * ### Absolute Triggers
 * - RFC 5545 allows absolute triggers (VALUE=DATE-TIME)
 * - Android doesn't support absolute triggers
 * - This mapper converts: minutes = (event_start - alarm_time) / 60000
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Reminders">CalendarContract.Reminders</a>
 */
object ReminderMapper {

    /**
     * Default reminder minutes when trigger cannot be determined.
     */
    const val DEFAULT_MINUTES = 15

    /**
     * Convert an [ICalAlarm] to ContentValues for the Reminders table.
     *
     * @param alarm The iCalDAV alarm to convert
     * @param eventId The Android event ID this reminder belongs to
     * @param eventDuration Optional event duration for RELATED=END conversion
     * @param eventStartMs Optional event start timestamp for absolute trigger conversion
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun toContentValues(
        alarm: ICalAlarm,
        eventId: Long,
        eventDuration: Duration? = null,
        eventStartMs: Long? = null
    ): ContentValues {
        return ContentValues().apply {
            put(Reminders.EVENT_ID, eventId)
            put(Reminders.MINUTES, calculateMinutes(alarm, eventDuration, eventStartMs))
            put(Reminders.METHOD, mapMethod(alarm.action))
        }
    }

    /**
     * Calculate minutes before event start for Android's Reminders table.
     *
     * Handles three cases:
     * 1. Relative trigger (default): -PT15M → 15 minutes
     * 2. RELATED=END: Converts to start-relative using event duration
     * 3. Absolute trigger: Converts using event start time
     *
     * @param alarm The alarm to calculate minutes for
     * @param eventDuration Event duration for RELATED=END conversion
     * @param eventStartMs Event start timestamp for absolute trigger conversion
     * @return Positive minutes value for Android Reminders table
     */
    fun calculateMinutes(
        alarm: ICalAlarm,
        eventDuration: Duration? = null,
        eventStartMs: Long? = null
    ): Int {
        // Case 1: Relative trigger
        alarm.trigger?.let { trigger ->
            val triggerMinutes = trigger.toMinutes().toInt().absoluteValue

            // Case 2: RELATED=END - convert to start-relative
            if (alarm.triggerRelatedToEnd && eventDuration != null) {
                val durationMinutes = eventDuration.toMinutes().toInt()
                // RELATED=END:-PT15M means 15 min before end
                // = (duration - 15) min after start
                // For reminder purposes, we want minutes BEFORE start, so this is a bit tricky
                // Actually, Android reminders are always "minutes before event START"
                // So if alarm is 15 min before END, and event is 60 min long,
                // that's 45 minutes AFTER start, which doesn't make sense for a reminder
                // Android can only do "X minutes BEFORE start"
                // Best we can do: (duration - triggerMinutes) = when after start, convert to 0 if positive
                val minutesAfterStart = durationMinutes - triggerMinutes
                return maxOf(0, -minutesAfterStart) // Will be 0 for most RELATED=END cases
            }

            return triggerMinutes
        }

        // Case 3: Absolute trigger
        alarm.triggerAbsolute?.let { absTime ->
            if (eventStartMs != null) {
                val alarmMs = absTime.timestamp
                val diffMinutes = ((eventStartMs - alarmMs) / 60000).toInt()
                return maxOf(0, diffMinutes)
            }
        }

        // Default fallback
        return DEFAULT_MINUTES
    }

    /**
     * Map AlarmAction to Android Reminders.METHOD constant.
     */
    private fun mapMethod(action: AlarmAction): Int {
        return when (action) {
            AlarmAction.EMAIL -> Reminders.METHOD_EMAIL
            AlarmAction.AUDIO -> Reminders.METHOD_ALERT
            AlarmAction.DISPLAY -> Reminders.METHOD_ALERT
        }
    }

    /**
     * Convert a Cursor row from Reminders table to an [ICalAlarm].
     *
     * Note: Android Reminders have limited information compared to RFC 5545 VALARM.
     * Only action and trigger duration are preserved.
     *
     * @param cursor A cursor positioned at a valid Reminders row
     * @return Reconstructed ICalAlarm (with limited fidelity)
     */
    fun fromCursor(cursor: Cursor): ICalAlarm {
        val minutes = cursor.getIntOrDefault(Reminders.MINUTES, DEFAULT_MINUTES)
        val method = cursor.getIntOrNull(Reminders.METHOD) ?: Reminders.METHOD_DEFAULT

        val action = when (method) {
            Reminders.METHOD_EMAIL -> AlarmAction.EMAIL
            else -> AlarmAction.DISPLAY
        }

        // Android stores positive minutes, RFC 5545 uses negative for "before"
        val trigger = Duration.ofMinutes(-minutes.toLong())

        return ICalAlarm(
            action = action,
            trigger = trigger,
            triggerAbsolute = null,
            triggerRelatedToEnd = false,
            description = null,
            summary = null
        )
    }
}
