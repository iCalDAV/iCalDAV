package org.onekash.icaldav.android.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Events
import org.onekash.icaldav.android.*
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.sync.SyncResultHandler
import org.onekash.icaldav.sync.SyncState

/**
 * Implementation of [SyncResultHandler] that writes sync results to Android's CalendarContract.
 *
 * This handler receives events from icaldav-sync's SyncEngine and persists them
 * to the Android system calendar.
 *
 * ## Usage
 *
 * ```kotlin
 * val handler = CalendarContractSyncHandler(
 *     contentResolver = context.contentResolver,
 *     accountName = "user@example.com",
 *     accountType = "org.onekash.myapp",
 *     calendarIdProvider = { calDavUrl ->
 *         calendarsMap[calDavUrl]?.id ?: -1
 *     },
 *     stateStorage = { state ->
 *         // Store sync state to preferences/database
 *         syncStatePrefs.saveSyncState(state)
 *     }
 * )
 *
 * val syncEngine = CalDavSyncEngine(
 *     client = calDavClient,
 *     localProvider = provider,
 *     resultHandler = handler,
 *     pendingStore = pendingStore
 * )
 * ```
 *
 * ## Sync State Storage
 *
 * The [SyncState] is passed to the [stateStorage] callback for persistence.
 * The caller is responsible for storing it (e.g., in SharedPreferences or Room).
 *
 * @param contentResolver The ContentResolver for calendar operations
 * @param accountName The account name for sync adapter operations
 * @param accountType The account type for sync adapter operations
 * @param calendarIdProvider Function to resolve CalDAV URL to Android calendar ID
 * @param stateStorage Callback to persist sync state
 */
class CalendarContractSyncHandler(
    private val contentResolver: ContentResolver,
    private val accountName: String,
    private val accountType: String,
    private val calendarIdProvider: (calDavUrl: String) -> Long,
    private val stateStorage: (SyncState) -> Unit
) : SyncResultHandler {

    // Track URL to calendar ID for upsert operations
    private var currentCalendarUrl: String? = null
    private var currentCalendarId: Long = -1

    /**
     * Called for each event that should be upserted.
     *
     * Inserts new events or updates existing events based on _SYNC_ID match.
     *
     * @param event The event to upsert
     * @param url The CalDAV event URL
     * @param etag The server's ETag (stored for conflict detection)
     */
    override fun upsertEvent(event: ICalEvent, url: String, etag: String?) {
        // Extract calendar URL from event URL
        val calendarUrl = extractCalendarUrl(url)
        val calendarId = resolveCalendarId(calendarUrl)
        if (calendarId < 0) {
            return // Unknown calendar
        }

        // Create ContentValues for the event
        val values = CalendarContractMapper.toContentValues(event, calendarId)

        // Store ETag for conflict detection
        etag?.let { values.put(Events.SYNC_DATA1, it) }

        // Store event URL for update operations
        values.put(Events.SYNC_DATA2, url)

        // Check if event exists
        val existing = findEventBySyncId(calendarId, event.uid)

        val syncAdapterUri = SyncAdapterUri.asSyncAdapter(
            Events.CONTENT_URI,
            accountName,
            accountType
        )

        if (existing != null) {
            // Update existing event
            val eventUri = SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(Events.CONTENT_URI, existing),
                accountName,
                accountType
            )
            contentResolver.update(eventUri, values, null, null)
        } else {
            // Insert new event
            contentResolver.insert(syncAdapterUri, values)
        }
    }

    /**
     * Called for each event that should be deleted.
     *
     * @param importId The event's importId (uid or uid:RECID:datetime)
     */
    override fun deleteEvent(importId: String) {
        val (uid, _) = ICalEvent.parseImportId(importId)

        // Find all events with this UID
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events._SYNC_ID),
            "${Events._SYNC_ID} = ?",
            arrayOf(uid),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLongOrNull(Events._ID) ?: continue
                val syncId = cursor.getStringOrNull(Events._SYNC_ID) ?: continue

                // Check if this matches the full importId
                // For simple events, uid == importId
                // For exceptions, need to verify recurrence-id match
                if (uid == importId || syncId == uid) {
                    val eventUri = SyncAdapterUri.asSyncAdapter(
                        ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                        accountName,
                        accountType
                    )
                    contentResolver.delete(eventUri, null, null)
                }
            }
        }
    }

    /**
     * Save sync state for next sync.
     *
     * @param state The sync state to persist
     */
    override fun saveSyncState(state: SyncState) {
        stateStorage(state)

        // Also update calendar sync columns
        val calendarId = calendarIdProvider(state.calendarUrl)
        if (calendarId >= 0) {
            val calendarValues = CalendarMapper.toSyncStateValues(
                syncToken = state.syncToken,
                ctag = state.ctag
            )
            val calendarUri = SyncAdapterUri.asSyncAdapter(
                ContentUris.withAppendedId(
                    android.provider.CalendarContract.Calendars.CONTENT_URI,
                    calendarId
                ),
                accountName,
                accountType
            )
            contentResolver.update(calendarUri, calendarValues, null, null)
        }
    }

    /**
     * Extract calendar URL from event URL.
     *
     * Event URLs are typically: {calendarUrl}/{eventUid}.ics
     */
    private fun extractCalendarUrl(eventUrl: String): String {
        val lastSlash = eventUrl.lastIndexOf('/')
        return if (lastSlash > 0) {
            eventUrl.substring(0, lastSlash)
        } else {
            eventUrl
        }
    }

    /**
     * Resolve calendar ID with caching.
     */
    private fun resolveCalendarId(calendarUrl: String): Long {
        if (calendarUrl == currentCalendarUrl && currentCalendarId >= 0) {
            return currentCalendarId
        }
        currentCalendarUrl = calendarUrl
        currentCalendarId = calendarIdProvider(calendarUrl)
        return currentCalendarId
    }

    /**
     * Find existing event by sync ID (UID).
     */
    private fun findEventBySyncId(calendarId: Long, syncId: String): Long? {
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events._SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString(), syncId),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLongOrNull(Events._ID)
            }
        }
        return null
    }
}
