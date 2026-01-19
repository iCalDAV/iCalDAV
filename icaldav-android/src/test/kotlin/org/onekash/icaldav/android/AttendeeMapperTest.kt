package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.Attendees
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AttendeeMapper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AttendeeMapperTest {

    // ==================== Basic Mapping Tests ====================

    @Test
    fun `attendee email mapped correctly`() {
        val attendee = Attendee(
            email = "john@example.com",
            name = "John Doe",
            partStat = PartStat.ACCEPTED,
            role = AttendeeRole.REQ_PARTICIPANT,
            rsvp = true
        )

        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)

        assertThat(values.getAsString(Attendees.ATTENDEE_EMAIL)).isEqualTo("john@example.com")
        assertThat(values.getAsString(Attendees.ATTENDEE_NAME)).isEqualTo("John Doe")
        assertThat(values.getAsLong(Attendees.EVENT_ID)).isEqualTo(1L)
    }

    @Test
    fun `null name handled gracefully`() {
        val attendee = Attendee(
            email = "test@example.com",
            name = null,
            partStat = PartStat.ACCEPTED,
            role = AttendeeRole.REQ_PARTICIPANT,
            rsvp = false
        )

        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)

        assertThat(values.containsKey(Attendees.ATTENDEE_NAME)).isTrue()
        assertThat(values.getAsString(Attendees.ATTENDEE_NAME)).isNull()
    }

    // ==================== PARTSTAT Mapping Tests ====================

    @Test
    fun `PARTSTAT ACCEPTED maps correctly`() {
        val attendee = createAttendee(partStat = PartStat.ACCEPTED)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_ACCEPTED)
    }

    @Test
    fun `PARTSTAT DECLINED maps correctly`() {
        val attendee = createAttendee(partStat = PartStat.DECLINED)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_DECLINED)
    }

    @Test
    fun `PARTSTAT TENTATIVE maps correctly`() {
        val attendee = createAttendee(partStat = PartStat.TENTATIVE)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_TENTATIVE)
    }

    @Test
    fun `PARTSTAT NEEDS_ACTION maps to NONE`() {
        val attendee = createAttendee(partStat = PartStat.NEEDS_ACTION)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_NONE)
    }

    @Test
    fun `PARTSTAT DELEGATED maps to NONE`() {
        val attendee = createAttendee(partStat = PartStat.DELEGATED)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_NONE)
    }

    // ==================== ROLE Mapping Tests ====================

    @Test
    fun `ROLE CHAIR maps to TYPE_REQUIRED and RELATIONSHIP_ORGANIZER`() {
        val attendee = createAttendee(role = AttendeeRole.CHAIR)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_TYPE)).isEqualTo(Attendees.TYPE_REQUIRED)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP))
            .isEqualTo(Attendees.RELATIONSHIP_ORGANIZER)
    }

    @Test
    fun `ROLE REQ_PARTICIPANT maps to TYPE_REQUIRED`() {
        val attendee = createAttendee(role = AttendeeRole.REQ_PARTICIPANT)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_TYPE)).isEqualTo(Attendees.TYPE_REQUIRED)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP))
            .isEqualTo(Attendees.RELATIONSHIP_ATTENDEE)
    }

    @Test
    fun `ROLE OPT_PARTICIPANT maps to TYPE_OPTIONAL`() {
        val attendee = createAttendee(role = AttendeeRole.OPT_PARTICIPANT)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_TYPE)).isEqualTo(Attendees.TYPE_OPTIONAL)
    }

    @Test
    fun `ROLE NON_PARTICIPANT maps to TYPE_NONE`() {
        val attendee = createAttendee(role = AttendeeRole.NON_PARTICIPANT)
        val values = AttendeeMapper.toContentValues(attendee, eventId = 1L)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_TYPE)).isEqualTo(Attendees.TYPE_NONE)
    }

    // ==================== Organizer Mapping Tests ====================

    @Test
    fun `organizer mapped with RELATIONSHIP_ORGANIZER`() {
        val organizer = Organizer(
            email = "organizer@example.com",
            name = "Meeting Host",
            sentBy = null
        )

        val values = AttendeeMapper.organizerToContentValues(organizer, eventId = 1L)

        assertThat(values.getAsString(Attendees.ATTENDEE_EMAIL)).isEqualTo("organizer@example.com")
        assertThat(values.getAsString(Attendees.ATTENDEE_NAME)).isEqualTo("Meeting Host")
        assertThat(values.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP))
            .isEqualTo(Attendees.RELATIONSHIP_ORGANIZER)
        assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS))
            .isEqualTo(Attendees.ATTENDEE_STATUS_ACCEPTED)
    }

    @Test
    fun `organizer with null name`() {
        val organizer = Organizer(
            email = "organizer@example.com",
            name = null,
            sentBy = null
        )

        val values = AttendeeMapper.organizerToContentValues(organizer, eventId = 1L)

        assertThat(values.getAsString(Attendees.ATTENDEE_EMAIL)).isEqualTo("organizer@example.com")
        assertThat(values.getAsString(Attendees.ATTENDEE_NAME)).isNull()
    }

    // ==================== Event ID Tests ====================

    @Test
    fun `eventId set correctly for attendee`() {
        val attendee = createAttendee()
        val values = AttendeeMapper.toContentValues(attendee, eventId = 42L)
        assertThat(values.getAsLong(Attendees.EVENT_ID)).isEqualTo(42L)
    }

    @Test
    fun `eventId set correctly for organizer`() {
        val organizer = Organizer("org@example.com", "Organizer", null)
        val values = AttendeeMapper.organizerToContentValues(organizer, eventId = 99L)
        assertThat(values.getAsLong(Attendees.EVENT_ID)).isEqualTo(99L)
    }

    // ==================== Helpers ====================

    private fun createAttendee(
        email: String = "test@example.com",
        name: String? = "Test User",
        partStat: PartStat = PartStat.ACCEPTED,
        role: AttendeeRole = AttendeeRole.REQ_PARTICIPANT,
        rsvp: Boolean = false
    ): Attendee {
        return Attendee(
            email = email,
            name = name,
            partStat = partStat,
            role = role,
            rsvp = rsvp
        )
    }
}
