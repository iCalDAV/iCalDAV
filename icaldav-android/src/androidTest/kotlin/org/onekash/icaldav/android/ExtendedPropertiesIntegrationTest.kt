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
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Transparency
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration tests for [ExtendedPropertiesMapper] with real CalendarProvider.
 *
 * Tests verify that extended properties (X-* and CATEGORIES) are correctly
 * stored and retrieved from the ExtendedProperties table.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ExtendedPropertiesIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private lateinit var context: Context
    private val contentResolver by lazy { context.contentResolver }
    private lateinit var helper: CalendarProviderHelper

    private val accountName = "extprops-test@test.local"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL

    private var testCalendarId: Long = -1
    private var testEventId: Long = -1
    private val testRunId = UUID.randomUUID().toString().take(8)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        helper = CalendarProviderHelper(contentResolver, accountName, accountType)
        testCalendarId = createTestCalendar()
        testEventId = createTestEvent()
    }

    @After
    fun cleanup() {
        if (testCalendarId > 0) {
            // Delete all events and extended properties
            contentResolver.delete(
                asSyncAdapter(Events.CONTENT_URI),
                "${Events.CALENDAR_ID} = ?",
                arrayOf(testCalendarId.toString())
            )

            // Delete calendar
            contentResolver.delete(
                asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, testCalendarId)),
                null, null
            )
        }
    }

    // ==================== CATEGORIES Tests ====================

    @Test
    fun insertCategories_singleCategory() {
        val categories = listOf("Work")
        val result = helper.insertCategories(categories, testEventId)

        assertThat(result).isGreaterThan(0L)

        val retrieved = helper.queryCategories(testEventId)
        assertThat(retrieved).containsExactly("Work")
    }

    @Test
    fun insertCategories_multipleCategories() {
        val categories = listOf("Work", "Important", "Meeting")
        helper.insertCategories(categories, testEventId)

        val retrieved = helper.queryCategories(testEventId)
        assertThat(retrieved).containsExactly("Work", "Important", "Meeting")
    }

    @Test
    fun queryCategories_emptyWhenNotSet() {
        val retrieved = helper.queryCategories(testEventId)
        assertThat(retrieved).isEmpty()
    }

    // ==================== X-* Property Tests ====================

    @Test
    fun insertExtendedProperty_xProperty() {
        helper.insertExtendedProperty("X-MY-CUSTOM-PROP", "custom-value", testEventId)

        val properties = helper.queryExtendedProperties(testEventId)
        assertThat(properties).containsKey("x-my-custom-prop") // Stored lowercase
        assertThat(properties["x-my-custom-prop"]).isEqualTo("custom-value")
    }

    @Test
    fun insertXProperties_fromRawProperties() {
        val rawProperties = mapOf(
            "X-CUSTOM-1" to "value1",
            "X-CUSTOM-2" to "value2",
            "CLASS" to "PRIVATE" // Should be filtered out
        )

        val count = helper.insertXProperties(rawProperties, testEventId)
        assertThat(count).isEqualTo(2) // Only X-* properties

        val retrieved = helper.queryXProperties(testEventId)
        assertThat(retrieved).hasSize(2)
        assertThat(retrieved["X-CUSTOM-1"]).isEqualTo("value1")
        assertThat(retrieved["X-CUSTOM-2"]).isEqualTo("value2")
    }

    @Test
    fun deleteExtendedProperties_clearsAll() {
        // Insert some properties
        helper.insertCategories(listOf("Work"), testEventId)
        helper.insertExtendedProperty("X-TEST", "value", testEventId)

        // Verify they exist
        assertThat(helper.queryExtendedProperties(testEventId)).isNotEmpty()

        // Delete all
        val deleted = helper.deleteExtendedPropertiesForEvent(testEventId)
        assertThat(deleted).isEqualTo(2)

        // Verify cleared
        assertThat(helper.queryExtendedProperties(testEventId)).isEmpty()
    }

    // ==================== Helpers ====================

    private fun createTestCalendar(): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, "extprops-test-$testRunId")
            put(Calendars.CALENDAR_DISPLAY_NAME, "ExtProps Test Calendar")
            put(Calendars.CALENDAR_COLOR, 0xFFFF0000.toInt())
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
        }

        val uri = contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun createTestEvent(): Long {
        val uid = UUID.randomUUID().toString()
        val event = ICalEvent(
            uid = uid,
            importId = uid,
            summary = "ExtProps Test Event",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromTimestamp(System.currentTimeMillis(), ZoneOffset.UTC),
            dtEnd = ICalDateTime.fromTimestamp(System.currentTimeMillis() + 3600000, ZoneOffset.UTC),
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
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )

        val values = CalendarContractMapper.toContentValues(event, testCalendarId)
        val uri = contentResolver.insert(asSyncAdapter(Events.CONTENT_URI), values)
        return ContentUris.parseId(uri!!)
    }

    private fun asSyncAdapter(uri: android.net.Uri): android.net.Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }
}
