# iCalDAV

[![Maven Central](https://img.shields.io/maven-central/v/io.github.icaldav/caldav-core)](https://central.sonatype.com/namespace/io.github.icaldav)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9+-purple.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-17+-orange.svg)](https://openjdk.org)

A modern Kotlin library for CalDAV calendar synchronization and iCalendar parsing.

Built for production use with real-world CalDAV servers including iCloud, Google Calendar, Fastmail, and standard CalDAV implementations.

## Why iCalDAV?

| Challenge | iCalDAV Solution |
|-----------|------------------|
| **iCloud is notoriously difficult** | Battle-tested quirks handling for CDATA responses, namespace issues, regional redirects |
| **Bandwidth-heavy full syncs** | Etag-only queries reduce bandwidth by 96% |
| **Offline support is complex** | Built-in operation queue with automatic coalescing |
| **Conflict resolution** | Multiple strategies: server-wins, local-wins, newest-wins, manual merge |
| **Server differences** | Auto-detected provider quirks for iCloud, Google, Fastmail |
| **Reliability concerns** | 900+ tests, production-proven with real CalDAV servers |

## Features

- **Kotlin-first, Java-compatible** - Idiomatic Kotlin with full Java interop
- **Production-ready HTTP** - OkHttp 4.x with retries, rate limiting, and resilience
- **Provider quirks handling** - Automatic handling of iCloud, Google, and other server differences
- **Complete sync engine** - Pull/push synchronization with offline support and conflict resolution
- **RFC compliant** - Full support for CalDAV (RFC 4791), iCalendar (RFC 5545), and Collection Sync (RFC 6578)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    // Core CalDAV client (includes iCalendar parsing)
    implementation("io.github.icaldav:caldav-core:1.0.0")

    // Optional: Sync engine with offline support and conflict resolution
    implementation("io.github.icaldav:caldav-sync:1.0.0")

    // Optional: ICS subscription fetcher for read-only calendar feeds
    implementation("io.github.icaldav:ics-subscription:1.0.0")
}
```

**Requirements:** JVM 17+, Kotlin 1.9+

## Quick Start

### Discover Calendars

```kotlin
val client = CalDavClient.forProvider(
    serverUrl = "https://caldav.icloud.com",
    username = "user@icloud.com",
    password = "app-specific-password"  // Use app-specific password for iCloud
)

when (val result = client.discoverAccount("https://caldav.icloud.com")) {
    is DavResult.Success -> {
        val account = result.value
        println("Found ${account.calendars.size} calendars:")
        account.calendars.forEach { calendar ->
            println("  - ${calendar.displayName} (${calendar.href})")
        }
    }
    is DavResult.HttpError -> println("HTTP ${result.code}: ${result.message}")
    is DavResult.NetworkError -> println("Network error: ${result.exception.message}")
    is DavResult.ParseError -> println("Parse error: ${result.message}")
}
```

### Create, Update, Delete Events

```kotlin
// Create an event
val event = ICalEvent(
    uid = UUID.randomUUID().toString(),
    summary = "Team Meeting",
    description = "Weekly sync",
    dtStart = ICalDateTime.fromInstant(Instant.now()),
    dtEnd = ICalDateTime.fromInstant(Instant.now().plus(1, ChronoUnit.HOURS)),
    location = "Conference Room A"
)

val createResult = client.createEvent(calendarUrl, event)
if (createResult is DavResult.Success) {
    val (href, etag) = createResult.value
    println("Created event at $href")

    // Update the event
    val updated = event.copy(summary = "Team Meeting (Updated)")
    client.updateEvent(href, updated, etag)

    // Delete the event
    client.deleteEvent(href, etag)
}
```

### Fetch Events

```kotlin
// Fetch events in a date range
val events = client.fetchEvents(
    calendarUrl = calendarUrl,
    start = Instant.now(),
    end = Instant.now().plus(30, ChronoUnit.DAYS)
)

// Fetch specific events by URL
val specific = client.fetchEventsByHref(calendarUrl, listOf(href1, href2))

// Fetch only ETags for efficient change detection (96% less bandwidth)
val etags = client.fetchEtagsInRange(calendarUrl, start, end)
```

## Modules

```
┌─────────────────────────────────────────────────────────────┐
│                       caldav-sync                           │
│         (Sync engine, conflict resolution, offline)         │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                       caldav-core                           │
│            (CalDAV client, discovery, CRUD)                 │
└─────────────────────────────────────────────────────────────┘
                    │                   │
┌───────────────────────────┐   ┌───────────────────────────┐
│      icalendar-core       │   │       webdav-core         │
│  (RFC 5545 parse/generate)│   │   (WebDAV HTTP protocol)  │
└───────────────────────────┘   └───────────────────────────┘
```

| Module | Purpose |
|--------|---------|
| `icalendar-core` | Parse and generate iCalendar (RFC 5545) data |
| `webdav-core` | Low-level WebDAV HTTP operations |
| `caldav-core` | High-level CalDAV client with discovery |
| `caldav-sync` | Sync engine with offline support and conflict resolution |
| `ics-subscription` | Fetch read-only .ics calendar subscriptions |

## Performance Optimizations

| Feature | Benefit |
|---------|---------|
| **Etag-only queries** | 96% bandwidth reduction for change detection |
| **Incremental sync** | Only fetch changes since last sync (RFC 6578) |
| **Operation coalescing** | CREATE→UPDATE→DELETE becomes no-op |
| **Response size limits** | 10MB max prevents OOM on large calendars |
| **Connection pooling** | OkHttp connection reuse for lower latency |

### Bandwidth-Efficient Sync

When sync tokens expire (403/410), avoid re-fetching all events:

```kotlin
// Instead of fetching full events (expensive)
val events = client.fetchEvents(calendarUrl, start, end)

// Fetch only etags (96% smaller response)
val serverEtags = client.fetchEtagsInRange(calendarUrl, start, end)

// Compare with local etags to find changes
val changedHrefs = serverEtags
    .filter { it.etag != localEtags[it.href] }
    .map { it.href }

// Fetch only changed events
val changedEvents = client.fetchEventsByHref(calendarUrl, changedHrefs)
```

## Provider Quirks System

CalDAV servers have implementation differences. iCalDAV handles these automatically:

```kotlin
// Auto-detects provider from URL
val client = CalDavClient.forProvider(serverUrl, username, password)

// Or explicitly specify
val client = CalDavClient(
    webDavClient = webDavClient,
    quirks = ICloudQuirks()
)
```

### Supported Providers

| Provider | Quirks Handled |
|----------|----------------|
| **iCloud** | CDATA-wrapped responses, non-prefixed XML namespaces, regional server redirects, app-specific passwords, eventual consistency |
| **Google Calendar** | OAuth2 bearer auth, no MKCALENDAR, no VTODO |
| **Fastmail** | Standard CalDAV with minor variations |
| **Radicale** | Direct URL access (skip discovery), simple auth |
| **Baikal** | sabre/dav based, standard CalDAV with nginx proxy |
| **Generic CalDAV** | RFC-compliant default behavior |

## Sync Engine

The `caldav-sync` module provides production-grade synchronization:

### Pull Changes from Server

```kotlin
val engine = SyncEngine(client)

// Initial sync (full fetch)
val result = engine.sync(
    calendarUrl = calendarUrl,
    previousState = SyncState.initial(calendarUrl),
    localProvider = myLocalProvider,
    handler = myResultHandler
)

// Incremental sync (only changes since last sync)
val result = engine.syncWithIncremental(
    calendarUrl = calendarUrl,
    previousState = savedSyncState,
    localProvider = myLocalProvider,
    handler = myResultHandler,
    forceFullSync = false
)

// Save state for next sync
saveSyncState(result.newState)
```

### Push Local Changes

```kotlin
val syncEngine = CalDavSyncEngine(client, localProvider, handler, pendingStore)

// Queue local changes (works offline)
syncEngine.queueCreate(calendarUrl, newEvent)
syncEngine.queueUpdate(modifiedEvent, eventUrl, etag)
syncEngine.queueDelete(eventUid, eventUrl, etag)

// Push to server when online
val pushResult = syncEngine.push()
```

### Conflict Resolution

When local and server changes conflict (HTTP 412):

```kotlin
// Automatic resolution
syncEngine.resolveConflict(operation, ConflictStrategy.SERVER_WINS)
syncEngine.resolveConflict(operation, ConflictStrategy.LOCAL_WINS)
syncEngine.resolveConflict(operation, ConflictStrategy.NEWEST_WINS)

// Manual resolution
syncEngine.resolveConflict(operation, ConflictStrategy.MANUAL) { local, server ->
    // Return merged event
    mergeEvents(local, server)
}
```

### Operation Coalescing

Multiple local changes to the same event are automatically combined:

| Sequence | Result |
|----------|--------|
| CREATE → UPDATE | Single CREATE with final data |
| CREATE → DELETE | No server operation needed |
| UPDATE → UPDATE | Single UPDATE with final data |
| UPDATE → DELETE | Single DELETE |

## iCalendar Parsing

Parse and generate RFC 5545 compliant iCalendar data:

```kotlin
val parser = ICalParser()

// Parse iCalendar string
when (val result = parser.parseAllEvents(icalString)) {
    is ParseResult.Success -> {
        result.value.forEach { event ->
            println("${event.summary} at ${event.dtStart}")
        }
    }
    is ParseResult.Error -> println("Parse error: ${result.message}")
}

// Generate iCalendar string
val generator = ICalGenerator()
val icalString = generator.generate(event)
```

### Supported Properties

| Category | Properties |
|----------|------------|
| **Core** | UID, SUMMARY, DESCRIPTION, LOCATION, STATUS |
| **Timing** | DTSTART, DTEND, DURATION, TRANSP |
| **Recurrence** | RRULE, EXDATE, RECURRENCE-ID |
| **People** | ORGANIZER, ATTENDEE |
| **Alerts** | VALARM (DISPLAY, EMAIL, AUDIO) |
| **Extended** | CATEGORIES, URL, ATTACH, IMAGE, CONFERENCE |

### Timezone Handling

```kotlin
// UTC time
ICalDateTime.fromInstant(Instant.now())

// With timezone
ICalDateTime.fromZonedDateTime(ZonedDateTime.now(ZoneId.of("America/New_York")))

// All-day event
ICalDateTime.fromLocalDate(LocalDate.now())

// Floating time (device timezone)
ICalDateTime.floating(LocalDateTime.now())
```

## Authentication

### Basic Auth

```kotlin
val client = CalDavClient.withBasicAuth(username, password)
```

### Bearer Token (OAuth)

```kotlin
val client = CalDavClient(
    webDavClient = WebDavClient(httpClient, DavAuth.Bearer(accessToken)),
    quirks = GoogleQuirks()
)
```

### iCloud App-Specific Passwords

iCloud requires app-specific passwords for third-party apps:
1. Go to appleid.apple.com → Security → App-Specific Passwords
2. Generate a password for your app
3. Use that password (not your Apple ID password)

## Error Handling

All operations return `DavResult<T>` for explicit error handling:

```kotlin
sealed class DavResult<out T> {
    data class Success<T>(val value: T) : DavResult<T>()
    data class HttpError(val code: Int, val message: String) : DavResult<Nothing>()
    data class NetworkError(val exception: Exception) : DavResult<Nothing>()
    data class ParseError(val message: String) : DavResult<Nothing>()
}

// Usage
when (val result = client.fetchEvents(calendarUrl, start, end)) {
    is DavResult.Success -> handleEvents(result.value)
    is DavResult.HttpError -> when (result.code) {
        401 -> promptReauth()
        403, 410 -> handleExpiredSyncToken()  // Re-sync needed
        404 -> handleNotFound()
        412 -> handleConflict()  // ETag mismatch
        429 -> handleRateLimit()
        else -> handleError(result)
    }
    is DavResult.NetworkError -> showOfflineMessage()
    is DavResult.ParseError -> reportBug(result.message)
}
```

## HTTP Resilience

Built-in resilience for production use:

| Feature | Behavior |
|---------|----------|
| **Retries** | 2 retries with exponential backoff (500-2000ms) |
| **Rate Limiting** | Respects `Retry-After` header on 429 responses |
| **Response Limits** | 10MB max response size (prevents OOM) |
| **Timeouts** | Connect: 30s, Read: 300s, Write: 60s |
| **Redirects** | Preserves auth headers on cross-host redirects |

## Thread Safety

- `CalDavClient` is thread-safe and can be shared across threads
- `SyncEngine` operations should be serialized per calendar
- `ICalParser` and `ICalGenerator` are stateless and thread-safe
- Use a single `OkHttpClient` instance for connection pooling benefits

## Android Integration

### Gradle Setup

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("io.github.icaldav:caldav-core:1.0.0")
    implementation("io.github.icaldav:caldav-sync:1.0.0")
}
```

### ProGuard Rules

```proguard
# iCalDAV
-keep class com.icalendar.** { *; }
-keepclassmembers class com.icalendar.** { *; }

# OkHttp (if not already included)
-dontwarn okhttp3.**
-dontwarn okio.**
```

### Background Sync with WorkManager

```kotlin
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val client = CalDavClient.forProvider(serverUrl, username, password)
            val engine = SyncEngine(client)

            val result = engine.syncWithIncremental(
                calendarUrl = calendarUrl,
                previousState = loadSyncState(),
                localProvider = localProvider,
                handler = resultHandler
            )

            if (result.success) {
                saveSyncState(result)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// Schedule periodic sync
val syncRequest = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
    repeatInterval = 15,
    repeatIntervalTimeUnit = TimeUnit.MINUTES
).setConstraints(
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
).build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "calendar_sync",
    ExistingPeriodicWorkPolicy.KEEP,
    syncRequest
)
```

## ICS Subscriptions

Fetch read-only calendar feeds:

```kotlin
val client = IcsSubscriptionClient()

// Fetch with ETag caching
val result = client.fetch(
    url = "webcal://example.com/calendar.ics",
    previousEtag = savedEtag
)

when (result) {
    is IcsResult.Success -> {
        saveEtag(result.etag)
        processEvents(result.events)
    }
    is IcsResult.NotModified -> println("No changes")
    is IcsResult.Error -> println("Error: ${result.message}")
}
```

## Troubleshooting

### iCloud Returns 403 Forbidden

**Cause:** Sync token expired or invalid.

**Solution:** Fall back to full sync or use etag-based comparison:
```kotlin
when (val result = client.syncCollection(calendarUrl, syncToken)) {
    is DavResult.HttpError -> if (result.code == 403 || result.code == 410) {
        // Sync token expired, perform full sync
        client.fetchEvents(calendarUrl)
    }
}
```

### Events Not Appearing After Create (iCloud)

**Cause:** iCloud has eventual consistency - events may take a few seconds to propagate.

**Solution:** Retry with exponential backoff:
```kotlin
suspend fun fetchWithRetry(href: String, maxRetries: Int = 3): EventWithMetadata? {
    repeat(maxRetries) { attempt ->
        val result = client.fetchEventsByHref(calendarUrl, listOf(href))
        if (result is DavResult.Success && result.value.isNotEmpty()) {
            return result.value.first()
        }
        delay(100L * (1 shl attempt))  // 100ms, 200ms, 400ms
    }
    return null
}
```

### Google Calendar OAuth Token Expired

**Cause:** Access token has expired.

**Solution:** Refresh the token and retry:
```kotlin
val client = CalDavClient(
    webDavClient = WebDavClient(httpClient, DavAuth.Bearer(refreshedToken)),
    quirks = GoogleQuirks()
)
```

### Large Calendar Causes OOM

**Cause:** Fetching too many events at once.

**Solution:** Use date range filters and pagination:
```kotlin
// Fetch in chunks
val chunks = generateDateRanges(start, end, chunkSizeDays = 30)
val allEvents = chunks.flatMap { (chunkStart, chunkEnd) ->
    client.fetchEvents(calendarUrl, chunkStart, chunkEnd)
        .getOrNull() ?: emptyList()
}
```

### Radicale Discovery Fails

**Cause:** Radicale uses a simpler URL structure without DAV principal discovery.

**Solution:** Skip discovery and use direct URLs:
```kotlin
val client = CalDavClient.withBasicAuth(username, password)

// Radicale URL pattern: http://host:5232/{username}/{calendar}/
val calendarUrl = "http://localhost:5232/$username/personal/"

// All operations work with direct URLs
val events = client.fetchEventsInRange(calendarUrl, startMs, endMs)
val syncResult = client.syncCollection(calendarUrl, syncToken)
client.createEvent(calendarUrl, event)
```

**Radicale URL patterns:**
| Resource | Pattern |
|----------|---------|
| Calendar Home | `http://host:5232/{user}/` |
| Calendar | `http://host:5232/{user}/{calendar}/` |
| Event | `http://host:5232/{user}/{calendar}/{uid}.ics` |

### Baikal Setup

**Baikal** is a lightweight CalDAV/CardDAV server based on sabre/dav.

**URL patterns (standard CalDAV):**
| Resource | Pattern |
|----------|---------|
| DAV Root | `http://host/dav.php/` |
| Principal | `http://host/dav.php/principals/{user}/` |
| Calendar Home | `http://host/dav.php/calendars/{user}/` |
| Calendar | `http://host/dav.php/calendars/{user}/{calendar}/` |
| Event | `http://host/dav.php/calendars/{user}/{calendar}/{uid}.ics` |

**Discovery works normally** - Baikal supports full DAV principal discovery:
```kotlin
val client = CalDavClient.forProvider(
    serverUrl = "http://localhost:8081",
    username = "user",
    password = "password"
)

// Discovery works
val account = client.discoverAccount("http://localhost:8081/dav.php/")
```

### Google Calendar (OAuth2)

**Google Calendar** requires OAuth2 authentication - basic auth is not supported.

**Limitations:**
- No MKCALENDAR (calendars must be created via Google Calendar UI/API)
- No VTODO/VJOURNAL support
- No LOCK/UNLOCK/COPY/MOVE

**Usage with Bearer token:**
```kotlin
// Get OAuth2 access token via Google's OAuth flow
val accessToken = "ya29.a0..." // Your OAuth2 access token

// Create client with bearer auth
val auth = DavAuth.Bearer(accessToken)
val httpClient = WebDavClient.withAuth(auth)
val client = CalDavClient(WebDavClient(httpClient, auth))

// Google CalDAV URLs
val baseUrl = "https://apidata.googleusercontent.com/caldav/v2"
val calendarUrl = "$baseUrl/primary/events/" // or specific calendar ID

// All operations work with bearer auth
val events = client.fetchEventsInRange(calendarUrl, startMs, endMs)
client.createEvent(calendarUrl, event)
```

**URL patterns:**
| Resource | Pattern |
|----------|---------|
| Principal | `https://apidata.googleusercontent.com/caldav/v2/{calendarId}/user` |
| Calendar | `https://apidata.googleusercontent.com/caldav/v2/{calendarId}/events/` |
| Event | `https://apidata.googleusercontent.com/caldav/v2/{calendarId}/events/{uid}.ics` |

**Note:** `{calendarId}` is `primary` for the main calendar or the calendar's email address found in Google Calendar settings.

## Java Interoperability

iCalDAV is written in Kotlin but fully compatible with Java:

```java
CalDavClient client = CalDavClient.withBasicAuth("user", "pass");
DavResult<CalDavAccount> result = client.discoverAccount(serverUrl);

if (result instanceof DavResult.Success) {
    CalDavAccount account = ((DavResult.Success<CalDavAccount>) result).getValue();
    for (Calendar calendar : account.getCalendars()) {
        System.out.println(calendar.getDisplayName());
    }
}
```

## RFC Compliance

| RFC | Description | Support |
|-----|-------------|---------|
| RFC 5545 | iCalendar | Full |
| RFC 4791 | CalDAV | Full |
| RFC 6578 | Collection Sync | Full |
| RFC 7986 | iCalendar Extensions | Partial (IMAGE, CONFERENCE) |
| RFC 9073 | Structured Locations | Partial |
| RFC 9253 | iCalendar Relationships | Full (LINK, RELATED-TO) |

## Links

- [Maven Central](https://central.sonatype.com/namespace/io.github.icaldav)
- [GitHub Issues](https://github.com/nicholashagen/icaldav/issues)
- [Changelog](CHANGELOG.md)

## License

Apache License 2.0

## Contributing

Contributions are welcome. Please open an issue to discuss significant changes before submitting a PR.

### Running Tests

```bash
# Full test suite (900+ tests)
./gradlew test

# Specific module
./gradlew :caldav-core:test
./gradlew :caldav-sync:test

# With coverage report
./gradlew test jacocoTestReport

# Integration tests against real servers (requires Docker)
./run-integration-tests.sh      # Nextcloud (184 tests)
./run-radicale-tests.sh         # Radicale (184 tests)
./run-baikal-tests.sh           # Baikal (184 tests)

# Google Calendar (requires OAuth2 setup)
./run-google-tests.sh --setup   # Show OAuth2 setup instructions
./run-google-tests.sh           # Run tests (requires env vars)
```

## Security

Report security vulnerabilities privately to the maintainers. Do not open public issues for security concerns.