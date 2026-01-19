package org.onekash.icaldav.integration

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventCreateResult
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.discovery.CalDavDiscovery
import org.onekash.icaldav.model.Calendar
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.quirks.ICloudQuirks
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integration tests against real iCloud CalDAV server.
 *
 * iCloud CalDAV has unique characteristics:
 * - Uses non-prefixed XML namespaces
 * - Wraps calendar-data in CDATA blocks
 * - Redirects to regional partition servers (p*-caldav.icloud.com)
 * - Requires app-specific passwords
 * - Has eventual consistency - newly created events may not appear immediately
 *
 * Run with:
 * ```bash
 * ./run-icloud-tests.sh
 * ```
 *
 * Requires environment variables:
 *   ICLOUD_USERNAME - Apple ID email
 *   ICLOUD_APP_PASSWORD - App-specific password (generate at appleid.apple.com)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfEnvironmentVariable(named = "ICLOUD_USERNAME", matches = ".+")
@DisplayName("iCloud CalDAV Integration Tests")
class ICloudIntegrationTest {

    private val serverUrl = "https://caldav.icloud.com"
    private val username = System.getenv("ICLOUD_USERNAME") ?: ""
    private val password = System.getenv("ICLOUD_APP_PASSWORD") ?: ""

    private lateinit var calDavClient: CalDavClient
    private lateinit var webDavClient: WebDavClient
    private lateinit var discovery: CalDavDiscovery
    private lateinit var quirks: ICloudQuirks

    // Discovered URLs - populated during tests
    private var principalUrl: String? = null
    private var calendarHomeUrl: String? = null
    private var defaultCalendarUrl: String? = null

    // Track created events for cleanup
    private val createdEventUrls = mutableListOf<Pair<String, String?>>() // (url, etag)

    // Sync token for incremental sync tests
    private var lastSyncToken: String? = null

    private val testRunId = UUID.randomUUID().toString().take(8)

    @BeforeAll
    fun setup() {
        println("=== iCloud Integration Test Setup ===")
        println("Server: $serverUrl")
        println("User: ${username.take(5)}***")
        println("Test Run ID: $testRunId")

        Assumptions.assumeTrue(
            username.isNotBlank() && password.isNotBlank(),
            "iCloud credentials not available. Set ICLOUD_USERNAME and ICLOUD_APP_PASSWORD environment variables."
        )

        calDavClient = CalDavClient.withBasicAuth(username, password)

        val auth = DavAuth.Basic(username, password)
        val httpClient = WebDavClient.withAuth(auth)
        webDavClient = WebDavClient(httpClient, auth)
        discovery = CalDavDiscovery(webDavClient)

        quirks = ICloudQuirks()
    }

    @AfterAll
    fun cleanup() {
        println("=== Cleaning up ${createdEventUrls.size} test events ===")
        createdEventUrls.forEach { (url, etag) ->
            try {
                calDavClient.deleteEvent(url, etag)
                println("  Deleted: $url")
            } catch (e: Exception) {
                println("  Cleanup failed for $url: ${e.message}")
            }
        }
    }

    // ======================== Helper Methods ========================

    private fun generateUid(testName: String): String {
        return "$testRunId-$testName@icaldav.test"
    }

    private fun formatICalTimestamp(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        return formatter.format(instant.atZone(ZoneOffset.UTC))
    }

    private fun trackEvent(url: String, etag: String?) {
        createdEventUrls.add(Pair(url, etag))
    }

    private fun createAndTrackEvent(uid: String, icalData: String): EventCreateResult {
        assertNotNull(defaultCalendarUrl, "Calendar URL required")

        val result = calDavClient.createEventRaw(
            calendarUrl = defaultCalendarUrl!!,
            uid = uid,
            icalData = icalData
        )

        assertTrue(result is DavResult.Success, "Should create event: $result")
        @Suppress("UNCHECKED_CAST")
        val createResult = (result as DavResult.Success<EventCreateResult>).value

        trackEvent(createResult.href, createResult.etag)
        return createResult
    }

    // ======================== Discovery Tests ========================

    @Test
    @Order(1)
    @DisplayName("1. Discover principal URL")
    fun `discover principal URL`() {
        val result = discovery.discoverPrincipal(serverUrl)

        println("Principal discovery result: $result")
        assertTrue(result is DavResult.Success, "Should discover principal: $result")

        principalUrl = (result as DavResult.Success<String>).value
        println("Principal URL: $principalUrl")
        assertNotNull(principalUrl)
        assertTrue(principalUrl!!.isNotBlank(), "Principal URL should not be blank")
    }

    @Test
    @Order(2)
    @DisplayName("2. Discover calendar home URL")
    fun `discover calendar home URL`() {
        Assumptions.assumeTrue(principalUrl != null, "Principal URL required")

        // iCloud returns full URLs, but let's handle both cases
        val fullPrincipalUrl = if (principalUrl!!.startsWith("http")) principalUrl!!
            else "$serverUrl$principalUrl"

        val result = discovery.discoverCalendarHome(fullPrincipalUrl)

        println("Calendar home discovery result: $result")
        assertTrue(result is DavResult.Success, "Should discover calendar home: $result")

        calendarHomeUrl = (result as DavResult.Success<String>).value
        println("Calendar Home URL: $calendarHomeUrl")
        assertNotNull(calendarHomeUrl)
        assertTrue(calendarHomeUrl!!.isNotBlank(), "Calendar home URL should not be blank")
    }

    @Test
    @Order(3)
    @DisplayName("3. List calendars")
    fun `list calendars`() {
        Assumptions.assumeTrue(calendarHomeUrl != null, "Calendar home URL required")

        val fullHomeUrl = if (calendarHomeUrl!!.startsWith("http")) calendarHomeUrl!!
            else "$serverUrl$calendarHomeUrl"

        val result = discovery.listCalendars(fullHomeUrl)

        println("List calendars result: $result")
        assertTrue(result is DavResult.Success, "Should list calendars: $result")

        @Suppress("UNCHECKED_CAST")
        val calendars = (result as DavResult.Success<List<Calendar>>).value
        println("\nFound ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}")
            println("    URL: ${cal.href}")
            println("    CTag: ${cal.ctag}")
        }

        assertTrue(calendars.isNotEmpty(), "Should have at least one calendar")

        // Use first non-inbox/outbox calendar as default
        val calendar = calendars.firstOrNull { cal ->
            !cal.href.contains("inbox", ignoreCase = true) &&
            !cal.href.contains("outbox", ignoreCase = true)
        }
        assertNotNull(calendar, "Should find a writable calendar")
        defaultCalendarUrl = calendar!!.href
        println("\nDefault calendar: ${calendar.displayName} at $defaultCalendarUrl")
    }

    // ======================== Change Detection Tests ========================

    @Test
    @Order(10)
    @DisplayName("10. Get CTag for calendar")
    fun `get ctag for calendar`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.getCtag(defaultCalendarUrl!!)

        println("CTag result: $result")
        assertTrue(result is DavResult.Success, "Should get ctag: $result")

        val ctag = (result as DavResult.Success<String?>).value
        println("CTag: $ctag")
        assertNotNull(ctag)
        assertTrue(ctag!!.isNotBlank(), "CTag should not be blank")
    }

    @Test
    @Order(11)
    @DisplayName("11. Get sync token for calendar")
    fun `get sync token for calendar`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.getSyncToken(defaultCalendarUrl!!)

        println("Sync token result: $result")
        when (result) {
            is DavResult.Success -> {
                lastSyncToken = result.value
                println("Sync token: $lastSyncToken")
            }
            else -> {
                println("Sync token not available (server may not support it)")
            }
        }
    }

    // ======================== Basic CRUD Tests ========================

    @Test
    @Order(20)
    @DisplayName("20. Create simple event")
    fun `create simple event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("simple")
        val now = Instant.now()
        val startTime = Instant.now().plus(7, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:iCloud Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created event: ${created.href}")
        assertNotNull(created.etag, "Should have etag")
    }

    @Test
    @Order(21)
    @DisplayName("21. Fetch events in time range")
    fun `fetch events in time range`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val now = Instant.now()
        val start = now.minus(30, ChronoUnit.DAYS)
        val end = now.plus(365, ChronoUnit.DAYS)

        val result = calDavClient.fetchEvents(defaultCalendarUrl!!, start, end)

        println("Fetch events result: $result")
        assertTrue(result is DavResult.Success, "Should fetch events: $result")

        @Suppress("UNCHECKED_CAST")
        val events = (result as DavResult.Success<List<EventWithMetadata>>).value
        println("Found ${events.size} events in range")
        events.take(5).forEach { event ->
            println("  - ${event.href}")
            println("    etag: ${event.etag}")
        }
    }

    @Test
    @Order(22)
    @DisplayName("22. Get single event")
    fun `get single event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        // First create an event
        val uid = generateUid("get-single")
        val now = Instant.now()
        val startTime = Instant.now().plus(14, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Get Single Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)

        // Now fetch it
        val result = calDavClient.getEvent(created.href)

        println("Get event result: $result")
        assertTrue(result is DavResult.Success, "Should get event: $result")

        @Suppress("UNCHECKED_CAST")
        val event = (result as DavResult.Success<EventWithMetadata>).value
        assertNotNull(event.rawIcal, "Should have raw iCal data")
        assertTrue(event.rawIcal!!.contains("BEGIN:VCALENDAR"), "Should be valid iCal")
    }

    @Test
    @Order(23)
    @DisplayName("23. Update event")
    fun `update event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        // First create an event
        val uid = generateUid("update")
        val now = Instant.now()
        val startTime = Instant.now().plus(21, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Original Title
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created event for update: ${created.href}")

        // Update it
        val updatedIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Updated Title
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val updateResult = calDavClient.updateEventRaw(created.href, updatedIcal, created.etag)

        println("Update result: $updateResult")
        assertTrue(updateResult is DavResult.Success, "Should update event: $updateResult")

        @Suppress("UNCHECKED_CAST")
        val newEtag = (updateResult as DavResult.Success<String?>).value
        // Update tracked etag
        createdEventUrls.removeIf { it.first == created.href }
        createdEventUrls.add(Pair(created.href, newEtag))
    }

    @Test
    @Order(24)
    @DisplayName("24. Delete event")
    fun `delete event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        // First create an event
        val uid = generateUid("delete")
        val now = Instant.now()
        val startTime = Instant.now().plus(28, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Event to Delete
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created event for delete: ${created.href}")

        // Delete it
        val deleteResult = calDavClient.deleteEvent(created.href, created.etag)

        println("Delete result: $deleteResult")
        assertTrue(deleteResult is DavResult.Success, "Should delete event: $deleteResult")

        // Remove from tracking since we already deleted it
        createdEventUrls.removeIf { it.first == created.href }
    }

    // ======================== All-Day Events ========================

    @Test
    @Order(30)
    @DisplayName("30. Create all-day event")
    fun `create all-day event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("all-day")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20270315
            DTEND;VALUE=DATE:20270316
            SUMMARY:All-Day Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created all-day event: ${created.href}")
    }

    // ======================== Recurring Events ========================

    @Test
    @Order(40)
    @DisplayName("40. Create recurring event")
    fun `create recurring event`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("recurring")
        val now = Instant.now()
        val startTime = Instant.now().plus(35, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Recurring Event
            RRULE:FREQ=WEEKLY;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created recurring event: ${created.href}")
    }

    // ======================== Sync Tests ========================

    @Test
    @Order(80)
    @DisplayName("80. Sync collection")
    fun `sync collection`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")
        Assumptions.assumeTrue(lastSyncToken != null, "Sync token required")

        val result = calDavClient.syncCollection(defaultCalendarUrl!!, lastSyncToken!!)

        println("Sync collection result: $result")
        when (result) {
            is DavResult.Success -> {
                val syncResult = result.value
                println("Sync report:")
                println("  New sync token: ${syncResult.newSyncToken.take(50)}...")
                println("  Added: ${syncResult.added.size}")
                println("  Deleted: ${syncResult.deleted.size}")
            }
            is DavResult.HttpError -> {
                if (result.code == 403 || result.code == 410) {
                    println("Sync token expired/invalid (expected behavior)")
                } else {
                    fail("Sync collection failed: $result")
                }
            }
            else -> {
                println("Sync collection error (may be expected): $result")
            }
        }
    }

    // ======================== Full Discovery Workflow ========================

    @Test
    @Order(100)
    @DisplayName("100. Full discovery and fetch workflow")
    fun `full discovery and fetch workflow`() {
        println("\n=== Starting Full Discovery Workflow ===\n")

        // Step 1: Discover principal
        println("Step 1: Discovering principal...")
        val principalResult = discovery.discoverPrincipal(serverUrl)
        assertTrue(principalResult is DavResult.Success, "Principal discovery should succeed")
        val principal = (principalResult as DavResult.Success<String>).value
        println("Principal: $principal\n")

        // Step 2: Discover calendar home
        println("Step 2: Discovering calendar home...")
        val fullPrincipal = if (principal.startsWith("http")) principal else "$serverUrl$principal"
        val homeResult = discovery.discoverCalendarHome(fullPrincipal)
        assertTrue(homeResult is DavResult.Success, "Calendar home discovery should succeed")
        val home = (homeResult as DavResult.Success<String>).value
        println("Calendar home: $home\n")

        // Step 3: List calendars
        println("Step 3: Listing calendars...")
        val fullHome = if (home.startsWith("http")) home else "$serverUrl$home"
        val calendarsResult = discovery.listCalendars(fullHome)
        assertTrue(calendarsResult is DavResult.Success, "Calendar listing should succeed")
        @Suppress("UNCHECKED_CAST")
        val calendars = (calendarsResult as DavResult.Success<List<Calendar>>).value
        println("Found ${calendars.size} calendars\n")

        // Step 4: For each calendar, get ctag and count events
        var totalEvents = 0
        for (cal in calendars) {
            if (cal.href.contains("inbox", ignoreCase = true) ||
                cal.href.contains("outbox", ignoreCase = true)) {
                println("Skipping ${cal.displayName} (inbox/outbox)")
                continue
            }

            println("Processing: ${cal.displayName}")

            // Get ctag
            val ctagResult = calDavClient.getCtag(cal.href)
            if (ctagResult is DavResult.Success) {
                println("  CTag: ${ctagResult.value}")
            }

            // Fetch events
            val now = Instant.now()
            val eventsResult = calDavClient.fetchEvents(
                cal.href,
                now.minus(90, ChronoUnit.DAYS),
                now.plus(365, ChronoUnit.DAYS)
            )

            if (eventsResult is DavResult.Success) {
                @Suppress("UNCHECKED_CAST")
                val events = (eventsResult as DavResult.Success<List<EventWithMetadata>>).value
                println("  Events: ${events.size}")
                totalEvents += events.size
            } else {
                println("  Error: $eventsResult")
            }
            println()
        }

        println("=== Workflow Complete ===")
        println("Total calendars: ${calendars.size}")
        println("Total events: $totalEvents")
    }

    // ======================== iCloud-Specific Tests ========================

    @Test
    @Order(110)
    @DisplayName("110. Event with custom X-properties preserved in rawIcal")
    fun `event with custom properties preserved in rawIcal`() {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("x-props")
        val now = Instant.now()
        val startTime = Instant.now().plus(42, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Event with X-Properties
            X-ICALDAV-TEST-PROP:test-value-12345
            X-CUSTOM-METADATA:{"key":"value"}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created event with X-properties: ${created.href}")

        // Allow for iCloud eventual consistency
        Thread.sleep(2000)

        // Fetch and verify rawIcal preserves X-properties
        val getResult = calDavClient.getEvent(created.href)
        assertTrue(getResult is DavResult.Success, "Should get event: $getResult")

        @Suppress("UNCHECKED_CAST")
        val event = (getResult as DavResult.Success<EventWithMetadata>).value
        assertNotNull(event.rawIcal, "rawIcal should be populated")
        assertTrue(
            event.rawIcal!!.contains("X-ICALDAV-TEST-PROP"),
            "rawIcal should preserve X-ICALDAV-TEST-PROP"
        )
        println("X-properties preserved in rawIcal: verified")
    }
}
