package com.icalendar.core.parser

import com.icalendar.core.generator.ICalGenerator
import com.icalendar.core.model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for RFC 9253 property parsing and roundtrip.
 * Tests LINK and RELATED-TO properties.
 *
 * Note: ical4j requires VALUE=URI parameter for LINK properties.
 */
class ICalParserRfc9253Test {

    private lateinit var parser: ICalParser
    private lateinit var generator: ICalGenerator

    @BeforeEach
    fun setup() {
        parser = ICalParser()
        generator = ICalGenerator()
    }

    @Nested
    inner class LinkPropertyParsing {

        @Test
        fun `parse event with single LINK property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Link
                LINK;VALUE=URI:https://example.com/event-details
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.links.size)
            assertEquals("https://example.com/event-details", event.links[0].uri)
            assertEquals(LinkRelationType.RELATED, event.links[0].relation)
        }

        @Test
        fun `parse LINK with REL parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Alternate Link
                LINK;VALUE=URI;REL=alternate:https://example.com/event.html
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals(LinkRelationType.ALTERNATE, event.links[0].relation)
        }

        @Test
        fun `parse LINK with FMTTYPE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with PDF Link
                LINK;VALUE=URI;FMTTYPE=application/pdf:https://example.com/agenda.pdf
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals("application/pdf", event.links[0].mediaType)
        }

        @Test
        fun `parse LINK with TITLE parameter`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Titled Link
                LINK;VALUE=URI;TITLE="Event Details Page":https://example.com/details
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(1, event.links.size)
            assertEquals("Event Details Page", event.links[0].title)
        }

        @Test
        fun `parse LINK with multiple parameters`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Full Link
                LINK;VALUE=URI;REL=describedby;FMTTYPE=text/html;TITLE="More Info";LABEL=Details;LANGUAGE=en:https://example.com/info
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]
            val link = event.links[0]

            assertEquals("https://example.com/info", link.uri)
            assertEquals(LinkRelationType.DESCRIBEDBY, link.relation)
            assertEquals("text/html", link.mediaType)
            assertEquals("More Info", link.title)
            assertEquals("Details", link.label)
            assertEquals("en", link.language)
        }

        @Test
        fun `parse multiple LINK properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:link-test-6
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Multiple Links
                LINK;VALUE=URI;REL=alternate:https://example.com/event.html
                LINK;VALUE=URI;REL=describedby:https://example.com/spec.pdf
                LINK;VALUE=URI;REL=related:https://example.com/related
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(3, event.links.size)
        }
    }

    @Nested
    inner class RelatedToPropertyParsing {

        @Test
        fun `parse event with single RELATED-TO property`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Child Event
                RELATED-TO:parent-event-uid-123
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)

            val event = result.getOrNull()!![0]
            assertEquals(1, event.relations.size)
            assertEquals("parent-event-uid-123", event.relations[0].uid)
            assertEquals(RelationType.PARENT, event.relations[0].relationType)
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=PARENT`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-2
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Child Event with Explicit Parent
                RELATED-TO;RELTYPE=PARENT:parent-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.PARENT, event.relations[0].relationType)
            assertTrue(event.relations[0].isParent())
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=CHILD`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-3
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Parent Event
                RELATED-TO;RELTYPE=CHILD:child-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.CHILD, event.relations[0].relationType)
            assertTrue(event.relations[0].isChild())
        }

        @Test
        fun `parse RELATED-TO with RELTYPE=SIBLING`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-4
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Sibling Event
                RELATED-TO;RELTYPE=SIBLING:sibling-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(RelationType.SIBLING, event.relations[0].relationType)
            assertTrue(event.relations[0].isSibling())
        }

        @Test
        fun `parse multiple RELATED-TO properties`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-5
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Multiple Relations
                RELATED-TO;RELTYPE=PARENT:parent-uid
                RELATED-TO;RELTYPE=SIBLING:sibling-uid-1
                RELATED-TO;RELTYPE=SIBLING:sibling-uid-2
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(3, event.relations.size)
        }

        @Test
        fun `parse RELATED-TO with RFC 9253 RELTYPE values`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:related-test-6
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Event with Advanced Relations
                RELATED-TO;RELTYPE=DEPENDS-ON:dependency-uid
                RELATED-TO;RELTYPE=NEXT:next-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event = result.getOrNull()!![0]

            assertEquals(2, event.relations.size)
            assertEquals(RelationType.DEPENDS_ON, event.relations[0].relationType)
            assertEquals(RelationType.NEXT, event.relations[1].relationType)
        }
    }

    @Nested
    inner class RoundtripTests {

        @Test
        fun `LINK property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-link-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Link Test
                LINK;VALUE=URI;REL=alternate;FMTTYPE=text/html:https://example.com/event.html
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.links.size)

            // Generate
            val generated = generator.generate(event1)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify
            assertEquals(event1.links.size, event2.links.size)
            assertEquals(event1.links[0].uri, event2.links[0].uri)
            assertEquals(event1.links[0].relation, event2.links[0].relation)
            assertEquals(event1.links[0].mediaType, event2.links[0].mediaType)
        }

        @Test
        fun `RELATED-TO property survives roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-related-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Roundtrip Related Test
                RELATED-TO;RELTYPE=PARENT:parent-event-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(1, event1.relations.size)

            // Generate
            val generated = generator.generate(event1)

            // Parse generated
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify
            assertEquals(event1.relations.size, event2.relations.size)
            assertEquals(event1.relations[0].uid, event2.relations[0].uid)
            assertEquals(event1.relations[0].relationType, event2.relations[0].relationType)
        }

        @Test
        fun `multiple links and relations survive roundtrip`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:roundtrip-multi-1
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Multi Property Test
                LINK;VALUE=URI;REL=alternate:https://example.com/alt.html
                LINK;VALUE=URI;REL=describedby:https://example.com/spec.pdf
                RELATED-TO;RELTYPE=PARENT:parent-uid
                RELATED-TO;RELTYPE=SIBLING:sibling-uid
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            // Parse original
            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertEquals(2, event1.links.size)
            assertEquals(2, event1.relations.size)

            // Generate and re-parse
            val generated = generator.generate(event1)
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            // Verify counts preserved
            assertEquals(2, event2.links.size)
            assertEquals(2, event2.relations.size)
        }

        @Test
        fun `event without RFC 9253 properties still works`() {
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:no-rfc9253-props
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T140000Z
                SUMMARY:Basic Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            assertTrue(result is ParseResult.Success)
            val event1 = result.getOrNull()!![0]
            assertTrue(event1.links.isEmpty())
            assertTrue(event1.relations.isEmpty())

            // Generate and re-parse
            val generated = generator.generate(event1)
            val result2 = parser.parseAllEvents(generated)
            assertTrue(result2 is ParseResult.Success)
            val event2 = result2.getOrNull()!![0]

            assertTrue(event2.links.isEmpty())
            assertTrue(event2.relations.isEmpty())
        }
    }

    @Nested
    inner class LinkModelTests {

        @Test
        fun `ICalLink factory methods work correctly`() {
            val alternate = ICalLink.alternate("https://example.com", "text/html", "Title")
            assertEquals(LinkRelationType.ALTERNATE, alternate.relation)
            assertEquals("text/html", alternate.mediaType)
            assertEquals("Title", alternate.title)

            val describedBy = ICalLink.describedBy("https://example.com/spec")
            assertEquals(LinkRelationType.DESCRIBEDBY, describedBy.relation)

            val related = ICalLink.related("https://example.com/related")
            assertEquals(LinkRelationType.RELATED, related.relation)
        }

        @Test
        fun `ICalLink toICalString generates correct format`() {
            val link = ICalLink(
                uri = "https://example.com",
                relation = LinkRelationType.ALTERNATE,
                mediaType = "text/html"
            )

            val result = link.toICalString()
            assertTrue(result.startsWith("LINK;"))
            assertTrue(result.contains("VALUE=URI"))
            assertTrue(result.contains("REL=alternate"))
            assertTrue(result.contains("FMTTYPE=text/html"))
            assertTrue(result.endsWith(":https://example.com"))
        }
    }

    @Nested
    inner class RelationModelTests {

        @Test
        fun `ICalRelation factory methods work correctly`() {
            val parent = ICalRelation.parent("parent-uid")
            assertEquals(RelationType.PARENT, parent.relationType)
            assertTrue(parent.isParent())

            val child = ICalRelation.child("child-uid")
            assertEquals(RelationType.CHILD, child.relationType)
            assertTrue(child.isChild())

            val sibling = ICalRelation.sibling("sibling-uid")
            assertEquals(RelationType.SIBLING, sibling.relationType)
            assertTrue(sibling.isSibling())
        }

        @Test
        fun `ICalRelation toICalString generates correct format`() {
            val relation = ICalRelation(
                uid = "related-uid",
                relationType = RelationType.CHILD
            )

            val result = relation.toICalString()
            assertEquals("RELATED-TO;RELTYPE=CHILD:related-uid", result)
        }

        @Test
        fun `ICalRelation with PARENT omits RELTYPE (default)`() {
            val relation = ICalRelation.parent("parent-uid")
            val result = relation.toICalString()

            // PARENT is the default, so RELTYPE should be omitted
            assertEquals("RELATED-TO:parent-uid", result)
        }
    }
}
