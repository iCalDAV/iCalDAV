package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.ExtendedProperties

/**
 * Maps X-* properties and CATEGORIES to CalendarContract.ExtendedProperties table.
 *
 * ## ExtendedProperties Table Structure
 *
 * | Column | Type | Description |
 * |--------|------|-------------|
 * | EVENT_ID | long | FK to Events table |
 * | NAME | String | Property name (e.g., "x-my-prop", "categories") |
 * | VALUE | String | Property value |
 *
 * ## Naming Convention
 *
 * - **CATEGORIES**: Stored with name "categories" as comma-separated values
 * - **X-* properties**: Stored with lowercase name (e.g., "x-my-custom-prop")
 *
 * ## Permissions
 *
 * Same as calendar permissions (READ_CALENDAR, WRITE_CALENDAR).
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.ExtendedProperties">CalendarContract.ExtendedProperties</a>
 */
object ExtendedPropertiesMapper {

    /**
     * Property name for storing CATEGORIES.
     */
    const val NAME_CATEGORIES = "categories"

    /**
     * Prefix for X-* properties (stored lowercase).
     */
    const val NAME_X_PREFIX = "x-"

    /**
     * Separator for categories list (comma).
     */
    const val CATEGORIES_SEPARATOR = ","

    /**
     * Create ContentValues for an extended property.
     *
     * @param name Property name (will be lowercase)
     * @param value Property value
     * @param eventId The event ID this property belongs to
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun toContentValues(name: String, value: String, eventId: Long): ContentValues {
        return ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, eventId)
            put(ExtendedProperties.NAME, name.lowercase())
            put(ExtendedProperties.VALUE, value)
        }
    }

    /**
     * Parse an extended property from a cursor row.
     *
     * @param cursor Cursor positioned at a valid ExtendedProperties row
     * @return Pair of (name, value)
     */
    fun fromCursor(cursor: Cursor): Pair<String, String> {
        val name = cursor.getStringOrDefault(ExtendedProperties.NAME, "")
        val value = cursor.getStringOrDefault(ExtendedProperties.VALUE, "")
        return name to value
    }

    /**
     * Create ContentValues for CATEGORIES property.
     *
     * Categories are stored as a comma-separated string.
     *
     * @param categories List of category strings
     * @param eventId The event ID
     * @return ContentValues for the categories property
     */
    fun categoriesToContentValues(categories: List<String>, eventId: Long): ContentValues {
        val value = categories.joinToString(CATEGORIES_SEPARATOR)
        return toContentValues(NAME_CATEGORIES, value, eventId)
    }

    /**
     * Parse CATEGORIES from an extended property value.
     *
     * @param value The comma-separated categories string
     * @return List of category strings (trimmed)
     */
    fun parseCategories(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(CATEGORIES_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Check if a property name is an X-* property.
     *
     * @param name Property name to check
     * @return True if name starts with "x-" (case-insensitive)
     */
    fun isXProperty(name: String): Boolean {
        return name.lowercase().startsWith(NAME_X_PREFIX)
    }

    /**
     * Check if a property name is the CATEGORIES property.
     *
     * @param name Property name to check
     * @return True if name equals "categories" (case-insensitive)
     */
    fun isCategoriesProperty(name: String): Boolean {
        return name.equals(NAME_CATEGORIES, ignoreCase = true)
    }

    /**
     * Convert raw iCal properties map to extended property entries.
     *
     * Filters to only include X-* properties (not standard properties).
     *
     * @param rawProperties Map of property names to values from ICalEvent
     * @param eventId The event ID
     * @return List of ContentValues for each X-* property
     */
    fun rawPropertiesToContentValues(
        rawProperties: Map<String, String>,
        eventId: Long
    ): List<ContentValues> {
        return rawProperties
            .filter { (name, _) -> isXProperty(name) }
            .map { (name, value) -> toContentValues(name, value, eventId) }
    }

    /**
     * Convert extended properties to a map suitable for rawProperties.
     *
     * @param properties List of (name, value) pairs from ExtendedProperties
     * @return Map of property names to values
     */
    fun toRawPropertiesMap(properties: List<Pair<String, String>>): Map<String, String> {
        return properties
            .filter { (name, _) -> isXProperty(name) }
            .associate { (name, value) -> name.uppercase() to value }
    }
}
