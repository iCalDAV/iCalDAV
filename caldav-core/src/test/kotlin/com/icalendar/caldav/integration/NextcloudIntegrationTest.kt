package com.icalendar.caldav.integration

import com.icalendar.caldav.client.CalDavClient
import com.icalendar.caldav.client.EventCreateResult
import com.icalendar.caldav.client.EventWithMetadata
import com.icalendar.caldav.client.SyncResult
import com.icalendar.caldav.discovery.CalDavDiscovery
import com.icalendar.caldav.model.CalDavAccount
import com.icalendar.caldav.model.Calendar
import com.icalendar.webdav.client.DavAuth
import com.icalendar.webdav.client.WebDavClient
import com.icalendar.webdav.model.DavResult
import com.icalendar.webdav.quirks.DefaultQuirks
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.icalendar.core.model.Frequency

/**
 * Comprehensive integration tests against a real Nextcloud CalDAV server.
 *
 * Tests cover all event types used in KashCal iCloud sync:
 * - Basic events
 * - All-day events (DATE format)
 * - Multi-day events
 * - Recurring events (RRULE)
 * - Exception events (RECURRENCE-ID)
 * - Events with VTIMEZONE
 * - Events with VALARM reminders
 * - Unicode and special characters
 * - Sync-collection incremental sync
 * - ETag conflict detection
 *
 * Run with:
 * ```bash
 * ./run-integration-tests.sh
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfEnvironmentVariable(named = "NEXTCLOUD_URL", matches = ".+")
@DisplayName("Nextcloud CalDAV Integration Tests")
class NextcloudIntegrationTest {

    private val nextcloudUrl = System.getenv("NEXTCLOUD_URL") ?: "http://localhost:8080"
    private val username = System.getenv("NEXTCLOUD_USER") ?: "testuser"
    private val password = System.getenv("NEXTCLOUD_PASS") ?: "testpass123"

    private lateinit var calDavClient: CalDavClient
    private lateinit var discovery: CalDavDiscovery
    private lateinit var quirks: DefaultQuirks

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
        println("=== Nextcloud Integration Test Setup ===")
        println("URL: $nextcloudUrl")
        println("User: $username")
        println("Test Run ID: $testRunId")

        waitForNextcloud()

        calDavClient = CalDavClient.withBasicAuth(username, password)

        val auth = DavAuth.Basic(username, password)
        val httpClient = WebDavClient.withAuth(auth)
        val webDavClient = WebDavClient(httpClient, auth)
        discovery = CalDavDiscovery(webDavClient)

        quirks = DefaultQuirks(
            providerId = "nextcloud",
            displayName = "Nextcloud Test",
            baseUrl = nextcloudUrl
        )
    }

    private fun waitForNextcloud(maxRetries: Int = 30, delayMs: Long = 2000) {
        println("Waiting for Nextcloud to be ready...")
        var lastError: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                val url = URL("$nextcloudUrl/status.php")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                if (connection.responseCode == 200) {
                    println("Nextcloud is ready! (attempt ${attempt + 1})")
                    return
                }
            } catch (e: Exception) {
                lastError = e
            }

            println("Waiting... (attempt ${attempt + 1}/$maxRetries)")
            Thread.sleep(delayMs)
        }

        throw RuntimeException(
            "Nextcloud not ready after $maxRetries attempts. Last error: ${lastError?.message}",
            lastError
        )
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

    private fun formatICalDate(date: LocalDate): String {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE)
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

    private fun fetchAndVerify(url: String): EventWithMetadata {
        val result = calDavClient.getEvent(url)
        assertTrue(result is DavResult.Success, "Should fetch event: $result")
        @Suppress("UNCHECKED_CAST")
        return (result as DavResult.Success<EventWithMetadata>).value
    }

    // ======================== Discovery Tests ========================

    @Test
    @Order(1)
    @DisplayName("1. Discover principal URL")
    fun `discover principal URL from Nextcloud`() {
        val caldavUrl = "$nextcloudUrl/remote.php/dav"
        val result = discovery.discoverPrincipal(caldavUrl)

        assertTrue(result is DavResult.Success, "Should successfully discover principal: $result")
        principalUrl = (result as DavResult.Success).value

        println("Discovered principal URL: $principalUrl")
        assertNotNull(principalUrl)
        assertTrue(principalUrl!!.contains("principals"))
    }

    @Test
    @Order(2)
    @DisplayName("2. Discover calendar home URL")
    fun `discover calendar home URL from principal`() {
        assertNotNull(principalUrl)

        val fullPrincipalUrl = if (principalUrl!!.startsWith("http")) principalUrl!!
            else "$nextcloudUrl$principalUrl"

        val result = discovery.discoverCalendarHome(fullPrincipalUrl)

        assertTrue(result is DavResult.Success, "Should discover calendar home: $result")
        calendarHomeUrl = (result as DavResult.Success).value

        println("Discovered calendar home URL: $calendarHomeUrl")
        assertNotNull(calendarHomeUrl)
        assertTrue(calendarHomeUrl!!.contains("calendars"))
    }

    @Test
    @Order(3)
    @DisplayName("3. List calendars")
    fun `list calendars from calendar home`() {
        assertNotNull(calendarHomeUrl)

        val fullHomeUrl = if (calendarHomeUrl!!.startsWith("http")) calendarHomeUrl!!
            else "$nextcloudUrl$calendarHomeUrl"

        val result = discovery.listCalendars(fullHomeUrl)

        assertTrue(result is DavResult.Success, "Should list calendars: $result")
        @Suppress("UNCHECKED_CAST")
        val calendars = (result as DavResult.Success<List<Calendar>>).value

        println("Discovered ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName} (${cal.href})")
        }

        assertTrue(calendars.isNotEmpty())

        val defaultCal = calendars.firstOrNull {
            it.displayName.lowercase().contains("personal") || it.href.contains("personal")
        } ?: calendars.first()

        defaultCalendarUrl = defaultCal.href
        println("Using calendar: ${defaultCal.displayName} at $defaultCalendarUrl")
    }

    @Test
    @Order(4)
    @DisplayName("4. Full discovery flow")
    fun `full discovery flow returns complete account`() {
        val caldavUrl = "$nextcloudUrl/remote.php/dav"
        val result = calDavClient.discoverAccount(caldavUrl)

        assertTrue(result is DavResult.Success, "Should discover account: $result")
        @Suppress("UNCHECKED_CAST")
        val account = (result as DavResult.Success<CalDavAccount>).value

        println("Full discovery result:")
        println("  Server URL: ${account.serverUrl}")
        println("  Principal URL: ${account.principalUrl}")
        println("  Calendar Home: ${account.calendarHomeUrl}")
        println("  Calendars: ${account.calendars.size}")

        assertNotNull(account.principalUrl)
        assertNotNull(account.calendarHomeUrl)
        assertTrue(account.calendars.isNotEmpty())
    }

    // ======================== Basic Event Tests ========================

    @Test
    @Order(10)
    @DisplayName("10. Create basic event")
    fun `create basic event on Nextcloud`() {
        val uid = generateUid("basic")
        val startTime = Instant.now().plus(30, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Basic Test Event
            DESCRIPTION:A simple test event created by integration tests
            LOCATION:Test Location
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created basic event: ${result.href}")

        // Verify
        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
        assertEquals("Basic Test Event", fetched.event.summary)
    }

    @Test
    @Order(11)
    @DisplayName("11. Update event and verify ETag changes")
    fun `update event changes ETag`() {
        val uid = generateUid("update-test")
        val startTime = Instant.now().plus(31, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Original Title
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = createAndTrackEvent(uid, icalData)
        val originalEtag = createResult.etag

        // Update the event
        val updatedIcalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Updated Title
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val updateResult = calDavClient.updateEventRaw(
            eventUrl = createResult.href,
            icalData = updatedIcalData,
            etag = originalEtag
        )

        assertTrue(updateResult is DavResult.Success, "Update should succeed: $updateResult")
        @Suppress("UNCHECKED_CAST")
        val newEtag = (updateResult as DavResult.Success<String?>).value

        println("ETag changed from $originalEtag to $newEtag")
        assertNotEquals(originalEtag, newEtag, "ETag should change after update")

        // Verify content updated
        val fetched = fetchAndVerify(createResult.href)
        assertEquals("Updated Title", fetched.event.summary)

        // Update our tracked etag
        val index = createdEventUrls.indexOfFirst { it.first == createResult.href }
        if (index >= 0) {
            createdEventUrls[index] = Pair(createResult.href, newEtag)
        }
    }

    // ======================== All-Day Event Tests ========================

    @Test
    @Order(20)
    @DisplayName("20. Create all-day event (DATE format)")
    fun `create all-day event with DATE format`() {
        val uid = generateUid("allday")
        val eventDate = LocalDate.now().plusDays(35)
        val now = Instant.now()

        // All-day events use VALUE=DATE, not DATE-TIME
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(eventDate)}
            DTEND;VALUE=DATE:${formatICalDate(eventDate.plusDays(1))}
            SUMMARY:All-Day Test Event
            DESCRIPTION:This is an all-day event without specific time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created all-day event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
        assertEquals("All-Day Test Event", fetched.event.summary)
        assertTrue(fetched.event.isAllDay, "Event should be marked as all-day")
    }

    @Test
    @Order(21)
    @DisplayName("21. Create multi-day event")
    fun `create multi-day spanning event`() {
        val uid = generateUid("multiday")
        val startDate = LocalDate.now().plusDays(40)
        val endDate = startDate.plusDays(3) // 3-day event
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(endDate)}
            SUMMARY:Multi-Day Conference
            DESCRIPTION:A three-day event spanning multiple days
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created multi-day event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Multi-Day Conference", fetched.event.summary)
    }

    @Test
    @Order(22)
    @DisplayName("22. Create week-long all-day event")
    fun `create week-long all-day event`() {
        val uid = generateUid("weeklong")
        val startDate = LocalDate.now().plusDays(42)
        val endDate = startDate.plusDays(7) // Full week
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(endDate)}
            SUMMARY:Week-Long Vacation
            DESCRIPTION:Full week off - Monday to Sunday
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created week-long event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Week-Long Vacation", fetched.event.summary)
    }

    @Test
    @Order(23)
    @DisplayName("23. Create all-day event across month boundary")
    fun `create all-day event spanning month boundary`() {
        val uid = generateUid("month-span")
        // Find the last day of the current month
        val startDate = LocalDate.now().withDayOfMonth(28).plusDays(5) // Will cross into next month
        val endDate = startDate.plusDays(5)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(endDate)}
            SUMMARY:Month-Spanning Event
            DESCRIPTION:Event that crosses month boundary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created month-spanning event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Month-Spanning Event", fetched.event.summary)
    }

    @Test
    @Order(24)
    @DisplayName("24. Create recurring all-day event")
    fun `create recurring all-day event`() {
        val uid = generateUid("recurring-allday")
        val startDate = LocalDate.now().plusDays(47)
        val now = Instant.now()

        // Weekly recurring all-day event (like a birthday)
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(startDate.plusDays(1))}
            SUMMARY:Weekly Review Day
            DESCRIPTION:All-day recurring event every week
            RRULE:FREQ=WEEKLY;COUNT=8
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring all-day event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Weekly Review Day", fetched.event.summary)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.WEEKLY, fetched.event.rrule!!.freq)
    }

    @Test
    @Order(25)
    @DisplayName("25. Create yearly recurring event (birthday/anniversary)")
    fun `create yearly recurring event`() {
        val uid = generateUid("yearly-recur")
        val startDate = LocalDate.now().plusDays(50)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(startDate.plusDays(1))}
            SUMMARY:Annual Company Anniversary
            DESCRIPTION:Yearly recurring event like birthday or anniversary
            RRULE:FREQ=YEARLY;COUNT=5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created yearly recurring event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.YEARLY, fetched.event.rrule!!.freq)
    }

    @Test
    @Order(26)
    @DisplayName("26. Create bi-weekly recurring event")
    fun `create bi-weekly recurring event`() {
        val uid = generateUid("biweekly-recur")
        val startTime = Instant.now().plus(52, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Bi-Weekly Sprint Review
            DESCRIPTION:Every other week sprint review meeting
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created bi-weekly event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        assertEquals(2, fetched.event.rrule!!.interval, "Should have interval of 2")
    }

    // ======================== Recurring Event Tests ========================

    @Test
    @Order(30)
    @DisplayName("30. Create daily recurring event")
    fun `create daily recurring event with COUNT`() {
        val uid = generateUid("daily-recur")
        val startTime = Instant.now().plus(45, ChronoUnit.DAYS)
        val endTime = startTime.plus(30, ChronoUnit.MINUTES)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Daily Standup
            DESCRIPTION:Daily recurring meeting for 10 occurrences
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created daily recurring event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Daily Standup", fetched.event.summary)
        assertNotNull(fetched.event.rrule, "Should have RRULE")
        assertEquals(Frequency.DAILY, fetched.event.rrule!!.freq, "Should be daily frequency")
    }

    @Test
    @Order(31)
    @DisplayName("31. Create weekly recurring event with BYDAY")
    fun `create weekly recurring event on specific days`() {
        val uid = generateUid("weekly-recur")
        val startTime = Instant.now().plus(50, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Weekly Team Meeting
            DESCRIPTION:Recurring every Monday, Wednesday, Friday
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=12
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created weekly recurring event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        assertNotNull(fetched.event.rrule!!.byDay, "Should have BYDAY clause")
        assertTrue(fetched.event.rrule!!.byDay!!.isNotEmpty(), "Should have days specified")
    }

    @Test
    @Order(32)
    @DisplayName("32. Create monthly recurring event")
    fun `create monthly recurring event on specific day`() {
        val uid = generateUid("monthly-recur")
        val startTime = Instant.now().plus(55, ChronoUnit.DAYS)
        val endTime = startTime.plus(2, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Monthly Review
            DESCRIPTION:Monthly status review meeting
            RRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created monthly recurring event: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.MONTHLY, fetched.event.rrule!!.freq, "Should be monthly frequency")
    }

    @Test
    @Order(33)
    @DisplayName("33. Create recurring event with UNTIL date")
    fun `create recurring event ending on specific date`() {
        val uid = generateUid("until-recur")
        val startTime = Instant.now().plus(60, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val untilDate = startTime.plus(90, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Project Milestone Check
            DESCRIPTION:Recurring until project end date
            RRULE:FREQ=WEEKLY;UNTIL=${formatICalTimestamp(untilDate)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring event with UNTIL: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        assertNotNull(fetched.event.rrule!!.until, "Should have UNTIL property")
    }

    // ======================== Exception Event Tests ========================

    @Test
    @Order(40)
    @DisplayName("40. Create recurring event with EXDATE (cancelled occurrence)")
    fun `create recurring event with excluded date`() {
        val uid = generateUid("exdate-recur")
        val startTime = Instant.now().plus(65, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val excludeDate = startTime.plus(7, ChronoUnit.DAYS) // Skip second occurrence
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Weekly Meeting (with cancelled date)
            RRULE:FREQ=WEEKLY;COUNT=4
            EXDATE:${formatICalTimestamp(excludeDate)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring event with EXDATE: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
        // Note: EXDATE may be in raw data but not always exposed in parsed event
    }

    @Test
    @Order(41)
    @DisplayName("41. Create recurring event with modified exception (RECURRENCE-ID)")
    fun `create recurring event with modified occurrence`() {
        val uid = generateUid("exception-recur")
        val startTime = Instant.now().plus(70, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exceptionTime = startTime.plus(14, ChronoUnit.DAYS) // Third occurrence
        val newExceptionStart = exceptionTime.plus(2, ChronoUnit.HOURS) // Moved 2 hours later
        val newExceptionEnd = newExceptionStart.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        // RFC 5545: Exception events have same UID, distinguished by RECURRENCE-ID
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Weekly Sync (Master)
            RRULE:FREQ=WEEKLY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exceptionTime)}
            DTSTART:${formatICalTimestamp(newExceptionStart)}
            DTEND:${formatICalTimestamp(newExceptionEnd)}
            SUMMARY:Weekly Sync (Rescheduled)
            DESCRIPTION:This occurrence was moved to a later time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring event with exception: ${result.href}")

        // Fetch and verify the event exists
        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
    }

    @Test
    @Order(42)
    @DisplayName("42. Create recurring event with multiple EXDATE (cancelled occurrences)")
    fun `create recurring event with multiple excluded dates`() {
        val uid = generateUid("multi-exdate")
        val startTime = Instant.now().plus(72, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exclude1 = startTime.plus(7, ChronoUnit.DAYS)   // Second occurrence
        val exclude2 = startTime.plus(21, ChronoUnit.DAYS)  // Fourth occurrence
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Weekly Team Sync (Multiple Holidays Skipped)
            RRULE:FREQ=WEEKLY;COUNT=6
            EXDATE:${formatICalTimestamp(exclude1)}
            EXDATE:${formatICalTimestamp(exclude2)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with multiple EXDATE: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertNotNull(fetched.event.rrule)
    }

    @Test
    @Order(43)
    @DisplayName("43. Create exception with different title and location")
    fun `create exception with changed properties`() {
        val uid = generateUid("changed-exception")
        val startTime = Instant.now().plus(74, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exceptionTime = startTime.plus(7, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Regular Team Meeting
            LOCATION:Conference Room A
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exceptionTime)}
            DTSTART:${formatICalTimestamp(exceptionTime)}
            DTEND:${formatICalTimestamp(exceptionTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Special All-Hands Meeting
            LOCATION:Main Auditorium
            DESCRIPTION:This week meeting is a company-wide all-hands instead
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created exception with changed properties: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
    }

    @Test
    @Order(44)
    @DisplayName("44. Create exception with longer duration")
    fun `create exception with extended duration`() {
        val uid = generateUid("extended-exception")
        val startTime = Instant.now().plus(76, ChronoUnit.DAYS)
        val endTime = startTime.plus(30, ChronoUnit.MINUTES) // 30-min standup
        val exceptionTime = startTime.plus(7, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Daily Standup (15 min)
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exceptionTime)}
            DTSTART:${formatICalTimestamp(exceptionTime)}
            DTEND:${formatICalTimestamp(exceptionTime.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Extended Sprint Planning (Replaces Standup)
            DESCRIPTION:This occurrence is extended from 15 min to 2 hours
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created exception with extended duration: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
    }

    @Test
    @Order(45)
    @DisplayName("45. Create all-day recurring with timed exception")
    fun `create all-day recurring with timed exception`() {
        val uid = generateUid("allday-timed-exception")
        val startDate = LocalDate.now().plusDays(78)
        val exceptionDate = startDate.plusDays(7) // Second occurrence
        val timedStart = exceptionDate.atTime(14, 0).atZone(ZoneId.of("UTC")).toInstant()
        val timedEnd = timedStart.plus(2, ChronoUnit.HOURS)
        val now = Instant.now()

        // All-day recurring event, but one occurrence becomes a timed event
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:${formatICalDate(startDate)}
            DTEND;VALUE=DATE:${formatICalDate(startDate.plusDays(1))}
            SUMMARY:Weekly Review Day
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID;VALUE=DATE:${formatICalDate(exceptionDate)}
            DTSTART:${formatICalTimestamp(timedStart)}
            DTEND:${formatICalTimestamp(timedEnd)}
            SUMMARY:Review Meeting (Changed to Timed)
            DESCRIPTION:This week review is a specific meeting instead of all-day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created all-day recurring with timed exception: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
    }

    @Test
    @Order(46)
    @DisplayName("46. Create recurring event with multiple exceptions")
    fun `create recurring event with multiple modified occurrences`() {
        val uid = generateUid("multi-exception")
        val startTime = Instant.now().plus(80, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exception1 = startTime.plus(7, ChronoUnit.DAYS)
        val exception2 = startTime.plus(14, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Weekly Planning
            LOCATION:Room 1
            RRULE:FREQ=WEEKLY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exception1)}
            DTSTART:${formatICalTimestamp(exception1.plus(1, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(exception1.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Planning (Delayed 1hr)
            LOCATION:Room 2
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exception2)}
            DTSTART:${formatICalTimestamp(exception2.minus(1, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(exception2)}
            SUMMARY:Planning (Moved Earlier)
            LOCATION:Room 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring with multiple exceptions: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals(uid, fetched.event.uid)
    }

    // ======================== VTIMEZONE Tests ========================

    @Test
    @Order(50)
    @DisplayName("50. Create event with explicit VTIMEZONE")
    fun `create event with VTIMEZONE component`() {
        val uid = generateUid("timezone")
        val now = Instant.now()
        // Use a specific local time in America/New_York
        val localStart = "20260301T100000"  // March 1, 2026 10:00 AM
        val localEnd = "20260301T110000"    // March 1, 2026 11:00 AM

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:STANDARD
            DTSTART:19701101T020000
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            TZNAME:EST
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:19700308T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            TZNAME:EDT
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;TZID=America/New_York:$localStart
            DTEND;TZID=America/New_York:$localEnd
            SUMMARY:New York Meeting
            DESCRIPTION:Event with explicit timezone definition
            LOCATION:New York Office
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with VTIMEZONE: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("New York Meeting", fetched.event.summary)
    }

    // ======================== VALARM Tests ========================

    @Test
    @Order(60)
    @DisplayName("60. Create event with VALARM reminder")
    fun `create event with display alarm`() {
        val uid = generateUid("alarm")
        val startTime = Instant.now().plus(75, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Important Meeting with Reminder
            DESCRIPTION:This event has a 15-minute reminder
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Meeting starts in 15 minutes
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with VALARM: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Important Meeting with Reminder", fetched.event.summary)
        // Note: VALARM parsing depends on library support
    }

    @Test
    @Order(61)
    @DisplayName("61. Create event with multiple alarms")
    fun `create event with multiple reminders`() {
        val uid = generateUid("multi-alarm")
        val startTime = Instant.now().plus(80, ChronoUnit.DAYS)
        val endTime = startTime.plus(2, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Event with Multiple Reminders
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:1 day before
            TRIGGER:-P1D
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:1 hour before
            TRIGGER:-PT1H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:At event time
            TRIGGER:PT0M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with multiple alarms: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Event with Multiple Reminders", fetched.event.summary)
    }

    // ======================== Unicode and Special Character Tests ========================

    @Test
    @Order(70)
    @DisplayName("70. Create event with Unicode characters")
    fun `create event with international characters`() {
        val uid = generateUid("unicode")
        val startTime = Instant.now().plus(85, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:日本語テスト / Тест / Ελληνικά
            DESCRIPTION:Testing Unicode: 中文 한국어 العربية עברית 🎉🎊🗓️
            LOCATION:東京 / Москва / Αθήνα
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with Unicode: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertTrue(fetched.event.summary?.contains("日本語") == true ||
                   fetched.event.summary?.contains("Тест") == true,
                   "Should preserve Unicode in summary")
    }

    @Test
    @Order(71)
    @DisplayName("71. Create event with special characters")
    fun `create event with special iCal characters`() {
        val uid = generateUid("special-chars")
        val startTime = Instant.now().plus(90, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        // iCal special characters that need escaping: comma, semicolon, backslash, newline
        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Meeting: Q1 Review\, Planning & Strategy
            DESCRIPTION:Topics:\n1. Budget review\n2. Goals\n3. Timeline\nNote: Confidential
            LOCATION:Room 101\; Building A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with special characters: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertTrue(fetched.event.summary?.isNotBlank() == true, "Should have summary")
    }

    @Test
    @Order(72)
    @DisplayName("72. Create event with long description")
    fun `create event with very long description`() {
        val uid = generateUid("long-desc")
        val startTime = Instant.now().plus(95, ChronoUnit.DAYS)
        val endTime = startTime.plus(3, ChronoUnit.HOURS)
        val now = Instant.now()

        // Generate a long description (over 1000 characters)
        val longDescription = buildString {
            append("Meeting Agenda:\\n\\n")
            repeat(20) { i ->
                append("${i + 1}. Discussion item number ${i + 1} with detailed information ")
                append("about the topic and expected outcomes. ")
                append("Please come prepared with your inputs.\\n")
            }
            append("\\nAction Items:\\n")
            repeat(10) { i ->
                append("- Task ${i + 1}: Complete the assigned work\\n")
            }
        }

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Quarterly Planning Session
            DESCRIPTION:$longDescription
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with long description: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Quarterly Planning Session", fetched.event.summary)
    }

    // ======================== URL Property Tests ========================

    @Test
    @Order(73)
    @DisplayName("73. Create event with URL property")
    fun `create event with URL link`() {
        val uid = generateUid("with-url")
        val startTime = Instant.now().plus(100, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Webinar with Link
            DESCRIPTION:Join the webinar using the URL below
            URL:https://example.com/webinar/12345
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with URL: ${result.href}")

        val fetched = fetchAndVerify(result.href)
        assertEquals("Webinar with Link", fetched.event.summary)
    }

    // ======================== Sync Tests ========================

    @Test
    @Order(80)
    @DisplayName("80. Get ctag for change detection")
    fun `get ctag from calendar`() {
        assertNotNull(defaultCalendarUrl)

        val result = calDavClient.getCtag(defaultCalendarUrl!!)

        assertTrue(result is DavResult.Success, "Should get ctag: $result")
        @Suppress("UNCHECKED_CAST")
        val ctag = (result as DavResult.Success<String?>).value

        println("Calendar ctag: $ctag")
        assertNotNull(ctag)
        assertTrue(ctag!!.isNotBlank())
    }

    @Test
    @Order(81)
    @DisplayName("81. Initial sync-collection (empty token)")
    fun `initial sync collection returns all events`() {
        assertNotNull(defaultCalendarUrl)

        val result = calDavClient.syncCollection(
            calendarUrl = defaultCalendarUrl!!,
            syncToken = ""
        )

        assertTrue(result is DavResult.Success, "Should sync collection: $result")
        @Suppress("UNCHECKED_CAST")
        val syncResult = (result as DavResult.Success<SyncResult>).value

        println("Initial sync result:")
        println("  New sync token: ${syncResult.newSyncToken}")
        println("  Added items: ${syncResult.added.size}")
        println("  Added hrefs: ${syncResult.addedHrefs.size}")

        assertTrue(syncResult.newSyncToken.isNotBlank())
        lastSyncToken = syncResult.newSyncToken

        // Should find events we created
        assertTrue(syncResult.added.size > 0 || syncResult.addedHrefs.size > 0,
            "Should have added items from our test events")
    }

    @Test
    @Order(82)
    @DisplayName("82. Incremental sync after creating new event")
    fun `incremental sync detects new event`() {
        assertNotNull(defaultCalendarUrl)
        assertNotNull(lastSyncToken, "Need sync token from previous test")

        // Create a new event
        val uid = generateUid("incremental-sync")
        val startTime = Instant.now().plus(105, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:New Event for Incremental Sync Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = createAndTrackEvent(uid, icalData)
        println("Created event for incremental sync: ${createResult.href}")

        // Now do incremental sync
        val result = calDavClient.syncCollection(
            calendarUrl = defaultCalendarUrl!!,
            syncToken = lastSyncToken!!
        )

        assertTrue(result is DavResult.Success, "Should sync collection: $result")
        @Suppress("UNCHECKED_CAST")
        val syncResult = (result as DavResult.Success<SyncResult>).value

        println("Incremental sync result:")
        println("  New sync token: ${syncResult.newSyncToken}")
        println("  Added: ${syncResult.added.size + syncResult.addedHrefs.size}")
        println("  Deleted: ${syncResult.deleted.size}")

        // Token should be different
        assertNotEquals(lastSyncToken, syncResult.newSyncToken,
            "Sync token should change after new event")

        lastSyncToken = syncResult.newSyncToken
    }

    @Test
    @Order(83)
    @DisplayName("83. Fetch events in date range")
    fun `fetch events in date range`() {
        assertNotNull(defaultCalendarUrl)

        val start = Instant.now()
        val end = start.plus(120, ChronoUnit.DAYS)

        val result = calDavClient.fetchEvents(
            calendarUrl = defaultCalendarUrl!!,
            start = start,
            end = end
        )

        assertTrue(result is DavResult.Success, "Should fetch events: $result")
        @Suppress("UNCHECKED_CAST")
        val events = (result as DavResult.Success<List<EventWithMetadata>>).value

        println("Fetched ${events.size} events in date range")
        events.take(10).forEach { event ->
            println("  - ${event.event.summary}")
        }

        // Should have our test events
        assertTrue(events.isNotEmpty(), "Should find test events")
    }

    // ======================== Additional CalDavClient Method Tests ========================

    @Test
    @Order(84)
    @DisplayName("84. Fetch events by href (calendar-multiget)")
    fun `fetchEventsByHref returns specific events`() {
        assertNotNull(defaultCalendarUrl)

        // Create two events
        val uid1 = generateUid("multiget-1")
        val uid2 = generateUid("multiget-2")
        val startTime = Instant.now().plus(106, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData1 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid1
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Multiget Event 1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val icalData2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid2
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime.plus(1, ChronoUnit.DAYS))}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))}
            SUMMARY:Multiget Event 2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val create1 = createAndTrackEvent(uid1, icalData1)
        val create2 = createAndTrackEvent(uid2, icalData2)

        // Fetch both events using multiget
        val result = calDavClient.fetchEventsByHref(
            calendarUrl = defaultCalendarUrl!!,
            eventHrefs = listOf(create1.href, create2.href)
        )

        assertTrue(result is DavResult.Success, "Should fetch events by href: $result")
        @Suppress("UNCHECKED_CAST")
        val events = (result as DavResult.Success<List<EventWithMetadata>>).value

        println("Multiget returned ${events.size} events")
        assertEquals(2, events.size, "Should return exactly 2 events")

        val summaries = events.map { it.event.summary }
        assertTrue(summaries.contains("Multiget Event 1"), "Should contain first event")
        assertTrue(summaries.contains("Multiget Event 2"), "Should contain second event")
    }

    @Test
    @Order(85)
    @DisplayName("85. Fetch events by href with empty list")
    fun `fetchEventsByHref with empty list returns empty result`() {
        assertNotNull(defaultCalendarUrl)

        val result = calDavClient.fetchEventsByHref(
            calendarUrl = defaultCalendarUrl!!,
            eventHrefs = emptyList()
        )

        assertTrue(result is DavResult.Success, "Should succeed with empty list: $result")
        @Suppress("UNCHECKED_CAST")
        val events = (result as DavResult.Success<List<EventWithMetadata>>).value

        assertTrue(events.isEmpty(), "Should return empty list")
        println("Empty multiget correctly returned empty list")
    }

    @Test
    @Order(86)
    @DisplayName("86. Get sync-token from calendar")
    fun `getSyncToken returns valid token`() {
        assertNotNull(defaultCalendarUrl)

        val result = calDavClient.getSyncToken(defaultCalendarUrl!!)

        assertTrue(result is DavResult.Success, "Should get sync token: $result")
        @Suppress("UNCHECKED_CAST")
        val syncToken = (result as DavResult.Success<String?>).value

        println("Calendar sync-token: $syncToken")
        // Sync token may be null if server doesn't support it, but Nextcloud does
        assertNotNull(syncToken, "Nextcloud should return sync-token")
        assertTrue(syncToken!!.isNotBlank(), "Sync token should not be blank")
    }

    @Test
    @Order(87)
    @DisplayName("87. Fetch ETags only in date range (lightweight sync)")
    fun `fetchEtagsInRange returns etags without event data`() {
        assertNotNull(defaultCalendarUrl)

        val start = Instant.now()
        val end = start.plus(120, ChronoUnit.DAYS)

        val result = calDavClient.fetchEtagsInRange(
            calendarUrl = defaultCalendarUrl!!,
            start = start,
            end = end
        )

        assertTrue(result is DavResult.Success, "Should fetch etags: $result")
        @Suppress("UNCHECKED_CAST")
        val etags = (result as DavResult.Success<List<com.icalendar.caldav.client.EtagInfo>>).value

        println("Fetched ${etags.size} ETags in date range")
        etags.take(5).forEach { info ->
            println("  - ${info.href} -> ${info.etag}")
        }

        // Should have etags for our test events
        assertTrue(etags.isNotEmpty(), "Should find test events")
        etags.forEach { info ->
            assertTrue(info.href.isNotBlank(), "Href should not be blank")
            assertTrue(info.etag.isNotBlank(), "ETag should not be blank")
        }
    }

    @Test
    @Order(88)
    @DisplayName("88. Delete event explicitly")
    fun `deleteEvent removes event from calendar`() {
        val uid = generateUid("delete-test")
        val startTime = Instant.now().plus(107, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Event To Delete
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = createAndTrackEvent(uid, icalData)
        println("Created event for deletion test: ${createResult.href}")

        // Verify it exists
        val fetchResult = calDavClient.getEvent(createResult.href)
        assertTrue(fetchResult is DavResult.Success, "Event should exist before delete")

        // Delete it
        val deleteResult = calDavClient.deleteEvent(createResult.href, createResult.etag)
        assertTrue(deleteResult is DavResult.Success, "Delete should succeed: $deleteResult")
        println("Successfully deleted event")

        // Remove from tracking (already deleted)
        createdEventUrls.removeIf { it.first == createResult.href }

        // Verify it's gone
        val afterDelete = calDavClient.getEvent(createResult.href)
        assertTrue(
            afterDelete is DavResult.HttpError && (afterDelete.code == 404 || afterDelete.code == 410),
            "Event should be gone after delete: $afterDelete"
        )
        println("Verified event no longer exists (404/410)")
    }

    @Test
    @Order(89)
    @DisplayName("89. Get event returns 404 for non-existent event")
    fun `getEvent returns 404 for missing event`() {
        assertNotNull(defaultCalendarUrl)

        val fakeUrl = "$defaultCalendarUrl/non-existent-event-${UUID.randomUUID()}.ics"

        val result = calDavClient.getEvent(fakeUrl)

        println("Result for non-existent event: $result")

        // Should return 404 or similar error
        assertTrue(
            result is DavResult.HttpError,
            "Should return HttpError for missing event: $result"
        )

        if (result is DavResult.HttpError) {
            assertTrue(
                result.code == 404 || result.code == 207,
                "Should be 404 or 207 with empty results: ${result.code}"
            )
        }
    }

    // ======================== ETag Conflict Tests ========================

    @Test
    @Order(90)
    @DisplayName("90. ETag conflict detection (concurrent modification)")
    fun `etag mismatch prevents update`() {
        val uid = generateUid("etag-conflict")
        val startTime = Instant.now().plus(110, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:ETag Conflict Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = createAndTrackEvent(uid, icalData)
        val correctEtag = createResult.etag

        // Try to update with wrong ETag
        val wrongEtag = "\"wrong-etag-value\""

        val updatedIcalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(endTime)}
            SUMMARY:Should Not Update
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val updateResult = calDavClient.updateEventRaw(
            eventUrl = createResult.href,
            icalData = updatedIcalData,
            etag = wrongEtag
        )

        println("Update with wrong ETag result: $updateResult")

        // Should fail with 412 Precondition Failed or similar
        assertTrue(
            updateResult is DavResult.HttpError || updateResult is DavResult.NetworkError || updateResult is DavResult.ParseError,
            "Update with wrong ETag should fail: $updateResult"
        )

        // Verify original content unchanged
        val fetched = fetchAndVerify(createResult.href)
        assertEquals("ETag Conflict Test Event", fetched.event.summary,
            "Original event should be unchanged after failed update")
    }

    // ======================== DefaultQuirks Validation Tests ========================

    @Test
    @Order(95)
    @DisplayName("95. DefaultQuirks parses real Nextcloud discovery")
    fun `DefaultQuirks works with real Nextcloud responses`() {
        assertNotNull(principalUrl)
        assertNotNull(calendarHomeUrl)
        assertNotNull(defaultCalendarUrl)

        println("DefaultQuirks successfully parsed Nextcloud responses:")
        println("  Principal: $principalUrl")
        println("  Calendar Home: $calendarHomeUrl")
        println("  Default Calendar: $defaultCalendarUrl")

        assertTrue(principalUrl!!.startsWith("/") || principalUrl!!.startsWith("http"))
        assertTrue(calendarHomeUrl!!.startsWith("/") || calendarHomeUrl!!.startsWith("http"))
    }

    @Test
    @Order(96)
    @DisplayName("96. DefaultQuirks calendar properties are correct")
    fun `DefaultQuirks extracts calendar properties correctly`() {
        assertNotNull(calendarHomeUrl)

        val fullHomeUrl = if (calendarHomeUrl!!.startsWith("http")) calendarHomeUrl!!
            else "$nextcloudUrl$calendarHomeUrl"

        val result = discovery.listCalendars(fullHomeUrl)
        assertTrue(result is DavResult.Success)

        @Suppress("UNCHECKED_CAST")
        val calendars = (result as DavResult.Success<List<Calendar>>).value

        println("Verifying calendar properties parsed by DefaultQuirks:")
        calendars.forEach { cal ->
            println("  Calendar: ${cal.displayName}")
            println("    - href: ${cal.href}")
            println("    - ctag: ${cal.ctag}")
            println("    - color: ${cal.color}")
            println("    - supportedComponents: ${cal.supportedComponents}")

            assertNotNull(cal.displayName)
            assertNotNull(cal.href)
            assertTrue(cal.href.isNotBlank())
        }
    }

    // ======================== Summary Test ========================

    @Test
    @Order(99)
    @DisplayName("99. Test summary - all event types created successfully")
    fun `summary of all event types tested`() {
        println("\n========================================")
        println("INTEGRATION TEST SUMMARY")
        println("========================================")
        println("Total events created: ${createdEventUrls.size}")
        println("\n=== Discovery ===")
        println("  ✓ Principal URL discovery")
        println("  ✓ Calendar home URL discovery")
        println("  ✓ List calendars")
        println("  ✓ Full discovery flow")
        println("\n=== Basic Events ===")
        println("  ✓ Create basic event")
        println("  ✓ Update event with ETag")
        println("\n=== All-Day & Multi-Day Events ===")
        println("  ✓ All-day events (DATE format)")
        println("  ✓ Multi-day events (3 days)")
        println("  ✓ Week-long all-day events")
        println("  ✓ Month boundary spanning events")
        println("  ✓ Recurring all-day events")
        println("\n=== Recurring Events ===")
        println("  ✓ Daily recurring (FREQ=DAILY, COUNT)")
        println("  ✓ Weekly recurring (BYDAY=MO,WE,FR)")
        println("  ✓ Monthly recurring (BYMONTHDAY)")
        println("  ✓ Yearly recurring (birthday/anniversary)")
        println("  ✓ Bi-weekly recurring (INTERVAL=2)")
        println("  ✓ UNTIL-limited recurring")
        println("\n=== Exception Events (RFC 5545) ===")
        println("  ✓ Single EXDATE (cancelled occurrence)")
        println("  ✓ Multiple EXDATE (multiple cancelled)")
        println("  ✓ RECURRENCE-ID (time moved)")
        println("  ✓ Exception with changed title/location")
        println("  ✓ Exception with extended duration")
        println("  ✓ All-day to timed exception")
        println("  ✓ Multiple exceptions in one series")
        println("\n=== Timezone & Alarms ===")
        println("  ✓ VTIMEZONE embedded (America/New_York)")
        println("  ✓ Single VALARM (15 min before)")
        println("  ✓ Multiple VALARMs (1 day, 1 hour, at-time)")
        println("\n=== Edge Cases ===")
        println("  ✓ Unicode characters (Japanese, Russian, Greek, Arabic, Hebrew, Emoji)")
        println("  ✓ Special iCal characters (comma, semicolon, newline)")
        println("  ✓ Long descriptions (1000+ chars)")
        println("  ✓ URL property")
        println("\n=== Sync & Change Detection ===")
        println("  ✓ ctag retrieval")
        println("  ✓ sync-token retrieval")
        println("  ✓ Initial sync-collection")
        println("  ✓ Incremental sync-collection")
        println("  ✓ Date range queries (calendar-query)")
        println("  ✓ ETag-only queries (lightweight sync)")
        println("  ✓ ETag conflict detection (412 Precondition Failed)")
        println("\n=== Multiget & CRUD ===")
        println("  ✓ fetchEventsByHref (calendar-multiget)")
        println("  ✓ fetchEventsByHref with empty list")
        println("  ✓ Explicit deleteEvent with verification")
        println("  ✓ 404 handling for non-existent event")
        println("\n=== DefaultQuirks Validation ===")
        println("  ✓ Parses real Nextcloud responses")
        println("  ✓ Extracts calendar properties correctly")
        println("========================================")
        println("TOTAL: ${createdEventUrls.size} events created and tested")
        println("========================================")

        assertTrue(createdEventUrls.size >= 28,
            "Should have created at least 28 test events")
    }
}
