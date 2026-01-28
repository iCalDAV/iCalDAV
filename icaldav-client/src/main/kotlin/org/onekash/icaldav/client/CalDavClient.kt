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
import org.onekash.icaldav.xml.RequestBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level CalDAV client for calendar operations.
 *
 * Provides event CRUD operations with iCal parsing/generation.
 * Built on WebDavClient for HTTP operations.
 *
 * Uses raw HTTP approach for reliable iCloud compatibility.
 *
 * @param webDavClient WebDAV client for HTTP operations
 * @param iCalParser Parser for iCalendar data
 * @param iCalGenerator Generator for iCalendar data
 */
class CalDavClient(
    private val webDavClient: WebDavClient,
    private val iCalParser: ICalParser = ICalParser(),
    private val iCalGenerator: ICalGenerator = ICalGenerator()
) {
    private val discovery = CalDavDiscovery(webDavClient)

    // Thread-safe cache for server capabilities (per URL) with size limit
    private val capabilitiesCache = ConcurrentHashMap<String, ServerCapabilities>()

    /**
     * Get server capabilities (discovered via OPTIONS request).
     *
     * Results are cached for 1 hour to avoid repeated OPTIONS requests.
     * Use forceRefresh=true to bypass the cache.
     *
     * Thread-safe: Can be called from multiple threads concurrently.
     *
     * @param serverUrl Server URL to query (usually the server root)
     * @param forceRefresh If true, bypass cache and query server
     * @return Server capabilities or UNKNOWN if discovery fails
     *
     * @see ServerCapabilities
     */
    suspend fun getCapabilities(serverUrl: String, forceRefresh: Boolean = false): DavResult<ServerCapabilities> {
        val cached = capabilitiesCache[serverUrl]
        val now = System.currentTimeMillis()

        // Return cached if valid and not forcing refresh
        if (!forceRefresh && cached != null &&
            (now - cached.discoveredAt) < CAPABILITIES_CACHE_TTL_MS) {
            return DavResult.success(cached)
        }

        return webDavClient.options(serverUrl).also { result ->
            if (result is DavResult.Success) {
                // Enforce cache size limit with simple eviction of oldest entries
                if (capabilitiesCache.size >= MAX_CACHE_SIZE) {
                    evictOldestCacheEntries()
                }
                capabilitiesCache[serverUrl] = result.value
            }
        }
    }

    /**
     * Evict oldest cache entries when cache is full.
     * Removes entries that are expired or the oldest 25% if none expired.
     */
    private fun evictOldestCacheEntries() {
        val now = System.currentTimeMillis()

        // First, remove expired entries
        val expiredKeys = capabilitiesCache.entries
            .filter { (now - it.value.discoveredAt) >= CAPABILITIES_CACHE_TTL_MS }
            .map { it.key }

        expiredKeys.forEach { capabilitiesCache.remove(it) }

        // If still over limit, remove oldest 25%
        if (capabilitiesCache.size >= MAX_CACHE_SIZE) {
            val entriesToRemove = capabilitiesCache.entries
                .sortedBy { it.value.discoveredAt }
                .take(MAX_CACHE_SIZE / 4)
                .map { it.key }

            entriesToRemove.forEach { capabilitiesCache.remove(it) }
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
    suspend fun syncCollectionIfSupported(
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
    suspend fun discoverAccount(serverUrl: String): DavResult<CalDavAccount> {
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
    suspend fun fetchEvents(
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
    suspend fun fetchEventsByHref(
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
    suspend fun getCtag(calendarUrl: String): DavResult<String?> {
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
    suspend fun getSyncToken(calendarUrl: String): DavResult<String?> {
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
    suspend fun fetchEtagsInRange(
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
    suspend fun createEvent(
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
    suspend fun updateEvent(
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
    suspend fun deleteEvent(
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
    suspend fun getEvent(eventUrl: String): DavResult<EventWithMetadata> {
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
    suspend fun createEventRaw(calendarUrl: String, uid: String, icalData: String): DavResult<EventCreateResult> {
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
    suspend fun updateEventRaw(eventUrl: String, icalData: String, etag: String? = null): DavResult<String?> {
        return webDavClient.put(eventUrl, icalData, etag, ifNoneMatch = false).map { it.etag }
    }

    /**
     * Perform incremental sync using sync-token (RFC 6578).
     *
     * @param calendarUrl Calendar collection URL
     * @param syncToken Previous sync token (empty for full sync)
     * @return Sync result with changes
     */
    suspend fun syncCollection(
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

    // ============ VTODO Operations ============

    /**
     * Fetch all todos from a calendar within a time range.
     *
     * @param calendarUrl Calendar collection URL
     * @param start Start of time range (filters by DUE property)
     * @param end End of time range
     * @return List of parsed todos
     */
    suspend fun fetchTodos(
        calendarUrl: String,
        start: Instant? = null,
        end: Instant? = null
    ): DavResult<List<TodoWithMetadata>> {
        val startStr = start?.let { formatICalTimestamp(it) }
        val endStr = end?.let { formatICalTimestamp(it) }

        val reportBody = RequestBuilder.todoQuery(startStr, endStr)

        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                response.calendarData?.let { icalData ->
                    parseTodoResponse(response.href, icalData, response.etag)
                }
            }.flatten()
        }
    }

    /**
     * Create a new todo on the calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @param todo Todo to create
     * @return Created todo URL and ETag
     */
    suspend fun createTodo(
        calendarUrl: String,
        todo: ICalTodo
    ): DavResult<TodoCreateResult> {
        val todoUrl = buildEventUrl(calendarUrl, todo.uid)
        val icalData = iCalGenerator.generate(todo)

        val result = webDavClient.put(todoUrl, icalData, etag = null, ifNoneMatch = true)

        return result.map { putResponse ->
            TodoCreateResult(todoUrl, putResponse.etag)
        }
    }

    /**
     * Update an existing todo.
     *
     * @param todoUrl Full URL to the todo resource
     * @param todo Updated todo data
     * @param etag Current ETag for conflict detection
     * @return New ETag after update
     */
    suspend fun updateTodo(
        todoUrl: String,
        todo: ICalTodo,
        etag: String? = null
    ): DavResult<String?> {
        val icalData = iCalGenerator.generate(todo)
        val result = webDavClient.put(todoUrl, icalData, etag)

        return result.map { putResponse ->
            putResponse.etag
        }
    }

    /**
     * Delete a todo.
     *
     * @param todoUrl Full URL to the todo resource
     * @param etag Current ETag for conflict detection
     * @return Success or error
     */
    suspend fun deleteTodo(
        todoUrl: String,
        etag: String? = null
    ): DavResult<Unit> {
        return webDavClient.delete(todoUrl, etag)
    }

    /**
     * Fetch a single todo by URL.
     *
     * @param todoUrl Full URL to the todo resource
     * @return Success with todo, or HttpError(404) if not found
     */
    suspend fun getTodo(todoUrl: String): DavResult<TodoWithMetadata> {
        val calendarUrl = todoUrl.substringBeforeLast('/')
        val hrefs = listOf(todoUrl)
        val reportBody = RequestBuilder.calendarMultiget(hrefs)
        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            val response = multistatus.responses.firstOrNull()
            val icalData = response?.calendarData
            if (icalData != null) {
                val todos = parseTodoResponse(response.href, icalData, response.etag)
                todos.firstOrNull()
            } else {
                null
            }
        }.let { davResult ->
            when (davResult) {
                is DavResult.Success -> {
                    davResult.value?.let { DavResult.success(it) }
                        ?: DavResult.httpError(404, "Todo not found at $todoUrl")
                }
                is DavResult.HttpError -> davResult
                is DavResult.NetworkError -> davResult
                is DavResult.ParseError -> davResult
            }
        }
    }

    /**
     * Parse iCal data from a response into todos.
     */
    private fun parseTodoResponse(
        href: String,
        icalData: String,
        etag: String?
    ): List<TodoWithMetadata> {
        val parseResult = iCalParser.parseAllTodos(icalData)
        val todos = parseResult.getOrNull() ?: return emptyList()

        return todos.map { todo ->
            TodoWithMetadata(
                todo = todo,
                href = href,
                etag = etag,
                rawIcal = icalData
            )
        }
    }

    // ============ VJOURNAL Operations ============

    /**
     * Fetch all journals from a calendar within a time range.
     *
     * @param calendarUrl Calendar collection URL
     * @param start Start of time range (filters by DTSTART property)
     * @param end End of time range
     * @return List of parsed journals
     */
    suspend fun fetchJournals(
        calendarUrl: String,
        start: Instant? = null,
        end: Instant? = null
    ): DavResult<List<JournalWithMetadata>> {
        val startStr = start?.let { formatICalTimestamp(it) }
        val endStr = end?.let { formatICalTimestamp(it) }

        val reportBody = RequestBuilder.journalQuery(startStr, endStr)

        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                response.calendarData?.let { icalData ->
                    parseJournalResponse(response.href, icalData, response.etag)
                }
            }.flatten()
        }
    }

    /**
     * Create a new journal on the calendar.
     *
     * @param calendarUrl Calendar collection URL
     * @param journal Journal to create
     * @return Created journal URL and ETag
     */
    suspend fun createJournal(
        calendarUrl: String,
        journal: ICalJournal
    ): DavResult<JournalCreateResult> {
        val journalUrl = buildEventUrl(calendarUrl, journal.uid)
        val icalData = iCalGenerator.generate(journal)

        val result = webDavClient.put(journalUrl, icalData, etag = null, ifNoneMatch = true)

        return result.map { putResponse ->
            JournalCreateResult(journalUrl, putResponse.etag)
        }
    }

    /**
     * Update an existing journal.
     *
     * @param journalUrl Full URL to the journal resource
     * @param journal Updated journal data
     * @param etag Current ETag for conflict detection
     * @return New ETag after update
     */
    suspend fun updateJournal(
        journalUrl: String,
        journal: ICalJournal,
        etag: String? = null
    ): DavResult<String?> {
        val icalData = iCalGenerator.generate(journal)
        val result = webDavClient.put(journalUrl, icalData, etag)

        return result.map { putResponse ->
            putResponse.etag
        }
    }

    /**
     * Delete a journal.
     *
     * @param journalUrl Full URL to the journal resource
     * @param etag Current ETag for conflict detection
     * @return Success or error
     */
    suspend fun deleteJournal(
        journalUrl: String,
        etag: String? = null
    ): DavResult<Unit> {
        return webDavClient.delete(journalUrl, etag)
    }

    /**
     * Fetch a single journal by URL.
     *
     * @param journalUrl Full URL to the journal resource
     * @return Success with journal, or HttpError(404) if not found
     */
    suspend fun getJournal(journalUrl: String): DavResult<JournalWithMetadata> {
        val calendarUrl = journalUrl.substringBeforeLast('/')
        val hrefs = listOf(journalUrl)
        val reportBody = RequestBuilder.calendarMultiget(hrefs)
        val result = webDavClient.report(calendarUrl, reportBody, DavDepth.ONE)

        return result.map { multistatus ->
            val response = multistatus.responses.firstOrNull()
            val icalData = response?.calendarData
            if (icalData != null) {
                val journals = parseJournalResponse(response.href, icalData, response.etag)
                journals.firstOrNull()
            } else {
                null
            }
        }.let { davResult ->
            when (davResult) {
                is DavResult.Success -> {
                    davResult.value?.let { DavResult.success(it) }
                        ?: DavResult.httpError(404, "Journal not found at $journalUrl")
                }
                is DavResult.HttpError -> davResult
                is DavResult.NetworkError -> davResult
                is DavResult.ParseError -> davResult
            }
        }
    }

    /**
     * Parse iCal data from a response into journals.
     */
    private fun parseJournalResponse(
        href: String,
        icalData: String,
        etag: String?
    ): List<JournalWithMetadata> {
        val parseResult = iCalParser.parseAllJournals(icalData)
        val journals = parseResult.getOrNull() ?: return emptyList()

        return journals.map { journal ->
            JournalWithMetadata(
                journal = journal,
                href = href,
                etag = etag,
                rawIcal = icalData
            )
        }
    }

    // ============ ACL Operations (RFC 3744) ============

    /**
     * Get the ACL (Access Control List) for a resource.
     *
     * @param resourceUrl URL of the resource to get ACL for
     * @return ACL with list of ACEs
     */
    suspend fun getAcl(resourceUrl: String): DavResult<Acl> {
        val result = webDavClient.propfind(
            url = resourceUrl,
            body = RequestBuilder.propfindAcl(),
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            val props = multistatus.responses.firstOrNull()?.properties
            val aclXml = props?.get("acl")
            if (aclXml != null) {
                org.onekash.icaldav.xml.AclParser.parseAcl(aclXml)
            } else {
                Acl(emptyList())
            }
        }
    }

    /**
     * Set the ACL for a resource.
     *
     * Note: Not all ACEs may be modifiable. Protected ACEs (e.g., owner ACE)
     * cannot be changed. Check ServerCapabilities.supportsAclMethod first.
     *
     * @param resourceUrl URL of the resource to set ACL for
     * @param acl ACL to set
     * @return Success or error
     */
    suspend fun setAcl(resourceUrl: String, acl: Acl): DavResult<Unit> {
        val body = RequestBuilder.acl(acl.aces)
        return webDavClient.acl(resourceUrl, body)
    }

    /**
     * Get the current user's privileges on a resource.
     *
     * This is a lightweight way to check permissions without fetching the full ACL.
     * Use this to determine UI affordances (e.g., show/hide edit button).
     *
     * @param resourceUrl URL of the resource
     * @return CurrentUserPrivilegeSet with the user's privileges
     */
    suspend fun getCurrentUserPrivileges(resourceUrl: String): DavResult<CurrentUserPrivilegeSet> {
        val result = webDavClient.propfind(
            url = resourceUrl,
            body = RequestBuilder.propfindCurrentUserPrivilegeSet(),
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            val props = multistatus.responses.firstOrNull()?.properties
            val cupsXml = props?.get("current-user-privilege-set")
            if (cupsXml != null) {
                org.onekash.icaldav.xml.AclParser.parseCurrentUserPrivilegeSet(cupsXml)
            } else {
                CurrentUserPrivilegeSet.NONE
            }
        }
    }

    /**
     * Share a calendar with another user.
     *
     * Convenience method that creates an ACE granting read or read/write access.
     * This is a common operation for calendar sharing.
     *
     * Note: Requires server to support ACL method (check ServerCapabilities.supportsAclMethod).
     * Some servers may require the user principal URL format specific to that server.
     *
     * @param calendarUrl URL of the calendar to share
     * @param userPrincipal Principal URL of the user to share with
     * @param canWrite If true, grants write access; if false, grants read-only access
     * @return Success or error
     */
    suspend fun shareCalendar(
        calendarUrl: String,
        userPrincipal: String,
        canWrite: Boolean = false
    ): DavResult<Unit> {
        val privileges = if (canWrite) {
            setOf(Privilege.READ, Privilege.WRITE)
        } else {
            setOf(Privilege.READ)
        }

        val ace = Ace(
            principal = Principal.Href(userPrincipal),
            grant = privileges
        )

        // Note: This replaces the entire ACL, which may not be desired.
        // A more sophisticated implementation would first get the ACL,
        // add/modify the ACE, then set the updated ACL.
        // For now, this is a simplified implementation.
        val body = RequestBuilder.acl(listOf(ace))
        return webDavClient.acl(calendarUrl, body)
    }

    // ============ Scheduling Operations (RFC 6638) ============

    /**
     * Discover scheduling inbox/outbox URLs from principal.
     *
     * @param principalUrl User's principal URL
     * @return SchedulingUrls with inbox and outbox URLs
     */
    suspend fun discoverSchedulingUrls(principalUrl: String): DavResult<SchedulingUrls> {
        val result = webDavClient.propfind(
            url = principalUrl,
            body = RequestBuilder.propfindSchedulingUrls(),
            depth = DavDepth.ZERO
        )
        return result.map { multistatus ->
            val props = multistatus.responses.firstOrNull()?.properties
            SchedulingUrls(
                scheduleInboxUrl = props?.get("schedule-inbox-URL"),
                scheduleOutboxUrl = props?.get("schedule-outbox-URL")
            )
        }
    }

    /**
     * Check if server supports auto-schedule before sending messages.
     *
     * Example usage:
     * ```kotlin
     * if (client.checkSchedulingSupport(serverUrl)) {
     *     client.sendSchedulingMessage(outboxUrl, itipMessage, recipients)
     * } else {
     *     // Fall back to client-side scheduling or show error
     * }
     * ```
     *
     * @param serverUrl Server URL for capability check
     * @return true if server supports calendar-auto-schedule
     */
    suspend fun checkSchedulingSupport(serverUrl: String): Boolean {
        val caps = getCapabilities(serverUrl).getOrNull()
        return caps?.supportsAutoSchedule == true
    }

    /**
     * Send an iTIP message to attendees via schedule-outbox.
     *
     * IMPORTANT: Before calling this, verify server supports scheduling:
     * - Check ServerCapabilities.supportsAutoSchedule
     * - Verify schedulingUrls.supportsScheduling is true
     *
     * @param outboxUrl The schedule-outbox URL
     * @param itipMessage iTIP message (from ITipBuilder)
     * @param recipients List of attendee email addresses
     * @return SchedulingResult with per-recipient status
     */
    suspend fun sendSchedulingMessage(
        outboxUrl: String,
        itipMessage: String,
        recipients: List<String>
    ): DavResult<SchedulingResult> {
        val result = webDavClient.post(outboxUrl, itipMessage, recipients)
        return result.map { responseXml ->
            val schedulingResult = org.onekash.icaldav.xml.ScheduleResponseParser.parse(responseXml)
            schedulingResult.copy(rawRequest = itipMessage)
        }
    }

    /**
     * Query free/busy time for attendees.
     *
     * @param outboxUrl The schedule-outbox URL
     * @param organizer The requesting organizer
     * @param attendees Attendees to query
     * @param dtstart Start of time range
     * @param dtend End of time range
     * @return Map of attendee email to their free/busy information
     */
    suspend fun queryFreeBusy(
        outboxUrl: String,
        organizer: Organizer,
        attendees: List<Attendee>,
        dtstart: ICalDateTime,
        dtend: ICalDateTime
    ): DavResult<Map<String, ICalFreeBusy>> {
        val request = ICalGenerator.generateFreeBusyRequest(
            organizer = organizer,
            attendees = attendees,
            dtstart = dtstart,
            dtend = dtend
        )

        val result = webDavClient.post(outboxUrl, request, attendees.map { it.email })
        return result.map { responseXml ->
            val schedulingResult = org.onekash.icaldav.xml.ScheduleResponseParser.parse(responseXml)

            schedulingResult.recipientResults
                .filter { it.calendarData != null }
                .associate { recipientResult ->
                    val freeBusy = iCalParser.parseFreeBusy(recipientResult.calendarData!!)
                        ?: ICalFreeBusy(
                            uid = "",
                            dtstamp = ICalDateTime.now(),
                            dtstart = dtstart,
                            dtend = dtend
                        )
                    recipientResult.recipient to freeBusy
                }
        }
    }

    companion object {
        // Cache TTL: 1 hour (capabilities rarely change)
        private const val CAPABILITIES_CACHE_TTL_MS = 3600_000L

        // Max cache entries (prevents unbounded memory growth)
        private const val MAX_CACHE_SIZE = 100

        /**
         * Create CalDavClient with Basic authentication.
         *
         * Uses WebDavClient.withAuth() which handles redirects properly,
         * preserving authentication headers across cross-host redirects
         * (critical for iCloud which redirects to partition servers).
         *
         * @param username Username for authentication
         * @param password Password for authentication
         * @param userAgent User-Agent header to identify your application
         */
        fun withBasicAuth(
            username: String,
            password: String,
            userAgent: String = "iCalDAV/1.0 (Kotlin)"
        ): CalDavClient {
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth, userAgent)
            val webDavClient = WebDavClient(httpClient, auth)
            return CalDavClient(webDavClient)
        }

        /**
         * Create CalDavClient for a specific provider.
         *
         * This is the recommended way to create a CalDavClient when you know
         * the server URL ahead of time.
         *
         * @param serverUrl Base CalDAV server URL
         * @param username Username for authentication
         * @param password Password for authentication
         * @param userAgent User-Agent header to identify your application
         * @return CalDavClient configured for the server
         */
        fun forProvider(
            serverUrl: String,
            username: String,
            password: String,
            userAgent: String = "iCalDAV/1.0 (Kotlin)"
        ): CalDavClient {
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth, userAgent)
            val webDavClient = WebDavClient(httpClient, auth)
            return CalDavClient(webDavClient)
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

/**
 * Todo (VTODO) with server metadata.
 *
 * @property todo Parsed todo data
 * @property href Todo resource URL
 * @property etag ETag for conflict detection
 * @property rawIcal Original iCalendar data as received from server
 */
data class TodoWithMetadata(
    val todo: ICalTodo,
    val href: String,
    val etag: String?,
    val rawIcal: String? = null
)

/**
 * Result of todo creation.
 */
data class TodoCreateResult(
    val href: String,
    val etag: String?
)

/**
 * Journal (VJOURNAL) with server metadata.
 *
 * @property journal Parsed journal data
 * @property href Journal resource URL
 * @property etag ETag for conflict detection
 * @property rawIcal Original iCalendar data as received from server
 */
data class JournalWithMetadata(
    val journal: ICalJournal,
    val href: String,
    val etag: String?,
    val rawIcal: String? = null
)

/**
 * Result of journal creation.
 */
data class JournalCreateResult(
    val href: String,
    val etag: String?
)
