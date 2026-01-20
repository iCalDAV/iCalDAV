package org.onekash.icaldav.android

import android.content.ContentValues
import android.provider.CalendarContract.ExtendedProperties
import org.json.JSONArray
import org.json.JSONObject
import org.onekash.icaldav.model.ConferenceFeature
import org.onekash.icaldav.model.ICalConference

/**
 * Maps RFC 7986 CONFERENCE property to Android ExtendedProperties table.
 *
 * Android's CalendarContract doesn't have native support for conference/meeting URLs,
 * so we store them as JSON in the ExtendedProperties table. This allows preserving
 * all RFC 7986 CONFERENCE data (URI, features, label, language) through the Android
 * calendar provider.
 *
 * ## Storage Format
 *
 * Conferences are stored as a JSON array in an extended property named "conference":
 * ```json
 * [
 *   {"uri":"https://zoom.us/j/123","features":["VIDEO","AUDIO"],"label":"Join Meeting"},
 *   {"uri":"tel:+1-555-123-4567","features":["PHONE"],"label":"Dial-in"}
 * ]
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Store conferences
 * val values = ConferenceMapper.toContentValues(event.conferences, eventId)
 * contentResolver.insert(ExtendedProperties.CONTENT_URI, values)
 *
 * // Retrieve conferences
 * val conferences = ConferenceMapper.fromJson(propertyValue)
 * ```
 *
 * @see ICalConference
 * @see <a href="https://tools.ietf.org/html/rfc7986#section-5.11">RFC 7986 Section 5.11</a>
 */
object ConferenceMapper {

    /**
     * Property name for storing CONFERENCE data in ExtendedProperties.
     */
    const val NAME_CONFERENCE = "conference"

    // JSON keys
    private const val KEY_URI = "uri"
    private const val KEY_FEATURES = "features"
    private const val KEY_LABEL = "label"
    private const val KEY_LANGUAGE = "language"

    /**
     * Create ContentValues for storing conferences as an extended property.
     *
     * @param conferences List of conferences to store
     * @param eventId The event ID this property belongs to
     * @return ContentValues ready for ContentResolver.insert(), or null if list is empty
     */
    fun toContentValues(conferences: List<ICalConference>, eventId: Long): ContentValues? {
        if (conferences.isEmpty()) return null

        return ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, eventId)
            put(ExtendedProperties.NAME, NAME_CONFERENCE)
            put(ExtendedProperties.VALUE, toJson(conferences))
        }
    }

    /**
     * Serialize conferences to JSON string.
     *
     * @param conferences List of conferences to serialize
     * @return JSON array string
     */
    fun toJson(conferences: List<ICalConference>): String {
        val array = JSONArray()
        conferences.forEach { conf ->
            val obj = JSONObject().apply {
                put(KEY_URI, conf.uri)

                if (conf.features.isNotEmpty()) {
                    val featuresArray = JSONArray()
                    conf.features.forEach { featuresArray.put(it.name) }
                    put(KEY_FEATURES, featuresArray)
                }

                conf.label?.let { put(KEY_LABEL, it) }
                conf.language?.let { put(KEY_LANGUAGE, it) }
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * Deserialize conferences from JSON string.
     *
     * @param json JSON array string from ExtendedProperties
     * @return List of ICalConference objects
     */
    fun fromJson(json: String?): List<ICalConference> {
        if (json.isNullOrBlank()) return emptyList()

        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                parseConferenceObject(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse a single conference JSON object.
     */
    private fun parseConferenceObject(obj: JSONObject): ICalConference? {
        val uri = obj.optString(KEY_URI)
        if (uri.isBlank()) return null

        val features = mutableSetOf<ConferenceFeature>()
        obj.optJSONArray(KEY_FEATURES)?.let { featuresArray ->
            for (i in 0 until featuresArray.length()) {
                ConferenceFeature.fromString(featuresArray.optString(i))?.let {
                    features.add(it)
                }
            }
        }

        return ICalConference(
            uri = uri,
            features = features,
            label = obj.optString(KEY_LABEL).takeIf { it.isNotBlank() },
            language = obj.optString(KEY_LANGUAGE).takeIf { it.isNotBlank() }
        )
    }

    /**
     * Check if a property name is the CONFERENCE property.
     *
     * @param name Property name to check
     * @return True if name equals "conference" (case-insensitive)
     */
    fun isConferenceProperty(name: String): Boolean {
        return name.equals(NAME_CONFERENCE, ignoreCase = true)
    }

    /**
     * Extract the primary video conference URL from a list of conferences.
     *
     * Useful for quick access to the main meeting link.
     *
     * @param conferences List of conferences
     * @return Primary video conference URI, or null if none found
     */
    fun getPrimaryVideoUrl(conferences: List<ICalConference>): String? {
        return conferences.firstOrNull { it.hasVideo() }?.uri
    }

    /**
     * Extract phone dial-in numbers from conferences.
     *
     * @param conferences List of conferences
     * @return List of phone dial-in entries
     */
    fun getPhoneDialIns(conferences: List<ICalConference>): List<ICalConference> {
        return conferences.filter { it.isPhoneDialIn() }
    }

    /**
     * Create a simple video conference entry.
     *
     * Convenience method for creating common conference types.
     *
     * @param url Video conference URL (Zoom, Meet, Teams, etc.)
     * @param label Optional display label
     * @return ICalConference configured for video
     */
    fun createVideoConference(url: String, label: String? = null): ICalConference {
        return ICalConference.video(url, label)
    }

    /**
     * Create a phone dial-in entry.
     *
     * @param phoneNumber Phone number (with or without tel: prefix)
     * @param label Optional display label (e.g., "US Dial-in")
     * @return ICalConference configured for phone
     */
    fun createPhoneDialIn(phoneNumber: String, label: String? = null): ICalConference {
        return ICalConference.phone(phoneNumber, label)
    }
}
