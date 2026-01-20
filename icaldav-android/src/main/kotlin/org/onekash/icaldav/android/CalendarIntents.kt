package org.onekash.icaldav.android

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import org.onekash.icaldav.model.ICalEvent

/**
 * Type-safe intent builders for calendar app interactions.
 *
 * Creates standard ACTION_VIEW, ACTION_INSERT, and ACTION_EDIT intents
 * for interacting with calendar applications.
 *
 * ## Usage
 *
 * ```kotlin
 * // View an event
 * val intent = CalendarIntents.viewEvent(eventId)
 * startActivity(intent)
 *
 * // Create a new event
 * val intent = CalendarIntents.insertEvent(
 *     title = "Meeting",
 *     startMs = System.currentTimeMillis(),
 *     endMs = System.currentTimeMillis() + 3600000
 * )
 * startActivity(intent)
 * ```
 *
 * ## Important Notes
 *
 * - Intents are returned but not started - caller must handle ActivityNotFoundException
 * - Some devices may not have a calendar app installed
 * - Intent extras may be ignored by some calendar apps
 */
object CalendarIntents {

    // ==================== View Intents ====================

    /**
     * Create an intent to view a specific event.
     *
     * @param eventId The event ID to view
     * @return Intent with ACTION_VIEW for the event URI
     */
    fun viewEvent(eventId: Long): Intent {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
        }
    }

    /**
     * Create an intent to view the calendar at a specific time.
     *
     * @param timeMs The time to view (epoch milliseconds)
     * @return Intent with ACTION_VIEW for the calendar time
     */
    fun viewCalendar(timeMs: Long): Intent {
        val builder = CalendarContract.CONTENT_URI.buildUpon()
        builder.appendPath("time")
        ContentUris.appendId(builder, timeMs)

        return Intent(Intent.ACTION_VIEW).apply {
            data = builder.build()
        }
    }

    /**
     * Create an intent to view a specific calendar at a specific time.
     *
     * Note: The calendarId filter may not be supported by all calendar apps.
     *
     * @param calendarId The calendar ID to view
     * @param timeMs The time to view (epoch milliseconds)
     * @return Intent with ACTION_VIEW
     */
    fun viewCalendar(calendarId: Long, timeMs: Long): Intent {
        return viewCalendar(timeMs).apply {
            // Some calendar apps support filtering by calendar ID
            putExtra(Events.CALENDAR_ID, calendarId)
        }
    }

    // ==================== Edit Intents ====================

    /**
     * Create an intent to edit an existing event.
     *
     * @param eventId The event ID to edit
     * @return Intent with ACTION_EDIT for the event URI
     */
    fun editEvent(eventId: Long): Intent {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return Intent(Intent.ACTION_EDIT).apply {
            data = uri
        }
    }

    // ==================== Insert Intents ====================

    /**
     * Create an intent to insert a new event with the specified details.
     *
     * @param title Event title (optional)
     * @param description Event description (optional)
     * @param location Event location (optional)
     * @param startMs Start time in epoch milliseconds (optional)
     * @param endMs End time in epoch milliseconds (optional)
     * @param allDay Whether this is an all-day event (default: false)
     * @param calendarId Calendar ID to create the event in (optional)
     * @return Intent with ACTION_INSERT and event details as extras
     */
    fun insertEvent(
        title: String? = null,
        description: String? = null,
        location: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        allDay: Boolean = false,
        calendarId: Long? = null
    ): Intent {
        return Intent(Intent.ACTION_INSERT).apply {
            data = Events.CONTENT_URI

            title?.let { putExtra(Events.TITLE, it) }
            description?.let { putExtra(Events.DESCRIPTION, it) }
            location?.let { putExtra(Events.EVENT_LOCATION, it) }
            startMs?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            endMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            putExtra(Events.ALL_DAY, allDay)
            calendarId?.let { putExtra(Events.CALENDAR_ID, it) }
        }
    }

    /**
     * Create an intent to insert a new event from an ICalEvent.
     *
     * Maps relevant ICalEvent fields to intent extras.
     *
     * @param event The ICalEvent to create
     * @param calendarId Optional calendar ID to create the event in
     * @return Intent with ACTION_INSERT and event details as extras
     */
    fun insertEvent(event: ICalEvent, calendarId: Long? = null): Intent {
        return insertEvent(
            title = event.summary,
            description = event.description,
            location = event.location,
            startMs = event.dtStart.timestamp,
            endMs = event.effectiveEnd().timestamp,
            allDay = event.isAllDay,
            calendarId = calendarId
        ).apply {
            // Add RRULE if present
            event.rrule?.let { rrule ->
                putExtra(Events.RRULE, rrule.toICalString())
            }

            // Add availability
            putExtra(
                Events.AVAILABILITY,
                when (event.transparency) {
                    org.onekash.icaldav.model.Transparency.OPAQUE -> Events.AVAILABILITY_BUSY
                    org.onekash.icaldav.model.Transparency.TRANSPARENT -> Events.AVAILABILITY_FREE
                }
            )

            // Add access level from CLASS if present
            event.rawProperties["CLASS"]?.let { classValue ->
                val accessLevel = when (classValue.uppercase()) {
                    "PUBLIC" -> Events.ACCESS_PUBLIC
                    "PRIVATE" -> Events.ACCESS_PRIVATE
                    "CONFIDENTIAL" -> Events.ACCESS_CONFIDENTIAL
                    else -> Events.ACCESS_DEFAULT
                }
                putExtra(Events.ACCESS_LEVEL, accessLevel)
            }
        }
    }

    /**
     * Create an intent to insert a new all-day event.
     *
     * Convenience method that sets allDay = true and handles date-only times.
     *
     * @param title Event title (optional)
     * @param description Event description (optional)
     * @param location Event location (optional)
     * @param startMs Start date as midnight UTC milliseconds
     * @param endMs End date as midnight UTC milliseconds (exclusive)
     * @param calendarId Calendar ID to create the event in (optional)
     * @return Intent with ACTION_INSERT for an all-day event
     */
    fun insertAllDayEvent(
        title: String? = null,
        description: String? = null,
        location: String? = null,
        startMs: Long,
        endMs: Long,
        calendarId: Long? = null
    ): Intent {
        return insertEvent(
            title = title,
            description = description,
            location = location,
            startMs = startMs,
            endMs = endMs,
            allDay = true,
            calendarId = calendarId
        )
    }

    // ==================== Calendar App Detection ====================

    /**
     * Check if a calendar app is available to handle intents.
     *
     * @param context Context for accessing PackageManager
     * @return True if at least one app can handle calendar intents
     */
    fun isCalendarAppAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Events.CONTENT_URI
        }

        return intent.resolveActivity(context.packageManager) != null
    }

    /**
     * Get list of packages that can handle calendar intents.
     *
     * @param context Context for accessing PackageManager
     * @return List of package names that can handle calendar intents
     */
    @Suppress("DEPRECATION")
    fun getCalendarAppPackages(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Events.CONTENT_URI
        }

        val resolveInfos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.map { it.activityInfo.packageName }.distinct()
    }

    /**
     * Check if the default calendar app can handle inserting events.
     *
     * @param context Context for accessing PackageManager
     * @return True if insert intents can be handled
     */
    fun canInsertEvents(context: Context): Boolean {
        val intent = insertEvent(title = "Test")
        return intent.resolveActivity(context.packageManager) != null
    }

    /**
     * Check if the default calendar app can handle editing events.
     *
     * @param context Context for accessing PackageManager
     * @return True if edit intents can be handled
     */
    fun canEditEvents(context: Context): Boolean {
        // Use a dummy event ID - we just want to check if the intent can be handled
        val intent = editEvent(1L)
        return intent.resolveActivity(context.packageManager) != null
    }
}
