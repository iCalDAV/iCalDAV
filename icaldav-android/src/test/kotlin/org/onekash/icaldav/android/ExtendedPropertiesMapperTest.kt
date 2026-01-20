package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.ExtendedProperties
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ExtendedPropertiesMapper].
 *
 * Tests verify mapping of X-* properties and CATEGORIES to ExtendedProperties table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ExtendedPropertiesMapperTest {

    // ==================== toContentValues Tests ====================

    @Test
    fun `toContentValues sets NAME lowercase`() {
        val values = ExtendedPropertiesMapper.toContentValues(
            name = "X-MY-CUSTOM-PROP",
            value = "test value",
            eventId = 42L
        )

        assertThat(values.getAsString(ExtendedProperties.NAME)).isEqualTo("x-my-custom-prop")
        assertThat(values.getAsString(ExtendedProperties.VALUE)).isEqualTo("test value")
        assertThat(values.getAsLong(ExtendedProperties.EVENT_ID)).isEqualTo(42L)
    }

    @Test
    fun `toContentValues preserves value exactly`() {
        val value = "Value with\nnewlines and \"quotes\""
        val values = ExtendedPropertiesMapper.toContentValues("x-prop", value, 1L)

        assertThat(values.getAsString(ExtendedProperties.VALUE)).isEqualTo(value)
    }

    // ==================== CATEGORIES Tests ====================

    @Test
    fun `categoriesToContentValues joins with comma`() {
        val values = ExtendedPropertiesMapper.categoriesToContentValues(
            categories = listOf("Work", "Important", "Meeting"),
            eventId = 42L
        )

        assertThat(values.getAsString(ExtendedProperties.NAME)).isEqualTo("categories")
        assertThat(values.getAsString(ExtendedProperties.VALUE)).isEqualTo("Work,Important,Meeting")
    }

    @Test
    fun `parseCategories splits by comma`() {
        val categories = ExtendedPropertiesMapper.parseCategories("Work,Important,Meeting")

        assertThat(categories).containsExactly("Work", "Important", "Meeting")
    }

    @Test
    fun `parseCategories trims whitespace`() {
        val categories = ExtendedPropertiesMapper.parseCategories(" Work , Important , Meeting ")

        assertThat(categories).containsExactly("Work", "Important", "Meeting")
    }

    @Test
    fun `parseCategories returns empty list for blank string`() {
        assertThat(ExtendedPropertiesMapper.parseCategories("")).isEmpty()
        assertThat(ExtendedPropertiesMapper.parseCategories("  ")).isEmpty()
    }

    @Test
    fun `parseCategories filters empty segments`() {
        val categories = ExtendedPropertiesMapper.parseCategories("Work,,Important,")

        assertThat(categories).containsExactly("Work", "Important")
    }

    // ==================== Property Type Detection Tests ====================

    @Test
    fun `isXProperty returns true for x- prefix`() {
        assertThat(ExtendedPropertiesMapper.isXProperty("x-custom")).isTrue()
        assertThat(ExtendedPropertiesMapper.isXProperty("X-CUSTOM")).isTrue()
        assertThat(ExtendedPropertiesMapper.isXProperty("X-")).isTrue()
    }

    @Test
    fun `isXProperty returns false for non x- prefix`() {
        assertThat(ExtendedPropertiesMapper.isXProperty("categories")).isFalse()
        assertThat(ExtendedPropertiesMapper.isXProperty("SUMMARY")).isFalse()
        assertThat(ExtendedPropertiesMapper.isXProperty("")).isFalse()
    }

    @Test
    fun `isCategoriesProperty is case insensitive`() {
        assertThat(ExtendedPropertiesMapper.isCategoriesProperty("categories")).isTrue()
        assertThat(ExtendedPropertiesMapper.isCategoriesProperty("CATEGORIES")).isTrue()
        assertThat(ExtendedPropertiesMapper.isCategoriesProperty("Categories")).isTrue()
    }

    // ==================== rawProperties Conversion Tests ====================

    @Test
    fun `rawPropertiesToContentValues filters to X- properties only`() {
        val rawProperties = mapOf(
            "X-CUSTOM-PROP" to "value1",
            "X-ANOTHER" to "value2",
            "CLASS" to "PRIVATE", // Not X-* property
            "SUMMARY" to "Title"  // Not X-* property
        )

        val contentValuesList = ExtendedPropertiesMapper.rawPropertiesToContentValues(
            rawProperties, eventId = 42L
        )

        assertThat(contentValuesList).hasSize(2)
    }

    @Test
    fun `toRawPropertiesMap converts to uppercase keys`() {
        val properties = listOf(
            "x-custom-prop" to "value1",
            "x-another" to "value2",
            "categories" to "Work" // Not X-* property
        )

        val map = ExtendedPropertiesMapper.toRawPropertiesMap(properties)

        assertThat(map).hasSize(2)
        assertThat(map["X-CUSTOM-PROP"]).isEqualTo("value1")
        assertThat(map["X-ANOTHER"]).isEqualTo("value2")
    }
}
