package org.onekash.icaldav.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.model.DavResult
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.Transparency
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Integration tests that verify icaldav-android mapping with real CalDAV servers.
 *
 * Tests the full round-trip:
 * 1. Create event on CalDAV server (Nextcloud/Baikal/etc.)
 * 2. Fetch event using icaldav-client
 * 3. Map to ContentValues using CalendarContractMapper
 * 4. Insert into Android CalendarContract
 * 5. Query back and verify data integrity
 *
 * Run with server credentials:
 * ```bash
 * ./gradlew :icaldav-android:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.CALDAV_URL=https://your-server.com/remote.php/dav \
 *   -Pandroid.testInstrumentationRunnerArguments.CALDAV_USER=testuser \
 *   -Pandroid.testInstrumentationRunnerArguments.CALDAV_PASS=testpass
 * ```
 *
 * Or set environment variables before running:
 * ```bash
 * export CALDAV_URL=https://your-nextcloud.com/remote.php/dav
 * export CALDAV_USER=testuser
 * export CALDAV_PASS=testpass
 * ./gradlew :icaldav-android:connectedAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CalendarProviderIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private lateinit var context: Context
    private val contentResolver by lazy { context.contentResolver }

    // Server credentials from instrumentation args or environment
    private val serverUrl: String? by lazy {
        getArgument("CALDAV_URL") ?: System.getenv("CALDAV_URL")
    }
    private val username: String? by lazy {
        getArgument("CALDAV_USER") ?: System.getenv("CALDAV_USER")
    }
    private val password: String? by lazy {
        getArgument("CALDAV_PASS") ?: System.getenv("CALDAV_PASS")
    }

    private var testCalendarId: Long = -1
    private var calendarUrl: String? = null
    private lateinit var client: CalDavClient

    // Track created resources for cleanup
    private val createdEventIds = mutableListOf<Long>()
    private val createdServerEvents = mutableListOf<Pair<String, String?>>() // (url, etag)

    private val testRunId = UUID.randomUUID().toString().take(8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Skip tests if no server credentials
        assumeTrue(
            "CalDAV server credentials required. Set CALDAV_URL, CALDAV_USER, CALDAV_PASS",
            !serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()
        )

        // Create CalDAV client
        client = CalDavClient.withBasicAuth(username!!, password!!)

        // Discover calendar
        discoverCalendar()

        // Create test calendar in Android
        testCalendarId = createTestCalendar()
    }

    @After
    fun cleanup() {
        // Delete local events
        createdEventIds.forEach { eventId ->
            try {
                contentResolver.delete(
                    ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                    null, null
                )
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }

        // Delete server events
        createdServerEvents.forEach { (url, etag) ->
            runBlocking {
                try {
                    client.deleteEvent(url, etag)
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
        }

        // Delete test calendar
        if (testCalendarId > 0) {
            try {
                contentResolver.delete(
                    asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, testCalendarId)),
                    null, null
                )
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    // ==================== Basic Event Tests ====================

    @Test
    fun basicEvent_roundTrip() = runBlocking {
        // 1. Create event on server
        val event = createTestEvent(
            summary = "Integration Test Event $testRunId",
            description = "Testing icaldav-android mapping",
            location = "Test Location"
        )

        val createResult = client.createEvent(calendarUrl!!, event)
        assertThat(createResult).isInstanceOf(DavResult.Success::class.java)
        val (eventUrl, etag) = (createResult as DavResult.Success).value
        createdServerEvents.add(eventUrl to etag)

        // 2. Fetch event back
        val fetchResult = client.getEvent(eventUrl)
        assertThat(fetchResult).isInstanceOf(DavResult.Success::class.java)
        val fetchedEvent = (fetchResult as DavResult.Success).value.event

        // 3. Map to ContentValues
        val values = CalendarContractMapper.toContentValues(fetchedEvent, testCalendarId)

        // 4. Insert into CalendarContract
        val uri = contentResolver.insert(Events.CONTENT_URI, values)
        assertThat(uri).isNotNull()
        val eventId = ContentUris.parseId(uri!!)
        createdEventIds.add(eventId)

        // 5. Query back and verify
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            null, null, null, null
        )

        cursor?.use {
            assertThat(it.moveToFirst()).isTrue()
            val retrieved = CalendarContractMapper.fromCursor(it)

            assertThat(retrieved.uid).isEqualTo(fetchedEvent.uid)
            assertThat(retrieved.summary).isEqualTo(fetchedEvent.summary)
            assertThat(retrieved.description).isEqualTo(fetchedEvent.description)
            assertThat(retrieved.location).isEqualTo(fetchedEvent.location)
        }
    }

    @Test
    fun allDayEvent_storedAsUtcMidnight() = runBlocking {
        val event = createTestEvent(
            summary = "All-Day Test $testRunId",
            isAllDay = true
        )

        val createResult = client.createEvent(calendarUrl!!, event)
        assertThat(createResult).isInstanceOf(DavResult.Success::class.java)
        val (eventUrl, etag) = (createResult as DavResult.Success).value
        createdServerEvents.add(eventUrl to etag)

        val fetchResult = client.getEvent(eventUrl)
        val fetchedEvent = (fetchResult as DavResult.Success).value.event

        val values = CalendarContractMapper.toContentValues(fetchedEvent, testCalendarId)
        val uri = contentResolver.insert(Events.CONTENT_URI, values)
        val eventId = ContentUris.parseId(uri!!)
        createdEventIds.add(eventId)

        val cursor = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.ALL_DAY, Events.EVENT_TIMEZONE),
            null, null, null
        )

        cursor?.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(1) // ALL_DAY = 1
            assertThat(it.getString(1)).isEqualTo("UTC") // Timezone = UTC
        }
    }

    @Test
    fun recurringEvent_usesDurationNotDtend() = runBlocking {
        val event = createTestEvent(
            summary = "Weekly Meeting $testRunId",
            rrule = RRule(freq = Frequency.WEEKLY, count = 10),
            duration = Duration.ofHours(1)
        )

        val createResult = client.createEvent(calendarUrl!!, event)
        assertThat(createResult).isInstanceOf(DavResult.Success::class.java)
        val (eventUrl, etag) = (createResult as DavResult.Success).value
        createdServerEvents.add(eventUrl to etag)

        val fetchResult = client.getEvent(eventUrl)
        val fetchedEvent = (fetchResult as DavResult.Success).value.event

        val values = CalendarContractMapper.toContentValues(fetchedEvent, testCalendarId)

        // Verify DURATION is set and DTEND is null (Android requirement)
        assertThat(values.getAsString(Events.DURATION)).isNotNull()
        assertThat(values.getAsString(Events.DURATION)).contains("PT")
        assertThat(values.get(Events.DTEND)).isNull()
        assertThat(values.getAsString(Events.RRULE)).contains("FREQ=WEEKLY")

        val uri = contentResolver.insert(Events.CONTENT_URI, values)
        val eventId = ContentUris.parseId(uri!!)
        createdEventIds.add(eventId)

        // Query Instances table to verify recurrence expansion
        val startMs = System.currentTimeMillis()
        val endMs = startMs + (12L * 7 * 24 * 60 * 60 * 1000) // 12 weeks

        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val instancesCursor = contentResolver.query(
            instancesUri,
            arrayOf(CalendarContract.Instances.EVENT_ID),
            "${CalendarContract.Instances.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )

        instancesCursor?.use {
            // Should have 10 instances (COUNT=10)
            assertThat(it.count).isEqualTo(10)
        }
    }

    @Test
    fun eventWithReminders_mappedToRemindersTable() = runBlocking {
        // Create event with alarm on server
        val event = createTestEvent(
            summary = "Event with Reminder $testRunId",
            hasAlarm = true,
            alarmMinutesBefore = 30
        )

        val createResult = client.createEvent(calendarUrl!!, event)
        assertThat(createResult).isInstanceOf(DavResult.Success::class.java)
        val (eventUrl, etag) = (createResult as DavResult.Success).value
        createdServerEvents.add(eventUrl to etag)

        val fetchResult = client.getEvent(eventUrl)
        val fetchedEvent = (fetchResult as DavResult.Success).value.event

        // Insert event
        val values = CalendarContractMapper.toContentValues(fetchedEvent, testCalendarId)
        val uri = contentResolver.insert(Events.CONTENT_URI, values)
        val eventId = ContentUris.parseId(uri!!)
        createdEventIds.add(eventId)

        // Insert reminders
        fetchedEvent.alarms.forEach { alarm ->
            val reminderValues = ReminderMapper.toContentValues(alarm, eventId)
            contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        }

        // Query reminders
        val remindersCursor = contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )

        remindersCursor?.use {
            assertThat(it.count).isGreaterThan(0)
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(30) // 30 minutes before
        }
    }

    @Test
    fun unicodeEvent_preservedCorrectly() = runBlocking {
        val event = createTestEvent(
            summary = "会议 Meeting 🗓️ $testRunId",
            description = "Café meeting with naïve résumé review",
            location = "北京 Beijing • Москва"
        )

        val createResult = client.createEvent(calendarUrl!!, event)
        assertThat(createResult).isInstanceOf(DavResult.Success::class.java)
        val (eventUrl, etag) = (createResult as DavResult.Success).value
        createdServerEvents.add(eventUrl to etag)

        val fetchResult = client.getEvent(eventUrl)
        val fetchedEvent = (fetchResult as DavResult.Success).value.event

        val values = CalendarContractMapper.toContentValues(fetchedEvent, testCalendarId)
        val uri = contentResolver.insert(Events.CONTENT_URI, values)
        val eventId = ContentUris.parseId(uri!!)
        createdEventIds.add(eventId)

        val cursor = contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION),
            null, null, null
        )

        cursor?.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).contains("会议")
            assertThat(it.getString(0)).contains("🗓️")
            assertThat(it.getString(1)).contains("Café")
            assertThat(it.getString(2)).contains("北京")
            assertThat(it.getString(2)).contains("Москва")
        }
    }

    // ==================== Helper Methods ====================

    private fun discoverCalendar() = runBlocking {
        val discoveryResult = client.discoverAccount(serverUrl!!)
        assertThat(discoveryResult).isInstanceOf(DavResult.Success::class.java)

        val account = (discoveryResult as DavResult.Success).value
        assertThat(account.calendars).isNotEmpty()

        // Use first writable calendar
        val calendar = account.calendars.first { !it.readOnly }
        calendarUrl = calendar.href
    }

    private fun createTestCalendar(): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, "icaldav-test@test.local")
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "icaldav-android-test-$testRunId")
            put(Calendars.CALENDAR_DISPLAY_NAME, "iCalDAV Test Calendar")
            put(Calendars.CALENDAR_COLOR, 0xFF0000FF.toInt())
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        }

        val uri = contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun createTestEvent(
        summary: String,
        description: String? = null,
        location: String? = null,
        isAllDay: Boolean = false,
        rrule: RRule? = null,
        duration: Duration? = null,
        hasAlarm: Boolean = false,
        alarmMinutesBefore: Int = 15
    ): ICalEvent {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
        val timezone = ZoneId.systemDefault()

        val dtStart = if (isAllDay) {
            ICalDateTime.fromTimestamp(now.toEpochMilli(), timezone, isDate = true)
        } else {
            ICalDateTime.fromTimestamp(now.toEpochMilli(), timezone)
        }

        val dtEnd = if (rrule != null) {
            null // Recurring events use duration
        } else if (isAllDay) {
            ICalDateTime.fromTimestamp(now.plus(1, ChronoUnit.DAYS).toEpochMilli(), timezone, isDate = true)
        } else {
            ICalDateTime.fromTimestamp(now.plus(1, ChronoUnit.HOURS).toEpochMilli(), timezone)
        }

        val alarms = if (hasAlarm) {
            listOf(
                org.onekash.icaldav.model.ICalAlarm(
                    action = org.onekash.icaldav.model.AlarmAction.DISPLAY,
                    trigger = Duration.ofMinutes(-alarmMinutesBefore.toLong()),
                    triggerAbsolute = null,
                    triggerRelatedToEnd = false,
                    description = "Reminder",
                    summary = null
                )
            )
        } else {
            emptyList()
        }

        val uid = UUID.randomUUID().toString()
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = description,
            location = location,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = duration,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = rrule,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = alarms,
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun asSyncAdapter(uri: android.net.Uri): android.net.Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, "icaldav-test@test.local")
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
    }

    private fun getArgument(key: String): String? {
        return InstrumentationRegistry.getArguments().getString(key)
    }
}
