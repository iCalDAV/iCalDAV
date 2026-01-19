package org.onekash.icaldav.android

import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat

/**
 * Maps between iCalDAV's [Attendee]/[Organizer] models and Android's CalendarContract.Attendees table.
 *
 * ## RFC 5545 to CalendarContract Mapping
 *
 * ### PARTSTAT (Participation Status)
 * | RFC 5545 | Android |
 * |----------|---------|
 * | ACCEPTED | ATTENDEE_STATUS_ACCEPTED |
 * | DECLINED | ATTENDEE_STATUS_DECLINED |
 * | TENTATIVE | ATTENDEE_STATUS_TENTATIVE |
 * | NEEDS-ACTION | ATTENDEE_STATUS_NONE |
 * | DELEGATED | ATTENDEE_STATUS_NONE |
 *
 * ### ROLE (Attendee Role)
 * | RFC 5545 | Android TYPE | Android RELATIONSHIP |
 * |----------|--------------|---------------------|
 * | CHAIR | TYPE_REQUIRED | RELATIONSHIP_ORGANIZER |
 * | REQ-PARTICIPANT | TYPE_REQUIRED | RELATIONSHIP_ATTENDEE |
 * | OPT-PARTICIPANT | TYPE_OPTIONAL | RELATIONSHIP_ATTENDEE |
 * | NON-PARTICIPANT | TYPE_NONE | RELATIONSHIP_NONE |
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract.Attendees">CalendarContract.Attendees</a>
 */
object AttendeeMapper {

    /**
     * Convert an [Attendee] to ContentValues for the Attendees table.
     *
     * @param attendee The iCalDAV attendee to convert
     * @param eventId The Android event ID this attendee belongs to
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun toContentValues(attendee: Attendee, eventId: Long): ContentValues {
        return ContentValues().apply {
            put(Attendees.EVENT_ID, eventId)
            put(Attendees.ATTENDEE_EMAIL, attendee.email)
            put(Attendees.ATTENDEE_NAME, attendee.name)
            put(Attendees.ATTENDEE_STATUS, mapPartStat(attendee.partStat))
            put(Attendees.ATTENDEE_TYPE, mapAttendeeType(attendee.role))
            put(Attendees.ATTENDEE_RELATIONSHIP, mapRelationship(attendee.role))
        }
    }

    /**
     * Convert an [Organizer] to ContentValues for the Attendees table.
     *
     * The organizer is stored as an attendee with RELATIONSHIP_ORGANIZER.
     *
     * @param organizer The iCalDAV organizer to convert
     * @param eventId The Android event ID this organizer belongs to
     * @return ContentValues ready for ContentResolver.insert()
     */
    fun organizerToContentValues(organizer: Organizer, eventId: Long): ContentValues {
        return ContentValues().apply {
            put(Attendees.EVENT_ID, eventId)
            put(Attendees.ATTENDEE_EMAIL, organizer.email)
            put(Attendees.ATTENDEE_NAME, organizer.name)
            put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER)
        }
    }

    /**
     * Map PartStat to Android ATTENDEE_STATUS constant.
     */
    private fun mapPartStat(partStat: PartStat): Int {
        return when (partStat) {
            PartStat.ACCEPTED -> Attendees.ATTENDEE_STATUS_ACCEPTED
            PartStat.DECLINED -> Attendees.ATTENDEE_STATUS_DECLINED
            PartStat.TENTATIVE -> Attendees.ATTENDEE_STATUS_TENTATIVE
            PartStat.NEEDS_ACTION -> Attendees.ATTENDEE_STATUS_NONE
            PartStat.DELEGATED -> Attendees.ATTENDEE_STATUS_NONE
        }
    }

    /**
     * Map AttendeeRole to Android ATTENDEE_TYPE constant.
     */
    private fun mapAttendeeType(role: AttendeeRole): Int {
        return when (role) {
            AttendeeRole.CHAIR -> Attendees.TYPE_REQUIRED
            AttendeeRole.REQ_PARTICIPANT -> Attendees.TYPE_REQUIRED
            AttendeeRole.OPT_PARTICIPANT -> Attendees.TYPE_OPTIONAL
            AttendeeRole.NON_PARTICIPANT -> Attendees.TYPE_NONE
        }
    }

    /**
     * Map AttendeeRole to Android ATTENDEE_RELATIONSHIP constant.
     */
    private fun mapRelationship(role: AttendeeRole): Int {
        return when (role) {
            AttendeeRole.CHAIR -> Attendees.RELATIONSHIP_ORGANIZER
            else -> Attendees.RELATIONSHIP_ATTENDEE
        }
    }

    /**
     * Convert a Cursor row from Attendees table to an [Attendee].
     *
     * @param cursor A cursor positioned at a valid Attendees row
     * @return Reconstructed Attendee
     */
    fun fromCursor(cursor: Cursor): Attendee {
        val email = cursor.getStringOrDefault(Attendees.ATTENDEE_EMAIL, "")
        val name = cursor.getStringOrNull(Attendees.ATTENDEE_NAME)
        val status = cursor.getIntOrNull(Attendees.ATTENDEE_STATUS) ?: Attendees.ATTENDEE_STATUS_NONE
        val type = cursor.getIntOrNull(Attendees.ATTENDEE_TYPE) ?: Attendees.TYPE_NONE
        val relationship = cursor.getIntOrNull(Attendees.ATTENDEE_RELATIONSHIP)
            ?: Attendees.RELATIONSHIP_ATTENDEE

        return Attendee(
            email = email,
            name = name,
            partStat = parsePartStat(status),
            role = parseRole(type, relationship),
            rsvp = false // Not stored in Android
        )
    }

    /**
     * Check if a cursor row represents the organizer.
     */
    fun isOrganizer(cursor: Cursor): Boolean {
        val relationship = cursor.getIntOrNull(Attendees.ATTENDEE_RELATIONSHIP)
        return relationship == Attendees.RELATIONSHIP_ORGANIZER
    }

    /**
     * Convert a Cursor row to an [Organizer] (for organizer rows).
     */
    fun organizerFromCursor(cursor: Cursor): Organizer? {
        if (!isOrganizer(cursor)) return null

        return Organizer(
            email = cursor.getStringOrDefault(Attendees.ATTENDEE_EMAIL, ""),
            name = cursor.getStringOrNull(Attendees.ATTENDEE_NAME),
            sentBy = null
        )
    }

    /**
     * Parse Android ATTENDEE_STATUS to PartStat.
     */
    private fun parsePartStat(status: Int): PartStat {
        return when (status) {
            Attendees.ATTENDEE_STATUS_ACCEPTED -> PartStat.ACCEPTED
            Attendees.ATTENDEE_STATUS_DECLINED -> PartStat.DECLINED
            Attendees.ATTENDEE_STATUS_TENTATIVE -> PartStat.TENTATIVE
            Attendees.ATTENDEE_STATUS_INVITED -> PartStat.NEEDS_ACTION
            else -> PartStat.NEEDS_ACTION
        }
    }

    /**
     * Parse Android TYPE and RELATIONSHIP to AttendeeRole.
     */
    private fun parseRole(type: Int, relationship: Int): AttendeeRole {
        // Organizer relationship takes precedence
        if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
            return AttendeeRole.CHAIR
        }

        return when (type) {
            Attendees.TYPE_REQUIRED -> AttendeeRole.REQ_PARTICIPANT
            Attendees.TYPE_OPTIONAL -> AttendeeRole.OPT_PARTICIPANT
            Attendees.TYPE_RESOURCE -> AttendeeRole.NON_PARTICIPANT
            else -> AttendeeRole.REQ_PARTICIPANT
        }
    }
}
