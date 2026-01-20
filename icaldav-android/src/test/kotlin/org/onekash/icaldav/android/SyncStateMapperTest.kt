package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.SyncState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SyncStateMapper].
 *
 * Tests verify encoding/decoding, ContentValues creation, and data transformations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SyncStateMapperTest {

    // ==================== ContentValues Creation ====================

    @Test
    fun `toContentValues creates correct values`() {
        val data = "test-data".toByteArray()

        val values = SyncStateMapper.toContentValues(data, "user@example.com", "org.onekash.icaldav")

        assertThat(values.getAsString(SyncState.ACCOUNT_NAME)).isEqualTo("user@example.com")
        assertThat(values.getAsString(SyncState.ACCOUNT_TYPE)).isEqualTo("org.onekash.icaldav")
        assertThat(values.getAsByteArray(SyncState.DATA)).isEqualTo(data)
    }

    // ==================== Key-Value Encoding ====================

    @Test
    fun `encodeKeyValue creates correct format`() {
        val result = SyncStateMapper.encodeKeyValue("sync_token", "abc123")

        assertThat(result.toString(Charsets.UTF_8)).isEqualTo("sync_token=abc123")
    }

    @Test
    fun `decodeKeyValue parses correctly`() {
        val encoded = "sync_token=abc123".toByteArray()

        val result = SyncStateMapper.decodeKeyValue(encoded)

        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("sync_token")
        assertThat(result.second).isEqualTo("abc123")
    }

    @Test
    fun `decodeKeyValue handles value with equals sign`() {
        val encoded = "key=value=with=equals".toByteArray()

        val result = SyncStateMapper.decodeKeyValue(encoded)

        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("key")
        assertThat(result.second).isEqualTo("value=with=equals")
    }

    @Test
    fun `decodeKeyValue returns null for invalid format`() {
        val encoded = "no-equals-sign".toByteArray()

        val result = SyncStateMapper.decodeKeyValue(encoded)

        assertThat(result).isNull()
    }

    // ==================== JSON Map Encoding ====================

    @Test
    fun `encodeStateMap creates valid JSON`() {
        val state = mapOf(
            "sync_token" to "token123",
            "ctag" to "ctag456",
            "last_sync" to "1704067200000"
        )

        val encoded = SyncStateMapper.encodeStateMap(state)

        // Verify it's valid JSON by decoding
        val decoded = SyncStateMapper.decodeStateMap(encoded)
        assertThat(decoded).isEqualTo(state)
    }

    @Test
    fun `decodeStateMap parses JSON correctly`() {
        val json = """{"sync_token":"abc","ctag":"def"}"""
        val data = json.toByteArray()

        val result = SyncStateMapper.decodeStateMap(data)

        assertThat(result["sync_token"]).isEqualTo("abc")
        assertThat(result["ctag"]).isEqualTo("def")
    }

    @Test
    fun `decodeStateMap returns empty map for invalid JSON`() {
        val invalidJson = "not valid json".toByteArray()

        val result = SyncStateMapper.decodeStateMap(invalidJson)

        assertThat(result).isEmpty()
    }

    @Test
    fun `decodeStateMap handles empty JSON object`() {
        val emptyJson = "{}".toByteArray()

        val result = SyncStateMapper.decodeStateMap(emptyJson)

        assertThat(result).isEmpty()
    }

    // ==================== Round-trip Tests ====================

    @Test
    fun `key-value round-trip preserves data`() {
        val key = "test_key"
        val value = "test_value"

        val encoded = SyncStateMapper.encodeKeyValue(key, value)
        val decoded = SyncStateMapper.decodeKeyValue(encoded)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.first).isEqualTo(key)
        assertThat(decoded.second).isEqualTo(value)
    }

    @Test
    fun `map round-trip preserves all keys`() {
        val state = mapOf(
            SyncStateMapper.KEY_SYNC_TOKEN to "http://example.com/sync/token123",
            SyncStateMapper.KEY_CTAG to "W/\"abc-123\"",
            SyncStateMapper.KEY_LAST_SYNC to "1704067200000",
            SyncStateMapper.KEY_SYNC_VERSION to "1"
        )

        val encoded = SyncStateMapper.encodeStateMap(state)
        val decoded = SyncStateMapper.decodeStateMap(encoded)

        assertThat(decoded).isEqualTo(state)
    }

    @Test
    fun `map handles special characters in values`() {
        val state = mapOf(
            "key1" to "value with spaces",
            "key2" to "value/with/slashes",
            "key3" to "value?with=special&chars",
            "key4" to "日本語"
        )

        val encoded = SyncStateMapper.encodeStateMap(state)
        val decoded = SyncStateMapper.decodeStateMap(encoded)

        assertThat(decoded).isEqualTo(state)
    }

    // ==================== Timestamp Encoding ====================

    @Test
    fun `encodeTimestamp creates correct format`() {
        val timestamp = 1704067200000L

        val encoded = SyncStateMapper.encodeTimestamp(timestamp)

        assertThat(encoded.toString(Charsets.UTF_8)).isEqualTo("1704067200000")
    }

    @Test
    fun `decodeTimestamp parses correctly`() {
        val encoded = "1704067200000".toByteArray()

        val result = SyncStateMapper.decodeTimestamp(encoded)

        assertThat(result).isEqualTo(1704067200000L)
    }

    @Test
    fun `decodeTimestamp returns null for invalid value`() {
        val encoded = "not-a-number".toByteArray()

        val result = SyncStateMapper.decodeTimestamp(encoded)

        assertThat(result).isNull()
    }

    // ==================== String Encoding ====================

    @Test
    fun `encodeString handles UTF-8 correctly`() {
        val value = "Hello 世界"

        val encoded = SyncStateMapper.encodeString(value)
        val decoded = SyncStateMapper.decodeString(encoded)

        assertThat(decoded).isEqualTo(value)
    }

    @Test
    fun `string round-trip preserves value`() {
        val value = "http://example.com/sync?token=abc123&version=2"

        val encoded = SyncStateMapper.encodeString(value)
        val decoded = SyncStateMapper.decodeString(encoded)

        assertThat(decoded).isEqualTo(value)
    }

    // ==================== Key Constants ====================

    @Test
    fun `key constants are defined`() {
        assertThat(SyncStateMapper.KEY_SYNC_TOKEN).isEqualTo("sync_token")
        assertThat(SyncStateMapper.KEY_CTAG).isEqualTo("ctag")
        assertThat(SyncStateMapper.KEY_LAST_SYNC).isEqualTo("last_sync")
        assertThat(SyncStateMapper.KEY_SYNC_VERSION).isEqualTo("sync_version")
    }

    // ==================== SyncStateEntry Data Class ====================

    @Test
    fun `SyncStateEntry stringValue returns decoded string`() {
        val entry = SyncStateEntry(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            data = "test-value".toByteArray()
        )

        assertThat(entry.stringValue).isEqualTo("test-value")
    }

    @Test
    fun `SyncStateEntry mapValue returns decoded map`() {
        val json = """{"key1":"value1","key2":"value2"}"""
        val entry = SyncStateEntry(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            data = json.toByteArray()
        )

        val map = entry.mapValue
        assertThat(map["key1"]).isEqualTo("value1")
        assertThat(map["key2"]).isEqualTo("value2")
    }

    @Test
    fun `SyncStateEntry timestampValue returns decoded timestamp`() {
        val entry = SyncStateEntry(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            data = "1704067200000".toByteArray()
        )

        assertThat(entry.timestampValue).isEqualTo(1704067200000L)
    }

    @Test
    fun `SyncStateEntry equality considers byte array content`() {
        val entry1 = SyncStateEntry(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            data = "test".toByteArray()
        )

        val entry2 = SyncStateEntry(
            id = 1L,
            accountName = "user@example.com",
            accountType = "org.onekash.icaldav",
            data = "test".toByteArray()
        )

        assertThat(entry1).isEqualTo(entry2)
    }
}
