package org.onekash.icaldav.android

import android.os.Build
import android.provider.CalendarContract.Reminders
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneId

/**
 * Unit tests for [ReminderMapper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ReminderMapperTest {

    // ==================== Trigger Minutes Tests ====================

    @Test
    fun `alarm 15 minutes before maps to MINUTES=15`() {
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-15) // Negative = before
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(15)
    }

    @Test
    fun `alarm 1 hour before maps to MINUTES=60`() {
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofHours(-1)
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(60)
    }

    @Test
    fun `alarm 1 day before maps to MINUTES=1440`() {
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofDays(-1)
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(1440)
    }

    @Test
    fun `null trigger defaults to 15 minutes`() {
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = null
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(ReminderMapper.DEFAULT_MINUTES)
    }

    @Test
    fun `positive trigger uses absolute value`() {
        // Unusual case: alarm after event start
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(10) // Positive = after
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        // Should use absolute value
        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(10)
    }

    // ==================== Action Mapping Tests ====================

    @Test
    fun `DISPLAY action maps to METHOD_ALERT`() {
        val alarm = createTestAlarm(action = AlarmAction.DISPLAY)
        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)
        assertThat(values.getAsInteger(Reminders.METHOD)).isEqualTo(Reminders.METHOD_ALERT)
    }

    @Test
    fun `EMAIL action maps to METHOD_EMAIL`() {
        val alarm = createTestAlarm(action = AlarmAction.EMAIL)
        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)
        assertThat(values.getAsInteger(Reminders.METHOD)).isEqualTo(Reminders.METHOD_EMAIL)
    }

    @Test
    fun `AUDIO action maps to METHOD_ALERT`() {
        val alarm = createTestAlarm(action = AlarmAction.AUDIO)
        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)
        assertThat(values.getAsInteger(Reminders.METHOD)).isEqualTo(Reminders.METHOD_ALERT)
    }

    // ==================== Event ID Tests ====================

    @Test
    fun `eventId is set correctly`() {
        val alarm = createTestAlarm()
        val values = ReminderMapper.toContentValues(alarm, eventId = 42L)
        assertThat(values.getAsLong(Reminders.EVENT_ID)).isEqualTo(42L)
    }

    // ==================== RELATED=END Tests ====================

    @Test
    fun `RELATED=END alarm converted to start-relative`() {
        // Event duration: 1 hour
        // Alarm: 15 min before END
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-15),
            triggerRelatedToEnd = true
        )
        val eventDuration = Duration.ofHours(1)

        val minutes = ReminderMapper.calculateMinutes(alarm, eventDuration)

        // For RELATED=END with 15 min before end on a 60 min event:
        // This is 45 min AFTER start, which becomes 0 for Android
        // (Android only supports "minutes before start")
        assertThat(minutes).isEqualTo(0)
    }

    @Test
    fun `RELATED=END alarm before event start works`() {
        // Event duration: 30 min
        // Alarm: 1 hour before END = 30 min before START
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-60),
            triggerRelatedToEnd = true
        )
        val eventDuration = Duration.ofMinutes(30)

        val minutes = ReminderMapper.calculateMinutes(alarm, eventDuration)

        // 60 min before end of 30 min event = 30 min before start
        assertThat(minutes).isEqualTo(30)
    }

    // ==================== Absolute Trigger Tests ====================

    @Test
    fun `absolute trigger converted to relative`() {
        val eventStartMs = System.currentTimeMillis() + 3600000 // 1 hour from now
        val alarmMs = eventStartMs - (30 * 60 * 1000) // 30 min before event

        val alarm = createTestAlarm(
            trigger = null,
            triggerAbsolute = ICalDateTime.fromTimestamp(alarmMs, ZoneId.of("UTC"))
        )

        val minutes = ReminderMapper.calculateMinutes(alarm, null, eventStartMs)

        assertThat(minutes).isEqualTo(30)
    }

    @Test
    fun `absolute trigger after event start returns 0`() {
        val eventStartMs = System.currentTimeMillis()
        val alarmMs = eventStartMs + (30 * 60 * 1000) // 30 min AFTER event

        val alarm = createTestAlarm(
            trigger = null,
            triggerAbsolute = ICalDateTime.fromTimestamp(alarmMs, ZoneId.of("UTC"))
        )

        val minutes = ReminderMapper.calculateMinutes(alarm, null, eventStartMs)

        // Negative becomes 0 via maxOf
        assertThat(minutes).isEqualTo(0)
    }

    // ==================== Combined Duration Tests ====================

    @Test
    fun `combined duration PT1H30M correctly parsed to 90 minutes`() {
        val alarm = createTestAlarm(
            action = AlarmAction.DISPLAY,
            trigger = Duration.ofMinutes(-90)
        )

        val values = ReminderMapper.toContentValues(alarm, eventId = 1L)

        assertThat(values.getAsInteger(Reminders.MINUTES)).isEqualTo(90)
    }

    // ==================== Helpers ====================

    private fun createTestAlarm(
        action: AlarmAction = AlarmAction.DISPLAY,
        trigger: Duration? = Duration.ofMinutes(-15),
        triggerAbsolute: ICalDateTime? = null,
        triggerRelatedToEnd: Boolean = false
    ): ICalAlarm {
        return ICalAlarm(
            action = action,
            trigger = trigger,
            triggerAbsolute = triggerAbsolute,
            triggerRelatedToEnd = triggerRelatedToEnd,
            description = null,
            summary = null
        )
    }
}
