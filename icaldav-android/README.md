# icaldav-android

Android CalendarContract mapper for iCalDAV - bridges RFC 5545 events to Android's system calendar.

## Features

- Map `ICalEvent` to Android's `CalendarContract.Events` table
- Map `ICalAlarm` to `CalendarContract.Reminders` table
- Map `Attendee` and `Organizer` to `CalendarContract.Attendees` table
- Handle recurring events (RRULE, EXDATE, DURATION)
- Handle exception events (RECURRENCE-ID)
- Sync adapter URI helpers for `CALLER_IS_SYNCADAPTER` operations
- Optional integration with `icaldav-sync` for full sync capabilities

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.onekash:icaldav-android:2.2.0")

    // Optional: Full sync support
    implementation("org.onekash:icaldav-sync:2.2.0")
}
```

**Requirements:** Android API 21+ (Lollipop), Kotlin 1.9+

## Permissions

Your app needs calendar permissions:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.READ_CALENDAR"/>
<uses-permission android:name="android.permission.WRITE_CALENDAR"/>
```

Request at runtime:
```kotlin
val permissions = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR
)
ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
```

## Usage

### Convert ICalEvent to ContentValues

```kotlin
import org.onekash.icaldav.android.CalendarContractMapper
import org.onekash.icaldav.model.ICalEvent

val event: ICalEvent = // from icaldav-core or icaldav-client
val values = CalendarContractMapper.toContentValues(event, calendarId = 1L)

// Insert into calendar
contentResolver.insert(Events.CONTENT_URI, values)
```

### Convert Cursor to ICalEvent

```kotlin
val cursor = contentResolver.query(Events.CONTENT_URI, null, null, null, null)
cursor?.use {
    while (it.moveToNext()) {
        val event = CalendarContractMapper.fromCursor(it)
        // Process event
    }
}
```

### Map Reminders

```kotlin
import org.onekash.icaldav.android.ReminderMapper
import org.onekash.icaldav.model.ICalAlarm

val alarm: ICalAlarm = event.alarms.first()
val values = ReminderMapper.toContentValues(alarm, eventId = 123L)
contentResolver.insert(Reminders.CONTENT_URI, values)
```

### Map Attendees

```kotlin
import org.onekash.icaldav.android.AttendeeMapper
import org.onekash.icaldav.model.Attendee

val attendee: Attendee = event.attendees.first()
val values = AttendeeMapper.toContentValues(attendee, eventId = 123L)
contentResolver.insert(Attendees.CONTENT_URI, values)
```

### Sync Adapter Operations

When writing as a sync adapter, use `SyncAdapterUri` to set `CALLER_IS_SYNCADAPTER`:

```kotlin
import org.onekash.icaldav.android.SyncAdapterUri

val uri = SyncAdapterUri.asSyncAdapter(
    Events.CONTENT_URI,
    accountName = "user@example.com",
    accountType = "com.example.account"
)
contentResolver.insert(uri, values)
```

### Create a Calendar

```kotlin
import org.onekash.icaldav.android.CalendarMapper

val values = CalendarMapper.toContentValues(
    accountName = "user@example.com",
    accountType = "com.example.account",
    displayName = "Work Calendar",
    color = 0xFF0000FF.toInt(),
    calDavUrl = "https://caldav.example.com/calendars/work/"
)

val uri = SyncAdapterUri.asSyncAdapter(
    Calendars.CONTENT_URI,
    accountName = "user@example.com",
    accountType = "com.example.account"
)
contentResolver.insert(uri, values)
```

## Integration with icaldav-sync

For full CalDAV sync capabilities, add `icaldav-sync` as a dependency:

```kotlin
import org.onekash.icaldav.android.provider.CalendarContractEventProvider
import org.onekash.icaldav.android.provider.CalendarContractSyncHandler

// Create providers
val eventProvider = CalendarContractEventProvider(
    contentResolver = context.contentResolver,
    accountName = "user@example.com",
    accountType = "com.example.account"
)

val syncHandler = CalendarContractSyncHandler(
    contentResolver = context.contentResolver,
    accountName = "user@example.com",
    accountType = "com.example.account"
)

// Use with SyncEngine from icaldav-sync
val syncEngine = SyncEngine(
    client = calDavClient,
    localProvider = eventProvider,
    syncHandler = syncHandler,
    conflictStrategy = ConflictStrategy.SERVER_WINS
)
```

## RFC 5545 Compliance

### DURATION vs DTEND

Per RFC 5545 and Android requirements:
- **Recurring events**: Use `DURATION`, `DTEND` must be null
- **Non-recurring events**: Use `DTEND`, `DURATION` must be null

The mapper handles this automatically.

### RECURRENCE-ID

Exception events (modified occurrences) are mapped to:
- `ORIGINAL_SYNC_ID` - Same UID as master event
- `ORIGINAL_INSTANCE_TIME` - Original occurrence timestamp

### EXDATE

Excluded dates are stored as comma-separated iCalendar date-time strings.

### VALARM

Alarms with `TRIGGER` durations are converted to Android's `MINUTES` field:
- `TRIGGER:-PT15M` becomes `MINUTES=15`
- `RELATED=END` alarms are converted to start-relative

## Known Limitations

- **RDATE**: Not supported (icaldav-core limitation)
- **VTODO**: Not supported (VEVENT only)
- **X-properties**: Not preserved (no ExtendedProperties mapping)
- **VTIMEZONE**: Relies on IANA timezone IDs

## License

Apache License 2.0
