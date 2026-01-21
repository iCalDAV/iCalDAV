package org.onekash.icaldav.android

import android.database.MatrixCursor
import android.graphics.Color
import android.provider.CalendarContract.Calendars
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for CalendarMapper - mapping between CalDAV and Android CalendarContract.
 *
 * Uses Robolectric to provide Android framework classes (ContentValues, Cursor, etc.).
 * Uses Google Truth for fluent assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CalendarMapperTest {

    // ==================== toContentValues Tests ====================

    @Test
    fun `toContentValues creates ContentValues with required fields`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/"
        )

        assertThat(values.getAsString(Calendars.ACCOUNT_NAME)).isEqualTo("user@example.com")
        assertThat(values.getAsString(Calendars.ACCOUNT_TYPE)).isEqualTo("org.onekash.icaldav")
        assertThat(values.getAsString(Calendars.CALENDAR_DISPLAY_NAME)).isEqualTo("My Calendar")
        assertThat(values.getAsInteger(Calendars.CALENDAR_COLOR)).isEqualTo(Color.BLUE)
        assertThat(values.getAsString(CalendarMapper.SYNC_COLUMN_CALDAV_URL))
            .isEqualTo("https://server.com/calendars/user/default/")
    }

    @Test
    fun `toContentValues sets NAME to CalDAV URL for uniqueness`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/"
        )

        // NAME column should be the CalDAV URL for uniqueness
        assertThat(values.getAsString(Calendars.NAME))
            .isEqualTo("https://server.com/calendars/user/default/")
    }

    @Test
    fun `toContentValues sets default sync enabled and visible`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/"
        )

        // Default: sync enabled = true (1), visible = true (1)
        assertThat(values.getAsInteger(Calendars.SYNC_EVENTS)).isEqualTo(1)
        assertThat(values.getAsInteger(Calendars.VISIBLE)).isEqualTo(1)
    }

    @Test
    fun `toContentValues respects sync disabled flag`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/",
            syncEnabled = false
        )

        assertThat(values.getAsInteger(Calendars.SYNC_EVENTS)).isEqualTo(0)
    }

    @Test
    fun `toContentValues respects invisible flag`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/",
            visible = false
        )

        assertThat(values.getAsInteger(Calendars.VISIBLE)).isEqualTo(0)
    }

    @Test
    fun `toContentValues sets default access level to OWNER`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/"
        )

        assertThat(values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL))
            .isEqualTo(Calendars.CAL_ACCESS_OWNER)
    }

    @Test
    fun `toContentValues respects custom access level`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/",
            accessLevel = Calendars.CAL_ACCESS_READ
        )

        assertThat(values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL))
            .isEqualTo(Calendars.CAL_ACCESS_READ)
    }

    @Test
    fun `toContentValues sets owner account`() {
        val values = CalendarMapper.toContentValues(
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "My Calendar",
            color = Color.BLUE,
            calDavUrl = "https://server.com/calendars/user/default/"
        )

        assertThat(values.getAsString(Calendars.OWNER_ACCOUNT)).isEqualTo("user@example.com")
    }

    // ==================== toSyncStateValues Tests ====================

    @Test
    fun `toSyncStateValues sets both syncToken and ctag`() {
        val values = CalendarMapper.toSyncStateValues(
            syncToken = "sync-token-123",
            ctag = "ctag-456"
        )

        assertThat(values.getAsString(CalendarMapper.SYNC_COLUMN_SYNC_TOKEN))
            .isEqualTo("sync-token-123")
        assertThat(values.getAsString(CalendarMapper.SYNC_COLUMN_CTAG))
            .isEqualTo("ctag-456")
    }

    @Test
    fun `toSyncStateValues handles null syncToken`() {
        val values = CalendarMapper.toSyncStateValues(
            syncToken = null,
            ctag = "ctag-456"
        )

        assertThat(values.containsKey(CalendarMapper.SYNC_COLUMN_SYNC_TOKEN)).isTrue()
        assertThat(values.getAsString(CalendarMapper.SYNC_COLUMN_SYNC_TOKEN)).isNull()
    }

    @Test
    fun `toSyncStateValues handles null ctag`() {
        val values = CalendarMapper.toSyncStateValues(
            syncToken = "sync-token-123",
            ctag = null
        )

        assertThat(values.containsKey(CalendarMapper.SYNC_COLUMN_CTAG)).isTrue()
        assertThat(values.getAsString(CalendarMapper.SYNC_COLUMN_CTAG)).isNull()
    }

    @Test
    fun `toSyncStateValues handles both null`() {
        val values = CalendarMapper.toSyncStateValues(
            syncToken = null,
            ctag = null
        )

        assertThat(values.containsKey(CalendarMapper.SYNC_COLUMN_SYNC_TOKEN)).isTrue()
        assertThat(values.containsKey(CalendarMapper.SYNC_COLUMN_CTAG)).isTrue()
    }

    // ==================== fromCursor Tests ====================

    @Test
    fun `fromCursor parses all calendar fields`() {
        val cursor = createCalendarCursor(
            id = 42L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            displayName = "Work Calendar",
            color = Color.RED,
            calDavUrl = "https://server.com/calendars/user/work/",
            syncToken = "token-abc",
            ctag = "ctag-xyz",
            syncEnabled = true,
            visible = true,
            accessLevel = Calendars.CAL_ACCESS_OWNER,
            isPrimary = true,
            maxReminders = 10
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.id).isEqualTo(42L)
        assertThat(info.accountName).isEqualTo("user@example.com")
        assertThat(info.accountType).isEqualTo("org.onekash.icaldav")
        assertThat(info.displayName).isEqualTo("Work Calendar")
        assertThat(info.color).isEqualTo(Color.RED)
        assertThat(info.calDavUrl).isEqualTo("https://server.com/calendars/user/work/")
        assertThat(info.syncToken).isEqualTo("token-abc")
        assertThat(info.ctag).isEqualTo("ctag-xyz")
        assertThat(info.syncEnabled).isTrue()
        assertThat(info.visible).isTrue()
        assertThat(info.accessLevel).isEqualTo(Calendars.CAL_ACCESS_OWNER)
        assertThat(info.isPrimary).isTrue()
        assertThat(info.maxReminders).isEqualTo(10)
    }

    @Test
    fun `fromCursor handles missing optional fields`() {
        val cursor = createMinimalCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav"
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.displayName).isNull()
        assertThat(info.calDavUrl).isNull()
        assertThat(info.syncToken).isNull()
        assertThat(info.ctag).isNull()
    }

    @Test
    fun `fromCursor handles sync disabled`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            syncEnabled = false,
            visible = true
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.syncEnabled).isFalse()
    }

    @Test
    fun `fromCursor handles invisible calendar`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            syncEnabled = true,
            visible = false
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.visible).isFalse()
    }

    @Test
    fun `fromCursor parses allowed reminders`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            allowedReminders = "0,1,2"  // METHOD_DEFAULT, METHOD_ALERT, METHOD_EMAIL
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.allowedReminders).containsExactly(0, 1, 2)
    }

    @Test
    fun `fromCursor parses allowed availability`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            allowedAvailability = "0,1"  // AVAILABILITY_BUSY, AVAILABILITY_FREE
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.allowedAvailability).containsExactly(0, 1)
    }

    @Test
    fun `fromCursor parses allowed attendee types`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            allowedAttendeeTypes = "1,2"  // TYPE_REQUIRED, TYPE_OPTIONAL
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.allowedAttendeeTypes).containsExactly(1, 2)
    }

    @Test
    fun `fromCursor handles null allowed reminders`() {
        val cursor = createCalendarCursor(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            allowedReminders = null
        )
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        assertThat(info.allowedReminders).isNull()
    }

    @Test
    fun `fromCursor provides default values for missing columns`() {
        // Create cursor with minimal columns
        val columns = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE
        )
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf(1L, "user@example.com", "org.onekash.icaldav"))
        cursor.moveToFirst()

        val info = CalendarMapper.fromCursor(cursor)

        // Should use defaults when columns are missing
        assertThat(info.id).isEqualTo(1L)
        assertThat(info.accountName).isEqualTo("user@example.com")
        assertThat(info.color).isEqualTo(Color.BLUE) // Default color
        assertThat(info.syncEnabled).isTrue() // Default
        assertThat(info.visible).isTrue() // Default
        assertThat(info.maxReminders).isEqualTo(5) // Default
    }

    // ==================== Sync Column Constants Tests ====================

    @Test
    fun `sync column constants are defined correctly`() {
        assertThat(CalendarMapper.SYNC_COLUMN_CALDAV_URL).isEqualTo(Calendars.CAL_SYNC1)
        assertThat(CalendarMapper.SYNC_COLUMN_SYNC_TOKEN).isEqualTo(Calendars.CAL_SYNC2)
        assertThat(CalendarMapper.SYNC_COLUMN_CTAG).isEqualTo(Calendars.CAL_SYNC3)
    }

    // ==================== Helper Methods ====================

    private fun createCalendarCursor(
        id: Long,
        accountName: String,
        accountType: String,
        displayName: String? = null,
        color: Int = Color.BLUE,
        calDavUrl: String? = null,
        syncToken: String? = null,
        ctag: String? = null,
        syncEnabled: Boolean = true,
        visible: Boolean = true,
        accessLevel: Int = Calendars.CAL_ACCESS_OWNER,
        isPrimary: Boolean = false,
        maxReminders: Int = 5,
        allowedReminders: String? = null,
        allowedAvailability: String? = null,
        allowedAttendeeTypes: String? = null
    ): MatrixCursor {
        val columns = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_COLOR,
            CalendarMapper.SYNC_COLUMN_CALDAV_URL,
            CalendarMapper.SYNC_COLUMN_SYNC_TOKEN,
            CalendarMapper.SYNC_COLUMN_CTAG,
            Calendars.SYNC_EVENTS,
            Calendars.VISIBLE,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.MAX_REMINDERS,
            Calendars.ALLOWED_REMINDERS,
            Calendars.ALLOWED_AVAILABILITY,
            Calendars.ALLOWED_ATTENDEE_TYPES
        )

        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf(
            id,
            accountName,
            accountType,
            displayName,
            color,
            calDavUrl,
            syncToken,
            ctag,
            if (syncEnabled) 1 else 0,
            if (visible) 1 else 0,
            accessLevel,
            if (isPrimary) 1 else 0,
            maxReminders,
            allowedReminders,
            allowedAvailability,
            allowedAttendeeTypes
        ))
        return cursor
    }

    private fun createMinimalCalendarCursor(
        id: Long,
        accountName: String,
        accountType: String
    ): MatrixCursor {
        val columns = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_COLOR,
            CalendarMapper.SYNC_COLUMN_CALDAV_URL,
            CalendarMapper.SYNC_COLUMN_SYNC_TOKEN,
            CalendarMapper.SYNC_COLUMN_CTAG,
            Calendars.SYNC_EVENTS,
            Calendars.VISIBLE,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.MAX_REMINDERS,
            Calendars.ALLOWED_REMINDERS,
            Calendars.ALLOWED_AVAILABILITY,
            Calendars.ALLOWED_ATTENDEE_TYPES
        )

        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf(
            id,
            accountName,
            accountType,
            null, // displayName
            Color.BLUE, // color
            null, // calDavUrl
            null, // syncToken
            null, // ctag
            1, // syncEnabled
            1, // visible
            Calendars.CAL_ACCESS_OWNER,
            0, // isPrimary
            5, // maxReminders
            null, // allowedReminders
            null, // allowedAvailability
            null  // allowedAttendeeTypes
        ))
        return cursor
    }
}
