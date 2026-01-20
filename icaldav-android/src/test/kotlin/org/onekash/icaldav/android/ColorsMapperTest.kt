package org.onekash.icaldav.android

import android.database.MatrixCursor
import android.os.Build
import android.provider.CalendarContract.Colors
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ColorsMapper].
 *
 * Tests verify cursor parsing, ContentValues creation, and color utility functions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ColorsMapperTest {

    // ==================== Cursor Parsing ====================

    @Test
    fun `fromCursor parses all fields correctly`() {
        val cursor = createTestCursor(
            id = 100L,
            colorKey = "red",
            color = 0xFFFF0000.toInt(),
            colorType = ColorsMapper.TYPE_EVENT,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav"
        )
        cursor.moveToFirst()

        val calendarColor = ColorsMapper.fromCursor(cursor)

        assertThat(calendarColor.id).isEqualTo(100L)
        assertThat(calendarColor.colorKey).isEqualTo("red")
        assertThat(calendarColor.color).isEqualTo(0xFFFF0000.toInt())
        assertThat(calendarColor.colorType).isEqualTo(ColorsMapper.TYPE_EVENT)
        assertThat(calendarColor.accountName).isEqualTo("user@example.com")
        assertThat(calendarColor.accountType).isEqualTo("org.onekash.icaldav")
    }

    @Test
    fun `fromCursor parses calendar color type`() {
        val cursor = createTestCursor(
            id = 1L,
            colorKey = "blue",
            color = 0xFF0000FF.toInt(),
            colorType = ColorsMapper.TYPE_CALENDAR
        )
        cursor.moveToFirst()

        val calendarColor = ColorsMapper.fromCursor(cursor)

        assertThat(calendarColor.colorType).isEqualTo(ColorsMapper.TYPE_CALENDAR)
        assertThat(calendarColor.isCalendarColor).isTrue()
        assertThat(calendarColor.isEventColor).isFalse()
    }

    // ==================== ContentValues Creation ====================

    @Test
    fun `toContentValues creates correct values`() {
        val color = CalendarColor(
            colorKey = "green",
            color = 0xFF00FF00.toInt(),
            colorType = ColorsMapper.TYPE_EVENT
        )

        val values = ColorsMapper.toContentValues(color, "user@example.com", "org.onekash.icaldav")

        assertThat(values.getAsString(Colors.COLOR_KEY)).isEqualTo("green")
        assertThat(values.getAsInteger(Colors.COLOR)).isEqualTo(0xFF00FF00.toInt())
        assertThat(values.getAsInteger(Colors.COLOR_TYPE)).isEqualTo(ColorsMapper.TYPE_EVENT)
        assertThat(values.getAsString(Colors.ACCOUNT_NAME)).isEqualTo("user@example.com")
        assertThat(values.getAsString(Colors.ACCOUNT_TYPE)).isEqualTo("org.onekash.icaldav")
    }

    @Test
    fun `toContentValues includes optional data field`() {
        val color = CalendarColor(
            colorKey = "custom",
            color = 0xFFABCDEF.toInt(),
            colorType = ColorsMapper.TYPE_EVENT,
            data = "extra-metadata"
        )

        val values = ColorsMapper.toContentValues(color, "user@example.com", "org.onekash.icaldav")

        assertThat(values.getAsString(Colors.DATA)).isEqualTo("extra-metadata")
    }

    // ==================== Color String Parsing ====================

    @Test
    fun `parseColorString handles 6-digit hex`() {
        val result = ColorsMapper.parseColorString("#FF0000")

        assertThat(result).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `parseColorString handles 8-digit hex with alpha`() {
        val result = ColorsMapper.parseColorString("#80FF0000")

        assertThat(result).isEqualTo(0x80FF0000.toInt())
    }

    @Test
    fun `parseColorString handles 3-digit shorthand`() {
        val result = ColorsMapper.parseColorString("#F00")

        assertThat(result).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `parseColorString returns null for invalid format`() {
        assertThat(ColorsMapper.parseColorString("invalid")).isNull()
        assertThat(ColorsMapper.parseColorString("#GGG")).isNull()
        assertThat(ColorsMapper.parseColorString("#FFFFF")).isNull() // 5 digits - invalid
        assertThat(ColorsMapper.parseColorString("")).isNull()
    }

    @Test
    fun `parseColorString handles lowercase hex`() {
        val result = ColorsMapper.parseColorString("#ff0000")

        assertThat(result).isEqualTo(0xFFFF0000.toInt())
    }

    // ==================== Color String Formatting ====================

    @Test
    fun `formatColorString formats RGB without alpha`() {
        val result = ColorsMapper.formatColorString(0xFFFF0000.toInt(), includeAlpha = false)

        assertThat(result).isEqualTo("#FF0000")
    }

    @Test
    fun `formatColorString formats ARGB with alpha`() {
        val result = ColorsMapper.formatColorString(0x80FF0000.toInt(), includeAlpha = true)

        assertThat(result).isEqualTo("#80FF0000")
    }

    // ==================== Color Component Extraction ====================

    @Test
    fun `getAlpha extracts alpha component`() {
        val color = 0x80FF0000.toInt()

        assertThat(ColorsMapper.getAlpha(color)).isEqualTo(0x80)
    }

    @Test
    fun `getRed extracts red component`() {
        val color = 0xFFFF0000.toInt()

        assertThat(ColorsMapper.getRed(color)).isEqualTo(0xFF)
    }

    @Test
    fun `getGreen extracts green component`() {
        val color = 0xFF00FF00.toInt()

        assertThat(ColorsMapper.getGreen(color)).isEqualTo(0xFF)
    }

    @Test
    fun `getBlue extracts blue component`() {
        val color = 0xFF0000FF.toInt()

        assertThat(ColorsMapper.getBlue(color)).isEqualTo(0xFF)
    }

    // ==================== Color Creation ====================

    @Test
    fun `createColor assembles ARGB correctly`() {
        val result = ColorsMapper.createColor(0x80, 0xFF, 0x00, 0x00)

        assertThat(result).isEqualTo(0x80FF0000.toInt())
    }

    @Test
    fun `createOpaqueColor creates fully opaque color`() {
        val result = ColorsMapper.createOpaqueColor(0xFF, 0x00, 0x00)

        assertThat(result).isEqualTo(0xFFFF0000.toInt())
    }

    // ==================== CalendarColor Data Class ====================

    @Test
    fun `CalendarColor cssColor property returns correct string`() {
        val color = CalendarColor(
            colorKey = "red",
            color = 0xFFFF0000.toInt(),
            colorType = ColorsMapper.TYPE_EVENT
        )

        assertThat(color.cssColor).isEqualTo("#FF0000")
    }

    @Test
    fun `CalendarColor component properties work correctly`() {
        val color = CalendarColor(
            colorKey = "mixed",
            color = 0x80AABBCC.toInt(),
            colorType = ColorsMapper.TYPE_EVENT
        )

        assertThat(color.alpha).isEqualTo(0x80)
        assertThat(color.red).isEqualTo(0xAA)
        assertThat(color.green).isEqualTo(0xBB)
        assertThat(color.blue).isEqualTo(0xCC)
    }

    @Test
    fun `CalendarColor isEventColor returns true for TYPE_EVENT`() {
        val color = CalendarColor(
            colorKey = "event",
            color = 0xFFFF0000.toInt(),
            colorType = ColorsMapper.TYPE_EVENT
        )

        assertThat(color.isEventColor).isTrue()
        assertThat(color.isCalendarColor).isFalse()
    }

    @Test
    fun `CalendarColor isCalendarColor returns true for TYPE_CALENDAR`() {
        val color = CalendarColor(
            colorKey = "calendar",
            color = 0xFF00FF00.toInt(),
            colorType = ColorsMapper.TYPE_CALENDAR
        )

        assertThat(color.isCalendarColor).isTrue()
        assertThat(color.isEventColor).isFalse()
    }

    // ==================== Round-trip Tests ====================

    @Test
    fun `color string round-trip preserves value`() {
        val original = 0xFF1A2B3C.toInt()

        val cssColor = ColorsMapper.formatColorString(original)
        val parsed = ColorsMapper.parseColorString(cssColor)

        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `color components round-trip preserves value`() {
        val original = 0xAABBCCDD.toInt()

        val reconstructed = ColorsMapper.createColor(
            ColorsMapper.getAlpha(original),
            ColorsMapper.getRed(original),
            ColorsMapper.getGreen(original),
            ColorsMapper.getBlue(original)
        )

        assertThat(reconstructed).isEqualTo(original)
    }

    // ==================== Helpers ====================

    private fun createTestCursor(
        id: Long,
        colorKey: String,
        color: Int,
        colorType: Int,
        accountName: String = "user@example.com",
        accountType: String = "org.onekash.icaldav"
    ): MatrixCursor {
        val cursor = MatrixCursor(ColorsMapper.PROJECTION)
        cursor.addRow(
            arrayOf(
                id,
                colorKey,
                color,
                colorType,
                accountName,
                accountType
            )
        )
        return cursor
    }
}
