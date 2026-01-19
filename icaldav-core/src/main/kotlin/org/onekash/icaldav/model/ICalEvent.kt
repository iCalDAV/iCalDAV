package org.onekash.icaldav.model

import java.time.Duration

/**
 * Core event data structure representing a VEVENT component.
 *
 * The importId field is critical for uniquely identifying events:
 * - Regular events: "{uid}"
 * - Modified recurring instances: "{uid}:RECID:{recurrence-id-datetime}"
 *
 * This strategy handles RECURRENCE-ID events from iCloud properly.
 */
data class ICalEvent(
    /** Unique identifier from UID property */
    val uid: String,

    /**
     * Unique import ID for database storage.
     * Format: "{uid}" or "{uid}:RECID:{datetime}" for modified instances.
     */
    val importId: String,

    /** Event title from SUMMARY property */
    val summary: String?,

    /** Event description from DESCRIPTION property */
    val description: String?,

    /** Event location from LOCATION property */
    val location: String?,

    /** Start date/time from DTSTART property */
    val dtStart: ICalDateTime,

    /** End date/time from DTEND property (mutually exclusive with duration) */
    val dtEnd: ICalDateTime?,

    /** Duration from DURATION property (mutually exclusive with dtEnd) */
    val duration: Duration?,

    /** True if this is an all-day event (DATE format, not DATE-TIME) */
    val isAllDay: Boolean,

    /** Event status: CONFIRMED, TENTATIVE, CANCELLED */
    val status: EventStatus,

    /** SEQUENCE number for conflict detection */
    val sequence: Int,

    /** Recurrence rule from RRULE property */
    val rrule: RRule?,

    /** Exception dates from EXDATE property */
    val exdates: List<ICalDateTime>,

    /**
     * RECURRENCE-ID for modified instances of recurring events.
     * Non-null indicates this is a modified occurrence, not the master event.
     */
    val recurrenceId: ICalDateTime?,

    /** Alarms/reminders from VALARM components */
    val alarms: List<ICalAlarm>,

    /** Categories/tags from CATEGORIES property */
    val categories: List<String>,

    /** Meeting organizer from ORGANIZER property (for scheduling) */
    val organizer: Organizer?,

    /** Meeting attendees from ATTENDEE properties (for scheduling) */
    val attendees: List<Attendee>,

    /** Event color from COLOR property (RFC 7986) or calendar color */
    val color: String?,

    /** Creation timestamp from DTSTAMP property */
    val dtstamp: ICalDateTime?,

    /** Last modified timestamp from LAST-MODIFIED property */
    val lastModified: ICalDateTime?,

    /** Created timestamp from CREATED property */
    val created: ICalDateTime?,

    /** Transparency from TRANSP property (OPAQUE or TRANSPARENT) */
    val transparency: Transparency,

    /** URL associated with the event */
    val url: String?,

    // RFC 7986 Modern Properties

    /** Images associated with the event (RFC 7986) */
    val images: List<ICalImage> = emptyList(),

    /** Conference/video call information (RFC 7986) */
    val conferences: List<ICalConference> = emptyList(),

    // RFC 9073 Rich Event Properties

    /** Structured location information (RFC 9073) */
    val locations: List<ICalLocation> = emptyList(),

    /** Rich participant information (RFC 9073) */
    val participants: List<ICalParticipant> = emptyList(),

    // RFC 9253 Relationships

    /** Links to external resources (RFC 9253) */
    val links: List<ICalLink> = emptyList(),

    /** Relationships to other calendar components (RFC 9253 enhanced RELATED-TO) */
    val relations: List<ICalRelation> = emptyList(),

    /** Preserve unknown properties for round-trip fidelity */
    val rawProperties: Map<String, String>
) {
    /**
     * Calculate the effective end time.
     * Uses dtEnd if present, otherwise calculates from duration.
     * For all-day events without end, defaults to same day.
     */
    fun effectiveEnd(): ICalDateTime {
        return dtEnd ?: duration?.let { dur ->
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + dur.toMillis(),
                timezone = dtStart.timezone,
                isDate = dtStart.isDate
            )
        } ?: if (isAllDay) {
            // Default: all-day event ends at end of same day
            ICalDateTime.fromTimestamp(
                timestamp = dtStart.timestamp + 86400000L, // +24 hours
                timezone = dtStart.timezone,
                isDate = true
            )
        } else {
            // Default: instant event (same as start)
            dtStart
        }
    }

    /**
     * Check if this event has recurrence (either RRULE or is a modified instance).
     */
    fun isRecurring(): Boolean = rrule != null

    /**
     * Check if this is a modified instance of a recurring event.
     */
    fun isModifiedInstance(): Boolean = recurrenceId != null

    /**
     * Get the master event UID (strips RECID suffix if present).
     */
    fun masterUid(): String = uid

    companion object {
        /**
         * Generate importId for an event.
         *
         * @param uid The event's UID
         * @param recurrenceId Optional RECURRENCE-ID datetime string
         * @return Unique importId: "{uid}" or "{uid}:RECID:{datetime}"
         */
        fun generateImportId(uid: String, recurrenceId: ICalDateTime?): String {
            return if (recurrenceId != null) {
                "$uid:RECID:${recurrenceId.toICalString()}"
            } else {
                uid
            }
        }

        /**
         * Parse importId to extract UID and optional recurrenceId.
         *
         * @return Pair of (uid, recurrenceIdString?) where recurrenceIdString is the datetime
         */
        fun parseImportId(importId: String): Pair<String, String?> {
            val recidIndex = importId.indexOf(":RECID:")
            return if (recidIndex != -1) {
                val uid = importId.substring(0, recidIndex)
                val recid = importId.substring(recidIndex + 7)
                uid to recid
            } else {
                importId to null
            }
        }
    }
}

/**
 * Event status values per RFC 5545.
 */
enum class EventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED;

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): EventStatus {
            return when (value?.uppercase()) {
                "TENTATIVE" -> TENTATIVE
                "CANCELLED" -> CANCELLED
                else -> CONFIRMED
            }
        }
    }
}

/**
 * Event transparency per RFC 5545.
 */
enum class Transparency {
    OPAQUE,      // Time is blocked (busy)
    TRANSPARENT; // Time is free

    fun toICalString(): String = name

    companion object {
        fun fromString(value: String?): Transparency {
            return when (value?.uppercase()) {
                "TRANSPARENT" -> TRANSPARENT
                else -> OPAQUE
            }
        }
    }
}

/**
 * Meeting organizer from ORGANIZER property.
 */
data class Organizer(
    val email: String,
    val name: String?,
    val sentBy: String?
)

/**
 * Meeting attendee from ATTENDEE property.
 */
data class Attendee(
    val email: String,
    val name: String?,
    val partStat: PartStat,
    val role: AttendeeRole,
    val rsvp: Boolean
)

/**
 * Participation status for attendees.
 */
enum class PartStat {
    NEEDS_ACTION,
    ACCEPTED,
    DECLINED,
    TENTATIVE,
    DELEGATED;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): PartStat {
            return when (value?.uppercase()?.replace("-", "_")) {
                "ACCEPTED" -> ACCEPTED
                "DECLINED" -> DECLINED
                "TENTATIVE" -> TENTATIVE
                "DELEGATED" -> DELEGATED
                else -> NEEDS_ACTION
            }
        }
    }
}

/**
 * Attendee role per RFC 5545.
 */
enum class AttendeeRole {
    CHAIR,
    REQ_PARTICIPANT,
    OPT_PARTICIPANT,
    NON_PARTICIPANT;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): AttendeeRole {
            return when (value?.uppercase()?.replace("-", "_")) {
                "CHAIR" -> CHAIR
                "OPT_PARTICIPANT" -> OPT_PARTICIPANT
                "NON_PARTICIPANT" -> NON_PARTICIPANT
                else -> REQ_PARTICIPANT
            }
        }
    }
}
