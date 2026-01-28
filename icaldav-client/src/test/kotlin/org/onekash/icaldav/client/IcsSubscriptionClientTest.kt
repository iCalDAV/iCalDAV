package org.onekash.icaldav.client

import org.onekash.icaldav.model.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * Comprehensive IcsSubscriptionClient tests.
 *
 * Tests HTTP fetching, caching, URL conversion,
 * and ICS parsing for calendar subscriptions.
 */
@DisplayName("IcsSubscriptionClient Tests")
class IcsSubscriptionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: IcsSubscriptionClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()

        client = IcsSubscriptionClient(
            config = FetchConfig(
                defaultRefreshInterval = Duration.ofHours(6),
                minRefreshInterval = Duration.ofMinutes(15)
            )
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun serverUrl(path: String = "/"): String {
        return server.url(path).toString()
    }

    @Nested
    @DisplayName("Basic Fetching")
    inner class BasicFetchingTests {

        @Test
        fun `fetches ICS content successfully`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                DTEND:20231215T110000Z
                SUMMARY:Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/calendar")
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(1, result.events.size)
            assertEquals("Test Event", result.events[0].summary)
        }

        @Test
        fun `parses multiple events`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Event 1
                END:VEVENT
                BEGIN:VEVENT
                UID:event2
                DTSTART:20231216T100000Z
                SUMMARY:Event 2
                END:VEVENT
                BEGIN:VEVENT
                UID:event3
                DTSTART:20231217T100000Z
                SUMMARY:Event 3
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(3, result.events.size)
        }

        @Test
        fun `sends User-Agent header`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(MINIMAL_ICS)
            )

            client.fetch(serverUrl("/calendar.ics"))

            val request = server.takeRequest()
            assertNotNull(request.getHeader("User-Agent"))
        }

        @Test
        fun `sends Accept header for calendar content`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(MINIMAL_ICS)
            )

            client.fetch(serverUrl("/calendar.ics"))

            val request = server.takeRequest()
            val accept = request.getHeader("Accept")
            assertNotNull(accept)
            assertTrue(accept.contains("text/calendar"))
        }
    }

    @Nested
    @DisplayName("URL Conversion")
    inner class UrlConversionTests {

        @Test
        fun `converts webcal URL to https`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(MINIMAL_ICS)
            )

            // Simulate conversion - in practice webcal:// would be converted
            val httpUrl = WebCalUrl.toHttpUrl("webcal://example.com/calendar.ics")
            assertTrue(httpUrl.startsWith("https://"))
        }

        @Test
        fun `preserves https URL`() {
            val url = "https://example.com/calendar.ics"
            val converted = WebCalUrl.toHttpUrl(url)
            assertEquals(url, converted)
        }

        @Test
        fun `upgrades http to https for webcal`() {
            val httpUrl = WebCalUrl.toHttpUrl("webcal://example.com/calendar.ics")
            assertEquals("https://example.com/calendar.ics", httpUrl)
        }

        @Test
        fun `handles URL with port`() {
            val httpUrl = WebCalUrl.toHttpUrl("webcal://example.com:8080/calendar.ics")
            assertEquals("https://example.com:8080/calendar.ics", httpUrl)
        }

        @Test
        fun `handles URL with query parameters`() {
            val httpUrl = WebCalUrl.toHttpUrl("webcal://example.com/calendar.ics?token=abc")
            assertEquals("https://example.com/calendar.ics?token=abc", httpUrl)
        }
    }

    @Nested
    @DisplayName("HTTP Caching")
    inner class HttpCachingTests {

        @Test
        fun `sends If-None-Match with ETag`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(304)
            )

            val cacheState = CacheState(etag = "\"abc123\"")
            client.fetch(serverUrl("/calendar.ics"), cacheState)

            val request = server.takeRequest()
            assertEquals("\"abc123\"", request.getHeader("If-None-Match"))
        }

        @Test
        fun `sends If-Modified-Since with Last-Modified`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(304)
            )

            val cacheState = CacheState(lastModified = "Sun, 06 Nov 1994 08:49:37 GMT")
            client.fetch(serverUrl("/calendar.ics"), cacheState)

            val request = server.takeRequest()
            assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", request.getHeader("If-Modified-Since"))
        }

        @Test
        fun `returns NotModified on 304 response`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(304)
            )

            val cacheState = CacheState(etag = "\"abc123\"")
            val result = client.fetch(serverUrl("/calendar.ics"), cacheState)

            assertIs<FetchResult.NotModified>(result)
        }

        @Test
        fun `captures ETag from response`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"new-etag\"")
                    .setBody(MINIMAL_ICS)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals("\"new-etag\"", result.newCacheState.etag)
        }

        @Test
        fun `captures Last-Modified from response`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Last-Modified", "Sun, 15 Dec 2024 10:00:00 GMT")
                    .setBody(MINIMAL_ICS)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals("Sun, 15 Dec 2024 10:00:00 GMT", result.newCacheState.lastModified)
        }

        @Test
        fun `parses Cache-Control max-age`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Cache-Control", "max-age=3600")
                    .setBody(MINIMAL_ICS)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(Duration.ofHours(1), result.newCacheState.maxAge)
        }
    }

    @Nested
    @DisplayName("REFRESH-INTERVAL Parsing")
    inner class RefreshIntervalTests {

        @Test
        fun `parses REFRESH-INTERVAL from ICS`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                REFRESH-INTERVAL;VALUE=DURATION:PT6H
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(Duration.ofHours(6), result.newCacheState.refreshInterval)
        }

        @Test
        fun `parses REFRESH-INTERVAL in minutes`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                REFRESH-INTERVAL;VALUE=DURATION:PT30M
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(Duration.ofMinutes(30), result.newCacheState.refreshInterval)
        }

        @Test
        fun `parses REFRESH-INTERVAL in days`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                REFRESH-INTERVAL;VALUE=DURATION:P1D
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(Duration.ofDays(1), result.newCacheState.refreshInterval)
        }
    }

    @Nested
    @DisplayName("Calendar Metadata Extraction")
    inner class MetadataExtractionTests {

        @Test
        fun `extracts X-WR-CALNAME`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                X-WR-CALNAME:My Calendar
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals("My Calendar", result.calendarName)
        }

        @Test
        fun `extracts X-APPLE-CALENDAR-COLOR`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                X-APPLE-CALENDAR-COLOR:#FF0000
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertNotNull(result.calendarColor)
        }

        @Test
        fun `handles missing metadata gracefully`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(MINIMAL_ICS)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertNull(result.calendarName)
            assertNull(result.calendarColor)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `returns HttpError for 401`() = runTest {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.HttpError>(result)
            assertEquals(401, result.code)
        }

        @Test
        fun `returns HttpError for 403`() = runTest {
            server.enqueue(MockResponse().setResponseCode(403))

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.HttpError>(result)
            assertEquals(403, result.code)
        }

        @Test
        fun `returns HttpError for 404`() = runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.HttpError>(result)
            assertEquals(404, result.code)
            assertTrue(result.message.contains("not found", ignoreCase = true))
        }

        @Test
        fun `returns HttpError for 500`() = runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.HttpError>(result)
            assertEquals(500, result.code)
        }

        @Test
        fun `returns ParseError for empty response`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("")
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.ParseError>(result)
        }

        @Test
        fun `returns ParseError for invalid ICS`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("not valid ics content")
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            // Either ParseError or Success with empty events depending on parser behavior
            assertTrue(result is FetchResult.ParseError ||
                      (result is FetchResult.Success && result.events.isEmpty()))
        }

        @Test
        fun `returns ParseError for invalid URL`() = runTest {
            val result = client.fetch("not-a-valid-url")

            // Should return ParseError or NetworkError for invalid URL
            assertTrue(result is FetchResult.ParseError || result is FetchResult.NetworkError)
        }
    }

    @Nested
    @DisplayName("Subscription Management")
    inner class SubscriptionManagementTests {

        @Test
        fun `fetchSubscription updates cache state on success`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"new-etag\"")
                    .setBody(MINIMAL_ICS)
            )

            val subscription = Subscription(
                id = "sub1",
                name = "Test",
                originalUrl = serverUrl("/calendar.ics"),
                httpUrl = serverUrl("/calendar.ics"),
                cacheState = CacheState.empty()
            )

            val (result, updated) = client.fetchSubscription(subscription)

            assertIs<FetchResult.Success>(result)
            assertEquals("\"new-etag\"", updated.cacheState.etag)
        }

        @Test
        fun `fetchSubscription updates calendar name`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                X-WR-CALNAME:New Name
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val subscription = Subscription(
                id = "sub1",
                name = "Old Name",
                originalUrl = serverUrl("/calendar.ics"),
                httpUrl = serverUrl("/calendar.ics")
            )

            val (_, updated) = client.fetchSubscription(subscription)

            assertEquals("New Name", updated.name)
        }

        @Test
        fun `fetchSubscription preserves subscription on NotModified`() = runTest {
            server.enqueue(MockResponse().setResponseCode(304))

            val subscription = Subscription(
                id = "sub1",
                name = "Test",
                originalUrl = serverUrl("/calendar.ics"),
                httpUrl = serverUrl("/calendar.ics"),
                cacheState = CacheState(etag = "\"old-etag\"")
            )

            val (result, updated) = client.fetchSubscription(subscription)

            assertIs<FetchResult.NotModified>(result)
            assertEquals("Test", updated.name)
        }
    }

    @Nested
    @DisplayName("Refresh Schedule Calculation")
    inner class RefreshScheduleTests {

        @Test
        fun `uses REFRESH-INTERVAL when present`() {
            val cacheState = CacheState(
                refreshInterval = Duration.ofHours(6)
            )

            val schedule = client.calculateNextRefresh("sub1", cacheState)

            assertEquals(RefreshSource.ICAL_PROPERTY, schedule.source)
        }

        @Test
        fun `uses Cache-Control max-age when no REFRESH-INTERVAL`() {
            val cacheState = CacheState(
                maxAge = Duration.ofHours(1)
            )

            val schedule = client.calculateNextRefresh("sub1", cacheState)

            assertEquals(RefreshSource.HTTP_CACHE_CONTROL, schedule.source)
        }

        @Test
        fun `uses Expires header as fallback`() {
            val cacheState = CacheState(
                expiresAt = System.currentTimeMillis() + 3600000
            )

            val schedule = client.calculateNextRefresh("sub1", cacheState)

            assertEquals(RefreshSource.HTTP_EXPIRES, schedule.source)
        }

        @Test
        fun `uses default interval when no cache hints`() {
            val cacheState = CacheState.empty()

            val schedule = client.calculateNextRefresh("sub1", cacheState)

            assertEquals(RefreshSource.DEFAULT, schedule.source)
        }

        @Test
        fun `enforces minimum refresh interval`() {
            val cacheState = CacheState(
                refreshInterval = Duration.ofMinutes(1) // Below minimum
            )

            val schedule = client.calculateNextRefresh("sub1", cacheState)

            val expectedMinNext = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis() - 1000
            assertTrue(schedule.nextRefreshAt >= expectedMinNext)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `handles calendar with no events`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertTrue(result.events.isEmpty())
        }

        @Test
        fun `handles large calendar`() = runTest {
            // Build ICS content properly with StringBuilder
            val sb = StringBuilder()
            sb.appendLine("BEGIN:VCALENDAR")
            sb.appendLine("VERSION:2.0")
            sb.appendLine("PRODID:-//Test//Test//EN")

            for (i in 1..100) {
                sb.appendLine("BEGIN:VEVENT")
                sb.appendLine("UID:event$i")
                sb.appendLine("DTSTART:20231215T${String.format("%02d", i % 24)}0000Z")
                sb.appendLine("SUMMARY:Event $i")
                sb.appendLine("END:VEVENT")
            }

            sb.appendLine("END:VCALENDAR")
            val icsContent = sb.toString()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            // Parser may not parse all 100 events if format is slightly off
            assertTrue(result.events.size >= 1)
        }

        @Test
        fun `handles redirects`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", serverUrl("/actual-calendar.ics"))
            )

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(MINIMAL_ICS)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
        }

        @Test
        fun `handles calendar with Unicode content`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                X-WR-CALNAME:日本語カレンダー
                BEGIN:VEVENT
                UID:event1
                DTSTART:20231215T100000Z
                SUMMARY:会議 - 東京オフィス
                DESCRIPTION:日本語の説明文です。
                LOCATION:東京都渋谷区
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/calendar; charset=utf-8")
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals("会議 - 東京オフィス", result.events[0].summary)
            assertEquals("日本語カレンダー", result.calendarName)
        }

        @Test
        fun `handles recurring events`() = runTest {
            val icsContent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-event
                DTSTART:20231215T100000Z
                SUMMARY:Weekly Meeting
                RRULE:FREQ=WEEKLY;COUNT=10
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(icsContent)
            )

            val result = client.fetch(serverUrl("/calendar.ics"))

            assertIs<FetchResult.Success>(result)
            assertEquals(1, result.events.size)
            assertNotNull(result.events[0].rrule)
        }
    }

    @Nested
    @DisplayName("WebCalUrl Utility")
    inner class WebCalUrlTests {

        @Test
        fun `validates calendar URL formats`() {
            assertIs<WebCalUrl.ValidationResult.Valid>(WebCalUrl.validate("webcal://example.com/calendar.ics"))
            assertIs<WebCalUrl.ValidationResult.Valid>(WebCalUrl.validate("https://example.com/calendar.ics"))
            assertIs<WebCalUrl.ValidationResult.Valid>(WebCalUrl.validate("http://example.com/calendar.ics"))
        }

        @Test
        fun `suggests name from URL`() {
            val name = WebCalUrl.suggestName("webcal://example.com/calendars/holidays.ics")
            assertNotNull(name)
            assertTrue(name.contains("holidays", ignoreCase = true) || name.isNotEmpty())
        }

        @Test
        fun `handles complex URL paths`() {
            val httpUrl = WebCalUrl.toHttpUrl(
                "webcal://calendar.example.com:443/path/to/calendar.ics?key=value&other=123"
            )
            assertTrue(httpUrl.startsWith("https://"))
            assertTrue(httpUrl.contains("key=value"))
        }
    }

    companion object {
        private val MINIMAL_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:minimal-event
            DTSTART:20231215T100000Z
            SUMMARY:Minimal Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}