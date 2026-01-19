package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.provider.CalendarContract.Calendars

/**
 * Maps calendar metadata to Android's CalendarContract.Calendars table.
 *
 * This mapper handles the creation and reading of calendar entries for CalDAV sync.
 *
 * ## Sync Columns
 * - **CAL_SYNC1**: CalDAV calendar URL
 * - **CAL_SYNC2**: Sync token for incremental sync (RFC 6578)
 * - **CAL_SYNC3**: CTag for full sync detection
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Calendars">CalendarContract.Calendars</a>
 */
object CalendarMapper {

    /**
     * Sync column for storing CalDAV calendar URL.
     */
    const val SYNC_COLUMN_CALDAV_URL = Calendars.CAL_SYNC1

    /**
     * Sync column for storing sync token (RFC 6578).
     */
    const val SYNC_COLUMN_SYNC_TOKEN = Calendars.CAL_SYNC2

    /**
     * Sync column for storing CTag.
     */
    const val SYNC_COLUMN_CTAG = Calendars.CAL_SYNC3

    /**
     * Create ContentValues for inserting a new calendar.
     *
     * @param accountName The account name (e.g., user email)
     * @param accountType The account type (e.g., "org.onekash.icaldav")
     * @param displayName The user-visible calendar name
     * @param color The calendar color as ARGB int
     * @param calDavUrl The CalDAV calendar collection URL
     * @param syncEnabled Whether sync is enabled for this calendar
     * @param visible Whether the calendar is visible in UI
     * @param accessLevel The access level (default: CAL_ACCESS_OWNER)
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun toContentValues(
        accountName: String,
        accountType: String,
        displayName: String,
        color: Int,
        calDavUrl: String,
        syncEnabled: Boolean = true,
        visible: Boolean = true,
        accessLevel: Int = Calendars.CAL_ACCESS_OWNER
    ): ContentValues {
        return ContentValues().apply {
            // Account association (required)
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)

            // Calendar identification
            put(Calendars.NAME, calDavUrl) // Internal name = CalDAV URL for uniqueness
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)

            // Appearance
            put(Calendars.CALENDAR_COLOR, color)
            put(Calendars.VISIBLE, if (visible) 1 else 0)

            // Sync settings
            put(Calendars.SYNC_EVENTS, if (syncEnabled) 1 else 0)

            // Access level
            put(Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)

            // Store CalDAV URL for sync operations
            put(SYNC_COLUMN_CALDAV_URL, calDavUrl)

            // Owner account (for organizer display)
            put(Calendars.OWNER_ACCOUNT, accountName)
        }
    }

    /**
     * Create ContentValues for updating calendar sync state.
     *
     * @param syncToken The new sync token from server (or null to clear)
     * @param ctag The new CTag from server (or null to clear)
     * @return ContentValues for ContentResolver.update()
     */
    fun toSyncStateValues(
        syncToken: String?,
        ctag: String?
    ): ContentValues {
        return ContentValues().apply {
            if (syncToken != null) {
                put(SYNC_COLUMN_SYNC_TOKEN, syncToken)
            } else {
                putNull(SYNC_COLUMN_SYNC_TOKEN)
            }
            if (ctag != null) {
                put(SYNC_COLUMN_CTAG, ctag)
            } else {
                putNull(SYNC_COLUMN_CTAG)
            }
        }
    }

    /**
     * Parse calendar metadata from a Cursor.
     *
     * @param cursor A cursor positioned at a valid Calendars row
     * @return CalendarInfo containing the calendar's metadata
     */
    fun fromCursor(cursor: Cursor): CalendarInfo {
        return CalendarInfo(
            id = cursor.getLongOrDefault(Calendars._ID, -1),
            accountName = cursor.getStringOrDefault(Calendars.ACCOUNT_NAME, ""),
            accountType = cursor.getStringOrDefault(Calendars.ACCOUNT_TYPE, ""),
            displayName = cursor.getStringOrNull(Calendars.CALENDAR_DISPLAY_NAME),
            color = cursor.getIntOrDefault(Calendars.CALENDAR_COLOR, Color.BLUE),
            calDavUrl = cursor.getStringOrNull(SYNC_COLUMN_CALDAV_URL),
            syncToken = cursor.getStringOrNull(SYNC_COLUMN_SYNC_TOKEN),
            ctag = cursor.getStringOrNull(SYNC_COLUMN_CTAG),
            syncEnabled = cursor.getIntOrDefault(Calendars.SYNC_EVENTS, 1) == 1,
            visible = cursor.getIntOrDefault(Calendars.VISIBLE, 1) == 1,
            accessLevel = cursor.getIntOrDefault(
                Calendars.CALENDAR_ACCESS_LEVEL,
                Calendars.CAL_ACCESS_OWNER
            )
        )
    }
}

/**
 * Data class representing calendar metadata from CalendarContract.
 */
data class CalendarInfo(
    /** Android calendar ID (from _ID column) */
    val id: Long,

    /** Account name associated with this calendar */
    val accountName: String,

    /** Account type (e.g., "org.onekash.icaldav") */
    val accountType: String,

    /** User-visible display name */
    val displayName: String?,

    /** Calendar color as ARGB int */
    val color: Int,

    /** CalDAV calendar collection URL */
    val calDavUrl: String?,

    /** RFC 6578 sync token for incremental sync */
    val syncToken: String?,

    /** CTag for full sync detection */
    val ctag: String?,

    /** Whether sync is enabled */
    val syncEnabled: Boolean,

    /** Whether calendar is visible in UI */
    val visible: Boolean,

    /** Access level (CAL_ACCESS_*) */
    val accessLevel: Int
)
