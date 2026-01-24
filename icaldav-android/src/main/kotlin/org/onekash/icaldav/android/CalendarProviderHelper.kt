package org.onekash.icaldav.android

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.SyncState
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalConference
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
        val values = CalendarContractMapper.toContentValues(event, calendarId, asSyncAdapter)
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
        val values = CalendarContractMapper.toContentValues(event, calendarId, asSyncAdapter)
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

    // ==================== ETag Operations ====================

    /**
     * Get stored ETag for an event.
     *
     * ETags are stored in SYNC_DATA1 for conflict detection during sync.
     * Per Android docs: "SYNC_DATA1 through SYNC_DATA10 are for use by sync adapters."
     *
     * @param eventId The event ID
     * @return The stored ETag, or null if not set
     */
    fun getStoredEtag(eventId: Long): String? {
        contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.SYNC_DATA1),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getStringOrNull(Events.SYNC_DATA1)
            }
        }
        return null
    }

    /**
     * Store ETag for an event.
     *
     * ETags are stored in SYNC_DATA1 for conflict detection during sync.
     * Call this after successful CREATE or UPDATE to server.
     *
     * @param eventId The event ID
     * @param etag The ETag from server response (or null to clear)
     * @return Number of rows updated
     */
    fun storeEtag(eventId: Long, etag: String?): Int {
        val values = ContentValues().apply {
            if (etag != null) {
                put(Events.SYNC_DATA1, etag)
            } else {
                putNull(Events.SYNC_DATA1)
            }
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

    /**
     * Get stored CalDAV URL for an event.
     *
     * Event URLs are stored in SYNC_DATA2 for update/delete operations.
     *
     * @param eventId The event ID
     * @return The stored CalDAV URL, or null if not set
     */
    fun getStoredEventUrl(eventId: Long): String? {
        contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.SYNC_DATA2),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getStringOrNull(Events.SYNC_DATA2)
            }
        }
        return null
    }

    /**
     * Store CalDAV URL for an event.
     *
     * Event URLs are stored in SYNC_DATA2 for update/delete operations.
     *
     * @param eventId The event ID
     * @param url The CalDAV event URL (or null to clear)
     * @return Number of rows updated
     */
    fun storeEventUrl(eventId: Long, url: String?): Int {
        val values = ContentValues().apply {
            if (url != null) {
                put(Events.SYNC_DATA2, url)
            } else {
                putNull(Events.SYNC_DATA2)
            }
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

    /**
     * Store sync ID (_SYNC_ID) for an event.
     *
     * This is used after successfully pushing a locally-created event to the server.
     * Locally-created events (inserted without asSyncAdapter) have _SYNC_ID = null.
     * After push, we update _SYNC_ID to match the UID sent to the server so that
     * subsequent syncs can match the event.
     *
     * IMPORTANT: Only sync adapters can write _SYNC_ID, so this uses sync adapter URI.
     *
     * @param eventId The event ID
     * @param syncId The sync ID (UID) to store
     * @return Number of rows updated
     */
    fun storeSyncId(eventId: Long, syncId: String): Int {
        val values = ContentValues().apply {
            put(Events._SYNC_ID, syncId)
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

    /**
     * Query event instances in a time range as EventInstance objects.
     *
     * This method returns typed [EventInstance] objects instead of a raw Cursor.
     * For recurring events, returns one instance per occurrence within the range.
     *
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @param calendarId Optional calendar ID filter
     * @return List of EventInstance objects sorted by begin time
     */
    fun queryInstancesInRange(
        startMs: Long,
        endMs: Long,
        calendarId: Long? = null
    ): List<EventInstance> {
        val instances = mutableListOf<EventInstance>()

        val selection = calendarId?.let { "${Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }

        contentResolver.query(
            InstancesMapper.buildInstancesUri(startMs, endMs),
            InstancesMapper.PROJECTION,
            selection,
            selectionArgs,
            "${Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                instances.add(InstancesMapper.fromCursor(cursor))
            }
        }

        return instances
    }

    /**
     * Query instances for a specific event.
     *
     * For recurring events, returns all occurrences within the given time range.
     * For non-recurring events, returns the single instance if it falls within the range.
     *
     * @param eventId The event ID to query instances for
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @return List of EventInstance objects sorted by begin time
     */
    fun queryInstancesForEvent(
        eventId: Long,
        startMs: Long,
        endMs: Long
    ): List<EventInstance> {
        val instances = mutableListOf<EventInstance>()

        contentResolver.query(
            InstancesMapper.buildInstancesUri(startMs, endMs),
            InstancesMapper.PROJECTION,
            "${Instances.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                instances.add(InstancesMapper.fromCursor(cursor))
            }
        }

        return instances
    }

    /**
     * Count instances in a time range.
     *
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @param calendarId Optional calendar ID filter
     * @return Number of instances in the range
     */
    fun countInstancesInRange(
        startMs: Long,
        endMs: Long,
        calendarId: Long? = null
    ): Int {
        val selection = calendarId?.let { "${Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }

        contentResolver.query(
            InstancesMapper.buildInstancesUri(startMs, endMs),
            arrayOf(Instances._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            return cursor.count
        }

        return 0
    }

    /**
     * Search instances by text within a time range.
     *
     * Searches event title, description, and location fields.
     *
     * @param startMs Start of range (inclusive, epoch milliseconds)
     * @param endMs End of range (exclusive, epoch milliseconds)
     * @param searchQuery Text to search for
     * @param calendarId Optional calendar ID filter
     * @return List of matching EventInstance objects sorted by begin time
     */
    fun searchInstances(
        startMs: Long,
        endMs: Long,
        searchQuery: String,
        calendarId: Long? = null
    ): List<EventInstance> {
        val instances = mutableListOf<EventInstance>()

        val selection = calendarId?.let { "${Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }

        contentResolver.query(
            InstancesMapper.buildSearchUri(startMs, endMs, searchQuery),
            InstancesMapper.PROJECTION,
            selection,
            selectionArgs,
            "${Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                instances.add(InstancesMapper.fromCursor(cursor))
            }
        }

        return instances
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

    // ==================== Batch Operations ====================

    /**
     * Apply a batch of ContentProvider operations atomically.
     *
     * All operations succeed or all fail together. This prevents inconsistent
     * database state during sync operations.
     *
     * @param operations The batch operations to apply
     * @return Array of results, one per operation
     * @throws OperationApplicationException If an operation fails
     * @throws RemoteException If the ContentProvider process dies
     */
    @Throws(RemoteException::class, OperationApplicationException::class)
    fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        return contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }

    /**
     * Insert or update an event based on sync ID.
     *
     * This is the standard sync adapter pattern:
     * - If event with syncId exists: update it
     * - If no event with syncId: insert new
     *
     * @param event The event data
     * @param calendarId The calendar ID
     * @param syncId Optional sync ID to match on (defaults to event.uid)
     * @return The event ID (new or existing)
     */
    fun upsertEvent(event: ICalEvent, calendarId: Long, syncId: String? = null): Long {
        val effectiveSyncId = syncId ?: event.uid
        val existing = findEventBySyncId(calendarId, effectiveSyncId)

        return if (existing != null) {
            updateEvent(existing.first, event, calendarId, asSyncAdapter = true)
            existing.first
        } else {
            insertEvent(event, calendarId, asSyncAdapter = true)
        }
    }

    // ==================== Query Operations ====================

    /**
     * Query events within a time range.
     *
     * Efficiently filters by date using Instances table for RRULE expansion.
     *
     * @param calendarId The calendar ID to query
     * @param startMs Start of range (inclusive, epoch millis)
     * @param endMs End of range (exclusive, epoch millis)
     * @return List of events with instances in the range
     */
    fun queryEventsInRange(calendarId: Long, startMs: Long, endMs: Long): List<ICalEvent> {
        val eventIds = mutableSetOf<Long>()

        // First get event IDs from instances table
        queryInstances(startMs, endMs, calendarId)?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndex(Instances.EVENT_ID)
            if (eventIdIndex >= 0) {
                while (cursor.moveToNext()) {
                    eventIds.add(cursor.getLong(eventIdIndex))
                }
            }
        }

        if (eventIds.isEmpty()) {
            return emptyList()
        }

        // Then fetch full event data
        val events = mutableListOf<ICalEvent>()
        val placeholders = eventIds.joinToString(",") { "?" }
        val selectionArgs = eventIds.map { it.toString() }.toTypedArray()

        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events._ID} IN ($placeholders) AND ${Events.DELETED} = 0",
            selectionArgs,
            "${Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(CalendarContractMapper.fromCursor(cursor))
            }
        }

        return events
    }

    // ==================== Count Operations ====================

    /**
     * Count dirty events (modified locally, pending upload).
     *
     * @param calendarId The calendar ID to count
     * @return Number of dirty events
     */
    fun countDirtyEvents(calendarId: Long): Int {
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DIRTY} = 1 AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            return cursor.count
        }
        return 0
    }

    /**
     * Count deleted events (soft-deleted, pending server delete).
     *
     * @param calendarId The calendar ID to count
     * @return Number of soft-deleted events
     */
    fun countDeletedEvents(calendarId: Long): Int {
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 1",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            return cursor.count
        }
        return 0
    }

    /**
     * Count total events in a calendar.
     *
     * @param calendarId The calendar ID to count
     * @param includeDeleted Whether to include soft-deleted events
     * @return Total event count
     */
    fun countEvents(calendarId: Long, includeDeleted: Boolean = false): Int {
        val selection = if (includeDeleted) {
            "${Events.CALENDAR_ID} = ?"
        } else {
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0"
        }

        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            selection,
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            return cursor.count
        }
        return 0
    }

    // ==================== Extended Properties Operations ====================

    /**
     * Insert an extended property for an event.
     *
     * @param name Property name (will be lowercase)
     * @param value Property value
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The extended property ID, or -1 if insertion failed
     */
    fun insertExtendedProperty(
        name: String,
        value: String,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        val values = ExtendedPropertiesMapper.toContentValues(name, value, eventId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
        } else {
            ExtendedProperties.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Query all extended properties for an event.
     *
     * @param eventId The event ID
     * @return Map of property names to values
     */
    fun queryExtendedProperties(eventId: Long): Map<String, String> {
        val properties = mutableMapOf<String, String>()

        contentResolver.query(
            ExtendedProperties.CONTENT_URI,
            null,
            "${ExtendedProperties.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val (name, value) = ExtendedPropertiesMapper.fromCursor(cursor)
                properties[name] = value
            }
        }

        return properties
    }

    /**
     * Delete all extended properties for an event.
     *
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of properties deleted
     */
    fun deleteExtendedPropertiesForEvent(eventId: Long, asSyncAdapter: Boolean = true): Int {
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
        } else {
            ExtendedProperties.CONTENT_URI
        }

        return contentResolver.delete(
            uri,
            "${ExtendedProperties.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
    }

    /**
     * Insert CATEGORIES as an extended property.
     *
     * @param categories List of category strings
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The extended property ID, or -1 if insertion failed
     */
    fun insertCategories(
        categories: List<String>,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        if (categories.isEmpty()) return -1

        val values = ExtendedPropertiesMapper.categoriesToContentValues(categories, eventId)
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
        } else {
            ExtendedProperties.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Query CATEGORIES for an event.
     *
     * @param eventId The event ID
     * @return List of category strings, or empty list if not set
     */
    fun queryCategories(eventId: Long): List<String> {
        contentResolver.query(
            ExtendedProperties.CONTENT_URI,
            arrayOf(ExtendedProperties.VALUE),
            "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), ExtendedPropertiesMapper.NAME_CATEGORIES),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val value = cursor.getStringOrNull(ExtendedProperties.VALUE) ?: ""
                return ExtendedPropertiesMapper.parseCategories(value)
            }
        }
        return emptyList()
    }

    /**
     * Insert all X-* properties from rawProperties map.
     *
     * @param rawProperties Map of property names to values
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of properties inserted
     */
    fun insertXProperties(
        rawProperties: Map<String, String>,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Int {
        val contentValuesList = ExtendedPropertiesMapper.rawPropertiesToContentValues(
            rawProperties, eventId
        )

        var count = 0
        for (values in contentValuesList) {
            val uri = if (asSyncAdapter) {
                SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
            } else {
                ExtendedProperties.CONTENT_URI
            }
            contentResolver.insert(uri, values)?.let { count++ }
        }
        return count
    }

    /**
     * Query all X-* properties for an event.
     *
     * @param eventId The event ID
     * @return Map of X-* property names (uppercase) to values
     */
    fun queryXProperties(eventId: Long): Map<String, String> {
        val properties = mutableListOf<Pair<String, String>>()

        contentResolver.query(
            ExtendedProperties.CONTENT_URI,
            null,
            "${ExtendedProperties.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val (name, value) = ExtendedPropertiesMapper.fromCursor(cursor)
                if (ExtendedPropertiesMapper.isXProperty(name)) {
                    properties.add(name to value)
                }
            }
        }

        return ExtendedPropertiesMapper.toRawPropertiesMap(properties)
    }

    // ==================== Conference Operations (RFC 7986) ====================

    /**
     * Insert conferences for an event.
     *
     * Stores RFC 7986 CONFERENCE data in ExtendedProperties as JSON.
     * Android's CalendarContract doesn't natively support conferences,
     * so we preserve them using extended properties.
     *
     * @param conferences List of conferences to store
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The extended property ID, or -1 if insertion failed or list is empty
     */
    fun insertConferences(
        conferences: List<ICalConference>,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        val values = ConferenceMapper.toContentValues(conferences, eventId) ?: return -1

        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
        } else {
            ExtendedProperties.CONTENT_URI
        }

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Query conferences for an event.
     *
     * Retrieves RFC 7986 CONFERENCE data from ExtendedProperties.
     *
     * @param eventId The event ID
     * @return List of ICalConference objects, or empty list if none stored
     */
    fun queryConferences(eventId: Long): List<ICalConference> {
        contentResolver.query(
            ExtendedProperties.CONTENT_URI,
            arrayOf(ExtendedProperties.VALUE),
            "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), ConferenceMapper.NAME_CONFERENCE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val json = cursor.getStringOrNull(ExtendedProperties.VALUE)
                return ConferenceMapper.fromJson(json)
            }
        }
        return emptyList()
    }

    /**
     * Delete conferences for an event.
     *
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of rows deleted
     */
    fun deleteConferencesForEvent(eventId: Long, asSyncAdapter: Boolean = true): Int {
        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(ExtendedProperties.CONTENT_URI, accountName, accountType)
        } else {
            ExtendedProperties.CONTENT_URI
        }

        return contentResolver.delete(
            uri,
            "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), ConferenceMapper.NAME_CONFERENCE)
        )
    }

    /**
     * Update conferences for an event.
     *
     * Deletes existing conferences and inserts new ones.
     *
     * @param conferences New list of conferences
     * @param eventId The event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return The new extended property ID, or -1 if insertion failed
     */
    fun updateConferences(
        conferences: List<ICalConference>,
        eventId: Long,
        asSyncAdapter: Boolean = true
    ): Long {
        deleteConferencesForEvent(eventId, asSyncAdapter)
        return insertConferences(conferences, eventId, asSyncAdapter)
    }

    /**
     * Get the primary video conference URL for an event.
     *
     * Convenience method for quick access to the main meeting link.
     *
     * @param eventId The event ID
     * @return The primary video conference URL, or null if none found
     */
    fun getPrimaryConferenceUrl(eventId: Long): String? {
        val conferences = queryConferences(eventId)
        return ConferenceMapper.getPrimaryVideoUrl(conferences)
    }

    /**
     * Check if an event has any conference links.
     *
     * @param eventId The event ID
     * @return True if the event has at least one conference
     */
    fun hasConferences(eventId: Long): Boolean {
        contentResolver.query(
            ExtendedProperties.CONTENT_URI,
            arrayOf(ExtendedProperties._ID),
            "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), ConferenceMapper.NAME_CONFERENCE),
            null
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    // ==================== Colors Operations ====================

    /**
     * Query all calendar colors for this account.
     *
     * @return List of CalendarColor objects with TYPE_CALENDAR
     */
    fun queryCalendarColors(): List<CalendarColor> {
        val colors = mutableListOf<CalendarColor>()

        contentResolver.query(
            Colors.CONTENT_URI,
            ColorsMapper.PROJECTION,
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ? AND ${Colors.COLOR_TYPE} = ?",
            arrayOf(accountName, accountType, ColorsMapper.TYPE_CALENDAR.toString()),
            "${Colors.COLOR_KEY} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                colors.add(ColorsMapper.fromCursor(cursor))
            }
        }

        return colors
    }

    /**
     * Query all event colors for this account.
     *
     * @return List of CalendarColor objects with TYPE_EVENT
     */
    fun queryEventColors(): List<CalendarColor> {
        val colors = mutableListOf<CalendarColor>()

        contentResolver.query(
            Colors.CONTENT_URI,
            ColorsMapper.PROJECTION,
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ? AND ${Colors.COLOR_TYPE} = ?",
            arrayOf(accountName, accountType, ColorsMapper.TYPE_EVENT.toString()),
            "${Colors.COLOR_KEY} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                colors.add(ColorsMapper.fromCursor(cursor))
            }
        }

        return colors
    }

    /**
     * Query all colors (both calendar and event) for this account.
     *
     * @return List of CalendarColor objects
     */
    fun queryAllColors(): List<CalendarColor> {
        val colors = mutableListOf<CalendarColor>()

        contentResolver.query(
            Colors.CONTENT_URI,
            ColorsMapper.PROJECTION,
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, accountType),
            "${Colors.COLOR_TYPE} ASC, ${Colors.COLOR_KEY} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                colors.add(ColorsMapper.fromCursor(cursor))
            }
        }

        return colors
    }

    /**
     * Find a color by its key.
     *
     * @param colorKey The color key to find
     * @param colorType The color type (TYPE_CALENDAR or TYPE_EVENT)
     * @return The CalendarColor, or null if not found
     */
    fun findColorByKey(colorKey: String, colorType: Int): CalendarColor? {
        contentResolver.query(
            Colors.CONTENT_URI,
            ColorsMapper.PROJECTION,
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ? AND ${Colors.COLOR_KEY} = ? AND ${Colors.COLOR_TYPE} = ?",
            arrayOf(accountName, accountType, colorKey, colorType.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ColorsMapper.fromCursor(cursor)
            }
        }
        return null
    }

    /**
     * Insert a new color.
     *
     * Colors table requires sync adapter context.
     *
     * @param color The CalendarColor to insert
     * @return The new color's ID, or -1 if insertion failed
     */
    fun insertColor(color: CalendarColor): Long {
        val values = ColorsMapper.toContentValues(color, accountName, accountType)
        val uri = SyncAdapterUri.asSyncAdapter(Colors.CONTENT_URI, accountName, accountType)

        val resultUri = contentResolver.insert(uri, values)
        return resultUri?.let { ContentUris.parseId(it) } ?: -1
    }

    /**
     * Delete a color by its key.
     *
     * @param colorKey The color key to delete
     * @param colorType The color type (TYPE_CALENDAR or TYPE_EVENT)
     * @return Number of rows deleted
     */
    fun deleteColor(colorKey: String, colorType: Int): Int {
        val uri = SyncAdapterUri.asSyncAdapter(Colors.CONTENT_URI, accountName, accountType)

        return contentResolver.delete(
            uri,
            "${Colors.COLOR_KEY} = ? AND ${Colors.COLOR_TYPE} = ?",
            arrayOf(colorKey, colorType.toString())
        )
    }

    /**
     * Delete all colors of a specific type for this account.
     *
     * @param colorType The color type (TYPE_CALENDAR or TYPE_EVENT)
     * @return Number of rows deleted
     */
    fun deleteAllColors(colorType: Int): Int {
        val uri = SyncAdapterUri.asSyncAdapter(Colors.CONTENT_URI, accountName, accountType)

        return contentResolver.delete(
            uri,
            "${Colors.COLOR_TYPE} = ?",
            arrayOf(colorType.toString())
        )
    }

    /**
     * Count colors of a specific type.
     *
     * @param colorType The color type (TYPE_CALENDAR or TYPE_EVENT)
     * @return Number of colors
     */
    fun countColors(colorType: Int): Int {
        contentResolver.query(
            Colors.CONTENT_URI,
            arrayOf(Colors._ID),
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ? AND ${Colors.COLOR_TYPE} = ?",
            arrayOf(accountName, accountType, colorType.toString()),
            null
        )?.use { cursor ->
            return cursor.count
        }

        return 0
    }

    // ==================== SyncState Operations ====================

    /**
     * Get sync state data for this account.
     *
     * @return Raw data bytes, or null if no sync state exists
     */
    fun getSyncStateData(): ByteArray? {
        contentResolver.query(
            SyncState.CONTENT_URI,
            SyncStateMapper.PROJECTION,
            "${SyncState.ACCOUNT_NAME} = ? AND ${SyncState.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, accountType),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return SyncStateMapper.fromCursor(cursor)?.second
            }
        }
        return null
    }

    /**
     * Set sync state data for this account.
     *
     * Creates a new row if none exists, or updates the existing row.
     *
     * @param data The data bytes to store
     * @return True if successful
     */
    fun setSyncStateData(data: ByteArray): Boolean {
        val values = SyncStateMapper.toContentValues(data, accountName, accountType)

        // Try update first
        val updated = contentResolver.update(
            SyncState.CONTENT_URI,
            values,
            "${SyncState.ACCOUNT_NAME} = ? AND ${SyncState.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, accountType)
        )

        if (updated > 0) return true

        // No existing row, insert new one
        val uri = contentResolver.insert(SyncState.CONTENT_URI, values)
        return uri != null
    }

    /**
     * Get a sync state value by key.
     *
     * Uses the JSON map approach - all state is stored in a single blob.
     *
     * @param key The key to retrieve
     * @return The value, or null if not found
     */
    fun getSyncState(key: String): String? {
        val data = getSyncStateData() ?: return null
        val state = SyncStateMapper.decodeStateMap(data)
        return state[key]
    }

    /**
     * Set a sync state value by key.
     *
     * Uses the JSON map approach - reads existing state, updates the key, writes back.
     *
     * @param key The key to set
     * @param value The value to set
     * @return True if successful
     */
    fun setSyncState(key: String, value: String): Boolean {
        val existingData = getSyncStateData()
        val state = if (existingData != null) {
            SyncStateMapper.decodeStateMap(existingData).toMutableMap()
        } else {
            mutableMapOf()
        }

        state[key] = value
        return setSyncStateData(SyncStateMapper.encodeStateMap(state))
    }

    /**
     * Delete a sync state key.
     *
     * @param key The key to delete
     * @return True if successful (even if key didn't exist)
     */
    fun deleteSyncState(key: String): Boolean {
        val existingData = getSyncStateData() ?: return true
        val state = SyncStateMapper.decodeStateMap(existingData).toMutableMap()

        if (state.remove(key) != null) {
            return setSyncStateData(SyncStateMapper.encodeStateMap(state))
        }
        return true
    }

    /**
     * Get all sync state as a map.
     *
     * @return Map of all key-value pairs, or empty map if none
     */
    fun getAllSyncState(): Map<String, String> {
        val data = getSyncStateData() ?: return emptyMap()
        return SyncStateMapper.decodeStateMap(data)
    }

    /**
     * Clear all sync state for this account.
     *
     * @return Number of rows deleted
     */
    fun clearAllSyncState(): Int {
        return contentResolver.delete(
            SyncState.CONTENT_URI,
            "${SyncState.ACCOUNT_NAME} = ? AND ${SyncState.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, accountType)
        )
    }

    /**
     * Get the stored sync token for incremental sync.
     *
     * @return The sync token, or null if not set
     */
    fun getSyncToken(): String? = getSyncState(SyncStateMapper.KEY_SYNC_TOKEN)

    /**
     * Set the sync token for incremental sync.
     *
     * @param token The sync token from server
     * @return True if successful
     */
    fun setSyncToken(token: String): Boolean = setSyncState(SyncStateMapper.KEY_SYNC_TOKEN, token)

    /**
     * Get the last sync timestamp.
     *
     * @return Epoch milliseconds of last sync, or null if not set
     */
    fun getLastSyncTime(): Long? {
        return getSyncState(SyncStateMapper.KEY_LAST_SYNC)?.toLongOrNull()
    }

    /**
     * Set the last sync timestamp.
     *
     * @param timestamp Epoch milliseconds
     * @return True if successful
     */
    fun setLastSyncTime(timestamp: Long): Boolean {
        return setSyncState(SyncStateMapper.KEY_LAST_SYNC, timestamp.toString())
    }

    /**
     * Get the stored CTag.
     *
     * @return The CTag, or null if not set
     */
    fun getCtag(): String? = getSyncState(SyncStateMapper.KEY_CTAG)

    /**
     * Set the CTag.
     *
     * @param ctag The CTag from server
     * @return True if successful
     */
    fun setCtag(ctag: String): Boolean = setSyncState(SyncStateMapper.KEY_CTAG, ctag)

    // ==================== Exception Handling Operations ====================

    /**
     * Query all exception events for a master recurring event.
     *
     * Exception events are individual modifications to occurrences of a recurring event.
     * They are linked to the master via ORIGINAL_SYNC_ID column.
     *
     * @param masterEventId The master event ID
     * @return List of (exceptionEventId, exceptionEvent) pairs
     */
    fun queryExceptions(masterEventId: Long): List<Pair<Long, ICalEvent>> {
        // First get the master's sync ID
        val masterSyncId = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterEventId),
            arrayOf(Events._SYNC_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(Events._SYNC_ID) else null
        } ?: return emptyList()

        // Query all events with ORIGINAL_SYNC_ID pointing to the master
        val exceptions = mutableListOf<Pair<Long, ICalEvent>>()

        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(masterSyncId),
            "${Events.ORIGINAL_INSTANCE_TIME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLongOrDefault(Events._ID, -1)
                if (eventId >= 0) {
                    exceptions.add(eventId to CalendarContractMapper.fromCursor(cursor))
                }
            }
        }

        return exceptions
    }

    /**
     * Query exceptions for a master event by its sync ID (UID).
     *
     * @param masterSyncId The master event's sync ID (UID)
     * @return List of (exceptionEventId, exceptionEvent) pairs
     */
    fun queryExceptionsBySyncId(masterSyncId: String): List<Pair<Long, ICalEvent>> {
        val exceptions = mutableListOf<Pair<Long, ICalEvent>>()

        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(masterSyncId),
            "${Events.ORIGINAL_INSTANCE_TIME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLongOrDefault(Events._ID, -1)
                if (eventId >= 0) {
                    exceptions.add(eventId to CalendarContractMapper.fromCursor(cursor))
                }
            }
        }

        return exceptions
    }

    /**
     * Find a specific exception event by master UID and recurrence ID.
     *
     * @param masterUid The master event's UID
     * @param recurrenceIdMs The RECURRENCE-ID as epoch milliseconds
     * @return Pair of (eventId, event) or null if not found
     */
    fun queryExceptionByRecurrenceId(
        masterUid: String,
        recurrenceIdMs: Long
    ): Pair<Long, ICalEvent>? {
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} = 0",
            arrayOf(masterUid, recurrenceIdMs.toString()),
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
     * Count exception events for a master recurring event.
     *
     * @param masterEventId The master event ID
     * @return Number of exception events
     */
    fun countExceptions(masterEventId: Long): Int {
        // First get the master's sync ID
        val masterSyncId = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterEventId),
            arrayOf(Events._SYNC_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(Events._SYNC_ID) else null
        } ?: return 0

        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(masterSyncId),
            null
        )?.use { cursor ->
            return cursor.count
        }

        return 0
    }

    /**
     * Delete a specific exception event.
     *
     * @param exceptionEventId The exception event ID to delete
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of rows deleted
     */
    fun deleteException(exceptionEventId: Long, asSyncAdapter: Boolean = true): Int {
        return deleteEvent(exceptionEventId, asSyncAdapter)
    }

    /**
     * Delete all exceptions for a master recurring event.
     *
     * @param masterEventId The master event ID
     * @param asSyncAdapter Whether to operate as sync adapter
     * @return Number of exceptions deleted
     */
    fun deleteAllExceptions(masterEventId: Long, asSyncAdapter: Boolean = true): Int {
        // First get the master's sync ID
        val masterSyncId = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterEventId),
            arrayOf(Events._SYNC_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(Events._SYNC_ID) else null
        } ?: return 0

        val uri = if (asSyncAdapter) {
            SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)
        } else {
            Events.CONTENT_URI
        }

        return contentResolver.delete(
            uri,
            "${Events.ORIGINAL_SYNC_ID} = ?",
            arrayOf(masterSyncId)
        )
    }

    /**
     * Find the master event for an exception event.
     *
     * @param exceptionEventId The exception event ID
     * @return Pair of (masterEventId, masterEvent) or null if not found
     */
    fun findMasterEvent(exceptionEventId: Long): Pair<Long, ICalEvent>? {
        // Get the ORIGINAL_SYNC_ID from the exception
        val originalSyncId = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, exceptionEventId),
            arrayOf(Events.ORIGINAL_SYNC_ID, Events.CALENDAR_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(Events.ORIGINAL_SYNC_ID) else null
        } ?: return null

        // Find the master event by its sync ID
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events._SYNC_ID} = ? AND ${Events.ORIGINAL_SYNC_ID} IS NULL AND ${Events.DELETED} = 0",
            arrayOf(originalSyncId),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val masterId = cursor.getLongOrDefault(Events._ID, -1)
                if (masterId >= 0) {
                    return masterId to CalendarContractMapper.fromCursor(cursor)
                }
            }
        }

        return null
    }

    /**
     * Check if an event is an exception (has ORIGINAL_SYNC_ID).
     *
     * @param eventId The event ID to check
     * @return True if the event is an exception to a recurring event
     */
    fun isExceptionEvent(eventId: Long): Boolean {
        contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.ORIGINAL_SYNC_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getStringOrNull(Events.ORIGINAL_SYNC_ID) != null
            }
        }
        return false
    }

    /**
     * Check if an event is a master recurring event (has RRULE and no ORIGINAL_SYNC_ID).
     *
     * @param eventId The event ID to check
     * @return True if the event is a master recurring event
     */
    fun isMasterEvent(eventId: Long): Boolean {
        contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.RRULE, Events.ORIGINAL_SYNC_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val hasRrule = cursor.getStringOrNull(Events.RRULE) != null
                val isException = cursor.getStringOrNull(Events.ORIGINAL_SYNC_ID) != null
                return hasRrule && !isException
            }
        }
        return false
    }
}
