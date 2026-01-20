package org.onekash.icaldav.scheduling

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ITipBuilder Tests")
class ITipBuilderTest {
    private val builder = ITipBuilder()
    private val parser = ICalParser()

    private fun createTestEvent(sequence: Int = 0): ICalEvent {
        return ICalEvent(
            uid = "test-event-123",
            importId = "test-event-123",
            summary = "Test Meeting",
            description = null,
            location = null,
            dtStart = ICalDateTime.parse("20231215T140000Z"),
            dtEnd = ICalDateTime.parse("20231215T150000Z"),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = sequence,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = Organizer(
                email = "organizer@example.com",
                name = "Organizer",
                sentBy = null
            ),
            attendees = listOf(
                Attendee(
                    email = "attendee1@example.com",
                    name = "Attendee 1",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                ),
                Attendee(
                    email = "attendee2@example.com",
                    name = "Attendee 2",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.OPT_PARTICIPANT,
                    rsvp = false
                )
            ),
            color = null,
            dtstamp = ICalDateTime.parse("20231215T120000Z"),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    @Nested
    @DisplayName("createRequest Tests")
    inner class CreateRequestTests {
        @Test
        fun `createRequest sets METHOD REQUEST`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.ACCEPTED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("METHOD:REQUEST"))
        }

        @Test
        fun `createRequest sets PARTSTAT to NEEDS-ACTION`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.ACCEPTED,  // Will be changed to NEEDS_ACTION
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("PARTSTAT=NEEDS-ACTION"))
        }

        @Test
        fun `createRequest sets RSVP TRUE`() {
            val event = createTestEvent()
            val attendees = listOf(
                Attendee(
                    email = "new@example.com",
                    name = "New Attendee",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false  // Will be changed to true
                )
            )

            val ics = builder.createRequest(event, attendees)
            assertTrue(ics.contains("RSVP=TRUE"))
        }
    }

    @Nested
    @DisplayName("createReply Tests")
    inner class CreateReplyTests {
        @Test
        fun `createReply sets METHOD REPLY`() {
            val event = createTestEvent(sequence = 5)
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            assertTrue(ics.contains("METHOD:REPLY"))
        }

        @Test
        fun `createReply includes only responding attendee`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            // Should contain only one ATTENDEE (the responder)
            val attendeeCount = ics.split("ATTENDEE").size - 1
            assertEquals(1, attendeeCount)
            assertTrue(ics.contains("responder@example.com"))
        }

        @Test
        fun `createReply preserves original SEQUENCE - RFC 5546 requirement`() {
            val event = createTestEvent(sequence = 42)
            val attendee = Attendee(
                email = "responder@example.com",
                name = "Responder",
                partStat = PartStat.ACCEPTED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createReply(event, attendee)
            assertTrue(ics.contains("SEQUENCE:42"), "REPLY must preserve original SEQUENCE")
        }
    }

    @Nested
    @DisplayName("createCancel Tests")
    inner class CreateCancelTests {
        @Test
        fun `createCancel sets METHOD CANCEL`() {
            val event = createTestEvent()

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("METHOD:CANCEL"))
        }

        @Test
        fun `createCancel sets STATUS CANCELLED for full cancel`() {
            val event = createTestEvent()

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("STATUS:CANCELLED"))
        }

        @Test
        fun `createCancel preserves SEQUENCE`() {
            val event = createTestEvent(sequence = 10)

            val ics = builder.createCancel(event)
            assertTrue(ics.contains("SEQUENCE:10"))
        }

        @Test
        fun `createCancel for specific attendees includes only those attendees`() {
            val event = createTestEvent()
            val attendeesToCancel = listOf(
                Attendee(
                    email = "removed@example.com",
                    name = "Removed",
                    partStat = PartStat.DECLINED,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = false
                )
            )

            val ics = builder.createCancel(event, attendeesToCancel)
            assertTrue(ics.contains("removed@example.com"))
            val attendeeCount = ics.split("ATTENDEE").size - 1
            assertEquals(1, attendeeCount)
        }
    }

    @Nested
    @DisplayName("createUpdate Tests")
    inner class CreateUpdateTests {
        @Test
        fun `createUpdate increments SEQUENCE`() {
            val event = createTestEvent(sequence = 5)

            val ics = builder.createUpdate(event)
            assertTrue(ics.contains("SEQUENCE:6"), "Update should increment SEQUENCE")
        }

        @Test
        fun `createUpdate sets METHOD REQUEST`() {
            val event = createTestEvent()

            val ics = builder.createUpdate(event)
            assertTrue(ics.contains("METHOD:REQUEST"))
        }
    }

    @Nested
    @DisplayName("createCounter Tests")
    inner class CreateCounterTests {
        @Test
        fun `createCounter sets METHOD COUNTER`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("METHOD:COUNTER"))
        }

        @Test
        fun `createCounter includes proposed times`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("DTSTART:20231216T100000Z"))
            assertTrue(ics.contains("DTEND:20231216T110000Z"))
        }

        @Test
        fun `createCounter preserves original SEQUENCE - RFC 5546 requirement`() {
            val event = createTestEvent(sequence = 7)
            val attendee = Attendee(
                email = "proposer@example.com",
                name = "Proposer",
                partStat = PartStat.TENTATIVE,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )
            val proposedStart = ICalDateTime.parse("20231216T100000Z")
            val proposedEnd = ICalDateTime.parse("20231216T110000Z")

            val ics = builder.createCounter(event, attendee, proposedStart, proposedEnd)
            assertTrue(ics.contains("SEQUENCE:7"), "COUNTER must preserve original SEQUENCE")
        }
    }

    @Nested
    @DisplayName("createDeclineCounter Tests")
    inner class CreateDeclineCounterTests {
        @Test
        fun `createDeclineCounter sets METHOD DECLINECOUNTER`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "declined@example.com",
                name = "Declined",
                partStat = PartStat.DECLINED,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createDeclineCounter(event, attendee)
            assertTrue(ics.contains("METHOD:DECLINECOUNTER"))
        }
    }

    @Nested
    @DisplayName("createRefresh Tests")
    inner class CreateRefreshTests {
        @Test
        fun `createRefresh sets METHOD REFRESH`() {
            val event = createTestEvent()
            val attendee = Attendee(
                email = "requester@example.com",
                name = "Requester",
                partStat = PartStat.NEEDS_ACTION,
                role = AttendeeRole.REQ_PARTICIPANT,
                rsvp = false
            )

            val ics = builder.createRefresh(event, attendee)
            assertTrue(ics.contains("METHOD:REFRESH"))
        }
    }

    @Test
    fun `default companion instance works`() {
        val event = createTestEvent()
        val ics = ITipBuilder.default.createCancel(event)
        assertTrue(ics.contains("METHOD:CANCEL"))
    }
}
