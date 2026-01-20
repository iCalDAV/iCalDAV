package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Events
import org.onekash.icaldav.model.*
import org.onekash.icaldav.util.DurationUtils
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Maps between iCalDAV's [ICalEvent] model and Android's CalendarContract ContentValues.
 *
 * This mapper handles the bidirectional conversion required for syncing CalDAV events
 * to and from Android's system calendar.
 *
 * ## RFC 5545 Compliance
 *
 * ### DURATION vs DTEND
 * - **Recurring events**: MUST use DURATION column, DTEND MUST be null
 * - **Non-recurring events**: MUST use DTEND column, DURATION must be null
 *
 * This is a CalendarContract requirement for correct instance expansion.
 *
 * ### RECURRENCE-ID Handling
 * Exception events (modified occurrences) use ORIGINAL_SYNC_ID and ORIGINAL_INSTANCE_TIME
 * columns to link to their master event, rather than embedding in the UID.
 *
 * ### All-Day Events
 * All-day events are stored as midnight UTC per CalendarContract requirements.
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Events">CalendarContract.Events</a>
 */
object CalendarContractMapper {

    /**
     * Convert an [ICalEvent] to ContentValues for insertion/update in CalendarContract.
     *
     * @param event The iCalDAV event to convert
     * @param calendarId The Android calendar ID to associate with this event
     * @param asSyncAdapter Whether operating as sync adapter (required for _SYNC_ID columns)
     * @return ContentValues ready for ContentResolver.insert() or update()
     */
    fun toContentValues(event: ICalEvent, calendarId: Long, asSyncAdapter: Boolean = true): ContentValues {
        return ContentValues().apply {
            // Calendar association
            put(Events.CALENDAR_ID, calendarId)

            // Sync ID for CalDAV UID mapping (only sync adapters can write this)
            if (asSyncAdapter) {
                put(Events._SYNC_ID, event.uid)
            }

            // Basic fields
            put(Events.TITLE, event.summary)
            put(Events.DESCRIPTION, event.description)
            put(Events.EVENT_LOCATION, event.location)

            // Handle datetime based on all-day vs timed event
            if (event.isAllDay) {
                mapAllDayEvent(this, event)
            } else {
                mapTimedEvent(this, event)
            }

            // Status mapping
            put(Events.STATUS, mapStatus(event.status))

            // Transparency / Availability
            put(Events.AVAILABILITY, mapAvailability(event.transparency))

            // Recurrence
            event.rrule?.let { rrule ->
                put(Events.RRULE, rrule.toICalString())
            }

            // Exception dates (EXDATE)
            if (event.exdates.isNotEmpty()) {
                put(Events.EXDATE, event.exdates.joinToString(",") { it.toICalString() })
            }

            // RECURRENCE-ID handling for exception events (ORIGINAL_SYNC_ID requires sync adapter)
            event.recurrenceId?.let { recId ->
                if (asSyncAdapter) {
                    put(Events.ORIGINAL_SYNC_ID, event.masterUid())
                }
                put(Events.ORIGINAL_INSTANCE_TIME, recId.timestamp)
                put(Events.ORIGINAL_ALL_DAY, if (recId.isDate) 1 else 0)
            }

            // SYNC_DATA columns are restricted to sync adapters only
            if (asSyncAdapter) {
                // SEQUENCE for conflict detection
                put(Events.SYNC_DATA3, event.sequence.toString())

                // Color (if present)
                event.color?.let { color ->
                    // CalendarContract expects ARGB int, but ICalEvent stores CSS color string
                    // Store raw value in extended property for round-trip
                    put(Events.SYNC_DATA4, color)
                }

                // Timestamps for sync
                event.lastModified?.let {
                    put(Events.SYNC_DATA5, it.timestamp.toString())
                }
            }

            // URL
            event.url?.let { put(Events.CUSTOM_APP_URI, it) }

            // Organizer (CN format for display)
            event.organizer?.let { org ->
                put(Events.ORGANIZER, org.email)
            }

            // ACCESS_LEVEL from CLASS property (RFC 5545)
            put(Events.ACCESS_LEVEL, mapAccessLevel(event.rawProperties["CLASS"]))
        }
    }

    /**
     * Map iCal CLASS property value to CalendarContract ACCESS_LEVEL.
     *
     * | iCal CLASS    | ACCESS_LEVEL          |
     * |---------------|------------------------|
     * | PUBLIC        | ACCESS_PUBLIC (200)    |
     * | PRIVATE       | ACCESS_PRIVATE (100)   |
     * | CONFIDENTIAL  | ACCESS_CONFIDENTIAL (300) |
     * | null/other    | ACCESS_DEFAULT (0)     |
     */
    internal fun mapAccessLevel(classValue: String?): Int {
        return when (classValue?.uppercase()) {
            "PUBLIC" -> Events.ACCESS_PUBLIC
            "PRIVATE" -> Events.ACCESS_PRIVATE
            "CONFIDENTIAL" -> Events.ACCESS_CONFIDENTIAL
            else -> Events.ACCESS_DEFAULT
        }
    }

    /**
     * Map CalendarContract ACCESS_LEVEL back to iCal CLASS property value.
     *
     * @return CLASS value string, or null for ACCESS_DEFAULT (no CLASS property)
     */
    internal fun mapClassificationString(accessLevel: Int): String? {
        return when (accessLevel) {
            Events.ACCESS_PUBLIC -> "PUBLIC"
            Events.ACCESS_PRIVATE -> "PRIVATE"
            Events.ACCESS_CONFIDENTIAL -> "CONFIDENTIAL"
            else -> null // ACCESS_DEFAULT means no CLASS property
        }
    }

    /**
     * Map all-day event datetime fields.
     *
     * Per CalendarContract requirements, all-day events:
     * - Use midnight UTC for DTSTART/DTEND
     * - Have EVENT_TIMEZONE set to "UTC"
     * - Have ALL_DAY set to 1
     */
    private fun mapAllDayEvent(values: ContentValues, event: ICalEvent) {
        // Convert to midnight UTC
        val startDate = event.dtStart.toLocalDate()
        val startUtcMidnight = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        values.put(Events.DTSTART, startUtcMidnight)
        values.put(Events.EVENT_TIMEZONE, "UTC")
        values.put(Events.ALL_DAY, 1)

        // Recurring vs non-recurring
        if (event.rrule != null) {
            // Recurring: use DURATION, clear DTEND
            val endDate = event.effectiveEnd().toLocalDate()
            val durationDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
            values.put(Events.DURATION, "P${durationDays}D")
            values.putNull(Events.DTEND)
        } else {
            // Non-recurring: use DTEND, clear DURATION
            val endDate = event.effectiveEnd().toLocalDate()
            val endUtcMidnight = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            values.put(Events.DTEND, endUtcMidnight)
            values.putNull(Events.DURATION)
        }
    }

    /**
     * Map timed event datetime fields.
     *
     * For timed events:
     * - Preserve original timezone
     * - Handle DURATION vs DTEND based on recurrence
     */
    private fun mapTimedEvent(values: ContentValues, event: ICalEvent) {
        values.put(Events.DTSTART, event.dtStart.timestamp)
        values.put(
            Events.EVENT_TIMEZONE,
            event.dtStart.timezone?.id ?: TimeZone.getDefault().id
        )
        values.put(Events.ALL_DAY, 0)

        // End timezone (may differ from start for cross-timezone events)
        val endTz = event.dtEnd?.timezone ?: event.dtStart.timezone
        values.put(
            Events.EVENT_END_TIMEZONE,
            endTz?.id ?: TimeZone.getDefault().id
        )

        // Recurring vs non-recurring
        if (event.rrule != null) {
            // Recurring: MUST use DURATION, DTEND must be null
            val duration = event.duration ?: Duration.ofMillis(
                event.effectiveEnd().timestamp - event.dtStart.timestamp
            )
            values.put(Events.DURATION, DurationUtils.format(duration))
            values.putNull(Events.DTEND)
        } else {
            // Non-recurring: MUST use DTEND, DURATION must be null
            values.put(Events.DTEND, event.effectiveEnd().timestamp)
            values.putNull(Events.DURATION)
        }
    }

    /**
     * Map EventStatus to CalendarContract status constant.
     */
    private fun mapStatus(status: EventStatus): Int {
        return when (status) {
            EventStatus.CONFIRMED -> Events.STATUS_CONFIRMED
            EventStatus.TENTATIVE -> Events.STATUS_TENTATIVE
            EventStatus.CANCELLED -> Events.STATUS_CANCELED
        }
    }

    /**
     * Map Transparency to CalendarContract availability constant.
     */
    private fun mapAvailability(transparency: Transparency): Int {
        return when (transparency) {
            Transparency.OPAQUE -> Events.AVAILABILITY_BUSY
            Transparency.TRANSPARENT -> Events.AVAILABILITY_FREE
        }
    }

    /**
     * Convert a Cursor row from CalendarContract.Events to an [ICalEvent].
     *
     * @param cursor A cursor positioned at a valid Events row
     * @return The reconstructed ICalEvent
     */
    fun fromCursor(cursor: Cursor): ICalEvent {
        val syncId = cursor.getStringOrNull(Events._SYNC_ID)
            ?: throw IllegalArgumentException("Event missing _SYNC_ID")

        val isAllDay = cursor.getIntOrDefault(Events.ALL_DAY, 0) == 1
        val dtStartMs = cursor.getLongOrDefault(Events.DTSTART, System.currentTimeMillis())
        val timezone = cursor.getStringOrNull(Events.EVENT_TIMEZONE)
            ?.let { tzId ->
                try {
                    ZoneId.of(tzId)
                } catch (e: Exception) {
                    ZoneId.systemDefault()
                }
            }

        val dtStart = ICalDateTime(
            timestamp = dtStartMs,
            timezone = if (isAllDay) ZoneOffset.UTC else timezone,
            isUtc = isAllDay || timezone == null,
            isDate = isAllDay
        )

        // Handle dtEnd vs duration
        val dtEndMs = cursor.getLongOrNull(Events.DTEND)
        val durationStr = cursor.getStringOrNull(Events.DURATION)

        val dtEnd: ICalDateTime?
        val duration: Duration?

        when {
            dtEndMs != null -> {
                val endTz = cursor.getStringOrNull(Events.EVENT_END_TIMEZONE)
                    ?.let { try { ZoneId.of(it) } catch (e: Exception) { null } }
                    ?: timezone
                dtEnd = ICalDateTime(
                    timestamp = dtEndMs,
                    timezone = if (isAllDay) ZoneOffset.UTC else endTz,
                    isUtc = isAllDay || endTz == null,
                    isDate = isAllDay
                )
                duration = null
            }
            durationStr != null -> {
                dtEnd = null
                duration = DurationUtils.parse(durationStr)
            }
            else -> {
                // Default to instant event
                dtEnd = dtStart
                duration = null
            }
        }

        // Parse recurrence ID for exception events
        @Suppress("UNUSED_VARIABLE") // May be used for validation in future
        val originalSyncId = cursor.getStringOrNull(Events.ORIGINAL_SYNC_ID)
        val originalInstanceTime = cursor.getLongOrNull(Events.ORIGINAL_INSTANCE_TIME)
        val originalAllDay = cursor.getIntOrDefault(Events.ORIGINAL_ALL_DAY, 0) == 1

        val recurrenceId = if (originalInstanceTime != null) {
            ICalDateTime(
                timestamp = originalInstanceTime,
                timezone = if (originalAllDay) ZoneOffset.UTC else timezone,
                isUtc = originalAllDay,
                isDate = originalAllDay
            )
        } else null

        // Parse RRULE
        val rruleStr = cursor.getStringOrNull(Events.RRULE)
        val rrule = rruleStr?.let {
            try {
                RRule.parse(it)
            } catch (e: Exception) {
                null
            }
        }

        // Parse EXDATE
        val exdateStr = cursor.getStringOrNull(Events.EXDATE)
        val exdates = exdateStr?.split(",")?.mapNotNull { dateStr ->
            try {
                ICalDateTime.parse(dateStr.trim(), timezone?.id)
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

        // Map status
        val status = when (cursor.getIntOrNull(Events.STATUS)) {
            Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
            Events.STATUS_CANCELED -> EventStatus.CANCELLED
            else -> EventStatus.CONFIRMED
        }

        // Map transparency
        val transparency = when (cursor.getIntOrNull(Events.AVAILABILITY)) {
            Events.AVAILABILITY_FREE -> Transparency.TRANSPARENT
            Events.AVAILABILITY_TENTATIVE -> Transparency.TRANSPARENT
            else -> Transparency.OPAQUE
        }

        // Parse organizer
        val organizerEmail = cursor.getStringOrNull(Events.ORGANIZER)
        val organizer = organizerEmail?.let { email ->
            Organizer(email = email, name = null, sentBy = null)
        }

        // Parse sequence
        val sequence = cursor.getStringOrNull(Events.SYNC_DATA3)?.toIntOrNull() ?: 0

        // Parse last modified
        val lastModifiedMs = cursor.getStringOrNull(Events.SYNC_DATA5)?.toLongOrNull()
        val lastModified = lastModifiedMs?.let {
            ICalDateTime.fromTimestamp(it, ZoneOffset.UTC)
        }

        // Parse color
        val color = cursor.getStringOrNull(Events.SYNC_DATA4)

        // Parse ACCESS_LEVEL and convert to CLASS property for round-trip
        val accessLevel = cursor.getIntOrDefault(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
        val classValue = mapClassificationString(accessLevel)
        val rawProperties = buildMap {
            classValue?.let { put("CLASS", it) }
        }

        return ICalEvent(
            uid = syncId,
            importId = ICalEvent.generateImportId(syncId, recurrenceId),
            summary = cursor.getStringOrNull(Events.TITLE),
            description = cursor.getStringOrNull(Events.DESCRIPTION),
            location = cursor.getStringOrNull(Events.EVENT_LOCATION),
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = duration,
            isAllDay = isAllDay,
            status = status,
            sequence = sequence,
            rrule = rrule,
            exdates = exdates,
            recurrenceId = recurrenceId,
            alarms = emptyList(), // Alarms are in separate table
            categories = emptyList(),
            organizer = organizer,
            attendees = emptyList(), // Attendees are in separate table
            color = color,
            dtstamp = null,
            lastModified = lastModified,
            created = null,
            transparency = transparency,
            url = cursor.getStringOrNull(Events.CUSTOM_APP_URI),
            rawProperties = rawProperties
        )
    }
}
