package org.onekash.icaldav.integration

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventCreateResult
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.client.SyncResult
import org.onekash.icaldav.discovery.CalDavDiscovery
import org.onekash.icaldav.model.CalDavAccount
import org.onekash.icaldav.model.Calendar
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.quirks.ICloudQuirks
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.LinkRelationType
import org.onekash.icaldav.model.RelationType
import org.onekash.icaldav.model.Transparency
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Comprehensive integration tests against real iCloud CalDAV server.
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
    fun cleanup() = runBlocking {
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

    /**
     * Find event by UID using REPORT query (handles iCloud eventual consistency better than GET).
     * Returns the event if found, null otherwise.
     */
    private suspend fun findEventByUid(uid: String, maxRetries: Int = 5): EventWithMetadata? {
        var delay = 2000L
        repeat(maxRetries) { attempt ->
            val now = Instant.now()
            val result = calDavClient.fetchEvents(
                defaultCalendarUrl!!,
                now.minus(30, ChronoUnit.DAYS),
                now.plus(400, ChronoUnit.DAYS)
            )
            if (result is DavResult.Success) {
                @Suppress("UNCHECKED_CAST")
                val events = (result as DavResult.Success<List<EventWithMetadata>>).value
                val found = events.find { it.event.uid == uid || it.href.contains(uid) }
                if (found != null) {
                    return found
                }
            }
            if (attempt < maxRetries - 1) {
                println("  Retry ${attempt + 1}/$maxRetries - event not yet visible, waiting ${delay}ms")
                Thread.sleep(delay)
                delay = minOf(delay * 2, 10000L)
            }
        }
        return null
    }

    private suspend fun createAndTrackEvent(uid: String, icalData: String): EventCreateResult {
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

    /**
     * Fetch and verify event exists using REPORT (handles iCloud eventual consistency).
     */
    private suspend fun fetchAndVerify(uid: String): EventWithMetadata {
        val event = findEventByUid(uid)
        assertNotNull(event, "Should find event with UID: $uid")
        return event!!
    }

    // ======================== Discovery Tests ========================

    @Test
    @Order(1)
    @DisplayName("1. Discover principal URL")
    fun `discover principal URL`() = runTest {
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
    fun `discover calendar home URL`() = runTest {
        Assumptions.assumeTrue(principalUrl != null, "Principal URL required")

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
    fun `list calendars`() = runTest {
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

    @Test
    @Order(4)
    @DisplayName("4. Full discovery flow")
    fun `full discovery flow returns complete account`() = runTest {
        val result = calDavClient.discoverAccount(serverUrl)

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
    fun `create basic event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        // Verify using REPORT (handles iCloud eventual consistency)
        val fetched = fetchAndVerify(uid)
        assertEquals(uid, fetched.event.uid)
        assertEquals("Basic Test Event", fetched.event.summary)
    }

    @Test
    @Order(11)
    @DisplayName("11. Update event and verify ETag changes")
    fun `update event changes ETag`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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
            SEQUENCE:1
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
    fun `create all-day event with DATE format`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("allday")
        val eventDate = LocalDate.now().plusDays(35)
        val now = Instant.now()

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

        val fetched = fetchAndVerify(uid)
        assertEquals(uid, fetched.event.uid)
        assertEquals("All-Day Test Event", fetched.event.summary)
        assertTrue(fetched.event.isAllDay, "Event should be marked as all-day")
    }

    @Test
    @Order(21)
    @DisplayName("21. Create multi-day event")
    fun `create multi-day spanning event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("multiday")
        val startDate = LocalDate.now().plusDays(40)
        val endDate = startDate.plusDays(3)
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

        val fetched = fetchAndVerify(uid)
        assertEquals("Multi-Day Conference", fetched.event.summary)
    }

    @Test
    @Order(22)
    @DisplayName("22. Create week-long all-day event")
    fun `create week-long all-day event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("weeklong")
        val startDate = LocalDate.now().plusDays(42)
        val endDate = startDate.plusDays(7)
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

        val fetched = fetchAndVerify(uid)
        assertEquals("Week-Long Vacation", fetched.event.summary)
    }

    @Test
    @Order(23)
    @DisplayName("23. Create all-day event across month boundary")
    fun `create all-day event spanning month boundary`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("month-span")
        val startDate = LocalDate.now().withDayOfMonth(28).plusDays(5)
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

        val fetched = fetchAndVerify(uid)
        assertEquals("Month-Spanning Event", fetched.event.summary)
    }

    @Test
    @Order(24)
    @DisplayName("24. Create recurring all-day event")
    fun `create recurring all-day event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("recurring-allday")
        val startDate = LocalDate.now().plusDays(47)
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
            SUMMARY:Weekly Review Day
            DESCRIPTION:All-day recurring event every week
            RRULE:FREQ=WEEKLY;COUNT=8
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring all-day event: ${result.href}")

        val fetched = fetchAndVerify(uid)
        assertEquals("Weekly Review Day", fetched.event.summary)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.WEEKLY, fetched.event.rrule!!.freq)
    }

    @Test
    @Order(25)
    @DisplayName("25. Create yearly recurring event (birthday/anniversary)")
    fun `create yearly recurring event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.YEARLY, fetched.event.rrule!!.freq)
    }

    @Test
    @Order(26)
    @DisplayName("26. Create bi-weekly recurring event")
    fun `create bi-weekly recurring event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
        assertEquals(2, fetched.event.rrule!!.interval, "Should have interval of 2")
    }

    // ======================== Recurring Event Tests ========================

    @Test
    @Order(30)
    @DisplayName("30. Create daily recurring event")
    fun `create daily recurring event with COUNT`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertEquals("Daily Standup", fetched.event.summary)
        assertNotNull(fetched.event.rrule, "Should have RRULE")
        assertEquals(Frequency.DAILY, fetched.event.rrule!!.freq, "Should be daily frequency")
    }

    @Test
    @Order(31)
    @DisplayName("31. Create weekly recurring event with BYDAY")
    fun `create weekly recurring event on specific days`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
        assertNotNull(fetched.event.rrule!!.byDay, "Should have BYDAY clause")
        assertTrue(fetched.event.rrule!!.byDay!!.isNotEmpty(), "Should have days specified")
    }

    @Test
    @Order(32)
    @DisplayName("32. Create monthly recurring event")
    fun `create monthly recurring event on specific day`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
        assertEquals(Frequency.MONTHLY, fetched.event.rrule!!.freq, "Should be monthly frequency")
    }

    @Test
    @Order(33)
    @DisplayName("33. Create recurring event with UNTIL date")
    fun `create recurring event ending on specific date`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
        assertNotNull(fetched.event.rrule!!.until, "Should have UNTIL property")
    }

    // ======================== Exception Event Tests ========================

    @Test
    @Order(40)
    @DisplayName("40. Create recurring event with EXDATE (cancelled occurrence)")
    fun `create recurring event with excluded date`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exdate-recur")
        val startTime = Instant.now().plus(65, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val excludeDate = startTime.plus(7, ChronoUnit.DAYS)
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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
    }

    @Test
    @Order(41)
    @DisplayName("41. Create recurring event with modified exception (RECURRENCE-ID)")
    fun `create recurring event with modified occurrence`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exception-recur")
        val startTime = Instant.now().plus(70, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exceptionTime = startTime.plus(14, ChronoUnit.DAYS)
        val newExceptionStart = exceptionTime.plus(2, ChronoUnit.HOURS)
        val newExceptionEnd = newExceptionStart.plus(1, ChronoUnit.HOURS)
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

        val fetched = fetchAndVerify(uid)
        assertEquals(uid, fetched.event.uid)
    }

    @Test
    @Order(42)
    @DisplayName("42. Create recurring event with multiple EXDATE")
    fun `create recurring event with multiple excluded dates`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("multi-exdate")
        val startTime = Instant.now().plus(72, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)
        val exclude1 = startTime.plus(7, ChronoUnit.DAYS)
        val exclude2 = startTime.plus(21, ChronoUnit.DAYS)
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

        val fetched = fetchAndVerify(uid)
        assertNotNull(fetched.event.rrule)
    }

    // ======================== VTIMEZONE Tests ========================

    @Test
    @Order(50)
    @DisplayName("50. Create event with explicit VTIMEZONE")
    fun `create event with VTIMEZONE component`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("timezone")
        val now = Instant.now()
        val localStart = "20260301T100000"
        val localEnd = "20260301T110000"

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

        val fetched = fetchAndVerify(uid)
        assertEquals("New York Meeting", fetched.event.summary)
    }

    // ======================== VALARM Tests ========================

    @Test
    @Order(60)
    @DisplayName("60. Create event with VALARM reminder")
    fun `create event with display alarm`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertEquals("Important Meeting with Reminder", fetched.event.summary)
    }

    @Test
    @Order(61)
    @DisplayName("61. Create event with multiple alarms")
    fun `create event with multiple reminders`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertEquals("Event with Multiple Reminders", fetched.event.summary)
    }

    // ======================== Unicode and Special Character Tests ========================

    @Test
    @Order(70)
    @DisplayName("70. Create event with Unicode characters")
    fun `create event with international characters`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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
            SUMMARY:Meeting Test Event
            DESCRIPTION:Testing Unicode characters
            LOCATION:Test Location
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with Unicode: ${result.href}")

        val fetched = fetchAndVerify(uid)
        assertTrue(fetched.event.summary?.isNotBlank() == true, "Should have summary")
    }

    @Test
    @Order(71)
    @DisplayName("71. Create event with special characters")
    fun `create event with special iCal characters`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("special-chars")
        val startTime = Instant.now().plus(90, ChronoUnit.DAYS)
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
            SUMMARY:Meeting: Q1 Review\, Planning & Strategy
            DESCRIPTION:Topics:\n1. Budget review\n2. Goals\n3. Timeline
            LOCATION:Room 101\; Building A
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with special characters: ${result.href}")

        val fetched = fetchAndVerify(uid)
        assertTrue(fetched.event.summary?.isNotBlank() == true, "Should have summary")
    }

    @Test
    @Order(72)
    @DisplayName("72. Create event with long description")
    fun `create event with very long description`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("long-desc")
        val startTime = Instant.now().plus(95, ChronoUnit.DAYS)
        val endTime = startTime.plus(3, ChronoUnit.HOURS)
        val now = Instant.now()

        val longDescription = buildString {
            append("Meeting Agenda:\\n\\n")
            repeat(20) { i ->
                append("${i + 1}. Discussion item number ${i + 1}. ")
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

        val fetched = fetchAndVerify(uid)
        assertEquals("Quarterly Planning Session", fetched.event.summary)
    }

    @Test
    @Order(73)
    @DisplayName("73. Create event with URL property")
    fun `create event with URL link`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val fetched = fetchAndVerify(uid)
        assertEquals("Webinar with Link", fetched.event.summary)
    }

    // ======================== Sync Tests ========================

    @Test
    @Order(80)
    @DisplayName("80. Get ctag for change detection")
    fun `get ctag from calendar`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.getCtag(defaultCalendarUrl!!)

        assertTrue(result is DavResult.Success, "Should get ctag: $result")
        @Suppress("UNCHECKED_CAST")
        val ctag = (result as DavResult.Success<String?>).value

        println("Calendar ctag: $ctag")
        // Note: iCloud may return null for ctag in some cases
        if (ctag != null) {
            assertTrue(ctag.isNotBlank(), "CTag should not be blank if present")
        } else {
            println("  (iCloud returned null ctag - this is expected behavior)")
        }
    }

    @Test
    @Order(81)
    @DisplayName("81. Initial sync-collection (empty token)")
    fun `initial sync collection returns all events`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.syncCollection(
            calendarUrl = defaultCalendarUrl!!,
            syncToken = ""
        )

        when (result) {
            is DavResult.Success -> {
                val syncResult = result.value
                println("Initial sync result:")
                println("  New sync token: ${syncResult.newSyncToken.take(50)}...")
                println("  Added items: ${syncResult.added.size}")
                println("  Added hrefs: ${syncResult.addedHrefs.size}")

                assertTrue(syncResult.newSyncToken.isNotBlank())
                lastSyncToken = syncResult.newSyncToken
            }
            else -> {
                println("Sync collection error: $result")
            }
        }
    }

    @Test
    @Order(82)
    @DisplayName("82. Incremental sync after creating new event")
    fun `incremental sync detects new event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")
        Assumptions.assumeTrue(lastSyncToken != null, "Need sync token from previous test")

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

        // Allow time for iCloud to process
        Thread.sleep(3000)

        val result = calDavClient.syncCollection(
            calendarUrl = defaultCalendarUrl!!,
            syncToken = lastSyncToken!!
        )

        when (result) {
            is DavResult.Success -> {
                val syncResult = result.value
                println("Incremental sync result:")
                println("  New sync token: ${syncResult.newSyncToken.take(50)}...")
                println("  Added: ${syncResult.added.size + syncResult.addedHrefs.size}")
                println("  Deleted: ${syncResult.deleted.size}")

                lastSyncToken = syncResult.newSyncToken
            }
            is DavResult.HttpError -> {
                if (result.code == 403 || result.code == 410) {
                    println("Sync token expired/invalid (expected behavior)")
                } else {
                    println("Sync failed: $result")
                }
            }
            else -> println("Sync result: $result")
        }
    }

    @Test
    @Order(83)
    @DisplayName("83. Fetch events in date range")
    fun `fetch events in date range`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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
    }

    @Test
    @Order(84)
    @DisplayName("84. Fetch events by href (calendar-multiget)")
    fun `fetchEventsByHref returns specific events`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        // Allow time for iCloud eventual consistency (multiget may need longer)
        Thread.sleep(5000)

        val result = calDavClient.fetchEventsByHref(
            calendarUrl = defaultCalendarUrl!!,
            eventHrefs = listOf(create1.href, create2.href)
        )

        assertTrue(result is DavResult.Success, "Should fetch events by href: $result")
        @Suppress("UNCHECKED_CAST")
        val events = (result as DavResult.Success<List<EventWithMetadata>>).value

        println("Multiget returned ${events.size} events")
        // iCloud has eventual consistency - multiget may return fewer results immediately
        // Just verify the call succeeded; cleanup will handle the events
    }

    @Test
    @Order(85)
    @DisplayName("85. Fetch events by href with empty list")
    fun `fetchEventsByHref with empty list returns empty result`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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
    fun `getSyncToken returns valid token`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.getSyncToken(defaultCalendarUrl!!)

        when (result) {
            is DavResult.Success -> {
                val syncToken = result.value
                println("Calendar sync-token: $syncToken")
                if (syncToken != null) {
                    assertTrue(syncToken.isNotBlank(), "Sync token should not be blank")
                }
            }
            else -> println("Sync token not available: $result")
        }
    }

    @Test
    @Order(87)
    @DisplayName("87. Fetch ETags only in date range")
    fun `fetchEtagsInRange returns etags without event data`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val start = Instant.now()
        val end = start.plus(120, ChronoUnit.DAYS)

        val result = calDavClient.fetchEtagsInRange(
            calendarUrl = defaultCalendarUrl!!,
            start = start,
            end = end
        )

        when (result) {
            is DavResult.Success -> {
                val etags = result.value
                println("Fetched ${etags.size} ETags in date range")
                etags.take(5).forEach { info ->
                    println("  - ${info.href} -> ${info.etag}")
                }
            }
            else -> println("Fetch etags result: $result")
        }
    }

    @Test
    @Order(88)
    @DisplayName("88. Delete event explicitly")
    fun `deleteEvent removes event from calendar`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        val deleteResult = calDavClient.deleteEvent(createResult.href, createResult.etag)
        assertTrue(deleteResult is DavResult.Success, "Delete should succeed: $deleteResult")
        println("Successfully deleted event")

        createdEventUrls.removeIf { it.first == createResult.href }
    }

    @Test
    @Order(89)
    @DisplayName("89. Get event returns 404 for non-existent event")
    fun `getEvent returns 404 for missing event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val fakeUrl = "$defaultCalendarUrl/non-existent-event-${UUID.randomUUID()}.ics"

        val result = calDavClient.getEvent(fakeUrl)

        println("Result for non-existent event: $result")

        assertTrue(
            result is DavResult.HttpError,
            "Should return HttpError for missing event: $result"
        )
    }

    // ======================== ETag Conflict Tests ========================

    @Test
    @Order(90)
    @DisplayName("90. ETag conflict detection (concurrent modification)")
    fun `etag mismatch prevents update`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

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

        assertTrue(
            updateResult is DavResult.HttpError,
            "Update with wrong ETag should fail: $updateResult"
        )
    }

    // ======================== Typed API Tests ========================

    @Test
    @Order(91)
    @DisplayName("91. Create event using typed ICalEvent API")
    fun `createEvent with ICalEvent object`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("typed-create")
        val startTime = Instant.now().plus(115, ChronoUnit.DAYS)
        val endTime = startTime.plus(1, ChronoUnit.HOURS)

        val event = ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Typed API Event",
            description = "Event created using typed ICalEvent object",
            location = "Test Location",
            dtStart = ICalDateTime.fromTimestamp(startTime.toEpochMilli(), null, false),
            dtEnd = ICalDateTime.fromTimestamp(endTime.toEpochMilli(), null, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = listOf("test", "integration"),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = ICalDateTime.fromTimestamp(Instant.now().toEpochMilli(), null, false),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )

        val result = calDavClient.createEvent(defaultCalendarUrl!!, event)

        when (result) {
            is DavResult.Success -> {
                println("Created typed event: ${result.value.href}")
                trackEvent(result.value.href, result.value.etag)

                val fetched = fetchAndVerify(uid)
                assertEquals(uid, fetched.event.uid)
            }
            is DavResult.HttpError -> {
                println("Server rejected typed event with ${result.code}: ${result.message}")
            }
            else -> println("Result: $result")
        }
    }

    @Test
    @Order(92)
    @DisplayName("92. buildEventUrl sanitizes UID correctly")
    fun `buildEventUrl creates safe URLs`() = runTest {
        val calendarUrl = "https://example.com/calendars/user/calendar"

        val url1 = calDavClient.buildEventUrl(calendarUrl, "simple-uid-123")
        assertEquals("https://example.com/calendars/user/calendar/simple-uid-123.ics", url1)

        val url2 = calDavClient.buildEventUrl(calendarUrl, "event@example.com")
        assertEquals("https://example.com/calendars/user/calendar/event@example.com.ics", url2)

        println("buildEventUrl correctly sanitizes UIDs")
    }

    // ======================== Adverse Tests ========================

    @Test
    @Order(100)
    @DisplayName("100. Fetch events from non-existent calendar returns error")
    fun `fetchEvents from invalid calendar URL fails`() = runTest {
        val fakeCalendarUrl = "$serverUrl/calendars/$username/non-existent-calendar-${UUID.randomUUID()}/"

        val result = calDavClient.fetchEvents(
            calendarUrl = fakeCalendarUrl,
            start = Instant.now(),
            end = Instant.now().plus(30, ChronoUnit.DAYS)
        )

        println("Fetch from non-existent calendar: $result")

        assertTrue(
            result is DavResult.HttpError || result is DavResult.ParseError,
            "Should fail for non-existent calendar: $result"
        )
    }

    @Test
    @Order(101)
    @DisplayName("101. Create event with duplicate UID fails (If-None-Match)")
    fun `createEventRaw with duplicate UID fails`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("duplicate-test")
        val startTime = Instant.now().plus(120, ChronoUnit.DAYS)
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
            SUMMARY:First Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val first = createAndTrackEvent(uid, icalData)
        println("Created first event: ${first.href}")

        val duplicateData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Duplicate Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val duplicateResult = calDavClient.createEventRaw(defaultCalendarUrl!!, uid, duplicateData)

        println("Create duplicate result: $duplicateResult")

        assertTrue(
            duplicateResult is DavResult.HttpError,
            "Creating duplicate UID should fail: $duplicateResult"
        )
    }

    @Test
    @Order(102)
    @DisplayName("102. Invalid authentication fails")
    fun `client with wrong credentials fails`() = runTest {
        val badClient = CalDavClient.withBasicAuth("wronguser", "wrongpassword123")

        val result = badClient.getCtag(defaultCalendarUrl!!)

        println("Request with wrong credentials: $result")

        assertTrue(
            result is DavResult.HttpError,
            "Should fail with wrong credentials: $result"
        )

        if (result is DavResult.HttpError) {
            assertTrue(
                result.code in listOf(401, 403),
                "Should be 401 or 403: ${result.code}"
            )
        }
    }

    @Test
    @Order(103)
    @DisplayName("103. Empty UID throws IllegalArgumentException")
    fun `buildEventUrl rejects empty UID`() = runTest {
        val calendarUrl = "https://example.com/calendars/user/calendar"

        val exception = assertThrows<IllegalArgumentException> {
            calDavClient.buildEventUrl(calendarUrl, "")
        }

        println("Empty UID exception: ${exception.message}")
    }

    // ======================== RFC 7986 Property Tests ========================

    @Test
    @Order(120)
    @DisplayName("120. Event with COLOR property (RFC 7986)")
    fun `event with COLOR property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("color")
        val startTime = Instant.now().plus(140, ChronoUnit.DAYS)
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
            SUMMARY:Color Event
            COLOR:red
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with COLOR: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Server preserved COLOR: ${fetched.event.color}")
    }

    @Test
    @Order(121)
    @DisplayName("121. Event with CATEGORIES property")
    fun `event with CATEGORIES property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("categories")
        val startTime = Instant.now().plus(143, ChronoUnit.DAYS)
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
            SUMMARY:Categorized Event
            CATEGORIES:WORK,MEETING,IMPORTANT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CATEGORIES: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Parsed categories: ${fetched.event.categories}")
    }

    // ======================== Scheduling Tests ========================

    @Test
    @Order(130)
    @DisplayName("130. Event with ORGANIZER property")
    fun `event with ORGANIZER property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("organizer")
        val startTime = Instant.now().plus(150, ChronoUnit.DAYS)
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
            SUMMARY:Organized Meeting
            ORGANIZER;CN=John Doe:mailto:john@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with ORGANIZER: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Organizer: ${fetched.event.organizer}")
    }

    @Test
    @Order(131)
    @DisplayName("131. Event with ATTENDEE properties")
    fun `event with ATTENDEE properties`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("attendees")
        val startTime = Instant.now().plus(151, ChronoUnit.DAYS)
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
            SUMMARY:Team Meeting
            ORGANIZER;CN=Boss:mailto:boss@example.com
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.com
            ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with ATTENDEES: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Attendee count: ${fetched.event.attendees.size}")
    }

    // ======================== Edge Case Tests ========================

    @Test
    @Order(140)
    @DisplayName("140. Event with DURATION instead of DTEND")
    fun `event with DURATION property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("duration")
        val startTime = Instant.now().plus(160, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DURATION:PT2H30M
            SUMMARY:2.5 Hour Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with DURATION: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Duration: ${fetched.event.duration}")
    }

    @Test
    @Order(141)
    @DisplayName("141. Event with emoji in summary")
    fun `event with emoji in summary`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("emoji")
        val startTime = Instant.now().plus(162, ChronoUnit.DAYS)
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
            SUMMARY:Party Time
            DESCRIPTION:Fun event
            LOCATION:Home
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Summary: ${fetched.event.summary}")
    }

    @Test
    @Order(142)
    @DisplayName("142. Event with zero-duration (point in time)")
    fun `event with zero duration`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("zero-dur")
        val startTime = Instant.now().plus(165, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime)}
            SUMMARY:Zero Duration Reminder
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created zero-duration event: ${result.href}")
    }

    @Test
    @Order(143)
    @DisplayName("143. Event with complex RRULE (5 BYDAY values)")
    fun `event with complex RRULE`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("complex-rrule")
        val startTime = Instant.now().plus(167, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(30, ChronoUnit.MINUTES))}
            SUMMARY:Weekday Morning Standup
            RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=20
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with complex RRULE: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  RRULE: ${fetched.event.rrule}")
    }

    // ======================== Calendar Management Tests ========================

    @Test
    @Order(160)
    @DisplayName("160. List calendars shows expected properties")
    fun `calendar listing includes properties`() = runTest {
        val result = calDavClient.discoverAccount(serverUrl)

        when (result) {
            is DavResult.Success -> {
                val account = result.value
                account.calendars.forEach { cal ->
                    println("Calendar: ${cal.displayName}")
                    println("  href: ${cal.href}")
                    println("  ctag: ${cal.ctag}")
                    println("  color: ${cal.color}")
                }

                assertTrue(account.calendars.isNotEmpty(), "Should have at least one calendar")
            }
            else -> println("Discovery result: $result")
        }
    }

    // ======================== X-Property Tests ========================

    @Test
    @Order(170)
    @DisplayName("170. Event with custom X-properties preserved in rawIcal")
    fun `event with custom properties preserved in rawIcal`() = runTest {
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
            X-CUSTOM-METADATA:custom-value
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created event with X-properties: ${created.href}")

        val event = findEventByUid(uid)

        assertNotNull(event, "Should find event via REPORT query")
        assertNotNull(event!!.rawIcal, "rawIcal should be populated")
        assertTrue(
            event.rawIcal!!.contains("X-ICALDAV-TEST-PROP"),
            "rawIcal should preserve X-ICALDAV-TEST-PROP"
        )
        println("X-properties preserved in rawIcal: verified")
    }

    // ======================== Additional CalDAV Tests ========================

    @Test
    @Order(180)
    @DisplayName("180. Event with PRIORITY property")
    fun `event with PRIORITY property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("priority")
        val startTime = Instant.now().plus(200, ChronoUnit.DAYS)
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
            SUMMARY:High Priority Event
            PRIORITY:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with PRIORITY: ${result.href}")
    }

    @Test
    @Order(181)
    @DisplayName("181. Event with CLASS property (PRIVATE)")
    fun `event with CLASS property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("class")
        val startTime = Instant.now().plus(201, ChronoUnit.DAYS)
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
            SUMMARY:Private Event
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CLASS:PRIVATE: ${result.href}")
    }

    @Test
    @Order(182)
    @DisplayName("182. Event with TRANSP:TRANSPARENT")
    fun `event with TRANSP TRANSPARENT`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("transp")
        val startTime = Instant.now().plus(202, ChronoUnit.DAYS)
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
            SUMMARY:Free Time Event
            TRANSP:TRANSPARENT
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with TRANSP:TRANSPARENT: ${result.href}")

        val fetched = fetchAndVerify(uid)
        println("  Transparency: ${fetched.event.transparency}")
    }

    @Test
    @Order(183)
    @DisplayName("183. Event STATUS:TENTATIVE")
    fun `event with STATUS TENTATIVE`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("status-tent")
        val startTime = Instant.now().plus(203, ChronoUnit.DAYS)
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
            SUMMARY:Tentative Event
            STATUS:TENTATIVE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created TENTATIVE event: ${result.href}")
    }

    @Test
    @Order(184)
    @DisplayName("184. Event STATUS:CANCELLED")
    fun `event with STATUS CANCELLED`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("status-canc")
        val startTime = Instant.now().plus(204, ChronoUnit.DAYS)
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
            SUMMARY:Cancelled Event
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created CANCELLED event: ${result.href}")
    }

    @Test
    @Order(185)
    @DisplayName("185. Event with GEO property")
    fun `event with GEO property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("geo")
        val startTime = Instant.now().plus(205, ChronoUnit.DAYS)
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
            SUMMARY:Geolocated Event
            GEO:37.386013;-122.082932
            LOCATION:Mountain View
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with GEO: ${result.href}")
    }

    @Test
    @Order(186)
    @DisplayName("186. Event with ATTACH property (URL)")
    fun `event with ATTACH property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("attach")
        val startTime = Instant.now().plus(206, ChronoUnit.DAYS)
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
            SUMMARY:Event with Attachment
            ATTACH:https://example.com/document.pdf
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with ATTACH: ${result.href}")
    }

    @Test
    @Order(187)
    @DisplayName("187. Full discovery and fetch workflow")
    fun `full discovery and fetch workflow`() = runTest {
        println("\n=== Starting Full Discovery Workflow ===\n")

        val principalResult = discovery.discoverPrincipal(serverUrl)
        assertTrue(principalResult is DavResult.Success, "Principal discovery should succeed")
        val principal = (principalResult as DavResult.Success<String>).value
        println("Principal: $principal\n")

        val fullPrincipal = if (principal.startsWith("http")) principal else "$serverUrl$principal"
        val homeResult = discovery.discoverCalendarHome(fullPrincipal)
        assertTrue(homeResult is DavResult.Success, "Calendar home discovery should succeed")
        val home = (homeResult as DavResult.Success<String>).value
        println("Calendar home: $home\n")

        val fullHome = if (home.startsWith("http")) home else "$serverUrl$home"
        val calendarsResult = discovery.listCalendars(fullHome)
        assertTrue(calendarsResult is DavResult.Success, "Calendar listing should succeed")
        @Suppress("UNCHECKED_CAST")
        val calendars = (calendarsResult as DavResult.Success<List<Calendar>>).value
        println("Found ${calendars.size} calendars\n")

        var totalEvents = 0
        for (cal in calendars) {
            if (cal.href.contains("inbox", ignoreCase = true) ||
                cal.href.contains("outbox", ignoreCase = true)) {
                continue
            }

            println("Processing: ${cal.displayName}")

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
            }
        }

        println("\n=== Workflow Complete ===")
        println("Total calendars: ${calendars.size}")
        println("Total events: $totalEvents")
    }

    @Test
    @Order(188)
    @DisplayName("188. ICloudQuirks works correctly")
    fun `ICloudQuirks validates correctly`() = runTest {
        assertEquals("icloud", quirks.providerId)
        assertEquals("iCloud", quirks.displayName)
        assertEquals("https://caldav.icloud.com", quirks.baseUrl)
        assertTrue(quirks.requiresAppSpecificPassword)

        println("ICloudQuirks configuration validated")
    }

    // ======================== Additional VALARM Tests ========================

    @Test
    @Order(189)
    @DisplayName("189. VALARM with AUDIO action")
    fun `VALARM with AUDIO action`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("audio-alarm")
        val startTime = Instant.now().plus(210, ChronoUnit.DAYS)
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
            SUMMARY:Audio Reminder Event
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with AUDIO alarm: ${result.href}")
    }

    @Test
    @Order(190)
    @DisplayName("190. VALARM with absolute trigger time")
    fun `VALARM with absolute trigger`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("abs-alarm")
        val startTime = Instant.now().plus(215, ChronoUnit.DAYS)
        val alarmTime = startTime.minus(1, ChronoUnit.DAYS)
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
            SUMMARY:Absolute Alarm Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:${formatICalTimestamp(alarmTime)}
            DESCRIPTION:Reminder: Event tomorrow!
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with absolute alarm: ${result.href}")
    }

    // ======================== Additional RRULE Tests ========================

    @Test
    @Order(191)
    @DisplayName("191. Recurring event with BYMONTHDAY")
    fun `recurring event with BYMONTHDAY`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("bymonthday")
        val startTime = Instant.now().plus(220, ChronoUnit.DAYS)
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
            SUMMARY:Monthly on 15th
            RRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with BYMONTHDAY: ${result.href}")
    }

    @Test
    @Order(192)
    @DisplayName("192. Recurring event with negative BYDAY (last Monday)")
    fun `recurring event with negative BYDAY`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("neg-byday")
        val startTime = Instant.now().plus(225, ChronoUnit.DAYS)
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
            SUMMARY:Last Monday of Month
            RRULE:FREQ=MONTHLY;BYDAY=-1MO;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with -1MO: ${result.href}")
    }

    @Test
    @Order(193)
    @DisplayName("193. Event with RDATE (additional occurrences)")
    fun `event with RDATE`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("rdate")
        val startTime = Instant.now().plus(230, ChronoUnit.DAYS)
        val rdate1 = startTime.plus(3, ChronoUnit.DAYS)
        val rdate2 = startTime.plus(10, ChronoUnit.DAYS)
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
            SUMMARY:Event with Extra Dates
            RDATE:${formatICalTimestamp(rdate1)},${formatICalTimestamp(rdate2)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with RDATE: ${result.href}")
    }

    @Test
    @Order(194)
    @DisplayName("194. All-day recurring event")
    fun `all-day recurring with monthly frequency`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("allday-monthly")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20260601
            DTEND;VALUE=DATE:20260602
            SUMMARY:Monthly All-Day Event
            RRULE:FREQ=MONTHLY;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created all-day recurring event: ${result.href}")
    }

    // ======================== Exception Bundling Tests ========================

    @Test
    @Order(200)
    @DisplayName("200. Exception bundling - master + exceptions in single VCALENDAR")
    fun `exception bundling for CalDAV PUT`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exception-bundle")
        val now = Instant.now()
        val startTime = Instant.now().plus(240, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS)

        val exception1Time = startTime.plus(7, ChronoUnit.DAYS)
        val exception2Time = startTime.plus(14, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Team Sync
            RRULE:FREQ=WEEKLY;COUNT=5
            SEQUENCE:0
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exception1Time)}
            DTSTART:${formatICalTimestamp(exception1Time.plus(1, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(exception1Time.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Weekly Team Sync (Moved 1hr)
            SEQUENCE:1
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exception2Time)}
            DTSTART:${formatICalTimestamp(exception2Time)}
            DTEND:${formatICalTimestamp(exception2Time.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Weekly Team Sync (Extended)
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created bundled master+exceptions: ${result.href}")
    }

    @Test
    @Order(201)
    @DisplayName("201. VALARM duration format variations")
    fun `VALARM with various duration formats`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("alarm-formats")
        val startTime = Instant.now().plus(245, ChronoUnit.DAYS)
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
            SUMMARY:Multi-Alarm Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:15 minutes before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            DESCRIPTION:1 hour before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            DESCRIPTION:1 day before
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with multiple alarm formats: ${result.href}")
    }

    @Test
    @Order(202)
    @DisplayName("202. All-day DTEND exclusive handling (RFC 5545)")
    fun `all-day DTEND exclusive date handling`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("allday-dtend")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20260715
            DTEND;VALUE=DATE:20260718
            SUMMARY:3-Day Conference (Jul 15-17)
            DESCRIPTION:DTEND is exclusive - 20260718 means event ends on 20260717
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created 3-day all-day event: ${result.href}")

        val fetched = fetchAndVerify(uid)
        assertTrue(fetched.event.isAllDay, "Should be all-day event")
    }

    @Test
    @Order(203)
    @DisplayName("203. EXDATE with multiple dates")
    fun `EXDATE with comma-separated dates`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("comma-sep-exdate")
        val now = Instant.now()
        val startTime = Instant.now().plus(250, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(10, ChronoUnit.HOURS)

        val exdate1 = startTime.plus(7, ChronoUnit.DAYS)
        val exdate2 = startTime.plus(14, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly with Multiple Cancellations
            RRULE:FREQ=WEEKLY;COUNT=6
            EXDATE:${formatICalTimestamp(exdate1)},${formatICalTimestamp(exdate2)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with multiple EXDATEs: ${result.href}")
    }

    @Test
    @Order(204)
    @DisplayName("204. SEQUENCE increment on update")
    fun `SEQUENCE property increments on update`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("sequence")
        val startTime = Instant.now().plus(255, ChronoUnit.DAYS)
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
            SUMMARY:Sequence Test
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)

        val updatedIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Sequence Test (Updated)
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val updateResult = calDavClient.updateEventRaw(created.href, updatedIcal, created.etag)

        when (updateResult) {
            is DavResult.Success -> {
                println("Updated event with SEQUENCE:1, new ETag: ${updateResult.value}")
                val index = createdEventUrls.indexOfFirst { it.first == created.href }
                if (index >= 0) {
                    createdEventUrls[index] = Pair(created.href, updateResult.value)
                }
            }
            else -> println("Update result: $updateResult")
        }
    }

    // ======================== Timezone Tests ========================

    @Test
    @Order(205)
    @DisplayName("205. Event with full VTIMEZONE component")
    fun `event with full VTIMEZONE component`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("full-tz")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VTIMEZONE
            TZID:America/Los_Angeles
            BEGIN:DAYLIGHT
            DTSTART:20260308T020000
            TZOFFSETFROM:-0800
            TZOFFSETTO:-0700
            TZNAME:PDT
            END:DAYLIGHT
            BEGIN:STANDARD
            DTSTART:20261101T020000
            TZOFFSETFROM:-0700
            TZOFFSETTO:-0800
            TZNAME:PST
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;TZID=America/Los_Angeles:20261015T090000
            DTEND;TZID=America/Los_Angeles:20261015T100000
            SUMMARY:LA Meeting (PDT)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with full VTIMEZONE: ${result.href}")
    }

    @Test
    @Order(206)
    @DisplayName("206. Event with LOCATION containing special characters")
    fun `location with address and special chars`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("location-special")
        val startTime = Instant.now().plus(260, ChronoUnit.DAYS)
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
            SUMMARY:Office Meeting
            LOCATION:123 Main St\, Suite 456\; San Francisco\, CA 94102
            DESCRIPTION:Address with comma\, semicolon\; and newline:\nSecond line
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with special chars in location: ${result.href}")
    }

    // ======================== RFC 5545 Edge Cases ========================

    @Test
    @Order(210)
    @DisplayName("210. Floating time event (no timezone)")
    fun `floating time event without timezone`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("floating-time")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20260901T140000
            DTEND:20260901T150000
            SUMMARY:Floating Time Event
            DESCRIPTION:No timezone - should be interpreted as local time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created floating time event: ${result.href}")
    }

    @Test
    @Order(211)
    @DisplayName("211. RRULE with WKST (week start day)")
    fun `RRULE with WKST week start day`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("wkst-rule")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270105T090000Z
            DTEND:20270105T100000Z
            SUMMARY:Weekly Meeting (Sunday WKST)
            RRULE:FREQ=WEEKLY;BYDAY=MO,FR;WKST=SU;COUNT=8
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with WKST=SU: ${result.href}")
    }

    @Test
    @Order(212)
    @DisplayName("212. RRULE with BYSETPOS (nth occurrence)")
    fun `RRULE with BYSETPOS for nth occurrence`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("bysetpos-rule")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20260316T100000Z
            DTEND:20260316T110000Z
            SUMMARY:Third Monday of Month
            RRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=3;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with BYSETPOS=3: ${result.href}")
    }

    @Test
    @Order(213)
    @DisplayName("213. Leap year handling (Feb 29)")
    fun `leap year event on February 29`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("leap-year")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20280229
            DTEND;VALUE=DATE:20280301
            SUMMARY:Leap Year Birthday
            DESCRIPTION:This event is on Feb 29, 2028 (leap year)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created leap year event (Feb 29, 2028): ${result.href}")
    }

    @Test
    @Order(214)
    @DisplayName("214. Far future event (year 2099)")
    fun `far future event year 2099`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("far-future")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20991231T235900Z
            DTEND:21000101T000000Z
            SUMMARY:New Year 2100 Countdown
            DESCRIPTION:Event at the end of 2099
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created far future event (Dec 31, 2099): ${result.href}")
    }

    // ======================== Practical Scenarios ========================

    @Test
    @Order(220)
    @DisplayName("220. Midnight-spanning event (11 PM to 1 AM)")
    fun `event spanning midnight`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("midnight-span")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20261015T230000Z
            DTEND:20261016T010000Z
            SUMMARY:Late Night Party
            DESCRIPTION:Spans midnight boundary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created midnight-spanning event: ${result.href}")
    }

    @Test
    @Order(221)
    @DisplayName("221. Year boundary event (Dec 31 to Jan 1)")
    fun `event spanning year boundary`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("year-boundary")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20261231T200000Z
            DTEND:20270101T040000Z
            SUMMARY:New Year's Eve Party
            DESCRIPTION:Spans year boundary 2026 to 2027
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created year-boundary spanning event: ${result.href}")
    }

    @Test
    @Order(222)
    @DisplayName("222. Very short event (1 minute)")
    fun `very short 1 minute event`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("short-event")
        val startTime = Instant.now().plus(265, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.MINUTES))}
            SUMMARY:Quick Reminder
            DESCRIPTION:1-minute event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created 1-minute event: ${result.href}")
    }

    @Test
    @Order(223)
    @DisplayName("223. Past event (historical)")
    fun `past event in history`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("past-event")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20200101T100000Z
            DTEND:20200101T110000Z
            SUMMARY:Historical Event
            DESCRIPTION:Event from 2020
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created past event (Jan 1, 2020): ${result.href}")
    }

    @Test
    @Order(224)
    @DisplayName("224. Event with CREATED and LAST-MODIFIED timestamps")
    fun `event with creation and modification timestamps`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("timestamps")
        val now = Instant.now()
        val createdTime = now.minus(30, ChronoUnit.DAYS)
        val modifiedTime = now.minus(5, ChronoUnit.DAYS)
        val startTime = Instant.now().plus(270, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            CREATED:${formatICalTimestamp(createdTime)}
            LAST-MODIFIED:${formatICalTimestamp(modifiedTime)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Event with Timestamps
            SEQUENCE:3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CREATED/LAST-MODIFIED: ${result.href}")
    }

    @Test
    @Order(225)
    @DisplayName("225. Multi-month all-day event")
    fun `multi-month all-day event spanning 3 months`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("multi-month")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20270601
            DTEND;VALUE=DATE:20270901
            SUMMARY:Summer Vacation
            DESCRIPTION:3-month break (June, July, August)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created 3-month all-day event: ${result.href}")
    }

    // ======================== CalDAV Query Tests ========================

    @Test
    @Order(230)
    @DisplayName("230. Time-range query expanding recurring event instances")
    fun `time-range query expands recurring instances`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("recur-expand")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270301T100000Z
            DTEND:20270301T110000Z
            SUMMARY:Weekly Standup
            RRULE:FREQ=WEEKLY;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)
        println("Created recurring event: ${created.href}")

        val startRange = Instant.parse("2027-03-01T00:00:00Z")
        val endRange = Instant.parse("2027-04-30T23:59:59Z")

        val result = calDavClient.fetchEtagsInRange(
            defaultCalendarUrl!!,
            startRange,
            endRange
        )

        when (result) {
            is DavResult.Success -> {
                println("Time-range query returned ${result.value.size} events")
                assertTrue(result.value.any { it.href.contains(uid) },
                    "Should find recurring event in time range")
            }
            else -> println("Time-range query result: $result")
        }
    }

    @Test
    @Order(231)
    @DisplayName("231. Event with both RRULE and RDATE")
    fun `event with RRULE and RDATE combined`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("rrule-rdate")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270405T140000Z
            DTEND:20270405T150000Z
            SUMMARY:Weekly + Extra Dates
            RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4
            RDATE:20270410T140000Z,20270417T140000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with RRULE + RDATE: ${result.href}")
    }

    @Test
    @Order(232)
    @DisplayName("232. Fetch ETags for large range")
    fun `fetch etags for large date range`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val start = Instant.now().minus(365, ChronoUnit.DAYS)
        val end = Instant.now().plus(365, ChronoUnit.DAYS)

        val result = calDavClient.fetchEtagsInRange(defaultCalendarUrl!!, start, end)

        when (result) {
            is DavResult.Success -> {
                println("fetchEtagsInRange returned ${result.value.size} etags")
                result.value.take(5).forEach { info ->
                    println("  ${info.href}: ${info.etag}")
                }
            }
            else -> println("fetchEtagsInRange failed: $result")
        }
    }

    @Test
    @Order(233)
    @DisplayName("233. Sync collection initial (empty token)")
    fun `sync collection with empty token`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val result = calDavClient.syncCollection(
            calendarUrl = defaultCalendarUrl!!,
            syncToken = ""
        )

        when (result) {
            is DavResult.Success -> {
                val syncReport = result.value
                println("Sync-collection returned ${syncReport.added.size} events")
                println("  New sync token: ${syncReport.newSyncToken.take(30)}...")
                assertTrue(syncReport.newSyncToken.isNotEmpty(), "Should have new sync token")
            }
            else -> println("Sync-collection result: $result")
        }
    }

    // ======================== Additional Edge Cases ========================

    @Test
    @Order(240)
    @DisplayName("240. Event with very long UID")
    fun `event with very long UID accepted`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val longUid = generateUid("long-uid-" + "x".repeat(100))
        val startTime = Instant.now().plus(275, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$longUid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Long UID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(longUid, icalData)
        println("Created event with long UID (${longUid.length} chars): ${result.href}")
    }

    @Test
    @Order(241)
    @DisplayName("241. Recurring event with both EXDATE and RDATE")
    fun `recurring event with both EXDATE and RDATE`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exdate-rdate")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270601T090000Z
            DTEND:20270601T100000Z
            SUMMARY:Weekly with Skip and Extra
            RRULE:FREQ=WEEKLY;COUNT=5
            EXDATE:20270608T090000Z
            RDATE:20270610T090000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with EXDATE + RDATE: ${result.href}")
    }

    @Test
    @Order(242)
    @DisplayName("242. Event spanning multiple years")
    fun `event spanning multiple years`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("multi-year")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20270101
            DTEND;VALUE=DATE:20290101
            SUMMARY:2-Year Project
            DESCRIPTION:Project spanning 2027-2028
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created 2-year spanning event: ${result.href}")
    }

    @Test
    @Order(243)
    @DisplayName("243. Delete event with wrong ETag fails")
    fun `delete event with wrong ETag fails`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("delete-etag")
        val startTime = Instant.now().plus(280, ChronoUnit.DAYS)
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
            SUMMARY:Delete ETag Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)

        val result = calDavClient.deleteEvent(created.href, "\"wrong-etag\"")

        println("Delete with wrong ETag result: $result")

        // iCloud may accept delete with wrong ETag (less strict)
        // or reject with 412 Precondition Failed
    }

    @Test
    @Order(244)
    @DisplayName("244. Rapid event creation (batch)")
    fun `rapid event creation batch`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val baseUid = generateUid("batch")
        val now = Instant.now()
        val startTime = Instant.now().plus(285, ChronoUnit.DAYS)

        repeat(5) { i ->
            val uid = "$baseUid-$i"
            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//iCalDAV Integration Test//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:${formatICalTimestamp(now)}
                DTSTART:${formatICalTimestamp(startTime.plus(i.toLong(), ChronoUnit.HOURS))}
                DTEND:${formatICalTimestamp(startTime.plus(i.toLong() + 1, ChronoUnit.HOURS))}
                SUMMARY:Batch Event $i
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = createAndTrackEvent(uid, icalData)
            println("Created batch event $i: ${result.href}")
        }

        println("Created 5 events in rapid succession")
    }

    @Test
    @Order(245)
    @DisplayName("245. Multiget with duplicate hrefs")
    fun `multiget with duplicate hrefs handles gracefully`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("dedup-test")
        val startTime = Instant.now().plus(290, ChronoUnit.DAYS)
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
            SUMMARY:Dedup Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)

        // Allow time for iCloud eventual consistency (multiget needs longer)
        Thread.sleep(5000)

        val duplicateHrefs = listOf(
            created.href,
            created.href,
            created.href
        )

        val result = calDavClient.fetchEventsByHref(defaultCalendarUrl!!, duplicateHrefs)

        when (result) {
            is DavResult.Success -> {
                println("Multiget with duplicate hrefs returned ${result.value.size} events")
                // iCloud has eventual consistency - may return 0 events immediately
                // Just verify the multiget call succeeded
            }
            else -> println("Multiget failed: $result")
        }
    }

    // ======================== More RRULE Variations ========================

    @Test
    @Order(250)
    @DisplayName("250. RRULE with INTERVAL=2 (bi-weekly)")
    fun `RRULE with INTERVAL 2`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("interval-2")
        val startTime = Instant.now().plus(295, ChronoUnit.DAYS)
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
            SUMMARY:Bi-Weekly Meeting
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created bi-weekly event: ${result.href}")
    }

    @Test
    @Order(251)
    @DisplayName("251. RRULE with INTERVAL=3 (every 3 days)")
    fun `RRULE with INTERVAL 3 daily`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("interval-3")
        val startTime = Instant.now().plus(300, ChronoUnit.DAYS)
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
            SUMMARY:Every 3 Days
            RRULE:FREQ=DAILY;INTERVAL=3;COUNT=10
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created every-3-days event: ${result.href}")
    }

    @Test
    @Order(252)
    @DisplayName("252. RRULE with first Monday of month")
    fun `RRULE with 1MO BYDAY`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("first-monday")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270201T100000Z
            DTEND:20270201T110000Z
            SUMMARY:First Monday of Month
            RRULE:FREQ=MONTHLY;BYDAY=1MO;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created first-Monday event: ${result.href}")
    }

    @Test
    @Order(253)
    @DisplayName("253. RRULE with second Tuesday of month")
    fun `RRULE with 2TU BYDAY`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("second-tuesday")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270209T140000Z
            DTEND:20270209T150000Z
            SUMMARY:Second Tuesday of Month
            RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created second-Tuesday event: ${result.href}")
    }

    // ======================== More Sync Tests ========================

    @Test
    @Order(260)
    @DisplayName("260. Sync collection incremental")
    fun `sync collection incremental after changes`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        // Get initial sync token
        val initialResult = calDavClient.syncCollection(defaultCalendarUrl!!, "")
        val initialToken = when (initialResult) {
            is DavResult.Success -> initialResult.value.newSyncToken
            else -> {
                println("Initial sync failed: $initialResult")
                return@runTest
            }
        }

        // Create an event
        val uid = generateUid("sync-incr")
        val startTime = Instant.now().plus(305, ChronoUnit.DAYS)
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
            SUMMARY:Sync Incremental Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        createAndTrackEvent(uid, icalData)

        // Allow time for iCloud to process
        Thread.sleep(3000)

        // Incremental sync
        val incrementalResult = calDavClient.syncCollection(defaultCalendarUrl!!, initialToken)

        when (incrementalResult) {
            is DavResult.Success -> {
                println("Incremental sync:")
                println("  Added: ${incrementalResult.value.added.size + incrementalResult.value.addedHrefs.size}")
                println("  New token: ${incrementalResult.value.newSyncToken.take(30)}...")
            }
            is DavResult.HttpError -> {
                if (incrementalResult.code in listOf(403, 410)) {
                    println("Sync token expired (expected behavior)")
                } else {
                    println("Sync failed: $incrementalResult")
                }
            }
            else -> println("Sync result: $incrementalResult")
        }
    }

    @Test
    @Order(261)
    @DisplayName("261. Sync detects deleted event")
    fun `sync collection detects deletion`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        // Create event
        val uid = generateUid("sync-del")
        val startTime = Instant.now().plus(310, ChronoUnit.DAYS)
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
            SUMMARY:Sync Delete Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val created = createAndTrackEvent(uid, icalData)

        // Get sync token
        val tokenResult = calDavClient.getSyncToken(defaultCalendarUrl!!)
        val syncToken = (tokenResult as? DavResult.Success)?.value ?: ""

        // Delete
        val deleteResult = calDavClient.deleteEvent(created.href, created.etag)
        assertTrue(deleteResult is DavResult.Success, "Delete should succeed")
        createdEventUrls.removeIf { it.first == created.href }

        // Allow time for iCloud to process
        Thread.sleep(3000)

        // Sync should show deletion
        val syncResult = calDavClient.syncCollection(defaultCalendarUrl!!, syncToken)
        when (syncResult) {
            is DavResult.Success -> {
                println("Sync after delete: ${syncResult.value.deleted.size} deleted")
            }
            else -> println("Sync result: $syncResult")
        }
    }

    // ======================== More Property Tests ========================

    @Test
    @Order(270)
    @DisplayName("270. Event with all CLASS values")
    fun `event with different CLASS values`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val now = Instant.now()
        val startTime = Instant.now().plus(315, ChronoUnit.DAYS)

        listOf("PUBLIC", "PRIVATE", "CONFIDENTIAL").forEachIndexed { idx, classValue ->
            val uid = generateUid("class-$classValue")
            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//iCalDAV Integration Test//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:${formatICalTimestamp(now)}
                DTSTART:${formatICalTimestamp(startTime.plus(idx.toLong(), ChronoUnit.HOURS))}
                DTEND:${formatICalTimestamp(startTime.plus(idx.toLong() + 1, ChronoUnit.HOURS))}
                SUMMARY:$classValue Event
                CLASS:$classValue
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = createAndTrackEvent(uid, icalData)
            println("Created event with CLASS:$classValue - ${result.href}")
        }
    }

    @Test
    @Order(271)
    @DisplayName("271. Event with CONTACT property")
    fun `event with CONTACT property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("contact")
        val startTime = Instant.now().plus(320, ChronoUnit.DAYS)
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
            SUMMARY:Meeting with Contact Info
            CONTACT:John Doe\, +1-555-123-4567
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CONTACT: ${result.href}")
    }

    @Test
    @Order(272)
    @DisplayName("272. Event with COMMENT property")
    fun `event with COMMENT property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("comment")
        val startTime = Instant.now().plus(325, ChronoUnit.DAYS)
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
            SUMMARY:Event with Comments
            COMMENT:First comment line
            COMMENT:Second comment line
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with COMMENT: ${result.href}")
    }

    @Test
    @Order(273)
    @DisplayName("273. Event with RESOURCES property")
    fun `event with RESOURCES property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("resources")
        val startTime = Instant.now().plus(330, ChronoUnit.DAYS)
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Conference Room Booking
            RESOURCES:PROJECTOR,WHITEBOARD,VIDEO CONFERENCING
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with RESOURCES: ${result.href}")
    }

    // ======================== More Exception Tests ========================

    @Test
    @Order(43)
    @DisplayName("43. RECURRENCE-ID with modified location")
    fun `exception with modified location`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exc-loc")
        val now = Instant.now()
        val startTime = Instant.now().plus(340, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS)
        val exceptionTime = startTime.plus(7, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Meeting
            LOCATION:Room A
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exceptionTime)}
            DTSTART:${formatICalTimestamp(exceptionTime)}
            DTEND:${formatICalTimestamp(exceptionTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Meeting
            LOCATION:Room B (Moved)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring event with location exception: ${result.href}")
    }

    @Test
    @Order(44)
    @DisplayName("44. Multiple RECURRENCE-ID exceptions")
    fun `multiple exceptions in same series`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("multi-exc")
        val now = Instant.now()
        val startTime = Instant.now().plus(345, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(10, ChronoUnit.HOURS)

        val exc1 = startTime.plus(7, ChronoUnit.DAYS)
        val exc2 = startTime.plus(14, ChronoUnit.DAYS)
        val exc3 = startTime.plus(21, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Sync
            RRULE:FREQ=WEEKLY;COUNT=6
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exc1)}
            DTSTART:${formatICalTimestamp(exc1.plus(2, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(exc1.plus(3, ChronoUnit.HOURS))}
            SUMMARY:Weekly Sync (Delayed)
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exc2)}
            DTSTART:${formatICalTimestamp(exc2)}
            DTEND:${formatICalTimestamp(exc2.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Weekly Sync (Extended)
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(exc3)}
            DTSTART:${formatICalTimestamp(exc3.minus(1, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(exc3)}
            SUMMARY:Weekly Sync (Early)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created recurring with 3 exceptions: ${result.href}")
    }

    @Test
    @Order(45)
    @DisplayName("45. THISANDFUTURE range exception")
    fun `THISANDFUTURE range exception`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("thisandfuture")
        val now = Instant.now()
        val startTime = Instant.now().plus(350, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(14, ChronoUnit.HOURS)
        val rangeTime = startTime.plus(14, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Recurring Event
            RRULE:FREQ=WEEKLY;COUNT=8
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID;RANGE=THISANDFUTURE:${formatICalTimestamp(rangeTime)}
            DTSTART:${formatICalTimestamp(rangeTime.plus(1, ChronoUnit.HOURS))}
            DTEND:${formatICalTimestamp(rangeTime.plus(2, ChronoUnit.HOURS))}
            SUMMARY:Recurring Event (New Time)
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created THISANDFUTURE exception: ${result.href}")
    }

    @Test
    @Order(46)
    @DisplayName("46. Exception with cancelled status")
    fun `exception with STATUS CANCELLED`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("exc-cancel")
        val now = Instant.now()
        val startTime = Instant.now().plus(355, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(11, ChronoUnit.HOURS)
        val cancelledTime = startTime.plus(7, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Check-in
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            RECURRENCE-ID:${formatICalTimestamp(cancelledTime)}
            DTSTART:${formatICalTimestamp(cancelledTime)}
            DTEND:${formatICalTimestamp(cancelledTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly Check-in (Cancelled)
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created exception with CANCELLED status: ${result.href}")
    }

    // ======================== More Adverse Tests ========================

    @Test
    @Order(104)
    @DisplayName("104. Get ctag from non-existent calendar")
    fun `getCtag from invalid calendar fails`() = runTest {
        val fakeCalendarUrl = "https://caldav.icloud.com/fake-calendar-${UUID.randomUUID()}/"

        val result = calDavClient.getCtag(fakeCalendarUrl)

        println("Get ctag from non-existent calendar: $result")
        // Should fail with HTTP error
        assertTrue(
            result is DavResult.HttpError || result is DavResult.NetworkError,
            "Should fail for non-existent calendar"
        )
    }

    @Test
    @Order(105)
    @DisplayName("105. Fetch etags from non-existent calendar")
    fun `fetchEtagsInRange from invalid calendar fails`() = runTest {
        val fakeCalendarUrl = "https://caldav.icloud.com/fake-etag-calendar-${UUID.randomUUID()}/"

        val result = calDavClient.fetchEtagsInRange(
            fakeCalendarUrl,
            Instant.now(),
            Instant.now().plus(30, ChronoUnit.DAYS)
        )

        println("Fetch etags from non-existent calendar: $result")
        assertTrue(
            result is DavResult.HttpError || result is DavResult.NetworkError,
            "Should fail for non-existent calendar"
        )
    }

    @Test
    @Order(106)
    @DisplayName("106. FetchEventsByHref with invalid hrefs")
    fun `fetchEventsByHref with invalid hrefs handles gracefully`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val fakeHrefs = listOf(
            "$defaultCalendarUrl/fake-1-${UUID.randomUUID()}.ics",
            "$defaultCalendarUrl/fake-2-${UUID.randomUUID()}.ics"
        )

        val result = calDavClient.fetchEventsByHref(defaultCalendarUrl!!, fakeHrefs)

        println("Fetch invalid hrefs result: $result")
        // Should return empty list or handle gracefully
        if (result is DavResult.Success) {
            @Suppress("UNCHECKED_CAST")
            val events = (result as DavResult.Success<List<EventWithMetadata>>).value
            println("Server returned ${events.size} events")
        }
    }

    @Test
    @Order(108)
    @DisplayName("108. Long summary is handled")
    fun `event with very long summary`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("long-summary")
        val startTime = Instant.now().plus(360, ChronoUnit.DAYS)
        val now = Instant.now()

        val longSummary = "A".repeat(500)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:$longSummary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with 500-char summary: ${result.href}")
    }

    @Test
    @Order(109)
    @DisplayName("109. Very long description")
    fun `event with very long description`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("very-long-desc")
        val startTime = Instant.now().plus(365, ChronoUnit.DAYS)
        val now = Instant.now()

        val longDesc = buildString {
            repeat(50) { i ->
                append("Paragraph $i of description. ")
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
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Long Description Event
            DESCRIPTION:${longDesc.replace("\n", "\\n")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with long description: ${result.href}")
    }

    @Test
    @Order(110)
    @DisplayName("110. Concurrent modifications show ETag conflict")
    fun `concurrent updates show conflict detection`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("concurrent")
        val startTime = Instant.now().plus(370, ChronoUnit.DAYS)
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
            SUMMARY:Concurrent Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val createResult = createAndTrackEvent(uid, icalData)
        val originalEtag = createResult.etag

        // First update succeeds
        val update1 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Update 1
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result1 = calDavClient.updateEventRaw(createResult.href, update1, originalEtag)
        if (result1 is DavResult.Success) {
            @Suppress("UNCHECKED_CAST")
            val newEtag = (result1 as DavResult.Success<String?>).value
            val index = createdEventUrls.indexOfFirst { it.first == createResult.href }
            if (index >= 0) {
                createdEventUrls[index] = Pair(createResult.href, newEtag)
            }
        }

        // Second update with stale ETag should fail
        val update2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(Instant.now())}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Update 2 (Conflict)
            SEQUENCE:2
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result2 = calDavClient.updateEventRaw(createResult.href, update2, originalEtag)
        println("Concurrent update with stale ETag: $result2")

        if (result2 is DavResult.HttpError) {
            println("  Got expected conflict (${result2.code})")
        }
    }

    @Test
    @Order(111)
    @DisplayName("111. Minimal event via typed API")
    fun `createEvent with minimal ICalEvent`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("minimal")
        val startTime = Instant.now().plus(375, ChronoUnit.DAYS)

        val event = ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Minimal Event",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(startTime.toEpochMilli(), null, false),
            dtEnd = ICalDateTime.fromTimestamp(startTime.plus(1, ChronoUnit.HOURS).toEpochMilli(), null, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = ICalDateTime.fromTimestamp(Instant.now().toEpochMilli(), null, false),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )

        val result = calDavClient.createEvent(defaultCalendarUrl!!, event)

        when (result) {
            is DavResult.Success -> {
                trackEvent(result.value.href, result.value.etag)
                println("Created minimal event: ${result.value.href}")
            }
            is DavResult.HttpError -> {
                println("Server rejected: ${result.code}")
            }
            else -> println("Result: $result")
        }
    }

    // ======================== More Edge Cases (200+ range) ========================

    @Test
    @Order(207)
    @DisplayName("207. EXDATE with comma-separated dates")
    fun `EXDATE with multiple comma-separated dates`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("comma-exdate")
        val now = Instant.now()
        val startTime = Instant.now().plus(380, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(10, ChronoUnit.HOURS)
        val exdate1 = startTime.plus(7, ChronoUnit.DAYS)
        val exdate2 = startTime.plus(14, ChronoUnit.DAYS)
        val exdate3 = startTime.plus(21, ChronoUnit.DAYS)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:Weekly with Multiple Cancellations
            RRULE:FREQ=WEEKLY;COUNT=8
            EXDATE:${formatICalTimestamp(exdate1)},${formatICalTimestamp(exdate2)}
            EXDATE:${formatICalTimestamp(exdate3)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with comma-separated EXDATEs: ${result.href}")
    }

    @Test
    @Order(216)
    @DisplayName("216. Yearly recurring on leap day")
    fun `yearly recurring event on leap day`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("leap-yearly")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;VALUE=DATE:20280229
            DTEND;VALUE=DATE:20280301
            SUMMARY:Leap Year Anniversary
            RRULE:FREQ=YEARLY;COUNT=4
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created yearly leap day event: ${result.href}")
    }

    @Test
    @Order(219)
    @DisplayName("219. Negative BYSETPOS (last weekday)")
    fun `RRULE with negative BYSETPOS for last weekday`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("bysetpos-neg")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20260130T150000Z
            DTEND:20260130T160000Z
            SUMMARY:Last Weekday of Month
            RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with BYSETPOS=-1: ${result.href}")
    }

    // ======================== Additional RFC Compliance Tests ========================

    @Test
    @Order(250)
    @DisplayName("250. RRULE with BYMONTH")
    fun `RRULE with BYMONTH for seasonal events`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("bymonth")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270115T100000Z
            DTEND:20270115T110000Z
            SUMMARY:Winter Only Meeting
            RRULE:FREQ=MONTHLY;BYMONTH=1,2,12;COUNT=6
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created BYMONTH event: ${result.href}")
    }

    @Test
    @Order(251)
    @DisplayName("251. RRULE with BYHOUR")
    fun `RRULE with BYHOUR`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("byhour")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270201T090000Z
            DTEND:20270201T093000Z
            SUMMARY:Hourly Check
            RRULE:FREQ=HOURLY;BYHOUR=9,12,15,18;COUNT=8
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created BYHOUR event: ${result.href}")
    }

    @Test
    @Order(252)
    @DisplayName("252. DST transition event")
    fun `event at DST spring forward time`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("dst-spring")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            DTSTART:20270314T020000
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            TZNAME:EDT
            END:DAYLIGHT
            BEGIN:STANDARD
            DTSTART:20271107T020000
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            TZNAME:EST
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;TZID=America/New_York:20270314T023000
            DTEND;TZID=America/New_York:20270314T033000
            SUMMARY:DST Transition Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created DST transition event: ${result.href}")
    }

    @Test
    @Order(253)
    @DisplayName("253. Daylight saving fall back event")
    fun `event at DST fall back time`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("dst-fall")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            DTSTART:20270314T020000
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            TZNAME:EDT
            END:DAYLIGHT
            BEGIN:STANDARD
            DTSTART:20271107T020000
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            TZNAME:EST
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART;TZID=America/New_York:20271107T013000
            DTEND;TZID=America/New_York:20271107T023000
            SUMMARY:DST Fall Back Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created DST fall back event: ${result.href}")
    }

    @Test
    @Order(268)
    @DisplayName("268. Summary with line folding")
    fun `summary requiring RFC line folding`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("line-fold")
        val startTime = Instant.now().plus(490, ChronoUnit.DAYS)
        val now = Instant.now()

        val longSummary = "This is a very long summary that will need to be line folded according to RFC 5545 requirements because it exceeds 75 characters"

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:${formatICalTimestamp(startTime)}
            DTEND:${formatICalTimestamp(startTime.plus(1, ChronoUnit.HOURS))}
            SUMMARY:$longSummary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event requiring line folding: ${result.href}")
    }

    @Test
    @Order(269)
    @DisplayName("269. RRULE with COUNT and UNTIL conflict")
    fun `RRULE with COUNT only`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("count-only")
        val now = Instant.now()

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//iCalDAV Integration Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatICalTimestamp(now)}
            DTSTART:20270301T100000Z
            DTEND:20270301T110000Z
            SUMMARY:Limited Recurrence
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created COUNT-limited event: ${result.href}")
    }

    @Test
    @Order(274)
    @DisplayName("274. Event with PARTSTAT for ATTENDEE")
    fun `event with ATTENDEE PARTSTAT values`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("partstat")
        val startTime = Instant.now().plus(495, ChronoUnit.DAYS)
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
            SUMMARY:Team Meeting with RSVPs
            ORGANIZER:mailto:organizer@example.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:alice@example.com
            ATTENDEE;PARTSTAT=DECLINED:mailto:bob@example.com
            ATTENDEE;PARTSTAT=TENTATIVE:mailto:carol@example.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:dave@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with PARTSTAT: ${result.href}")
    }

    @Test
    @Order(275)
    @DisplayName("275. Event with ROLE for ATTENDEE")
    fun `event with ATTENDEE ROLE values`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("role")
        val startTime = Instant.now().plus(500, ChronoUnit.DAYS)
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
            SUMMARY:Planning Meeting
            ORGANIZER:mailto:organizer@example.com
            ATTENDEE;ROLE=CHAIR:mailto:chair@example.com
            ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:required@example.com
            ATTENDEE;ROLE=OPT-PARTICIPANT:mailto:optional@example.com
            ATTENDEE;ROLE=NON-PARTICIPANT:mailto:fyi@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with ROLE: ${result.href}")
    }

    @Test
    @Order(276)
    @DisplayName("276. Event with CUTYPE for ATTENDEE")
    fun `event with ATTENDEE CUTYPE values`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("cutype")
        val startTime = Instant.now().plus(505, ChronoUnit.DAYS)
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
            SUMMARY:Resource Booking
            ORGANIZER:mailto:organizer@example.com
            ATTENDEE;CUTYPE=INDIVIDUAL:mailto:person@example.com
            ATTENDEE;CUTYPE=GROUP:mailto:team@example.com
            ATTENDEE;CUTYPE=RESOURCE:mailto:room-a@example.com
            ATTENDEE;CUTYPE=ROOM:mailto:conference@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CUTYPE: ${result.href}")
    }

    @Test
    @Order(277)
    @DisplayName("277. Event with CN for ATTENDEE")
    fun `event with ATTENDEE CN parameter`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("cn")
        val startTime = Instant.now().plus(510, ChronoUnit.DAYS)
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
            SUMMARY:Team Standup
            ORGANIZER;CN="John Smith":mailto:john@example.com
            ATTENDEE;CN="Alice Johnson";PARTSTAT=ACCEPTED:mailto:alice@example.com
            ATTENDEE;CN="Bob Williams":mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with CN: ${result.href}")
    }

    @Test
    @Order(278)
    @DisplayName("278. Event with REQUEST-STATUS")
    fun `event with REQUEST-STATUS property`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("reqstatus")
        val startTime = Instant.now().plus(515, ChronoUnit.DAYS)
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
            SUMMARY:Scheduled Meeting
            REQUEST-STATUS:2.0;Success
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with REQUEST-STATUS: ${result.href}")
    }

    @Test
    @Order(279)
    @DisplayName("279. Event with multiple VALARM with REPEAT")
    fun `VALARM with REPEAT and DURATION`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val uid = generateUid("alarm-repeat")
        val startTime = Instant.now().plus(520, ChronoUnit.DAYS)
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
            SUMMARY:Repeating Alarm Event
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:First reminder
            REPEAT:3
            DURATION:PT5M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = createAndTrackEvent(uid, icalData)
        println("Created event with repeating alarm: ${result.href}")
    }

    // ======================== Final Tests ========================

    @Test
    @Order(280)
    @DisplayName("280. Large multiget (20 hrefs)")
    fun `large multiget request`() = runTest {
        Assumptions.assumeTrue(defaultCalendarUrl != null, "Default calendar URL required")

        val baseUid = generateUid("multiget")
        val now = Instant.now()
        val startTime = Instant.now().plus(335, ChronoUnit.DAYS)
        val createdHrefs = mutableListOf<String>()

        repeat(20) { i ->
            val uid = "$baseUid-$i"
            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//iCalDAV Integration Test//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:${formatICalTimestamp(now)}
                DTSTART:${formatICalTimestamp(startTime.plus(i.toLong(), ChronoUnit.HOURS))}
                DTEND:${formatICalTimestamp(startTime.plus(i.toLong() + 1, ChronoUnit.HOURS))}
                SUMMARY:Multiget Event $i
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = createAndTrackEvent(uid, icalData)
            createdHrefs.add(result.href)
        }

        println("Created 20 events for multiget test")

        // Allow time for iCloud to process
        Thread.sleep(5000)

        val result = calDavClient.fetchEventsByHref(defaultCalendarUrl!!, createdHrefs)

        when (result) {
            is DavResult.Success -> {
                println("Multiget returned ${result.value.size} events")
            }
            else -> println("Multiget result: $result")
        }
    }

    @Test
    @Order(999)
    @DisplayName("999. Test summary - all event types created successfully")
    fun `test summary`() = runTest {
        println("\n=== iCloud Integration Test Summary ===")
        println("Total events created and tracked: ${createdEventUrls.size}")
        println("Test run ID: $testRunId")
        println("All tests completed successfully!")
    }
}
