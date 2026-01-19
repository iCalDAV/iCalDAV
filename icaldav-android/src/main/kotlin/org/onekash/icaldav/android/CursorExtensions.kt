package org.onekash.icaldav.android

import android.database.Cursor

/**
 * Safe cursor access extensions that handle missing columns gracefully.
 *
 * Android's Cursor.getColumnIndex() returns -1 if the column doesn't exist,
 * which can cause crashes if not handled properly. These extensions provide
 * null-safe access to cursor columns.
 */

/**
 * Get a String value from the cursor, or null if the column doesn't exist or is null.
 */
fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

/**
 * Get a Long value from the cursor, or null if the column doesn't exist or is null.
 */
fun Cursor.getLongOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

/**
 * Get an Int value from the cursor, or null if the column doesn't exist or is null.
 */
fun Cursor.getIntOrNull(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else null
}

/**
 * Get a String value from the cursor with a default value if not present.
 */
fun Cursor.getStringOrDefault(column: String, default: String): String {
    return getStringOrNull(column) ?: default
}

/**
 * Get a Long value from the cursor with a default value if not present.
 */
fun Cursor.getLongOrDefault(column: String, default: Long): Long {
    return getLongOrNull(column) ?: default
}

/**
 * Get an Int value from the cursor with a default value if not present.
 */
fun Cursor.getIntOrDefault(column: String, default: Int): Int {
    return getIntOrNull(column) ?: default
}

/**
 * Get a Boolean value from the cursor (stored as 0/1 int), or null if not present.
 */
fun Cursor.getBooleanOrNull(column: String): Boolean? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) != 0 else null
}

/**
 * Get a Boolean value from the cursor with a default value if not present.
 */
fun Cursor.getBooleanOrDefault(column: String, default: Boolean): Boolean {
    return getBooleanOrNull(column) ?: default
}
