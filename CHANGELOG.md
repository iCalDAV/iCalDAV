# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-01-17

### Breaking Changes
- **Package rename**: All packages changed from `com.icalendar.*` to `org.onekash.icaldav.*`
- **Maven coordinates**: Group ID changed from `io.github.icaldav` to `org.onekash`
- **Module consolidation**: 5 modules reduced to 3
  - `icalendar-core` -> `icaldav-core`
  - `webdav-core` + `caldav-core` + `ics-subscription` -> `icaldav-client`
  - `caldav-sync` -> `icaldav-sync`
- **Package structure changes**:
  - `com.icalendar.core.model` -> `org.onekash.icaldav.model`
  - `com.icalendar.core.parser` -> `org.onekash.icaldav.parser`
  - `com.icalendar.core.generator` -> `org.onekash.icaldav.parser`
  - `com.icalendar.caldav.client` -> `org.onekash.icaldav.client`
  - `com.icalendar.webdav.model` -> `org.onekash.icaldav.model`
  - `com.icalendar.sync.*` -> `org.onekash.icaldav.sync`

### Migration Guide
Update your imports:
```kotlin
// Old
import com.icalendar.caldav.client.CalDavClient
import com.icalendar.core.model.ICalEvent
import com.icalendar.webdav.model.DavResult

// New
import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.DavResult
```

Update your dependencies:
```kotlin
// Old
implementation("io.github.icaldav:caldav-core:1.2.0")

// New
implementation("org.onekash:icaldav-client:2.0.0")
```

## [1.2.0] - 2026-01-17

### Added
- X-* vendor property preservation for round-trip fidelity
- CLASS property parsing (PUBLIC, PRIVATE, CONFIDENTIAL)
- rawProperties map in ICalEvent for unhandled properties
- KashCal edge case tests (114 tests ported)
- Baikal CalDAV server integration tests (184 tests)
- Radicale CalDAV server integration tests (184 tests)
- RFC 9253 LINK and RELATED-TO property support

## [1.1.0] - 2026-01-10

### Added
- `fetchEtagsInRange()` - Lightweight etag-only queries for 96% bandwidth savings
- `getSyncToken()` - Retrieve sync token for incremental sync
- `EtagInfo` data class for etag results
- 66 new tests covering advanced sync patterns

## [1.0.0] - 2026-01-05

### Added

#### CalDAV Core
- Complete CalDAV client with discovery, CRUD, and sync operations
- Provider quirks system for iCloud, Radicale, Baikal
- HTTP resilience with retries, rate limiting, and 10MB response limits
- Etag-only queries for 96% bandwidth reduction
- Incremental sync support (RFC 6578 Collection Sync)

#### iCalendar Core
- RFC 5545 compliant iCalendar parser and generator
- RFC 7986 extensions (IMAGE, CONFERENCE, COLOR)
- RFC 9074 VALARM extensions (ACKNOWLEDGED, PROXIMITY, DEFAULT-ALARM)
- RFC 9253 relationship properties (LINK, RELATED-TO)
- All-day event handling with VALUE=DATE
- Timezone support with TZID parameters
- Recurring event support (RRULE, EXDATE, RECURRENCE-ID)
- VALARM parsing with DISPLAY, EMAIL, and AUDIO actions

#### CalDAV Sync
- Full sync engine with pull/push support
- Conflict resolution strategies (server-wins, local-wins, newest-wins, manual)
- Operation coalescing (CREATE+UPDATE+DELETE optimization)
- Offline operation queue

#### ICS Subscription
- Read-only .ics calendar feed fetcher
- ETag-based caching for change detection

#### Integration Tests
- Nextcloud CalDAV server tests (186 tests)
- Radicale CalDAV server tests (186 tests)
- Baikal CalDAV server tests (186 tests)

### Security
- XXE prevention in XML parsing
- Response size limits to prevent OOM
- Input validation and sanitization

## Links

- [GitHub](https://github.com/iCalDAV/iCalDAV)
- [Maven Central](https://central.sonatype.com/namespace/org.onekash)
