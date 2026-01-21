# iCalDAV

[![Maven Central](https://img.shields.io/maven-central/v/org.onekash/icaldav-client)](https://central.sonatype.com/namespace/org.onekash)
[![Build](https://github.com/iCalDAV/iCalDAV/actions/workflows/ci.yml/badge.svg)](https://github.com/iCalDAV/iCalDAV/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9+-purple.svg)](https://kotlinlang.org)

A Kotlin CalDAV client with offline sync and conflict resolution. Sync calendars with iCloud, Nextcloud, and other CalDAV servers.

## RFC Compliance

| RFC | Title | Status |
|-----|-------|--------|
| [RFC 5545](https://datatracker.ietf.org/doc/html/rfc5545) | iCalendar Core (VEVENT, VTODO, VJOURNAL, RRULE) | ✅ Full |
| [RFC 5546](https://datatracker.ietf.org/doc/html/rfc5546) | iTIP (iCalendar Transport-Independent Interoperability) | ✅ Full |
| [RFC 4918](https://datatracker.ietf.org/doc/html/rfc4918) | WebDAV (PROPFIND, REPORT, PUT, DELETE) | ✅ Full |
| [RFC 4791](https://datatracker.ietf.org/doc/html/rfc4791) | CalDAV (calendar-query, calendar-multiget) | ✅ Full |
| [RFC 6578](https://datatracker.ietf.org/doc/html/rfc6578) | WebDAV Sync (sync-collection, sync-token) | ✅ Full |
| [RFC 3744](https://datatracker.ietf.org/doc/html/rfc3744) | WebDAV ACL (access control, privileges) | ✅ Full |
| [RFC 6638](https://datatracker.ietf.org/doc/html/rfc6638) | CalDAV Scheduling (iTIP delivery, free-busy) | ✅ Full |
| [RFC 7986](https://datatracker.ietf.org/doc/html/rfc7986) | New iCalendar Properties (COLOR, IMAGE, CONFERENCE) | ✅ Full |
| [RFC 9073](https://datatracker.ietf.org/doc/html/rfc9073) | Event Publishing Extensions (VLOCATION, PARTICIPANT) | ✅ Full |
| [RFC 9074](https://datatracker.ietf.org/doc/html/rfc9074) | VALARM Extensions (ACKNOWLEDGED, UID, PROXIMITY) | ✅ Full |
| [RFC 9253](https://datatracker.ietf.org/doc/html/rfc9253) | iCalendar Relationships (LINK, enhanced RELATED-TO) | ✅ Full |
| [RFC 6047](https://datatracker.ietf.org/doc/html/rfc6047) | iMIP (iCalendar Message-Based Interop) | ⬜ Not planned |
| [RFC 4324](https://datatracker.ietf.org/doc/html/rfc4324) | iCAL (Calendar Access Protocol) | ⬜ Obsolete |

## Features

### iCalendar (RFC 5545)
- Complete VEVENT, VTODO, and VJOURNAL parsing and generation
- Recurring events (RRULE, RDATE, EXDATE, RECURRENCE-ID)
- Access classification (CLASS property: PUBLIC, PRIVATE, CONFIDENTIAL)
- All-day and multi-day event support
- Timezone handling with VTIMEZONE and timezone distribution service
- Alarms (VALARM) with DISPLAY, EMAIL, and AUDIO actions

### CalDAV Protocol
- CalDAV client with automatic server discovery
- WebDAV ACL support (RFC 3744) - read and modify access control
- CalDAV Scheduling (RFC 6638) with iTIP message building
- Sync engine with offline support and conflict resolution
- Provider quirks handling (iCloud, Nextcloud, Radicale, Baikal)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.onekash:icaldav-client:2.6.2")

    // Optional: Sync engine with offline support
    implementation("org.onekash:icaldav-sync:2.6.2")

    // Optional: Android CalendarContract mapper (API 21+)
    implementation("org.onekash:icaldav-android:2.6.2")
}
```

**Requirements:** JVM 17+, Kotlin 1.9+. Android module requires API 21+.

## Quick Start

```kotlin
import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.DavResult
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// Create client
val client = CalDavClient.forProvider(
    serverUrl = "https://caldav.example.com",
    username = "user@example.com",
    password = "password"
)

// Discover calendars
val discovery = client.discoverAccount("https://caldav.example.com")
if (discovery is DavResult.Success) {
    val calendarUrl = discovery.value.calendars.first().href

    // Create event
    val event = ICalEvent(
        uid = UUID.randomUUID().toString(),
        summary = "Team Meeting",
        dtStart = ICalDateTime.fromInstant(Instant.now()),
        dtEnd = ICalDateTime.fromInstant(Instant.now().plus(1, ChronoUnit.HOURS))
    )

    val createResult = client.createEvent(calendarUrl, event)
    if (createResult is DavResult.Success) {
        val (href, etag) = createResult.value

        // Update event
        val updated = event.copy(summary = "Team Meeting (Updated)")
        client.updateEvent(href, updated, etag)

        // Delete event
        client.deleteEvent(href, etag)
    }

    // Fetch events in date range
    val start = Instant.now()
    val end = Instant.now().plus(30, ChronoUnit.DAYS)
    val events = client.fetchEvents(calendarUrl, start, end)
}
```

### Error Handling

All operations return `DavResult<T>`:

```kotlin
when (val result = client.fetchEvents(calendarUrl, start, end)) {
    is DavResult.Success -> handleEvents(result.value)
    is DavResult.HttpError -> println("HTTP ${result.code}: ${result.message}")
    is DavResult.NetworkError -> println("Network: ${result.exception.message}")
    is DavResult.ParseError -> println("Parse: ${result.message}")
}
```

## Modules

| Module | Purpose |
|--------|---------|
| `icaldav-core` | Parse and generate iCalendar (RFC 5545) |
| `icaldav-client` | CalDAV/WebDAV client with discovery and CRUD |
| `icaldav-sync` | Sync engine with offline support and conflict resolution |
| `icaldav-android` | Android CalendarContract mapper (API 21+) |

## Tested Providers

| Provider | Notes |
|----------|-------|
| **iCloud** | CDATA responses, namespace quirks, regional redirects, app-specific passwords |
| **Nextcloud** | Standard CalDAV |
| **Radicale** | Direct URL access (skip discovery) |
| **Baikal** | sabre/dav based, standard CalDAV |

Other CalDAV servers following RFC 4791 should work. [Open an issue](https://github.com/iCalDAV/iCalDAV/issues) if you encounter problems.

## Links

- [Maven Central](https://central.sonatype.com/namespace/org.onekash)
- [GitHub](https://github.com/iCalDAV/iCalDAV)
- [Contributing](CONTRIBUTING.md)

## License

Apache License 2.0
