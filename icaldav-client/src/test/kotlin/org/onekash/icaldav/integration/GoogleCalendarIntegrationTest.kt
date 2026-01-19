package org.onekash.icaldav.integration

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.SyncResult
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.DavResult
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for Google Calendar CalDAV API.
 *
 * Google CalDAV has specific requirements and limitations:
 * - OAuth 2.0 authentication required (no basic auth)
 * - No MKCALENDAR support (can't create calendars programmatically)
 * - No VTODO/VJOURNAL support
 * - No LOCK/UNLOCK/COPY/MOVE support
 *
 * Environment variables required:
 * - GOOGLE_REFRESH_TOKEN: OAuth2 refresh token
 * - GOOGLE_CLIENT_ID: OAuth2 client ID
 * - GOOGLE_CLIENT_SECRET: OAuth2 client secret
 * - GOOGLE_CALENDAR_ID: Calendar ID (default: primary)
 *
 * To obtain OAuth tokens:
 * 1. Create OAuth2 credentials in Google Cloud Console
 * 2. Use OAuth Playground (https://developers.google.com/oauthplayground/)
 * 3. Authorize with scope: https://www.googleapis.com/auth/calendar
 * 4. Exchange authorization code for refresh token
 *
 * Run tests:
 *   GOOGLE_REFRESH_TOKEN=xxx GOOGLE_CLIENT_ID=xxx GOOGLE_CLIENT_SECRET=xxx ./gradlew :caldav-core:test --tests "*GoogleCalendarIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "GOOGLE_REFRESH_TOKEN", matches = ".+")
class GoogleCalendarIntegrationTest {

    companion object {
        private const val GOOGLE_CALDAV_BASE = "https://apidata.googleusercontent.com/caldav/v2"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        // Test UID prefix for cleanup
        private const val TEST_UID_PREFIX = "gcal-test-"
    }

    private lateinit var client: CalDavClient
    private lateinit var calendarUrl: String
    private lateinit var accessToken: String
    private var calendarId: String = "primary"

    private val createdEventUrls = mutableListOf<String>()
    private val createdEventUids = mutableListOf<String>()
    private val eventCounter = AtomicInteger(0)

    // Track if we can run tests
    private var canRunTests = false
    private var skipReason: String = ""

    @BeforeAll
    fun setup() {
        val refreshToken = System.getenv("GOOGLE_REFRESH_TOKEN")
        val clientId = System.getenv("GOOGLE_CLIENT_ID")
        val clientSecret = System.getenv("GOOGLE_CLIENT_SECRET")
        calendarId = System.getenv("GOOGLE_CALENDAR_ID") ?: "primary"

        if (refreshToken.isNullOrBlank() || clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            skipReason = "Google OAuth credentials not configured. Set GOOGLE_REFRESH_TOKEN, GOOGLE_CLIENT_ID, and GOOGLE_CLIENT_SECRET environment variables."
            canRunTests = false
            return
        }

        // Get access token from refresh token
        try {
            accessToken = refreshAccessToken(refreshToken, clientId, clientSecret)
            println("Successfully obtained Google access token")
        } catch (e: Exception) {
            skipReason = "Failed to obtain access token: ${e.message}"
            canRunTests = false
            return
        }

        // Build calendar URL
        calendarUrl = "$GOOGLE_CALDAV_BASE/$calendarId/events/"
        println("Google Calendar URL: $calendarUrl")

        // Create CalDAV client with bearer auth
        val auth = DavAuth.Bearer(accessToken)
        val httpClient = WebDavClient.withAuth(auth)
        client = CalDavClient(WebDavClient(httpClient, auth))

        canRunTests = true
        println("Google Calendar integration tests ready")
    }

    @AfterAll
    fun cleanup() {
        if (!canRunTests) return

        println("\n=== Cleaning up ${createdEventUrls.size} test events ===")
        createdEventUrls.forEach { url ->
            try {
                client.deleteEvent(url, null)
                println("  Deleted: $url")
            } catch (e: Exception) {
                println("  Failed to delete $url: ${e.message}")
            }
        }
    }

    private fun refreshAccessToken(refreshToken: String, clientId: String, clientSecret: String): String {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Token refresh failed: ${response.code} ${response.body?.string()}")
        }

        val responseBody = response.body?.string() ?: "{}"
        // Simple JSON parsing for access_token
        val tokenMatch = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(responseBody)
            ?: throw RuntimeException("access_token not found in response")
        return tokenMatch.groupValues[1]
    }

    private fun skipIfNotConfigured() {
        if (!canRunTests) {
            throw TestAbortedException(skipReason)
        }
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    private fun generateTestUid(): String {
        return "$TEST_UID_PREFIX${UUID.randomUUID()}"
    }

    private fun createTestEventIcal(
        uid: String = generateTestUid(),
        summary: String = "Google Calendar Test Event",
        startOffset: Long = 1,
        durationHours: Long = 1
    ): Pair<String, String> {
        val startTime = Instant.now().plus(startOffset, ChronoUnit.HOURS)
        val endTime = startTime.plus(durationHours, ChronoUnit.HOURS)
        val dtStart = dateFormatter.format(startTime)
        val dtEnd = dateFormatter.format(endTime)
        val dtstamp = dateFormatter.format(Instant.now())

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:$dtstamp
            DTSTART:$dtStart
            DTEND:$dtEnd
            SUMMARY:$summary
            DESCRIPTION:Integration test event
            LOCATION:Virtual
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        return Pair(uid, icalData)
    }

    // ============================================================
    // Basic CRUD Tests
    // ============================================================

    @Test
    @Order(1)
    fun `001 Create event on Google Calendar`() {
        skipIfNotConfigured()

        val (uid, icalData) = createTestEventIcal(
            summary = "iCalDAV Test Event ${eventCounter.incrementAndGet()}"
        )

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("Create result: $result")

        assertTrue(result is DavResult.Success, "Should create event successfully: $result")
        val (href, etag) = (result as DavResult.Success).value
        assertNotNull(href, "Should return event href")
        assertNotNull(etag, "Should return etag")

        createdEventUrls.add(href)
        createdEventUids.add(uid)
        println("Created event at: $href with etag: $etag")
    }

    @Test
    @Order(2)
    fun `002 Fetch created event`() {
        skipIfNotConfigured()

        if (createdEventUrls.isEmpty()) {
            throw TestAbortedException("No events created yet")
        }

        val eventUrl = createdEventUrls.first()
        val result = client.getEvent(eventUrl)

        assertTrue(result is DavResult.Success, "Should fetch event: $result")
        val eventWithMeta = (result as DavResult.Success).value
        assertNotNull(eventWithMeta.event, "Should have parsed event")
        assertNotNull(eventWithMeta.event.uid, "Should have UID")
        println("Fetched event: ${eventWithMeta.event.summary} with etag: ${eventWithMeta.etag}")
    }

    @Test
    @Order(3)
    fun `003 Update event on Google Calendar`() {
        skipIfNotConfigured()

        if (createdEventUrls.isEmpty() || createdEventUids.isEmpty()) {
            throw TestAbortedException("No events created yet")
        }

        val eventUrl = createdEventUrls.first()
        val uid = createdEventUids.first()

        // First fetch to get current etag
        val fetchResult = client.getEvent(eventUrl)
        assertTrue(fetchResult is DavResult.Success, "Should fetch event")
        val currentEtag = (fetchResult as DavResult.Success).value.etag

        // Create updated event with same UID
        val (_, updatedIcal) = createTestEventIcal(
            uid = uid,
            summary = "Updated via iCalDAV ${System.currentTimeMillis()}"
        )

        val updateResult = client.updateEventRaw(eventUrl, updatedIcal, currentEtag)
        println("Update result: $updateResult")

        assertTrue(updateResult is DavResult.Success, "Should update event: $updateResult")
        val newEtag = (updateResult as DavResult.Success).value
        assertNotNull(newEtag, "Should return new etag")
        assertNotEquals(currentEtag, newEtag, "ETag should change after update")
        println("Updated event, new etag: $newEtag")
    }

    @Test
    @Order(4)
    fun `004 Delete event from Google Calendar`() {
        skipIfNotConfigured()

        // Create a new event specifically for deletion
        val (uid, icalData) = createTestEventIcal(
            summary = "Event to delete ${eventCounter.incrementAndGet()}"
        )

        val createResult = client.createEventRaw(calendarUrl, uid, icalData)
        assertTrue(createResult is DavResult.Success, "Should create event: $createResult")
        val (href, etag) = (createResult as DavResult.Success).value
        println("Created event for deletion: $href")

        // Delete it
        val deleteResult = client.deleteEvent(href, etag)
        println("Delete result: $deleteResult")

        assertTrue(deleteResult is DavResult.Success, "Should delete event: $deleteResult")

        // Verify it's gone
        val fetchResult = client.getEvent(href)
        assertTrue(
            fetchResult is DavResult.HttpError && (fetchResult as DavResult.HttpError).code in listOf(404, 410),
            "Event should be gone after delete"
        )
        println("Verified event deleted (404/410)")
    }

    // ============================================================
    // Sync Tests
    // ============================================================

    @Test
    @Order(10)
    fun `010 Get ctag from Google Calendar`() {
        skipIfNotConfigured()

        val result = client.getCtag(calendarUrl)
        println("Get ctag result: $result")

        // Google may or may not support ctag - accept either outcome
        when (result) {
            is DavResult.Success -> {
                assertNotNull(result.value, "ctag should not be null")
                println("Calendar ctag: ${result.value}")
            }
            is DavResult.HttpError -> {
                println("ctag not supported (HTTP ${result.code})")
            }
            else -> {
                println("ctag result: $result")
            }
        }
    }

    @Test
    @Order(11)
    fun `011 Get sync token from Google Calendar`() {
        skipIfNotConfigured()

        val result = client.getSyncToken(calendarUrl)
        println("Get sync token result: $result")

        when (result) {
            is DavResult.Success -> {
                assertNotNull(result.value, "sync token should not be null")
                println("Sync token: ${result.value}")
            }
            is DavResult.HttpError -> {
                println("Sync token not available (HTTP ${result.code})")
            }
            else -> {
                println("Sync token result: $result")
            }
        }
    }

    @Test
    @Order(12)
    fun `012 Fetch etags in time range`() {
        skipIfNotConfigured()

        // First create an event we know exists
        val (uid, icalData) = createTestEventIcal(
            summary = "Time range test ${eventCounter.incrementAndGet()}",
            startOffset = 24 // Tomorrow
        )

        val createResult = client.createEventRaw(calendarUrl, uid, icalData)
        assertTrue(createResult is DavResult.Success, "Should create test event: $createResult")
        val (href, _) = (createResult as DavResult.Success).value
        createdEventUrls.add(href)

        // Query for events in range (next 7 days)
        val start = Instant.now()
        val end = start.plus(7, ChronoUnit.DAYS)

        val result = client.fetchEtagsInRange(calendarUrl, start, end)
        println("Fetch etags in range result: $result")

        when (result) {
            is DavResult.Success -> {
                val etags = result.value
                println("Found ${etags.size} events in next 7 days")
                assertTrue(etags.isNotEmpty(), "Should find at least one event")
            }
            is DavResult.HttpError -> {
                println("fetchEtagsInRange returned HTTP ${result.code} (Google may not support)")
            }
            else -> {
                println("fetchEtagsInRange result: $result")
            }
        }
    }

    @Test
    @Order(13)
    fun `013 Sync collection with sync token`() {
        skipIfNotConfigured()

        // Get initial sync token
        val tokenResult = client.getSyncToken(calendarUrl)
        val syncToken = if (tokenResult is DavResult.Success) tokenResult.value else null

        if (syncToken == null) {
            println("Sync token not available, skipping sync-collection test")
            return
        }

        // Create a new event
        val (uid, icalData) = createTestEventIcal(
            summary = "Sync test ${eventCounter.incrementAndGet()}"
        )

        val createResult = client.createEventRaw(calendarUrl, uid, icalData)
        assertTrue(createResult is DavResult.Success, "Should create event: $createResult")
        val (href, _) = (createResult as DavResult.Success).value
        createdEventUrls.add(href)

        // Sync from previous token
        val syncResult = client.syncCollection(calendarUrl, syncToken)
        println("Sync result: $syncResult")

        when (syncResult) {
            is DavResult.Success -> {
                val report = syncResult.value
                println("Sync report: ${report.added.size} added, ${report.deleted.size} deleted")
                println("New sync token: ${report.newSyncToken}")
            }
            is DavResult.HttpError -> {
                println("Sync-collection returned HTTP ${syncResult.code}: ${syncResult.message}")
                // Google may return various errors for sync-collection
            }
            else -> {
                println("Sync result: $syncResult")
            }
        }
    }

    // ============================================================
    // Event Type Tests
    // ============================================================

    @Test
    @Order(20)
    fun `020 All-day event`() {
        skipIfNotConfigured()

        val uid = generateTestUid()
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T000000Z
            DTSTART;VALUE=DATE:20240315
            DTEND;VALUE=DATE:20240316
            SUMMARY:All-day Event Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("All-day event result: $result")

        assertTrue(result is DavResult.Success, "Should create all-day event: $result")
        val (href, _) = (result as DavResult.Success).value
        createdEventUrls.add(href)
        println("Created all-day event: $href")
    }

    @Test
    @Order(21)
    fun `021 Recurring event`() {
        skipIfNotConfigured()

        val uid = generateTestUid()
        val startTime = Instant.now().plus(48, ChronoUnit.HOURS)
        val dtStart = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(startTime)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T000000Z
            DTSTART:$dtStart
            DURATION:PT1H
            SUMMARY:Weekly Meeting Test
            RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("Recurring event result: $result")

        assertTrue(result is DavResult.Success, "Should create recurring event: $result")
        val (href, _) = (result as DavResult.Success).value
        createdEventUrls.add(href)
        println("Created recurring event: $href")
    }

    @Test
    @Order(22)
    fun `022 Event with attendees`() {
        skipIfNotConfigured()

        val uid = generateTestUid()
        val startTime = Instant.now().plus(72, ChronoUnit.HOURS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val dtStart = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(startTime)
        val dtEnd = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(endTime)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T000000Z
            DTSTART:$dtStart
            DTEND:$dtEnd
            SUMMARY:Meeting with Attendees Test
            ORGANIZER:mailto:organizer@example.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:attendee1@example.com
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:attendee2@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("Event with attendees result: $result")

        // Google may modify attendee handling
        when (result) {
            is DavResult.Success -> {
                val (href, _) = result.value
                createdEventUrls.add(href)
                println("Created event with attendees: $href")
            }
            is DavResult.HttpError -> {
                println("Attendee event returned HTTP ${result.code} (Google may restrict attendees)")
            }
            else -> {
                println("Attendee event result: $result")
            }
        }
    }

    @Test
    @Order(23)
    fun `023 Event with alarm`() {
        skipIfNotConfigured()

        val uid = generateTestUid()
        val startTime = Instant.now().plus(96, ChronoUnit.HOURS)
        val dtStart = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(startTime)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T000000Z
            DTSTART:$dtStart
            DURATION:PT1H
            SUMMARY:Event with Alarm Test
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("Event with alarm result: $result")

        assertTrue(result is DavResult.Success, "Should create event with alarm: $result")
        val (href, _) = (result as DavResult.Success).value
        createdEventUrls.add(href)
        println("Created event with alarm: $href")
    }

    // ============================================================
    // Error Handling Tests
    // ============================================================

    @Test
    @Order(30)
    fun `030 Get non-existent event returns 404`() {
        skipIfNotConfigured()

        val fakeUrl = "${calendarUrl}non-existent-event-${UUID.randomUUID()}.ics"
        val result = client.getEvent(fakeUrl)

        println("Get non-existent event: $result")

        assertTrue(
            result is DavResult.HttpError && (result as DavResult.HttpError).code == 404,
            "Should return 404 for non-existent event: $result"
        )
    }

    @Test
    @Order(31)
    fun `031 Update with wrong etag fails`() {
        skipIfNotConfigured()

        // Create event
        val (uid, icalData) = createTestEventIcal(summary = "ETag test ${eventCounter.incrementAndGet()}")
        val createResult = client.createEventRaw(calendarUrl, uid, icalData)
        assertTrue(createResult is DavResult.Success, "Should create event: $createResult")
        val (href, _) = (createResult as DavResult.Success).value
        createdEventUrls.add(href)

        // Try to update with wrong etag
        val wrongEtag = "\"wrong-etag-12345\""
        val (_, updatedIcal) = createTestEventIcal(uid = uid, summary = "Should fail")
        val updateResult = client.updateEventRaw(href, updatedIcal, wrongEtag)

        println("Update with wrong etag: $updateResult")

        assertTrue(
            updateResult is DavResult.HttpError && (updateResult as DavResult.HttpError).code == 412,
            "Should return 412 for etag conflict: $updateResult"
        )
    }

    @Test
    @Order(32)
    fun `032 Invalid authentication fails`() {
        // Create client with invalid token
        val invalidAuth = DavAuth.Bearer("invalid-token")
        val httpClient = WebDavClient.withAuth(invalidAuth)
        val invalidClient = CalDavClient(WebDavClient(httpClient, invalidAuth))

        // Use hardcoded URL since this test doesn't depend on valid credentials
        val testCalendarUrl = "$GOOGLE_CALDAV_BASE/primary/events/"
        val result = invalidClient.getCtag(testCalendarUrl)
        println("Invalid auth result: $result")

        assertTrue(
            result is DavResult.HttpError && (result as DavResult.HttpError).code == 401,
            "Should return 401 for invalid token: $result"
        )
    }

    // ============================================================
    // Google-specific limitation tests
    // ============================================================

    @Test
    @Order(40)
    fun `040 MKCALENDAR is not supported`() {
        skipIfNotConfigured()

        // Google doesn't support MKCALENDAR
        // This test documents the limitation
        println("Note: Google Calendar does not support MKCALENDAR")
        println("Calendars must be created via the Google Calendar web UI or API")
    }

    @Test
    @Order(41)
    fun `041 VTODO is not supported`() {
        skipIfNotConfigured()

        val uid = generateTestUid()
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV//Test//EN
            BEGIN:VTODO
            UID:$uid
            DTSTAMP:20240101T000000Z
            SUMMARY:Test Task
            STATUS:NEEDS-ACTION
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val result = client.createEventRaw(calendarUrl, uid, icalData)
        println("VTODO result: $result")

        // Google should reject VTODO
        when (result) {
            is DavResult.HttpError -> {
                println("VTODO correctly rejected with HTTP ${result.code}")
            }
            is DavResult.Success -> {
                // If it somehow worked, clean up
                val (href, _) = result.value
                createdEventUrls.add(href)
                println("Unexpected: VTODO was accepted")
            }
            else -> {
                println("VTODO result: $result")
            }
        }
    }

    // ============================================================
    // Test Summary
    // ============================================================

    @Test
    @Order(999)
    fun `999 Test summary`() {
        if (!canRunTests) {
            println("\n========================================")
            println("GOOGLE CALENDAR TESTS SKIPPED")
            println("Reason: $skipReason")
            println("========================================")
            return
        }

        println("\n========================================")
        println("GOOGLE CALENDAR INTEGRATION TESTS")
        println("========================================")
        println("Calendar URL: $calendarUrl")
        println("Events created: ${createdEventUrls.size}")
        println("========================================")
    }
}
