package org.onekash.icaldav.android.provider

import android.content.ContentResolver
import android.provider.CalendarContract.Events
import org.onekash.icaldav.android.*
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.sync.LocalEventProvider

/**
 * Implementation of [LocalEventProvider] that reads events from Android's CalendarContract.
 *
 * This provider allows icaldav-sync's SyncEngine to read local events for
 * sync comparison operations.
 *
 * ## Usage
 *
 * ```kotlin
 * val provider = CalendarContractEventProvider(
 *     contentResolver = context.contentResolver,
 *     accountName = "user@example.com",
 *     accountType = "org.onekash.myapp",
 *     calendarIdProvider = { calDavUrl ->
 *         // Look up Android calendar ID by CalDAV URL
 *         calendarsMap[calDavUrl]?.id ?: -1
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
 * @param contentResolver The ContentResolver for calendar queries
 * @param accountName The account name for queries
 * @param accountType The account type for queries
 * @param calendarIdProvider Function to resolve CalDAV URL to Android calendar ID
 */
class CalendarContractEventProvider(
    private val contentResolver: ContentResolver,
    private val accountName: String,
    private val accountType: String,
    private val calendarIdProvider: (calDavUrl: String) -> Long
) : LocalEventProvider {

    /**
     * Get all local events for a calendar.
     *
     * @param calendarUrl The CalDAV calendar collection URL
     * @return List of events in the calendar (excludes deleted)
     */
    override fun getLocalEvents(calendarUrl: String): List<ICalEvent> {
        val calendarId = calendarIdProvider(calendarUrl)
        if (calendarId < 0) return emptyList()

        val events = mutableListOf<ICalEvent>()
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    events.add(CalendarContractMapper.fromCursor(cursor))
                } catch (e: Exception) {
                    // Skip malformed events
                }
            }
        }

        return events
    }

    /**
     * Get local event by importId.
     *
     * @param importId The event's importId (uid or uid:RECID:datetime)
     * @return The event if found, null otherwise
     */
    override fun getEventByImportId(importId: String): ICalEvent? {
        // Parse importId to extract UID
        val (uid, _) = ICalEvent.parseImportId(importId)

        // Query by _SYNC_ID (which is the UID)
        contentResolver.query(
            Events.CONTENT_URI,
            null,
            "${Events._SYNC_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(uid),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val event = CalendarContractMapper.fromCursor(cursor)
                    // Match by full importId
                    if (event.importId == importId) {
                        return event
                    }
                } catch (e: Exception) {
                    // Skip malformed events
                }
            }
        }

        return null
    }

    /**
     * Check if event exists locally.
     *
     * @param importId The event's importId
     * @return True if the event exists
     */
    override fun hasEvent(importId: String): Boolean {
        return getEventByImportId(importId) != null
    }
}
