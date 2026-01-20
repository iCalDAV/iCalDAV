package org.onekash.icaldav.discovery

import org.onekash.icaldav.model.*
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.*
import org.onekash.icaldav.xml.RequestBuilder

/**
 * CalDAV server discovery following RFC 4791 Section 7.1 and RFC 6764.
 *
 * Discovery flow:
 * 1. PROPFIND on root URL → current-user-principal
 * 2. PROPFIND on principal → calendar-home-set
 * 3. PROPFIND on calendar-home → list calendars
 *
 * Optional DNS-SRV discovery (RFC 6764):
 * - _caldavs._tcp.{domain} for secure CalDAV
 * - _caldav._tcp.{domain} for plain CalDAV
 *
 * Production-tested with iCloud and other CalDAV servers.
 *
 * @property client WebDAV client for HTTP operations
 * @property dnsSrvDiscovery Optional DNS-SRV discovery helper for email-based discovery
 * @property enableWellKnownFallback Whether to try /.well-known/caldav on discovery failure
 *
 * @see <a href="https://tools.ietf.org/html/rfc6764">RFC 6764 - Locating Services for Calendaring</a>
 */
class CalDavDiscovery(
    private val client: WebDavClient,
    private val dnsSrvDiscovery: DnsSrvDiscovery? = null,
    private val enableWellKnownFallback: Boolean = true
) {
    /**
     * Discover CalDAV account starting from server URL.
     *
     * If direct discovery fails and enableWellKnownFallback is true, automatically
     * tries /.well-known/caldav as per RFC 6764 Section 5.
     *
     * @param serverUrl Base CalDAV URL (e.g., "https://caldav.icloud.com")
     * @return CalDavAccount with discovered calendars
     */
    fun discoverAccount(serverUrl: String): DavResult<CalDavAccount> {
        // Try direct discovery first
        val directResult = discoverAccountDirect(serverUrl)
        if (directResult.isSuccess) return directResult

        // Try well-known fallback if enabled
        if (enableWellKnownFallback) {
            val wellKnownUrl = buildWellKnownUrl(serverUrl)

            // Prevent infinite loop if well-known URL equals original
            if (wellKnownUrl != serverUrl && wellKnownUrl != serverUrl.trimEnd('/')) {
                val wellKnownResult = discoverAccountDirect(wellKnownUrl)
                if (wellKnownResult.isSuccess) return wellKnownResult
            }
        }

        // Return original error (not well-known error) for better diagnostics
        return directResult
    }

    /**
     * Direct discovery without well-known fallback.
     *
     * Discovery flow:
     * 1. PROPFIND on URL → current-user-principal
     * 2. PROPFIND on principal → calendar-home-set
     * 3. PROPFIND on calendar-home → list calendars
     * 4. PROPFIND on principal → schedule-inbox-URL, schedule-outbox-URL (optional)
     */
    private fun discoverAccountDirect(serverUrl: String): DavResult<CalDavAccount> {
        // Step 1: Discover principal URL
        val principalResult = discoverPrincipal(serverUrl)
        if (principalResult !is DavResult.Success) {
            return principalResult as DavResult<CalDavAccount>
        }
        val principalUrl = resolveUrl(serverUrl, principalResult.value)

        // Step 2: Discover calendar-home-set
        val homeResult = discoverCalendarHome(principalUrl)
        if (homeResult !is DavResult.Success) {
            return homeResult as DavResult<CalDavAccount>
        }
        val calendarHomeUrl = resolveUrl(serverUrl, homeResult.value)

        // Step 3: List calendars
        val calendarsResult = listCalendars(calendarHomeUrl)
        if (calendarsResult !is DavResult.Success) {
            return calendarsResult as DavResult<CalDavAccount>
        }

        // Step 4: Discover scheduling URLs (optional - graceful degradation)
        val schedulingUrls = try {
            val schedResult = client.propfind(
                url = principalUrl,
                body = RequestBuilder.propfindSchedulingUrls(),
                depth = DavDepth.ZERO
            )
            if (schedResult is DavResult.Success) {
                val props = schedResult.value.responses.firstOrNull()?.properties
                val inboxUrl = props?.get("schedule-inbox-URL")?.let { resolveUrl(serverUrl, it) }
                val outboxUrl = props?.get("schedule-outbox-URL")?.let { resolveUrl(serverUrl, it) }
                if (inboxUrl != null || outboxUrl != null) {
                    SchedulingUrls(
                        scheduleInboxUrl = inboxUrl,
                        scheduleOutboxUrl = outboxUrl
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null  // Scheduling not supported - continue without it
        }

        return DavResult.success(
            CalDavAccount(
                serverUrl = serverUrl,
                principalUrl = principalUrl,
                calendarHomeUrl = calendarHomeUrl,
                calendars = calendarsResult.value,
                schedulingUrls = schedulingUrls
            )
        )
    }

    /**
     * Build well-known CalDAV URL from server URL.
     *
     * Extracts scheme://host:port and appends /.well-known/caldav
     * Strips any existing path from the URL.
     */
    private fun buildWellKnownUrl(serverUrl: String): String {
        // Extract scheme://host:port, ignoring any path
        val hostMatch = Regex("""(https?://[^/]+)""").find(serverUrl)
        val host = hostMatch?.groupValues?.get(1) ?: serverUrl.trimEnd('/')
        return "$host/.well-known/caldav"
    }

    /**
     * Discover the current-user-principal URL.
     */
    fun discoverPrincipal(url: String): DavResult<String> {
        val result = client.propfind(
            url = url,
            body = RequestBuilder.propfindPrincipal(),
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            multistatus.responses.firstOrNull()
                ?.properties?.currentUserPrincipal
                ?: throw DavException.ParseException("current-user-principal not found")
        }
    }

    /**
     * Discover the calendar-home-set URL from principal.
     */
    fun discoverCalendarHome(principalUrl: String): DavResult<String> {
        val result = client.propfind(
            url = principalUrl,
            body = RequestBuilder.propfindCalendarHome(),
            depth = DavDepth.ZERO
        )

        return result.map { multistatus ->
            multistatus.responses.firstOrNull()
                ?.properties?.calendarHomeSet
                ?: throw DavException.ParseException("calendar-home-set not found")
        }
    }

    /**
     * List all calendars in the calendar home.
     */
    fun listCalendars(calendarHomeUrl: String): DavResult<List<Calendar>> {
        val result = client.propfind(
            url = calendarHomeUrl,
            body = RequestBuilder.propfindCalendars(),
            depth = DavDepth.ONE
        )

        return result.map { multistatus ->
            multistatus.responses.mapNotNull { response ->
                // Skip the calendar-home itself (first response)
                if (response.href == calendarHomeUrl || response.href.trimEnd('/') == calendarHomeUrl.trimEnd('/')) {
                    return@mapNotNull null
                }
                Calendar.fromDavProperties(
                    resolveUrl(calendarHomeUrl, response.href),
                    response.properties
                )
            }
        }
    }

    /**
     * Resolve relative URL against base URL.
     */
    private fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }

        val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val resolvedPath = if (path.startsWith("/")) path else "/$path"

        // Extract host from base URL
        val hostMatch = Regex("""(https?://[^/]+)""").find(base)
        val host = hostMatch?.groupValues?.get(1) ?: return "$base$resolvedPath"

        return "$host$resolvedPath"
    }

    /**
     * Discover CalDAV account from email address using DNS-SRV.
     *
     * Discovery order:
     * 1. DNS-SRV lookup for _caldavs._tcp.{domain} or _caldav._tcp.{domain}
     * 2. If SRV found, use that server URL
     * 3. Otherwise, fall back to https://{domain} discovery
     *
     * @param email User's email address (e.g., "user@example.com")
     * @return CalDavAccount with discovered calendars
     */
    fun discoverFromEmail(email: String): DavResult<CalDavAccount> {
        val domain = DnsSrvDiscovery.extractDomain(email)
            ?: return DavResult.parseError("Invalid email format: $email")

        // Try DNS-SRV first (if enabled)
        if (dnsSrvDiscovery != null) {
            val srvResult = dnsSrvDiscovery.discoverServerUrl(domain)
            if (srvResult is DavResult.Success && srvResult.value != null) {
                val result = discoverAccount(srvResult.value.toUrl())
                if (result.isSuccess) return result
                // Fall through to domain-based discovery if SRV-discovered URL fails
            }
        }

        // Fallback: construct URL from domain
        return discoverAccount("https://$domain")
    }

    companion object {
        /**
         * Create discovery helper with Basic auth.
         *
         * Uses WebDavClient.withAuth() which handles redirects properly,
         * preserving authentication headers across cross-host redirects
         * (critical for iCloud which redirects to partition servers).
         */
        fun withBasicAuth(
            username: String,
            password: String
        ): CalDavDiscovery {
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth)
            val client = WebDavClient(httpClient, auth)
            return CalDavDiscovery(client)
        }

        /**
         * Create discovery helper with Basic auth and DNS-SRV support.
         *
         * Enables email-based discovery using DNS-SRV records (RFC 6764).
         *
         * @param username Username for Basic auth
         * @param password Password for Basic auth
         * @param dnsResolver Optional custom DNS resolver (defaults to DnsJavaResolver)
         * @param enableWellKnownFallback Whether to try /.well-known/caldav on failure
         * @return CalDavDiscovery configured for DNS-SRV discovery
         */
        fun withBasicAuthAndDns(
            username: String,
            password: String,
            dnsResolver: DnsResolver = DnsJavaResolver(),
            enableWellKnownFallback: Boolean = true
        ): CalDavDiscovery {
            val auth = DavAuth.Basic(username, password)
            val httpClient = WebDavClient.withAuth(auth)
            val client = WebDavClient(httpClient, auth)
            return CalDavDiscovery(
                client = client,
                dnsSrvDiscovery = DnsSrvDiscovery(dnsResolver),
                enableWellKnownFallback = enableWellKnownFallback
            )
        }
    }
}