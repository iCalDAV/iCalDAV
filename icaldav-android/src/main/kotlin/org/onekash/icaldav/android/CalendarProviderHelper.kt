package org.onekash.icaldav.android

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalEvent

/**
 * Helper class for common CalendarContract ContentResolver operations.
 *
 * Provides type-safe wrappers around ContentResolver operations for calendars,
 * events, reminders, and attendees.
 *
 * ## Threading
 *
 * **Important**: All ContentResolver operations are synchronous and should be
 * called from a background thread (e.g., using `withContext(Dispatchers.IO)`).
 *
 * ## Sync Adapter Mode
 *
 * Pass `asSyncAdapter = true` when operating as a sync adapter to:
 * - Prevent changes from being marked as dirty
 * - Actually delete rows instead of soft-delete
 *
 * @param contentResolver The ContentResolver to use for operations
 * @param accountName The account name for sync adapter operations
 * @param accountType The account type for sync adapter operations
 */
class CalendarProviderHelper(
    private val contentResolver: ContentResolver,
    private val accountName: String,
    private val accountType: String
) {

    // ==================== Calendar Operations ====================

    /**
     * Insert a new calendar.
     *
     * @param displayName The user-visible calendar name
     * @param color The calendar color as ARGB int
     * @param calDavUrl The CalDAV calendar collection URL
     * @return The new calendar's ID, or -1 if insertion failed
     */
    fun insertCalendar(
        displayName: String,
        color: Int,
        calDavUrl: String
    ): Long {
        val values = CalendarMapper.toContentValues(
            accountName = accountName,
            accountType = accountType,
            displayName = displayName,
            color = color,
            calDavUrl = calDavUrl
        )

        val uri = contentResolver.insert(
            SyncAdapterUri.asSyncAdapter(Calendars.CONTENT_URI, accountName, accountType),
            values
        )

        return uri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Update calendar sync state (sync token and ctag).
     *
     * @param calendarId The calendar ID to update
     * @param syncToken The new sync token (or null to clear)
     * @param ctag The new ctag (or null to clear)
     * @return Number of rows updated
     */
    fun updateCalendarSyncState(
        calendarId: Long,
        syncToken: String?,
        ctag: String?
    ): Int {
        val values = CalendarMapper.toSyncStateValues(syncToken, ctag)
        return contentResolver.update(
            SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
                accountName,
                accountType
            ),
            values,
            null,
            null
        )
    }

    /**
     * Query calendars for this account.
     *
     * @return List of CalendarInfo for all calendars
     */
    fun queryCalendars(): List<CalendarInfo> {
        val calendars = mutableListOf<CalendarInfo>()

        contentResolver.query(
            Calendars.CONTENT_URI,
            null,
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, accountType),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars.add(CalendarMapper.fromCursor(cursor))
            }
        }

        return calendars
    }

    /**
     * Find calendar by CalDAV URL.
     */
    fun findCalendarByUrl(calDavUrl: String): CalendarInfo? {
        contentResolver.query(
            Calendars.CONTENT_URI,
            null,
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${CalendarMapper.SYNC_COLUMN_CALDAV_URL} = ?",
            arrayOf(accountName, accountType, calDavUrl),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return CalendarMapper.fromCursor(cursor)
            }
        }
        return null
    }

    // ==================== Event Operations ====================

    /**
     * Insert a new event.
     *
     * @param event The iCalDAV event to insert
     * @param calendarId The calendar ID to insert into
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The new event's ID, or -1 if insertion failed
     */
    fun insertEvent(
        event: ICalEvent,
        calendarId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        val values = CalendarContractMapper.toContentValues(event, calendarId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)
        } else {
            Events.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Update an existing event.
     *
     * @param eventId The event ID to update
     * @param event The updated event data
     * @param calendarId The calendar ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of rows updated
     */
    fun updateEvent(
        eventId: Long,
        event: ICalEvent,
        calendarId: Long,
        asSyncAdapter: Boolean = true
    ): Int {
        val values = CalendarContractMapper.toContentValues(event, calendarId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                accountName,
                accountType
            )
        } else {
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        }

        return contentResolver.update(uri, values, null, null)
    }

    /**
     * Delete an event.
     *
     * @param eventId The event ID to delete
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of rows deleted
     */
    fun deleteEvent(eventId: Long, asSyncAdapter: Boolean = true): Int {
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                accountName,
                accountType
            )
        } else {
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        }

        return contentResolver.delete(uri, null, null)
    }

    /**
     * Query events for a calendar.
     *
     * @param calendarId The calendar ID to query
     * @param includeDirty Whether to include dirty events (default true) - reserved for future use
     * @param includeDeleted Whether to include deleted events (default false)
     * @return List of events
     */
    @Suppress("UNUSED_PARAMETER") // includeDirty reserved for future filtering
    fun queryEvents(
        calendarId: Long,
        includeDirty: Boolean = true,
        includeDeleted: Boolean = false
    ): List<ICalEvent> {
        val selection = buildString {
            append("${Events.CALENDAR_ID} = ?")
            if (!includeDeleted) {
                append(" AND ${Events.DELETED} = 0")
            }
        }

        val events = mutableListOf<ICalEvent>()
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            selection,
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(CalendarContractMapper.fromCursor(cursor))
            }
        }

        return events
    }

    /**
     * Query dirty events (modified locally, pending sync).
     *
     * @param calendarId The calendar ID to query
     * @return List of dirty events
     */
    fun queryDirtyEvents(calendarId: Long): List<Pair<Long, ICalEvent>> {
        val events = mutableListOf<Pair<Long, ICalEvent>>()

        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.CALENDAR_ID} = ? AND ${Events.DIRTY} = 1 AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLongOrDefault(Events._ID, -1)
                if (eventId >= 0) {
                    events.add(eventId to CalendarContractMapper.fromCursor(cursor))
                }
            }
        }

        return events
    }

    /**
     * Query deleted events (soft-deleted, pending sync).
     *
     * @param calendarId The calendar ID to query
     * @return List of (eventId, syncId) pairs
     */
    fun queryDeletedEvents(calendarId: Long): List<Pair<Long, String>> {
        val deleted = mutableListOf<Pair<Long, String>>()

        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events._SYNC_ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 1",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLongOrNull(Events._ID)
                val syncId = cursor.getStringOrNull(Events._SYNC_ID)
                if (eventId != null && syncId != null) {
                    deleted.add(eventId to syncId)
                }
            }
        }

        return deleted
    }

    /**
     * Find event by sync ID (UID).
     *
     * @param calendarId The calendar ID to search in
     * @param syncId The sync ID (UID) to find
     * @return Pair of (eventId, event) or null if not found
     */
    fun findEventBySyncId(calendarId: Long, syncId: String): Pair<Long, ICalEvent>? {
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.CALENDAR_ID} = ? AND ${Events._SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString(), syncId),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val eventId = cursor.getLongOrDefault(Events._ID, -1)
                if (eventId >= 0) {
                    return eventId to CalendarContractMapper.fromCursor(cursor)
                }
            }
        }
        return null
    }

    /**
     * Clear dirty flag after successful sync.
     *
     * @param eventId The event ID to update
     * @return Number of rows updated
     */
    fun clearDirtyFlag(eventId: Long): Int {
        val values = ContentValues().apply {
            put(Events.DIRTY, 0)
        }
        return contentResolver.update(
            SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                accountName,
                accountType
            ),
            values,
            null,
            null
        )
    }

    // ==================== Instance Operations ====================

    /**
     * Query event instances in a time range.
     *
     * Uses the Instances table which handles RRULE expansion.
     *
     * @param startMs Start of range (inclusive)
     * @param endMs End of range (exclusive)
     * @param calendarId Optional calendar ID filter
     * @return Cursor over instances (caller must close)
     */
    fun queryInstances(
        startMs: Long,
        endMs: Long,
        calendarId: Long? = null
    ): Cursor? {
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val selection = calendarId?.let { "${Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }

        return contentResolver.query(
            builder.build(),
            null,
            selection,
            selectionArgs,
            "${Instances.BEGIN} ASC"
        )
    }

    // ==================== Reminder Operations ====================

    /**
     * Insert a reminder for an event.
     *
     * @param alarm The alarm to insert
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The reminder ID, or -1 if insertion failed
     */
    fun insertReminder(
        alarm: ICalAlarm,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        val values = ReminderMapper.toContentValues(alarm, eventId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Reminders.CONTENT_URI, accountName, accountType)
        } else {
            Reminders.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Delete all reminders for an event.
     *
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of reminders deleted
     */
    fun deleteRemindersForEvent(eventId: Long, asSyncAdapter: Boolean = true): Int {
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Reminders.CONTENT_URI, accountName, accountType)
        } else {
            Reminders.CONTENT_URI
        }

        return contentResolver.delete(
            uri,
            "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
    }

    /**
     * Query reminders for an event.
     *
     * @param eventId The event ID
     * @return List of alarms
     */
    fun queryReminders(eventId: Long): List<ICalAlarm> {
        val alarms = mutableListOf<ICalAlarm>()

        contentResolver.query(
            Reminders.CONTENT_URI,
            null,
            "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                alarms.add(ReminderMapper.fromCursor(cursor))
            }
        }

        return alarms
    }

    // ==================== Attendee Operations ====================

    /**
     * Insert an attendee for an event.
     *
     * @param attendee The attendee to insert
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The attendee ID, or -1 if insertion failed
     */
    fun insertAttendee(
        attendee: Attendee,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        val values = AttendeeMapper.toContentValues(attendee, eventId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)
        } else {
            Attendees.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Delete all attendees for an event.
     *
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of attendees deleted
     */
    fun deleteAttendeesForEvent(eventId: Long, asSyncAdapter: Boolean = true): Int {
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)
        } else {
            Attendees.CONTENT_URI
        }

        return contentResolver.delete(
            uri,
            "${Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
    }

    /**
     * Query attendees for an event.
     *
     * @param eventId The event ID
     * @return List of attendees (excluding organizer)
     */
    fun queryAttendees(eventId: Long): List<Attendee> {
        val attendees = mutableListOf<Attendee>()

        contentResolver.query(
            Attendees.CONTENT_URI,
            null,
            "${Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (!AttendeeMapper.isOrganizer(cursor)) {
                    attendees.add(AttendeeMapper.fromCursor(cursor))
                }
            }
        }

        return attendees
    }
}
