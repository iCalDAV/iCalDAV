package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.ExtendedProperties
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ConferenceFeature
import org.onekash.icaldav.model.ICalConference
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ConferenceMapper].
 *
 * Tests verify JSON serialization/deserialization and ContentValues generation
 * for RFC 7986 CONFERENCE property storage in ExtendedProperties.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ConferenceMapperTest {

    // ==================== JSON Serialization Tests ====================

    @Test
    fun `toJson serializes single conference correctly`() {
        val conference = ICalConference(
            uri = "https://zoom.us/j/123456789",
            features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO),
            label = "Join Zoom Meeting"
        )

        val json = ConferenceMapper.toJson(listOf(conference))

        // JSON may escape forward slashes, so check for either form
        assertThat(json.contains("zoom.us") && json.contains("123456789")).isTrue()
        assertThat(json).contains("VIDEO")
        assertThat(json).contains("AUDIO")
        assertThat(json).contains("Join Zoom Meeting")
    }

    @Test
    fun `toJson serializes multiple conferences`() {
        val conferences = listOf(
            ICalConference(uri = "https://zoom.us/j/123", features = setOf(ConferenceFeature.VIDEO)),
            ICalConference(uri = "tel:+1-555-123-4567", features = setOf(ConferenceFeature.PHONE))
        )

        val json = ConferenceMapper.toJson(conferences)

        // JSON may escape forward slashes, so check domain and number separately
        assertThat(json.contains("zoom.us") && json.contains("123")).isTrue()
        assertThat(json).contains("tel:+1-555-123-4567")
    }

    @Test
    fun `toJson handles empty list`() {
        val json = ConferenceMapper.toJson(emptyList())

        assertThat(json).isEqualTo("[]")
    }

    @Test
    fun `toJson handles conference without optional fields`() {
        val conference = ICalConference(uri = "https://meet.google.com/abc-defg-hij")

        val json = ConferenceMapper.toJson(listOf(conference))

        // JSON may escape forward slashes, so check domain and code separately
        assertThat(json.contains("meet.google.com") && json.contains("abc-defg-hij")).isTrue()
    }

    // ==================== JSON Deserialization Tests ====================

    @Test
    fun `fromJson deserializes single conference correctly`() {
        val json = """[{"uri":"https://zoom.us/j/123456789","features":["VIDEO","AUDIO"],"label":"Join Meeting"}]"""

        val conferences = ConferenceMapper.fromJson(json)

        assertThat(conferences).hasSize(1)
        assertThat(conferences[0].uri).isEqualTo("https://zoom.us/j/123456789")
        assertThat(conferences[0].features).containsExactly(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO)
        assertThat(conferences[0].label).isEqualTo("Join Meeting")
    }

    @Test
    fun `fromJson deserializes multiple conferences`() {
        val json = """[{"uri":"https://zoom.us/j/123","features":["VIDEO"]},{"uri":"tel:+1-555-123-4567","features":["PHONE"]}]"""

        val conferences = ConferenceMapper.fromJson(json)

        assertThat(conferences).hasSize(2)
        assertThat(conferences[0].uri).isEqualTo("https://zoom.us/j/123")
        assertThat(conferences[1].uri).isEqualTo("tel:+1-555-123-4567")
    }

    @Test
    fun `fromJson handles null input`() {
        val conferences = ConferenceMapper.fromJson(null)

        assertThat(conferences).isEmpty()
    }

    @Test
    fun `fromJson handles empty string`() {
        val conferences = ConferenceMapper.fromJson("")

        assertThat(conferences).isEmpty()
    }

    @Test
    fun `fromJson handles invalid JSON gracefully`() {
        val conferences = ConferenceMapper.fromJson("not valid json")

        assertThat(conferences).isEmpty()
    }

    @Test
    fun `fromJson handles empty array`() {
        val conferences = ConferenceMapper.fromJson("[]")

        assertThat(conferences).isEmpty()
    }

    @Test
    fun `fromJson skips entries without URI`() {
        val json = """[{"features":["VIDEO"]},{"uri":"https://zoom.us/j/123","features":["VIDEO"]}]"""

        val conferences = ConferenceMapper.fromJson(json)

        assertThat(conferences).hasSize(1)
        assertThat(conferences[0].uri).isEqualTo("https://zoom.us/j/123")
    }

    // ==================== Round-trip Tests ====================

    @Test
    fun `round-trip preserves all fields`() {
        val original = ICalConference(
            uri = "https://teams.microsoft.com/meet/abc123",
            features = setOf(ConferenceFeature.VIDEO, ConferenceFeature.AUDIO, ConferenceFeature.SCREEN),
            label = "Join Teams Meeting",
            language = "en"
        )

        val json = ConferenceMapper.toJson(listOf(original))
        val restored = ConferenceMapper.fromJson(json)

        assertThat(restored).hasSize(1)
        assertThat(restored[0].uri).isEqualTo(original.uri)
        assertThat(restored[0].features).isEqualTo(original.features)
        assertThat(restored[0].label).isEqualTo(original.label)
        assertThat(restored[0].language).isEqualTo(original.language)
    }

    @Test
    fun `round-trip preserves multiple conferences`() {
        val original = listOf(
            ICalConference.video("https://zoom.us/j/123", "Zoom"),
            ICalConference.phone("+1-555-123-4567", "US Dial-in")
        )

        val json = ConferenceMapper.toJson(original)
        val restored = ConferenceMapper.fromJson(json)

        assertThat(restored).hasSize(2)
        assertThat(restored[0].uri).isEqualTo("https://zoom.us/j/123")
        assertThat(restored[1].uri).isEqualTo("tel:+1-555-123-4567")
    }

    // ==================== ContentValues Tests ====================

    @Test
    fun `toContentValues creates correct values`() {
        val conferences = listOf(
            ICalConference(uri = "https://zoom.us/j/123", features = setOf(ConferenceFeature.VIDEO))
        )

        val values = ConferenceMapper.toContentValues(conferences, eventId = 42L)

        assertThat(values).isNotNull()
        assertThat(values!!.getAsLong(ExtendedProperties.EVENT_ID)).isEqualTo(42L)
        assertThat(values.getAsString(ExtendedProperties.NAME)).isEqualTo(ConferenceMapper.NAME_CONFERENCE)
        // JSON may escape forward slashes, so check domain and number separately
        val jsonValue = values.getAsString(ExtendedProperties.VALUE)
        assertThat(jsonValue.contains("zoom.us") && jsonValue.contains("123")).isTrue()
    }

    @Test
    fun `toContentValues returns null for empty list`() {
        val values = ConferenceMapper.toContentValues(emptyList(), eventId = 42L)

        assertThat(values).isNull()
    }

    // ==================== Property Name Tests ====================

    @Test
    fun `isConferenceProperty returns true for conference`() {
        assertThat(ConferenceMapper.isConferenceProperty("conference")).isTrue()
    }

    @Test
    fun `isConferenceProperty is case insensitive`() {
        assertThat(ConferenceMapper.isConferenceProperty("CONFERENCE")).isTrue()
        assertThat(ConferenceMapper.isConferenceProperty("Conference")).isTrue()
    }

    @Test
    fun `isConferenceProperty returns false for other names`() {
        assertThat(ConferenceMapper.isConferenceProperty("categories")).isFalse()
        assertThat(ConferenceMapper.isConferenceProperty("x-custom")).isFalse()
    }

    // ==================== Utility Method Tests ====================

    @Test
    fun `getPrimaryVideoUrl returns first video conference`() {
        val conferences = listOf(
            ICalConference(uri = "tel:+1-555-123-4567", features = setOf(ConferenceFeature.PHONE)),
            ICalConference(uri = "https://zoom.us/j/123", features = setOf(ConferenceFeature.VIDEO))
        )

        val url = ConferenceMapper.getPrimaryVideoUrl(conferences)

        assertThat(url).isEqualTo("https://zoom.us/j/123")
    }

    @Test
    fun `getPrimaryVideoUrl returns null when no video conference`() {
        val conferences = listOf(
            ICalConference(uri = "tel:+1-555-123-4567", features = setOf(ConferenceFeature.PHONE))
        )

        val url = ConferenceMapper.getPrimaryVideoUrl(conferences)

        assertThat(url).isNull()
    }

    @Test
    fun `getPhoneDialIns returns only phone entries`() {
        val conferences = listOf(
            ICalConference(uri = "https://zoom.us/j/123", features = setOf(ConferenceFeature.VIDEO)),
            ICalConference(uri = "tel:+1-555-123-4567", features = setOf(ConferenceFeature.PHONE)),
            ICalConference(uri = "tel:+44-20-1234-5678", features = setOf(ConferenceFeature.PHONE))
        )

        val phoneDialIns = ConferenceMapper.getPhoneDialIns(conferences)

        assertThat(phoneDialIns).hasSize(2)
        assertThat(phoneDialIns.all { it.uri.startsWith("tel:") }).isTrue()
    }

    @Test
    fun `createVideoConference creates proper conference`() {
        val conference = ConferenceMapper.createVideoConference(
            "https://meet.google.com/abc-def",
            "Google Meet"
        )

        assertThat(conference.uri).isEqualTo("https://meet.google.com/abc-def")
        assertThat(conference.label).isEqualTo("Google Meet")
        assertThat(conference.hasVideo()).isTrue()
        assertThat(conference.hasAudio()).isTrue()
    }

    @Test
    fun `createPhoneDialIn creates proper conference`() {
        val conference = ConferenceMapper.createPhoneDialIn("+1-555-123-4567", "US Number")

        assertThat(conference.uri).isEqualTo("tel:+1-555-123-4567")
        assertThat(conference.label).isEqualTo("US Number")
        assertThat(conference.isPhoneDialIn()).isTrue()
    }

    @Test
    fun `createPhoneDialIn handles tel prefix`() {
        val conference = ConferenceMapper.createPhoneDialIn("tel:+1-555-123-4567")

        assertThat(conference.uri).isEqualTo("tel:+1-555-123-4567")
    }

    // ==================== Feature Parsing Tests ====================

    @Test
    fun `fromJson parses all feature types`() {
        val json = """[{"uri":"https://example.com","features":["AUDIO","CHAT","FEED","MODERATOR","PHONE","SCREEN","VIDEO"]}]"""

        val conferences = ConferenceMapper.fromJson(json)

        assertThat(conferences[0].features).containsExactly(
            ConferenceFeature.AUDIO,
            ConferenceFeature.CHAT,
            ConferenceFeature.FEED,
            ConferenceFeature.MODERATOR,
            ConferenceFeature.PHONE,
            ConferenceFeature.SCREEN,
            ConferenceFeature.VIDEO
        )
    }

    @Test
    fun `fromJson ignores unknown features`() {
        val json = """[{"uri":"https://example.com","features":["VIDEO","UNKNOWN_FEATURE"]}]"""

        val conferences = ConferenceMapper.fromJson(json)

        assertThat(conferences[0].features).containsExactly(ConferenceFeature.VIDEO)
    }
}
