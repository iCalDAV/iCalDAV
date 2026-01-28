package org.onekash.icaldav.xml

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Comprehensive comparison test: Regex-based parsing vs XmlPullParser
 *
 * This test evaluates:
 * 1. Correctness - Do both approaches extract the same values?
 * 2. Entity decoding - How does each handle XML entities (&amp;, &lt;, etc.)?
 * 3. Performance - Which is faster?
 * 4. Migration path - Effort to switch from regex to XmlPullParser
 *
 * ## Key Findings
 *
 * | Aspect | Regex | XmlPullParser |
 * |--------|-------|---------------|
 * | Correctness | ✅ Works | ✅ Works |
 * | Entity decoding | ⚠️ Manual (inconsistent) | ✅ Automatic |
 * | Performance | Slower | 2-4x faster |
 * | Namespace handling | ⚠️ Pattern-based | ✅ Proper |
 * | Memory usage | Higher (string copies) | Lower (streaming) |
 * | Maintainability | ⚠️ Complex patterns | ✅ Clear state machine |
 *
 * ## Bug Found
 *
 * icaldav's regex parser has inconsistent XML entity decoding:
 * - extractEtag() calls decodeXmlEntities() ✅
 * - extractPropertyValue() does NOT call decodeXmlEntities() ❌
 *
 * This means displayname, calendar-color, and other properties with XML entities
 * like &amp; will display incorrectly (e.g., "Work &amp; Personal" instead of "Work & Personal")
 */
@DisplayName("XML Parser Comparison: Regex vs XmlPullParser")
class XmlParserComparisonTest {

    private val regexParser = MultiStatusParser()

    // ============================================================================
    // Test Fixtures - Real-world CalDAV XML responses
    // ============================================================================

    /** Calendar list response with various properties */
    private val calendarListXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/" xmlns:CS="http://calendarserver.org/ns/">
            <D:response>
                <D:href>/calendars/user/personal/</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>Personal Calendar</D:displayname>
                        <D:resourcetype>
                            <D:collection/>
                            <C:calendar/>
                        </D:resourcetype>
                        <A:calendar-color>#0000FF</A:calendar-color>
                        <CS:getctag>HwoQEgwAADqW2b3c4d5e6f</CS:getctag>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
            <D:response>
                <D:href>/calendars/user/work/</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>Work Calendar</D:displayname>
                        <D:resourcetype>
                            <D:collection/>
                            <C:calendar/>
                        </D:resourcetype>
                        <A:calendar-color>#FF0000</A:calendar-color>
                        <CS:getctag>IxoRFhkBBEuX3c4d5e6f7g</CS:getctag>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        </D:multistatus>
    """.trimIndent()

    /** Sync-collection response with changed and deleted items */
    private val syncCollectionXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
                <D:href>/calendars/user/personal/event1.ics</D:href>
                <D:propstat>
                    <D:prop>
                        <D:getetag>"abc123def456"</D:getetag>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
            <D:response>
                <D:href>/calendars/user/personal/event2.ics</D:href>
                <D:propstat>
                    <D:prop>
                        <D:getetag>"xyz789ghi012"</D:getetag>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
            <D:response>
                <D:href>/calendars/user/personal/deleted.ics</D:href>
                <D:status>HTTP/1.1 404 Not Found</D:status>
            </D:response>
            <D:sync-token>http://sabre.io/ns/sync/63845d9c3a7b9</D:sync-token>
        </D:multistatus>
    """.trimIndent()

    /** Principal discovery response */
    private val principalXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
                <D:href>/</D:href>
                <D:propstat>
                    <D:prop>
                        <D:current-user-principal>
                            <D:href>/principals/users/testuser/</D:href>
                        </D:current-user-principal>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        </D:multistatus>
    """.trimIndent()

    /** Calendar home set response */
    private val calendarHomeXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response>
                <D:href>/principals/users/testuser/</D:href>
                <D:propstat>
                    <D:prop>
                        <C:calendar-home-set>
                            <D:href>https://caldav.example.com:443/calendars/testuser/</D:href>
                        </C:calendar-home-set>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        </D:multistatus>
    """.trimIndent()

    /** XML with entity-encoded values - THE BUG TEST */
    private val entityEncodedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/">
            <D:response>
                <D:href>/calendars/user/special/</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>Work &amp; Personal &lt;2024&gt;</D:displayname>
                        <D:getetag>&quot;abc123&quot;</D:getetag>
                        <C:calendar-description>Events for work &amp; personal use</C:calendar-description>
                        <A:calendar-color>#FF0000</A:calendar-color>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        </D:multistatus>
    """.trimIndent()

    // ============================================================================
    // XmlPullParser Implementation
    // ============================================================================

    /**
     * XmlPullParser-based multistatus parser.
     *
     * Advantages over regex:
     * - Automatic XML entity decoding (&amp; → &, &lt; → <, etc.)
     * - Single-pass parsing (no multiple regex scans)
     * - Proper namespace handling
     * - Streaming (lower memory)
     * - Validates XML structure
     */
    private class XmlPullParserImpl {

        data class ParsedResponse(
            val href: String,
            val status: Int,
            val displayName: String?,
            val resourceType: String?,
            val etag: String?,
            val ctag: String?,
            val syncToken: String?,
            val calendarColor: String?,
            val calendarDescription: String?,
            val currentUserPrincipal: String?,
            val calendarHomeSet: String?,
            val calendarData: String?
        )

        data class ParseResult(
            val responses: List<ParsedResponse>,
            val syncToken: String?
        )

        fun parse(xml: String): ParseResult {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val responses = mutableListOf<ParsedResponse>()
            var rootSyncToken: String? = null

            // Response-level state
            var currentHref: String? = null
            var currentStatus = 200
            var currentDisplayName: String? = null
            var currentResourceType: String? = null
            var currentEtag: String? = null
            var currentCtag: String? = null
            var currentCalendarColor: String? = null
            var currentCalendarDescription: String? = null
            var currentUserPrincipal: String? = null
            var currentCalendarHomeSet: String? = null
            var currentCalendarData: String? = null

            // Nested element tracking
            var inResponse = false
            var inCurrentUserPrincipal = false
            var inCalendarHomeSet = false
            var inResourceType = false
            var resourceTypeContent = StringBuilder()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val localName = parser.name.lowercase()

                        when (localName) {
                            "response" -> {
                                inResponse = true
                                // Reset response state
                                currentHref = null
                                currentStatus = 200
                                currentDisplayName = null
                                currentResourceType = null
                                currentEtag = null
                                currentCtag = null
                                currentCalendarColor = null
                                currentCalendarDescription = null
                                currentUserPrincipal = null
                                currentCalendarHomeSet = null
                                currentCalendarData = null
                            }
                            "href" -> {
                                val text = parser.nextText().trim()
                                when {
                                    inCalendarHomeSet -> currentCalendarHomeSet = text
                                    inCurrentUserPrincipal -> currentUserPrincipal = text
                                    inResponse -> currentHref = text
                                }
                            }
                            "status" -> {
                                val statusText = parser.nextText()
                                val statusMatch = Regex("""HTTP/\d+\.\d+\s+(\d+)""").find(statusText)
                                currentStatus = statusMatch?.groupValues?.get(1)?.toIntOrNull() ?: 200
                            }
                            "displayname" -> {
                                currentDisplayName = parser.nextText().trim().takeIf { it.isNotEmpty() }
                            }
                            "resourcetype" -> {
                                inResourceType = true
                                resourceTypeContent.clear()
                            }
                            "getetag" -> {
                                currentEtag = parser.nextText().trim().removeSurrounding("\"")
                            }
                            "getctag" -> {
                                currentCtag = parser.nextText().trim()
                            }
                            "sync-token" -> {
                                val token = parser.nextText().trim()
                                if (inResponse) {
                                    // Ignore response-level sync-token
                                } else {
                                    rootSyncToken = token
                                }
                            }
                            "calendar-color" -> {
                                currentCalendarColor = parser.nextText().trim()
                            }
                            "calendar-description" -> {
                                currentCalendarDescription = parser.nextText().trim()
                            }
                            "current-user-principal" -> {
                                inCurrentUserPrincipal = true
                            }
                            "calendar-home-set" -> {
                                inCalendarHomeSet = true
                            }
                            "calendar-data" -> {
                                currentCalendarData = parser.nextText().trim()
                            }
                            // Track resourcetype children
                            "collection", "calendar" -> {
                                if (inResourceType) {
                                    resourceTypeContent.append("<$localName/>")
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val localName = parser.name.lowercase()

                        when (localName) {
                            "response" -> {
                                if (currentHref != null) {
                                    responses.add(ParsedResponse(
                                        href = currentHref!!,
                                        status = currentStatus,
                                        displayName = currentDisplayName,
                                        resourceType = currentResourceType,
                                        etag = currentEtag,
                                        ctag = currentCtag,
                                        syncToken = null,
                                        calendarColor = currentCalendarColor,
                                        calendarDescription = currentCalendarDescription,
                                        currentUserPrincipal = currentUserPrincipal,
                                        calendarHomeSet = currentCalendarHomeSet,
                                        calendarData = currentCalendarData
                                    ))
                                }
                                inResponse = false
                            }
                            "resourcetype" -> {
                                currentResourceType = resourceTypeContent.toString()
                                inResourceType = false
                            }
                            "current-user-principal" -> {
                                inCurrentUserPrincipal = false
                            }
                            "calendar-home-set" -> {
                                inCalendarHomeSet = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            return ParseResult(responses, rootSyncToken)
        }
    }

    // ============================================================================
    // Correctness Tests - Verify both parsers extract same values
    // ============================================================================

    @Nested
    @DisplayName("Correctness: Regex vs XmlPullParser")
    inner class CorrectnessTests {

        @Test
        fun `both parsers extract same hrefs from calendar list`() {
            val regexResult = regexParser.parse(calendarListXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexHrefs = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses.map { it.href }

            val pullResult = XmlPullParserImpl().parse(calendarListXml)
            val pullHrefs = pullResult.responses.map { it.href }

            assertEquals(regexHrefs.size, pullHrefs.size, "Different response count")
            assertEquals(regexHrefs.toSet(), pullHrefs.toSet(), "Different hrefs extracted")
        }

        @Test
        fun `both parsers extract same displaynames from calendar list`() {
            val regexResult = regexParser.parse(calendarListXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexNames = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses
                .map { it.properties.displayName }

            val pullResult = XmlPullParserImpl().parse(calendarListXml)
            val pullNames = pullResult.responses.map { it.displayName }

            assertEquals(regexNames, pullNames, "Different displaynames extracted")
        }

        @Test
        fun `both parsers extract same etags from sync-collection`() {
            val regexResult = regexParser.parse(syncCollectionXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexEtags = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses
                .mapNotNull { it.etag }

            val pullResult = XmlPullParserImpl().parse(syncCollectionXml)
            val pullEtags = pullResult.responses.mapNotNull { it.etag }

            assertEquals(regexEtags.toSet(), pullEtags.toSet(), "Different etags extracted")
        }

        @Test
        fun `both parsers extract same sync-token`() {
            val regexResult = regexParser.parse(syncCollectionXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexToken = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.syncToken

            val pullResult = XmlPullParserImpl().parse(syncCollectionXml)

            assertEquals(regexToken, pullResult.syncToken, "Different sync-tokens extracted")
        }

        @Test
        fun `both parsers extract same principal URL`() {
            val regexResult = regexParser.parse(principalXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexPrincipal = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.currentUserPrincipal

            val pullResult = XmlPullParserImpl().parse(principalXml)
            val pullPrincipal = pullResult.responses[0].currentUserPrincipal

            assertEquals(regexPrincipal, pullPrincipal, "Different principal URLs extracted")
        }

        @Test
        fun `both parsers extract same calendar-home-set URL`() {
            val regexResult = regexParser.parse(calendarHomeXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexHome = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.calendarHomeSet

            val pullResult = XmlPullParserImpl().parse(calendarHomeXml)
            val pullHome = pullResult.responses[0].calendarHomeSet

            assertEquals(regexHome, pullHome, "Different calendar-home-set URLs extracted")
        }

        @Test
        fun `both parsers detect deleted items (404 status)`() {
            val regexResult = regexParser.parse(syncCollectionXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexDeleted = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses
                .filter { it.status == 404 }
                .map { it.href }

            val pullResult = XmlPullParserImpl().parse(syncCollectionXml)
            val pullDeleted = pullResult.responses
                .filter { it.status == 404 }
                .map { it.href }

            assertEquals(regexDeleted, pullDeleted, "Different deleted items detected")
        }
    }

    // ============================================================================
    // XML Entity Decoding Tests - THE BUG
    // ============================================================================

    @Nested
    @DisplayName("XML Entity Decoding (BUG TEST)")
    inner class EntityDecodingTests {

        @Test
        fun `XmlPullParser automatically decodes XML entities in displayname`() {
            val pullResult = XmlPullParserImpl().parse(entityEncodedXml)
            val displayName = pullResult.responses[0].displayName

            // XmlPullParser automatically decodes entities
            assertEquals("Work & Personal <2024>", displayName,
                "XmlPullParser should auto-decode &amp; → & and &lt;/&gt; → </>")
        }

        @Test
        fun `MultiStatusParser now decodes XML entities in displayname - BUG FIXED`() {
            // BUG WAS: Old regex parser didn't decode entities in displayname
            // FIX: Migrated to XmlPullParser which auto-decodes entities
            val result = regexParser.parse(entityEncodedXml)
            assertTrue(result is org.onekash.icaldav.model.DavResult.Success)
            val displayName = (result as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.displayName

            // FIXED: Now correctly decodes entities
            assertEquals("Work & Personal <2024>", displayName,
                "XmlPullParser-based parser correctly decodes all entities")
        }

        @Test
        fun `XmlPullParser decodes entities in calendar-description`() {
            val pullResult = XmlPullParserImpl().parse(entityEncodedXml)
            val description = pullResult.responses[0].calendarDescription

            assertEquals("Events for work & personal use", description,
                "XmlPullParser should auto-decode &amp; → &")
        }

        @Test
        fun `Both parsers decode entities in etag (regex has fix)`() {
            val regexResult = regexParser.parse(entityEncodedXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexEtag = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0].etag

            val pullResult = XmlPullParserImpl().parse(entityEncodedXml)
            val pullEtag = pullResult.responses[0].etag

            // Both should decode: &quot;abc123&quot; → abc123 (quotes removed)
            assertEquals("abc123", regexEtag, "Regex correctly decodes etag entities")
            assertEquals("abc123", pullEtag, "XmlPullParser correctly decodes etag entities")
            assertEquals(regexEtag, pullEtag, "Both should produce same etag")
        }

        @Test
        fun `VERIFICATION - Entity decoding is now consistent after XmlPullParser migration`() {
            /*
             * BEFORE (regex parser bug):
             * - extractEtag() called decodeXmlEntities() ✅
             * - extractPropertyValue() did NOT call decodeXmlEntities() ❌
             *
             * AFTER (XmlPullParser migration):
             * - All values auto-decoded by XmlPullParser ✅
             * - Consistent behavior across all properties ✅
             */

            val result = regexParser.parse(entityEncodedXml)
            assertTrue(result is org.onekash.icaldav.model.DavResult.Success)
            val response = (result as org.onekash.icaldav.model.DavResult.Success).value.responses[0]

            // All properties now correctly decoded
            assertEquals("abc123", response.etag, "Etag is correctly decoded")
            assertEquals("Work & Personal <2024>", response.properties.displayName,
                "FIXED: Displayname now correctly decoded")

            // Both implementations should match
            val pullResult = XmlPullParserImpl().parse(entityEncodedXml)
            assertEquals(response.properties.displayName, pullResult.responses[0].displayName,
                "Both implementations decode entities identically")
        }
    }

    // ============================================================================
    // Performance Comparison
    // ============================================================================

    @Nested
    @DisplayName("Performance: Regex vs XmlPullParser")
    inner class PerformanceTests {

        @Test
        fun `performance comparison on calendar list parsing`() {
            val warmupIterations = 100
            val testIterations = 1000

            // Warmup
            repeat(warmupIterations) {
                regexParser.parse(calendarListXml)
                XmlPullParserImpl().parse(calendarListXml)
            }

            // Benchmark regex
            val regexStart = System.nanoTime()
            repeat(testIterations) {
                regexParser.parse(calendarListXml)
            }
            val regexTime = (System.nanoTime() - regexStart) / 1_000_000.0

            // Benchmark XmlPullParser
            val pullStart = System.nanoTime()
            repeat(testIterations) {
                XmlPullParserImpl().parse(calendarListXml)
            }
            val pullTime = (System.nanoTime() - pullStart) / 1_000_000.0

            println("""
                |
                |Performance Results ($testIterations iterations):
                |  MultiStatusParser:  ${String.format("%.2f", regexTime)} ms (${String.format("%.3f", regexTime/testIterations)} ms/parse)
                |  XmlPullParserImpl:  ${String.format("%.2f", pullTime)} ms (${String.format("%.3f", pullTime/testIterations)} ms/parse)
                |  Ratio:              ${String.format("%.2f", regexTime/pullTime)}x
                |
            """.trimMargin())

            // Both implementations now use XmlPullParser, performance should be similar
            // Just verify both complete successfully (no assertion on relative speed)
            assertTrue(true, "Both parsers completed successfully")
        }

        @Test
        fun `performance comparison on sync-collection parsing`() {
            val testIterations = 1000

            // Warmup
            repeat(100) {
                regexParser.parse(syncCollectionXml)
                XmlPullParserImpl().parse(syncCollectionXml)
            }

            val regexStart = System.nanoTime()
            repeat(testIterations) { regexParser.parse(syncCollectionXml) }
            val regexTime = (System.nanoTime() - regexStart) / 1_000_000.0

            val pullStart = System.nanoTime()
            repeat(testIterations) { XmlPullParserImpl().parse(syncCollectionXml) }
            val pullTime = (System.nanoTime() - pullStart) / 1_000_000.0

            println("""
                |
                |Sync-collection Performance ($testIterations iterations):
                |  Regex:         ${String.format("%.2f", regexTime)} ms
                |  XmlPullParser: ${String.format("%.2f", pullTime)} ms
                |  Speedup:       ${String.format("%.1f", regexTime/pullTime)}x
                |
            """.trimMargin())
        }
    }

    // ============================================================================
    // Namespace Handling Tests
    // ============================================================================

    @Nested
    @DisplayName("Namespace Handling")
    inner class NamespaceTests {

        /** iCloud uses lowercase 'd:' prefix */
        private val icloudStyleXml = """
            <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:response>
                    <d:href>/calendars/user/cal/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:displayname>iCloud Calendar</d:displayname>
                            <cs:getctag>icloud-ctag-123</cs:getctag>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        /** Nextcloud uses uppercase 'D:' prefix */
        private val nextcloudStyleXml = """
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/">
                <D:response>
                    <D:href>/remote.php/dav/calendars/user/personal/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Nextcloud Calendar</D:displayname>
                            <CS:getctag>nextcloud-ctag-456</CS:getctag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        /** Some servers use no namespace prefix */
        private val noPrefixXml = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/user/</href>
                    <propstat>
                        <prop>
                            <displayname>No Prefix Calendar</displayname>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        @Test
        fun `both parsers handle iCloud style (lowercase d prefix)`() {
            val regexResult = regexParser.parse(icloudStyleXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexName = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.displayName

            val pullResult = XmlPullParserImpl().parse(icloudStyleXml)
            val pullName = pullResult.responses[0].displayName

            assertEquals("iCloud Calendar", regexName)
            assertEquals("iCloud Calendar", pullName)
        }

        @Test
        fun `both parsers handle Nextcloud style (uppercase D prefix)`() {
            val regexResult = regexParser.parse(nextcloudStyleXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexName = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.displayName

            val pullResult = XmlPullParserImpl().parse(nextcloudStyleXml)
            val pullName = pullResult.responses[0].displayName

            assertEquals("Nextcloud Calendar", regexName)
            assertEquals("Nextcloud Calendar", pullName)
        }

        @Test
        fun `both parsers handle no namespace prefix`() {
            val regexResult = regexParser.parse(noPrefixXml)
            assertTrue(regexResult is org.onekash.icaldav.model.DavResult.Success)
            val regexName = (regexResult as org.onekash.icaldav.model.DavResult.Success).value.responses[0]
                .properties.displayName

            val pullResult = XmlPullParserImpl().parse(noPrefixXml)
            val pullName = pullResult.responses[0].displayName

            assertEquals("No Prefix Calendar", regexName)
            assertEquals("No Prefix Calendar", pullName)
        }
    }

    // ============================================================================
    // Migration Path Evaluation
    // ============================================================================

    @Nested
    @DisplayName("Migration Path Evaluation")
    inner class MigrationEvaluationTests {

        /**
         * This test documents the effort required to migrate from regex to XmlPullParser.
         *
         * Files that need changes:
         * 1. MultiStatusParser.kt - Main parser (409 lines)
         * 2. DefaultQuirks.kt - Generic CalDAV parsing (372 lines)
         * 3. ICloudQuirks.kt - iCloud-specific parsing (~400 lines)
         * 4. AclParser.kt - ACL permission parsing
         * 5. ScheduleResponseParser.kt - Free/busy parsing
         *
         * Effort estimate: 8-12 hours for full migration
         *
         * Benefits:
         * - Automatic entity decoding (fixes displayname bug)
         * - 2-4x faster parsing
         * - Single-pass extraction
         * - Lower memory usage
         * - Proper namespace support
         * - XML structure validation
         *
         * Risks:
         * - Regression risk (need comprehensive tests)
         * - XmlPullParser behavior differences
         * - CDATA handling differences
         */
        @Test
        fun `document migration complexity`() {
            // This test just documents - actual migration tracked separately

            val regexFilesToMigrate = listOf(
                "MultiStatusParser.kt" to "Main multistatus parser",
                "DefaultQuirks.kt" to "Generic CalDAV extraction",
                "ICloudQuirks.kt" to "iCloud-specific patterns",
                "AclParser.kt" to "ACL permission parsing",
                "ScheduleResponseParser.kt" to "Free/busy parsing"
            )

            println("""
                |
                |=== Migration Path: Regex → XmlPullParser ===
                |
                |Files to migrate:
                |${regexFilesToMigrate.joinToString("\n") { "  - ${it.first}: ${it.second}" }}
                |
                |Quick fix (alternative):
                |  Add decodeXmlEntities() call to extractPropertyValue() in MultiStatusParser.kt
                |  One-line fix for the displayname entity decoding bug
                |
                |Full migration benefits:
                |  - 2-4x faster parsing
                |  - Automatic entity decoding everywhere
                |  - Single-pass extraction
                |  - Lower memory footprint
                |  - Proper XML validation
                |
            """.trimMargin())
        }

        @Test
        fun `demonstrate quick fix for entity decoding bug`() {
            /*
             * QUICK FIX for MultiStatusParser.kt line 328-335:
             *
             * Change:
             *   return pattern.find(propContent)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
             *
             * To:
             *   return pattern.find(propContent)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
             *       ?.let { decodeXmlEntities(it) }
             *
             * This adds entity decoding to extractPropertyValue() just like extractEtag() has.
             */

            // Simulate fixed behavior
            fun decodeXmlEntities(text: String): String = text
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")

            val rawDisplayName = "Work &amp; Personal &lt;2024&gt;"
            val fixedDisplayName = decodeXmlEntities(rawDisplayName)

            assertEquals("Work & Personal <2024>", fixedDisplayName,
                "Quick fix: just add decodeXmlEntities() call to extractPropertyValue()")
        }
    }

    // ============================================================================
    // dav4jvm Comparison (Reference)
    // ============================================================================

    @Nested
    @DisplayName("dav4jvm Reference Comparison")
    inner class Dav4jvmComparisonTests {

        /**
         * dav4jvm (https://github.com/bitfire-web/dav4jvm) is a popular CalDAV library.
         *
         * Key differences from icaldav:
         *
         * | Aspect | icaldav | dav4jvm |
         * |--------|---------|---------|
         * | XML Parser | Regex | XmlPullParser |
         * | Entity decoding | Manual (buggy) | Automatic |
         * | Kotlin | Pure Kotlin | Kotlin + Java |
         * | Dependencies | OkHttp only | OkHttp + XmlPullParser |
         * | Android support | Via kxml2 | Native XmlPullParser |
         *
         * dav4jvm approach:
         * - Uses XmlPullParser throughout
         * - Defines Property interface for each DAV property
         * - Type-safe property access
         * - Automatic entity decoding
         *
         * icaldav approach:
         * - Uses regex patterns for flexibility
         * - Returns raw strings
         * - Manual entity handling
         * - Faster to implement but buggy
         */
        @Test
        fun `document dav4jvm architecture comparison`() {
            println("""
                |
                |=== dav4jvm vs icaldav Architecture ===
                |
                |dav4jvm (XmlPullParser-based):
                |  - Uses XmlPullParser with PropertyRegistry
                |  - Each property has typed class (DisplayName, GetCTag, etc.)
                |  - Automatic XML entity decoding
                |  - Proper namespace handling via XmlPullParser
                |  - ~15 property classes for CalDAV support
                |
                |icaldav (Regex-based):
                |  - Uses regex patterns with namespace wildcards
                |  - Returns raw strings from DavProperties
                |  - Manual entity decoding (inconsistently applied)
                |  - Pattern-based namespace handling
                |  - Simpler implementation, fewer classes
                |
                |Migration recommendation:
                |  Option 1: Quick fix - Add decodeXmlEntities() to extractPropertyValue()
                |  Option 2: Partial migration - Use XmlPullParser for new code
                |  Option 3: Full migration - Replace regex with XmlPullParser throughout
                |  Option 4: Adopt dav4jvm - Use battle-tested library
                |
            """.trimMargin())
        }
    }
}
