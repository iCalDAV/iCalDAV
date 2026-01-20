package org.onekash.icaldav.android

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Instances
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.PartStat

/**
 * Maps CalendarContract.Instances table to domain models.
 *
 * The Instances table expands recurring events into individual occurrences,
 * making it ideal for calendar UI display and date-range queries.
 *
 * ## Important Notes
 *
 * - Instances are read-only; modifications must go through Events table
 * - Time range must be specified in the URI using ContentUris.appendId()
 * - Instances for cancelled/deleted events are not returned
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Instances">CalendarContract.Instances</a>
 */
object InstancesMapper {

    /**
     * Projection for instance queries.
     * Includes essential fields for calendar display.
     */
    val PROJECTION = arrayOf(
        Instances._ID,
        Instances.EVENT_ID,
        Instances.BEGIN,
        Instances.END,
        Instances.TITLE,
        Instances.ALL_DAY,
        Instances.CALENDAR_ID,
        Instances.EVENT_LOCATION,
        Instances.STATUS,
        Instances.SELF_ATTENDEE_STATUS,
        Instances.CALENDAR_COLOR,
        Instances.EVENT_COLOR
    )

    /**
     * Column indices for efficient cursor access.
     */
    private const val COL_ID = 0
    private const val COL_EVENT_ID = 1
    private const val COL_BEGIN = 2
    private const val COL_END = 3
    private const val COL_TITLE = 4
    private const val COL_ALL_DAY = 5
    private const val COL_CALENDAR_ID = 6
    private const val COL_LOCATION = 7
    private const val COL_STATUS = 8
    private const val COL_SELF_ATTENDEE_STATUS = 9
    private const val COL_CALENDAR_COLOR = 10
    private const val COL_EVENT_COLOR = 11

    /**
     * Build an Instances URI for querying a time range.
     *
     * The Instances table requires start and end times to be encoded in the URI path.
     *
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @return URI for Instances query
     */
    fun buildInstancesUri(startMs: Long, endMs: Long): Uri {
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        return builder.build()
    }

    /**
     * Build an Instances URI for querying with a search term.
     *
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @param searchQuery Text to search for in event title/description/location
     * @return URI for Instances search query
     */
    fun buildSearchUri(startMs: Long, endMs: Long, searchQuery: String): Uri {
        val builder = Instances.CONTENT_SEARCH_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        builder.appendPath(searchQuery)
        return builder.build()
    }

    /**
     * Parse an EventInstance from a cursor positioned at a valid row.
     *
     * Call this when iterating over query results from the Instances table.
     *
     * @param cursor Cursor positioned at a valid Instances row
     * @return Parsed EventInstance
     */
    fun fromCursor(cursor: Cursor): EventInstance {
        return EventInstance(
            instanceId = cursor.getLong(COL_ID),
            eventId = cursor.getLong(COL_EVENT_ID),
            calendarId = cursor.getLong(COL_CALENDAR_ID),
            begin = cursor.getLong(COL_BEGIN),
            end = cursor.getLong(COL_END),
            title = if (cursor.isNull(COL_TITLE)) null else cursor.getString(COL_TITLE),
            location = if (cursor.isNull(COL_LOCATION)) null else cursor.getString(COL_LOCATION),
            allDay = cursor.getInt(COL_ALL_DAY) == 1,
            status = mapStatus(if (cursor.isNull(COL_STATUS)) null else cursor.getInt(COL_STATUS)),
            selfAttendeeStatus = mapSelfAttendeeStatus(
                if (cursor.isNull(COL_SELF_ATTENDEE_STATUS)) null else cursor.getInt(COL_SELF_ATTENDEE_STATUS)
            ),
            calendarColor = if (cursor.isNull(COL_CALENDAR_COLOR)) null else cursor.getInt(COL_CALENDAR_COLOR),
            eventColor = if (cursor.isNull(COL_EVENT_COLOR)) null else cursor.getInt(COL_EVENT_COLOR)
        )
    }

    /**
     * Parse an EventInstance using column names (for queries with custom projections).
     *
     * This method is slower but works with any column order.
     *
     * @param cursor Cursor positioned at a valid Instances row
     * @return Parsed EventInstance
     */
    fun fromCursorByName(cursor: Cursor): EventInstance {
        return EventInstance(
            instanceId = cursor.getLongOrDefault(Instances._ID, 0),
            eventId = cursor.getLongOrDefault(Instances.EVENT_ID, 0),
            calendarId = cursor.getLongOrDefault(Instances.CALENDAR_ID, 0),
            begin = cursor.getLongOrDefault(Instances.BEGIN, 0),
            end = cursor.getLongOrDefault(Instances.END, 0),
            title = cursor.getStringOrNull(Instances.TITLE),
            location = cursor.getStringOrNull(Instances.EVENT_LOCATION),
            allDay = cursor.getIntOrDefault(Instances.ALL_DAY, 0) == 1,
            status = mapStatus(cursor.getIntOrNull(Instances.STATUS)),
            selfAttendeeStatus = mapSelfAttendeeStatus(cursor.getIntOrNull(Instances.SELF_ATTENDEE_STATUS)),
            calendarColor = cursor.getIntOrNull(Instances.CALENDAR_COLOR),
            eventColor = cursor.getIntOrNull(Instances.EVENT_COLOR)
        )
    }

    /**
     * Map CalendarContract status value to EventStatus.
     */
    private fun mapStatus(statusValue: Int?): EventStatus? {
        return when (statusValue) {
            Instances.STATUS_CONFIRMED -> EventStatus.CONFIRMED
            Instances.STATUS_TENTATIVE -> EventStatus.TENTATIVE
            Instances.STATUS_CANCELED -> EventStatus.CANCELLED
            else -> null
        }
    }

    /**
     * Map self attendee status to PartStat.
     */
    private fun mapSelfAttendeeStatus(statusValue: Int?): PartStat? {
        return when (statusValue) {
            Attendees.ATTENDEE_STATUS_ACCEPTED -> PartStat.ACCEPTED
            Attendees.ATTENDEE_STATUS_DECLINED -> PartStat.DECLINED
            Attendees.ATTENDEE_STATUS_TENTATIVE -> PartStat.TENTATIVE
            Attendees.ATTENDEE_STATUS_INVITED -> PartStat.NEEDS_ACTION
            Attendees.ATTENDEE_STATUS_NONE -> null
            else -> null
        }
    }
}

/**
 * Represents a single occurrence of a calendar event.
 *
 * For non-recurring events, there is one instance per event.
 * For recurring events, there is one instance per occurrence within the queried time range.
 *
 * @property instanceId Unique ID for this instance (from Instances._ID)
 * @property eventId The parent event ID (from Events table)
 * @property calendarId The calendar this instance belongs to
 * @property begin Instance start time (epoch milliseconds, expanded from RRULE)
 * @property end Instance end time (epoch milliseconds, expanded from RRULE)
 * @property title Event title
 * @property location Event location
 * @property allDay Whether this is an all-day event
 * @property status Event status (CONFIRMED, TENTATIVE, CANCELLED)
 * @property selfAttendeeStatus The current user's attendance status
 * @property calendarColor Calendar color as ARGB int
 * @property eventColor Event-specific color override as ARGB int (or null to use calendar color)
 */
data class EventInstance(
    val instanceId: Long,
    val eventId: Long,
    val calendarId: Long,
    val begin: Long,
    val end: Long,
    val title: String?,
    val location: String?,
    val allDay: Boolean,
    val status: EventStatus?,
    val selfAttendeeStatus: PartStat?,
    val calendarColor: Int? = null,
    val eventColor: Int? = null
) {
    /**
     * Duration of this instance in milliseconds.
     */
    val durationMs: Long get() = end - begin

    /**
     * Effective display color (event color if set, otherwise calendar color).
     */
    val displayColor: Int? get() = eventColor ?: calendarColor

    /**
     * Whether the current user has responded to the event.
     */
    val hasResponded: Boolean get() = selfAttendeeStatus != null && selfAttendeeStatus != PartStat.NEEDS_ACTION
}
