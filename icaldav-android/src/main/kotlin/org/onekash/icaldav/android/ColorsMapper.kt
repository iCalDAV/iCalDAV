package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Colors

/**
 * Maps CalendarContract.Colors table for custom color palettes.
 *
 * The Colors table allows sync adapters to define custom color palettes
 * for calendars and events. Each color has a semantic key (e.g., "red", "blue")
 * that can be referenced from the Calendars and Events tables.
 *
 * ## Color Types
 *
 * - **TYPE_CALENDAR (0)**: Colors for calendar display
 * - **TYPE_EVENT (1)**: Colors for event display
 *
 * ## Important Notes
 *
 * - Colors table requires sync adapter context for modifications
 * - Each color key must be unique within an account and color type
 * - Colors are stored as ARGB integers (0xAARRGGBB format)
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Colors">CalendarContract.Colors</a>
 */
object ColorsMapper {

    /**
     * Color type for calendar colors.
     */
    const val TYPE_CALENDAR = Colors.TYPE_CALENDAR

    /**
     * Color type for event colors.
     */
    const val TYPE_EVENT = Colors.TYPE_EVENT

    /**
     * Projection for color queries.
     */
    val PROJECTION = arrayOf(
        Colors._ID,
        Colors.COLOR_KEY,
        Colors.COLOR,
        Colors.COLOR_TYPE,
        Colors.ACCOUNT_NAME,
        Colors.ACCOUNT_TYPE
    )

    /**
     * Column indices for efficient cursor access.
     */
    private const val COL_ID = 0
    private const val COL_COLOR_KEY = 1
    private const val COL_COLOR = 2
    private const val COL_COLOR_TYPE = 3
    private const val COL_ACCOUNT_NAME = 4
    private const val COL_ACCOUNT_TYPE = 5

    /**
     * Parse a CalendarColor from a cursor positioned at a valid row.
     *
     * @param cursor Cursor positioned at a valid Colors row
     * @return Parsed CalendarColor
     */
    fun fromCursor(cursor: Cursor): CalendarColor {
        return CalendarColor(
            id = cursor.getLong(COL_ID),
            colorKey = cursor.getString(COL_COLOR_KEY),
            color = cursor.getInt(COL_COLOR),
            colorType = cursor.getInt(COL_COLOR_TYPE),
            accountName = cursor.getString(COL_ACCOUNT_NAME),
            accountType = cursor.getString(COL_ACCOUNT_TYPE)
        )
    }

    /**
     * Parse a CalendarColor using column names (for queries with custom projections).
     *
     * @param cursor Cursor positioned at a valid Colors row
     * @return Parsed CalendarColor
     */
    fun fromCursorByName(cursor: Cursor): CalendarColor {
        return CalendarColor(
            id = cursor.getLongOrDefault(Colors._ID, 0),
            colorKey = cursor.getStringOrDefault(Colors.COLOR_KEY, ""),
            color = cursor.getIntOrDefault(Colors.COLOR, 0),
            colorType = cursor.getIntOrDefault(Colors.COLOR_TYPE, TYPE_EVENT),
            accountName = cursor.getStringOrNull(Colors.ACCOUNT_NAME),
            accountType = cursor.getStringOrNull(Colors.ACCOUNT_TYPE)
        )
    }

    /**
     * Create ContentValues for inserting a new color.
     *
     * @param color The CalendarColor to insert
     * @param accountName The account name for this color
     * @param accountType The account type for this color
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun toContentValues(
        color: CalendarColor,
        accountName: String,
        accountType: String
    ): ContentValues {
        return ContentValues().apply {
            put(Colors.COLOR_KEY, color.colorKey)
            put(Colors.COLOR, color.color)
            put(Colors.COLOR_TYPE, color.colorType)
            put(Colors.ACCOUNT_NAME, accountName)
            put(Colors.ACCOUNT_TYPE, accountType)
            // DATA column can be used for additional data
            color.data?.let { put(Colors.DATA, it) }
        }
    }

    /**
     * Parse a CSS color string to ARGB integer.
     *
     * Supports formats:
     * - "#RGB" (e.g., "#F00" → red)
     * - "#RRGGBB" (e.g., "#FF0000" → red)
     * - "#AARRGGBB" (e.g., "#80FF0000" → semi-transparent red)
     *
     * @param cssColor CSS color string
     * @return ARGB integer, or null if parsing fails
     */
    fun parseColorString(cssColor: String): Int? {
        val color = cssColor.trim()
        if (!color.startsWith("#")) return null

        return try {
            when (color.length) {
                4 -> { // #RGB
                    val r = color[1].digitToInt(16) * 0x11
                    val g = color[2].digitToInt(16) * 0x11
                    val b = color[3].digitToInt(16) * 0x11
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                7 -> { // #RRGGBB
                    val rgb = color.substring(1).toLong(16).toInt()
                    (0xFF shl 24) or rgb
                }
                9 -> { // #AARRGGBB
                    color.substring(1).toLong(16).toInt()
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Format an ARGB integer as a CSS color string.
     *
     * @param argb ARGB color integer
     * @param includeAlpha Whether to include alpha channel (default: false)
     * @return CSS color string (e.g., "#FF0000" or "#80FF0000")
     */
    fun formatColorString(argb: Int, includeAlpha: Boolean = false): String {
        return if (includeAlpha) {
            String.format("#%08X", argb)
        } else {
            String.format("#%06X", argb and 0xFFFFFF)
        }
    }

    /**
     * Extract the alpha component (0-255) from an ARGB color.
     */
    fun getAlpha(argb: Int): Int = (argb shr 24) and 0xFF

    /**
     * Extract the red component (0-255) from an ARGB color.
     */
    fun getRed(argb: Int): Int = (argb shr 16) and 0xFF

    /**
     * Extract the green component (0-255) from an ARGB color.
     */
    fun getGreen(argb: Int): Int = (argb shr 8) and 0xFF

    /**
     * Extract the blue component (0-255) from an ARGB color.
     */
    fun getBlue(argb: Int): Int = argb and 0xFF

    /**
     * Create an ARGB color from components.
     *
     * @param alpha Alpha component (0-255)
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @return ARGB color integer
     */
    fun createColor(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha and 0xFF) shl 24) or
               ((red and 0xFF) shl 16) or
               ((green and 0xFF) shl 8) or
               (blue and 0xFF)
    }

    /**
     * Create an opaque RGB color.
     *
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @return ARGB color integer with full alpha
     */
    fun createOpaqueColor(red: Int, green: Int, blue: Int): Int {
        return createColor(0xFF, red, green, blue)
    }
}

/**
 * Represents a color entry from CalendarContract.Colors table.
 *
 * @property id The color ID (from _ID column)
 * @property colorKey Semantic key for this color (e.g., "red", "blue", "custom1")
 * @property color The ARGB color value
 * @property colorType TYPE_CALENDAR (0) or TYPE_EVENT (1)
 * @property accountName Account name this color belongs to (optional for read)
 * @property accountType Account type this color belongs to (optional for read)
 * @property data Optional additional data stored with the color
 */
data class CalendarColor(
    val id: Long = 0,
    val colorKey: String,
    val color: Int,
    val colorType: Int,
    val accountName: String? = null,
    val accountType: String? = null,
    val data: String? = null
) {
    /**
     * Whether this is a calendar color.
     */
    val isCalendarColor: Boolean get() = colorType == ColorsMapper.TYPE_CALENDAR

    /**
     * Whether this is an event color.
     */
    val isEventColor: Boolean get() = colorType == ColorsMapper.TYPE_EVENT

    /**
     * Get the color as a CSS string (e.g., "#FF0000").
     */
    val cssColor: String get() = ColorsMapper.formatColorString(color)

    /**
     * Get the alpha component (0-255).
     */
    val alpha: Int get() = ColorsMapper.getAlpha(color)

    /**
     * Get the red component (0-255).
     */
    val red: Int get() = ColorsMapper.getRed(color)

    /**
     * Get the green component (0-255).
     */
    val green: Int get() = ColorsMapper.getGreen(color)

    /**
     * Get the blue component (0-255).
     */
    val blue: Int get() = ColorsMapper.getBlue(color)
}
