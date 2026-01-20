package org.onekash.icaldav.client

import org.onekash.icaldav.discovery.CalDavDiscovery
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.PutResponse
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.*
import org.onekash.icaldav.quirks.CalDavQuirks
import org.onekash.icaldav.quirks.DefaultQuirks
import org.onekash.icaldav.xml.RequestBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * High-level CalDAV client for calendar operations.
 *
 * Provides event CRUD operations with iCal parsing/generation.
 * Built on WebDavClient for HTTP operations.
 *
 * Uses raw HTTP approach for reliable iCloud compatibility.
 *
 * @param webDavClient WebDAV client for HTTP operations
 * @param quirks Provider-specific quirks for handling server differences
 * @param iCalParser Parser for iCalendar data
 * @param iCalGenerator Generator for iCalendar data
 */
class CalDavClient(
    private val webDavClient: WebDavClient,
    private val quirks: CalDavQuirks = DefaultQuirks("generic", "CalDAV", ""),
    private val iCalParser: ICalParser = ICalParser(),
    private val iCalGenerator: ICalGenerator = ICalGenerator()
) {
    private val discovery = CalDavDiscovery(webDavClient)

    // Cache for server capabilities (per URL)
    private val capabilitiesCache = mutableMapOf<String, ServerCapabilities>()

    /**
     * Get server capabilities (discovered via OPTIONS request).
     *
     * Results are cached for 1 hour to avoid repeated OPTIONS requests.
     * Use forceRefresh=true to bypass the cache.
     *
     * @param serverUrl Server URL to query (usually the server root)
     * @param forceRefresh If true, bypass cache and query server
     * @return Server capabilities or UNKNOWN if discovery fails
     *
     * @see ServerCapabilities
     */
    fun getCapabilities(serverUrl: String, forceRefresh: Boolean = false): DavResult<ServerCapabilities> {
        val cached = capabilitiesCache[serverUrl]
        val now = System.currentTimeMillis()

        // Return cached if valid and not forcing refresh
        if (!forceRefresh && cached != null &&
            (now - cached.discoveredAt) < CAPABILITIES_CACHE_TTL_MS) {
            return DavResult.success(cached)
        }

        return webDavClient.options(serverUrl).also { result ->
            if (result is DavResult.Success) {
                capabilitiesCache[serverUrl] = result.value
            }
        }
    }

    /**
     * Clear the capabilities cache.
     *
     * Call this if you need to re-discover capabilities after a server update.
     */
    fun clearCapabilitiesCache() {
        capabilitiesCache.clear()
    }

    /**
     * Perform sync-collection if supported by the server.
     *
     * This is a convenience method that checks capabilities before calling syncCollection().
     * If sync-collection is not supported, returns null (caller should use ctag-based sync).
     *
     * @param calendarUrl Calendar collection URL
     * @param syncToken Previous sync token (empty for full sync)
     * @param serverUrl Server URL for capability check (defaults to calendar URL root)
     * @return Sync result, or null if sync-collection not supported
     */
    fun syncCollectionIfSupported(
        calendarUrl: String,
        syncToken: String = "",
        serverUrl: String = extractServerRoot(calendarUrl)
    ): DavResult<SyncResult?> {
        val caps = getCapabilities(serverUrl)
        if (caps is DavResult.Success && !caps.value.supportsSyncCollection) {
            return DavResult.success(null) // Not supported
        }
        return syncCollection(calendarUrl, syncToken).map { it }
    }

    /**
     * Extract server root URL from a full URL.
     */
    private fun extractServerRoot(url: String): String {
        val match = Regex("""(https?://[^/]+)""").find(url)
        return match?.groupValues?.get(1) ?: url
    }

    /**
     * Discover and connect to a CalDAV account.
     *
     * @param serverUrl Base CalDAV URL
     * @return Discovered account with calendars
     */
    fun discoverAccount(serverUrl: String): DavResult<CalDavAccount> {
        return discovery.discoverAccount(serverUrl)
    }

    /**
     * Fetch all events from a calendar within a time range.
     *
     * @param calendarUrl Calendar collection URL
     * @param start Start of time range
     * @param end End of time range
     * @return List of parsed events
     */
    fun fetchEvents(
        calendarUrl: String,
        start: Instant? = null,
        end: Instant? = null
    ): DavResult<List<EventWithMetadata>> {
        val startStr = start?.let { formatICalTimestamp(it) }
        val endStr = end?.let { formatICalTimestamp(it) }

        val reportBody = RequestBuilder.calendarQuery(startStr, endStr)

        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                response.calendarData?.let { icalData ->
                    parseEventResponse(response.href, icalData, response.etag)
                }
            }.flatten()
        }
    }

    /**
     * Fetch specific events by their URLs.
     *
     * @param calendarUrl Calendar collection URL
     * @param eventHrefs List of event URLs to fetch
     * @return List of parsed events
     */
    fun fetchEventsByHref(
        calendarUrl: String,
        eventHrefs: List<String>
    ): DavResult<List<EventWithMetadata>> {
        if (eventHrefs.isEmpty()) {
            return DavResult.success(emptyList())
        }

        val reportBody = RequestBuilder.calendarMultiget(eventHrefs)
        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                response.calendarData?.let { icalData ->
                    parseEventResponse(response.href, icalData, response.etag)
                }
            }.flatten()
        }
    }

    /**
     * Get the ctag for a calendar (for change detection).
     *
     * @param calendarUrl Calendar collection URL
     * @return Current ctag value
     */
    fun getCtag(calendarUrl: String): DavResult<String?> {
        val result = webDavClient.propfind(
            url = calendarUrl,
            body = RequestBuilder.propfindCtag(),
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            multistatus.responses.firstOrNull()?.properties?.ctag
        }
    }

    /**
     * Get the sync-token for a calendar (for incremental sync).
     *
     * @param calendarUrl Calendar collection URL
     * @return Current sync-token value, or null if not supported
     */
    fun getSyncToken(calendarUrl: String): DavResult<String?> {
        val result = webDavClient.propfind(
            url = calendarUrl,
            body = RequestBuilder.propfindCtag(), // Also requests sync-token
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            multistatus.responses.firstOrNull()?.properties?.syncToken
        }
    }

    /**
     * Fetch only ETags for events in a time range (lightweight sync).
     *
     * This is a bandwidth-efficient alternative to fetchEvents() when you only
     * need to detect which events have changed. Returns ~96% less data than
     * full event fetch for large calendars.
     *
     * Use case: When sync-token expires (403/410), compare local etags with
     * server etags to determine which events need to be re-fetched.
     *
     * @param calendarUrl Calendar collection URL
     * @param start Start of time range
     * @param end End of time range
     * @return List of href/etag pairs for events in range
     */
    fun fetchEtagsInRange(
        calendarUrl: String,
        start: Instant,
        end: Instant
    ): DavResult<List<EtagInfo>> {
        val startStr = formatICalTimestamp(start)
        val endStr = formatICalTimestamp(end)

        val reportBody = RequestBuilder.calendarQueryEtagOnly(startStr, endStr)
        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                response.etag?.let { etag ->
                    EtagInfo(
                        href = response.href,
                        etag = etag.trim('"') // Remove surrounding quotes if present
                    )
                }
            }
        }
    }

    /**
     * Create a new event on the calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @param event Event to create
     * @return Created event URL and ETag
     */
    fun createEvent(
        calendarUrl: String,
        event: ICalEvent
    ): DavResult<EventCreateResult> {
        val eventUrl = buildEventUrl(calendarUrl, event.uid)
        val icalData = iCalGenerator.generate(event)

        // Use ifNoneMatch = true to fail if resource already exists (CREATE semantics)
        val result = webDavClient.put(eventUrl, icalData, etag = null, ifNoneMatch = true)

        return result.map { putResponse ->
            EventCreateResult(eventUrl, putResponse.etag)
        }
    }

    /**
     * Update an existing event.
     *
     * @param eventUrl Full URL to the event resource
     * @param event Updated event data
     * @param etag Current ETag for conflict detection
     * @return New ETag after update
     */
    fun updateEvent(
        eventUrl: String,
        event: ICalEvent,
        etag: String? = null
    ): DavResult<String?> {
        val icalData = iCalGenerator.generate(event)
        val result = webDavClient.put(eventUrl, icalData, etag)

        return result.map { putResponse ->
            putResponse.etag
        }
    }

    /**
     * Delete an event.
     *
     * @param eventUrl Full URL to the event resource
     * @param etag Current ETag for conflict detection
     * @return Success or error
     */
    fun deleteEvent(
        eventUrl: String,
        etag: String? = null
    ): DavResult<Unit> {
        return webDavClient.delete(eventUrl, etag)
    }

    /**
     * Fetch a single event by URL.
     *
     * @param eventUrl Full URL to the event resource
     * @return Success with event, or HttpError(404) if not found
     */
    fun getEvent(eventUrl: String): DavResult<EventWithMetadata> {
        val calendarUrl = eventUrl.substringBeforeLast('/')
        return when (val result = fetchEventsByHref(calendarUrl, listOf(eventUrl))) {
            is DavResult.Success -> {
                val event = result.value.firstOrNull()
                if (event != null) {
                    DavResult.success(event)
                } else {
                    DavResult.httpError(404, "Event not found at $eventUrl")
                }
            }
            is DavResult.HttpError -> result
            is DavResult.NetworkError -> result
            is DavResult.ParseError -> result
        }
    }

    /**
     * Create event from raw iCal data.
     *
     * Uses If-None-Match: * to fail if event already exists (conflict detection).
     * This avoids re-parsing stored iCal data when pushing from offline queue.
     *
     * @param calendarUrl Calendar collection URL
     * @param uid Event UID
     * @param icalData Raw iCalendar data
     * @return Created event URL and ETag, or 412 error if event exists
     */
    fun createEventRaw(calendarUrl: String, uid: String, icalData: String): DavResult<EventCreateResult> {
        val eventUrl = buildEventUrl(calendarUrl, uid)
        return webDavClient.put(eventUrl, icalData, etag = null, ifNoneMatch = true)
            .map { EventCreateResult(eventUrl, it.etag) }
    }

    /**
     * Update event from raw iCal data.
     *
     * Uses If-Match with etag for conflict detection.
     * This avoids re-parsing stored iCal data when pushing from offline queue.
     *
     * @param eventUrl Full URL to the event resource
     * @param icalData Raw iCalendar data
     * @param etag Current ETag for conflict detection (optional)
     * @return New ETag after update, or 412 error if etag mismatch
     */
    fun updateEventRaw(eventUrl: String, icalData: String, etag: String? = null): DavResult<String?> {
        return webDavClient.put(eventUrl, icalData, etag, ifNoneMatch = false).map { it.etag }
    }

    /**
     * Perform incremental sync using sync-token (RFC 6578).
     *
     * @param calendarUrl Calendar collection URL
     * @param syncToken Previous sync token (empty for full sync)
     * @return Sync result with changes
     */
    fun syncCollection(
        calendarUrl: String,
        syncToken: String = ""
    ): DavResult<SyncResult> {
        val reportBody = RequestBuilder.syncCollection(syncToken)
        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            val added = mutableListOf<EventWithMetadata>()
            val deleted = mutableListOf<String>()
            val addedHrefs = mutableListOf<ResourceHref>()

            multistatus.responses.forEach { response ->
                val calData = response.calendarData
                // Skip the calendar collection itself (usually first response)
                val isEventResource = response.href.endsWith(".ics")

                if (response.status == 404) {
                    // Deleted resource
                    deleted.add(response.href)
                } else if (calData != null) {
                    // Added or modified with calendar data
                    parseEventResponse(response.href, calData, response.etag)
                        .forEach { added.add(it) }
                } else if (isEventResource && response.status != 404) {
                    // Resource exists but server didn't include calendar-data
                    // (common with iCloud sync-collection responses)
                    addedHrefs.add(ResourceHref(response.href, response.etag))
                }
            }

            SyncResult(
                added = added,
                deleted = deleted,
                newSyncToken = multistatus.syncToken ?: "",
                addedHrefs = addedHrefs
            )
        }
    }

    /**
     * Parse iCal data from a response into events.
     */
    private fun parseEventResponse(
        href: String,
        icalData: String,
        etag: String?
    ): List<EventWithMetadata> {
        val parseResult = iCalParser.parseAllEvents(icalData)
        val events = parseResult.getOrNull() ?: return emptyList()

        return events.map { event ->
            EventWithMetadata(
                event = event,
                href = href,
                etag = etag,
                rawIcal = icalData
            )
        }
    }

    /**
     * Build event URL from calendar URL and UID.
     *
     * Sanitizes UID to prevent path traversal and other security issues.
     */
    fun buildEventUrl(calendarUrl: String, uid: String): String {
        val base = calendarUrl.trimEnd('/')
        val safeUid = sanitizeUidForUrl(uid)
        return "$base/$safeUid.ics"
    }

    /**
     * Sanitize UID for use in URL path.
     *
     * Security considerations:
     * - Prevents path traversal attacks (../, ..\)
     * - Removes URL-unsafe characters
     * - Ensures result is a valid filename
     *
     * @throws IllegalArgumentException if UID is empty or would result in unsafe path
     */
    private fun sanitizeUidForUrl(uid: String): String {
        require(uid.isNotBlank()) { "UID cannot be blank" }

        // Replace unsafe characters with underscore
        val sanitized = uid.replace(Regex("[^a-zA-Z0-9@._-]"), "_")

        // Check for path traversal attempts
        require(!sanitized.contains("..")) { "UID cannot contain path traversal sequences" }
        require(sanitized != ".") { "UID cannot be a single dot" }
        require(sanitized.isNotEmpty()) { "UID cannot be empty after sanitization" }

        // Ensure doesn't start or end with dots (hidden files, relative paths)
        val trimmed = sanitized.trim('.')
        require(trimmed.isNotEmpty()) { "UID cannot consist only of dots" }

        return trimmed
    }

    /**
     * Format Instant as iCal timestamp.
     */
    private fun formatICalTimestamp(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        return formatter.format(instant.atZone(ZoneOffset.UTC))
    }

    companion object {
        // Cache TTL: 1 hour (capabilities rarely change)
        private const val CAPABILITIES_CACHE_TTL_MS = 3600_000L

        /**
         * Create CalDavClient with Basic authentication.
         *
         * Uses WebDavClient.withAuth() which handles redirects properly,
         * preserving authentication headers across cross-host redirects
         * (critical for iCloud which redirects to partition servers).
         */
        fun withBasicAuth(
            username: String,
            password: String
        ): CalDavClient {
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth)
            val webDavClient = WebDavClient(httpClient, auth)
            return CalDavClient(webDavClient)
        }

        /**
         * Create CalDavClient for a specific provider with auto-detected quirks.
         *
         * Automatically detects the CalDAV provider from the URL and applies
         * appropriate quirks (e.g., ICloudQuirks for iCloud URLs).
         *
         * This is the recommended way to create a CalDavClient when you know
         * the server URL ahead of time.
         *
         * @param serverUrl Base CalDAV server URL (used for provider detection)
         * @param username Username for authentication
         * @param password Password for authentication
         * @return CalDavClient configured for the detected provider
         */
        fun forProvider(
            serverUrl: String,
            username: String,
            password: String
        ): CalDavClient {
            val quirks = CalDavQuirks.forServer(serverUrl)
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth)
            val webDavClient = WebDavClient(httpClient, auth)
            return CalDavClient(webDavClient, quirks)
        }
    }
}

/**
 * Event with server metadata.
 *
 * @property event Parsed event data
 * @property href Event resource URL
 * @property etag ETag for conflict detection
 * @property rawIcal Original iCalendar data as received from server.
 *                   Contains complete VCALENDAR (including VTIMEZONE, all VEVENTs).
 *                   For multi-event .ics files, all events share the same rawIcal.
 *
 * Note: rawIcal participates in equals/hashCode. Two instances with identical
 * event/href/etag but different rawIcal (e.g., whitespace) are considered unequal.
 * If this affects your use case, compare using event/href/etag fields directly.
 */
data class EventWithMetadata(
    val event: ICalEvent,
    val href: String,
    val etag: String?,
    val rawIcal: String? = null
)

/**
 * Result of event creation.
 */
data class EventCreateResult(
    val href: String,
    val etag: String?
)

/**
 * Resource href with etag (for resources without calendar-data).
 * Used when server returns hrefs but not the actual event data.
 */
data class ResourceHref(
    val href: String,
    val etag: String?
)

/**
 * Result of sync-collection operation.
 */
data class SyncResult(
    /** Events with parsed calendar data */
    val added: List<EventWithMetadata>,
    /** Hrefs of deleted resources */
    val deleted: List<String>,
    /** New sync token for subsequent delta syncs */
    val newSyncToken: String,
    /** Hrefs of resources that exist but didn't include calendar-data (some servers like iCloud) */
    val addedHrefs: List<ResourceHref> = emptyList()
)

/**
 * Etag information for a resource.
 * Used by fetchEtagsInRange() for lightweight sync.
 */
data class EtagInfo(
    val href: String,
    val etag: String
)
