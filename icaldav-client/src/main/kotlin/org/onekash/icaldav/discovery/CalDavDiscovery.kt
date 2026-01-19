package org.onekash.icaldav.discovery

import org.onekash.icaldav.model.*
import org.onekash.icaldav.client.DavAuth
import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.model.*
import org.onekash.icaldav.xml.RequestBuilder

/**
 * CalDAV server discovery following RFC 4791 Section 7.1.
 *
 * Discovery flow:
 * 1. PROPFIND on root URL → current-user-principal
 * 2. PROPFIND on principal → calendar-home-set
 * 3. PROPFIND on calendar-home → list calendars
 *
 * Production-tested with iCloud and other CalDAV servers.
 */
class CalDavDiscovery(
    private val client: WebDavClient
) {
    /**
     * Discover CalDAV account starting from server URL.
     *
     * @param serverUrl Base CalDAV URL (e.g., "https://caldav.icloud.com")
     * @return CalDavAccount with discovered calendars
     */
    fun discoverAccount(serverUrl: String): DavResult<CalDavAccount> {
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

        return DavResult.success(
            CalDavAccount(
                serverUrl = serverUrl,
                principalUrl = principalUrl,
                calendarHomeUrl = calendarHomeUrl,
                calendars = calendarsResult.value
            )
        )
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
    }
}