# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- X-* vendor property preservation for round-trip fidelity
- CLASS property parsing (PUBLIC, PRIVATE, CONFIDENTIAL)
- rawProperties map in ICalEvent for unhandled properties
- KashCal edge case tests (114 tests ported)

## [1.0.0] - 2024-01-15

### Added

#### CalDAV Core
- Complete CalDAV client with discovery, CRUD, and sync operations
- Provider quirks system for iCloud, Google Calendar, Fastmail, Radicale, Baikal
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
- Google Calendar CalDAV tests (20 tests)

### Security
- XXE prevention in XML parsing
- Response size limits to prevent OOM
- Input validation and sanitization

## Links

- [GitHub Repository](https://github.com/icaldav/icaldav)
- [Maven Central](https://central.sonatype.com/namespace/io.github.icaldav)
