package org.onekash.icaldav.client

/**
 * CalDAV server capabilities discovered via OPTIONS request.
 *
 * Used to determine which features are available before using them,
 * enabling graceful degradation on servers with limited support.
 *
 * @property davClasses DAV compliance classes from DAV header (e.g., "1", "2", "3", "calendar-access")
 * @property allowedMethods Allowed HTTP methods from Allow header (e.g., "PROPFIND", "REPORT", "MKCALENDAR")
 * @property rawDavHeader Raw DAV header value for debugging
 * @property discoveredAt Timestamp when capabilities were discovered (for cache expiry)
 *
 * @see <a href="https://devguide.calconnect.org/CalDAV/building-a-caldav-client/">CalConnect Developer Guide</a>
 */
data class ServerCapabilities(
    val davClasses: Set<String>,
    val allowedMethods: Set<String>,
    val rawDavHeader: String?,
    val discoveredAt: Long = System.currentTimeMillis()
) {
    /**
     * Whether server supports CalDAV (calendar-access extension).
     */
    val supportsCalDav: Boolean
        get() = davClasses.any { it.equals("calendar-access", ignoreCase = true) }

    /**
     * Whether server supports WebDAV sync (RFC 6578).
     * Indicated by "sync-collection" or DAV compliance class "3".
     */
    val supportsSyncCollection: Boolean
        get() = davClasses.any {
            it.equals("sync-collection", ignoreCase = true) ||
            it == "3"
        }

    /**
     * Whether server supports extended-mkcol for creating collections with properties.
     */
    val supportsExtendedMkcol: Boolean
        get() = davClasses.any { it.equals("extended-mkcol", ignoreCase = true) }

    /**
     * Whether server supports calendar-auto-schedule for automated scheduling.
     */
    val supportsAutoSchedule: Boolean
        get() = davClasses.any { it.equals("calendar-auto-schedule", ignoreCase = true) }

    /**
     * Whether PROPFIND method is allowed.
     */
    val supportsPropfind: Boolean
        get() = allowedMethods.any { it.equals("PROPFIND", ignoreCase = true) }

    /**
     * Whether REPORT method is allowed (used for calendar-query, sync-collection).
     */
    val supportsReport: Boolean
        get() = allowedMethods.any { it.equals("REPORT", ignoreCase = true) }

    /**
     * Whether MKCALENDAR method is allowed (for creating calendars).
     */
    val supportsMkcalendar: Boolean
        get() = allowedMethods.any { it.equals("MKCALENDAR", ignoreCase = true) }

    companion object {
        /**
         * Unknown capabilities (when OPTIONS fails or returns no useful info).
         */
        val UNKNOWN = ServerCapabilities(
            davClasses = emptySet(),
            allowedMethods = emptySet(),
            rawDavHeader = null
        )

        /**
         * Parse capabilities from OPTIONS response headers.
         *
         * @param davHeader Value of DAV header (comma-separated list)
         * @param allowHeader Value of Allow header (comma-separated methods)
         * @return Parsed ServerCapabilities
         */
        fun fromHeaders(davHeader: String?, allowHeader: String?): ServerCapabilities {
            val davClasses = davHeader?.split(",")
                ?.map { it.trim().removeSurrounding("<", ">") }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()

            val methods = allowHeader?.split(",")
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()

            return ServerCapabilities(
                davClasses = davClasses,
                allowedMethods = methods,
                rawDavHeader = davHeader
            )
        }
    }
}
