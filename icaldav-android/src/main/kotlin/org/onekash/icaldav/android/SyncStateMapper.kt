package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.SyncState
import org.json.JSONObject

/**
 * Maps CalendarContract.SyncState table for account-level sync metadata.
 *
 * The SyncState table stores arbitrary key-value pairs per account, useful for
 * persisting sync tokens, ctags, and other sync-related metadata that isn't
 * tied to a specific calendar.
 *
 * ## Storage Approach
 *
 * SyncState uses a blob (DATA column) for storage. This mapper provides two approaches:
 * 1. **Individual rows**: One row per key (simpler, more queries)
 * 2. **Single blob**: All keys in one JSON blob (fewer queries, atomic updates)
 *
 * ## Common Keys
 *
 * - **sync_token**: WebDAV sync-token for incremental sync
 * - **ctag**: Collection CTag for change detection
 * - **last_sync**: Timestamp of last successful sync
 * - **sync_version**: Protocol version for migrations
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.SyncState">CalendarContract.SyncState</a>
 */
object SyncStateMapper {

    // Common sync state keys
    const val KEY_SYNC_TOKEN = "sync_token"
    const val KEY_CTAG = "ctag"
    const val KEY_LAST_SYNC = "last_sync"
    const val KEY_SYNC_VERSION = "sync_version"
    const val KEY_DISCOVERY_URL = "discovery_url"
    const val KEY_PRINCIPAL_URL = "principal_url"

    /**
     * Projection for sync state queries.
     */
    val PROJECTION = arrayOf(
        SyncState._ID,
        SyncState.ACCOUNT_NAME,
        SyncState.ACCOUNT_TYPE,
        SyncState.DATA
    )

    /**
     * Column indices for efficient cursor access.
     */
    private const val COL_ID = 0
    private const val COL_ACCOUNT_NAME = 1
    private const val COL_ACCOUNT_TYPE = 2
    private const val COL_DATA = 3

    /**
     * Create ContentValues for inserting/updating sync state.
     *
     * @param data The data blob to store
     * @param accountName The account name
     * @param accountType The account type
     * @return ContentValues ready for ContentResolver operations
     */
    fun toContentValues(
        data: ByteArray,
        accountName: String,
        accountType: String
    ): ContentValues {
        return ContentValues().apply {
            put(SyncState.ACCOUNT_NAME, accountName)
            put(SyncState.ACCOUNT_TYPE, accountType)
            put(SyncState.DATA, data)
        }
    }

    /**
     * Parse sync state data from a cursor.
     *
     * @param cursor Cursor positioned at a valid SyncState row
     * @return Pair of (id, data bytes), or null if data is null
     */
    fun fromCursor(cursor: Cursor): Pair<Long, ByteArray>? {
        val id = cursor.getLong(COL_ID)
        val data = cursor.getBlob(COL_DATA)
        return if (data != null) id to data else null
    }

    /**
     * Encode a key-value pair as bytes.
     *
     * Uses a simple format: "key=value"
     *
     * @param key The key
     * @param value The value
     * @return Encoded bytes
     */
    fun encodeKeyValue(key: String, value: String): ByteArray {
        return "$key=$value".toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode a key-value pair from bytes.
     *
     * @param data The encoded bytes
     * @return Pair of (key, value), or null if parsing fails
     */
    fun decodeKeyValue(data: ByteArray): Pair<String, String>? {
        val str = data.toString(Charsets.UTF_8)
        val idx = str.indexOf('=')
        if (idx < 0) return null
        return str.substring(0, idx) to str.substring(idx + 1)
    }

    /**
     * Encode a map of key-value pairs as a JSON blob.
     *
     * This approach stores all sync state in a single row, which is
     * more efficient for bulk reads/writes but requires atomic updates.
     *
     * @param state Map of key-value pairs
     * @return JSON-encoded bytes
     */
    fun encodeStateMap(state: Map<String, String>): ByteArray {
        val json = JSONObject()
        for ((key, value) in state) {
            json.put(key, value)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode a map of key-value pairs from a JSON blob.
     *
     * @param data JSON-encoded bytes
     * @return Map of key-value pairs, or empty map if parsing fails
     */
    fun decodeStateMap(data: ByteArray): Map<String, String> {
        return try {
            val json = JSONObject(data.toString(Charsets.UTF_8))
            val result = mutableMapOf<String, String>()
            for (key in json.keys()) {
                result[key] = json.getString(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Encode a timestamp as bytes.
     *
     * @param timestamp Epoch milliseconds
     * @return Encoded bytes
     */
    fun encodeTimestamp(timestamp: Long): ByteArray {
        return timestamp.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode a timestamp from bytes.
     *
     * @param data Encoded bytes
     * @return Epoch milliseconds, or null if parsing fails
     */
    fun decodeTimestamp(data: ByteArray): Long? {
        return try {
            data.toString(Charsets.UTF_8).toLong()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encode a simple string value as bytes.
     *
     * @param value The string value
     * @return UTF-8 encoded bytes
     */
    fun encodeString(value: String): ByteArray {
        return value.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode a string value from bytes.
     *
     * @param data UTF-8 encoded bytes
     * @return Decoded string
     */
    fun decodeString(data: ByteArray): String {
        return data.toString(Charsets.UTF_8)
    }
}

/**
 * Represents sync state data from CalendarContract.SyncState table.
 *
 * @property id The row ID
 * @property accountName Account name
 * @property accountType Account type
 * @property data Raw data blob
 */
data class SyncStateEntry(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val data: ByteArray
) {
    /**
     * Decode data as a string value.
     */
    val stringValue: String get() = SyncStateMapper.decodeString(data)

    /**
     * Decode data as a key-value map.
     */
    val mapValue: Map<String, String> get() = SyncStateMapper.decodeStateMap(data)

    /**
     * Decode data as a timestamp.
     */
    val timestampValue: Long? get() = SyncStateMapper.decodeTimestamp(data)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SyncStateEntry

        if (id != other.id) return false
        if (accountName != other.accountName) return false
        if (accountType != other.accountType) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + accountName.hashCode()
        result = 31 * result + accountType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
