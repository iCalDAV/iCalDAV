package org.onekash.icaldav.model

import org.onekash.icaldav.model.DavProperties

/**
 * Represents a CalDAV calendar resource.
 */
data class Calendar(
    /** Full URL to the calendar collection */
    val href: String,

    /** Display name shown to user */
    val displayName: String,

    /** Calendar description */
    val description: String?,

    /** Calendar color (hex format like #FF5733 or named) */
    val color: String?,

    /** Change tag for detecting modifications */
    val ctag: String?,

    /** Sync token for incremental sync */
    val syncToken: String?,

    /** Supported component types (VEVENT, VTODO, etc.) */
    val supportedComponents: Set<String>,

    /** Whether this calendar is read-only */
    val readOnly: Boolean = false
) {
    /**
     * Check if this calendar supports events.
     */
    fun supportsEvents(): Boolean = supportedComponents.isEmpty() || supportedComponents.contains("VEVENT")

    /**
     * Check if this calendar supports tasks.
     */
    fun supportsTasks(): Boolean = supportedComponents.contains("VTODO")

    companion object {
        /**
         * Create Calendar from WebDAV properties.
         */
        fun fromDavProperties(href: String, props: DavProperties): Calendar? {
            // Only include actual calendars (not collections)
            if (!props.isCalendar) return null

            val supportedComponents = mutableSetOf<String>()
            // Parse supported-calendar-component-set if present
            // <comp name="VEVENT"/><comp name="VTODO"/>
            props.get("supported-calendar-component-set")?.let { value ->
                if (value.contains("VEVENT", ignoreCase = true)) supportedComponents.add("VEVENT")
                if (value.contains("VTODO", ignoreCase = true)) supportedComponents.add("VTODO")
                if (value.contains("VJOURNAL", ignoreCase = true)) supportedComponents.add("VJOURNAL")
            }

            return Calendar(
                href = href,
                displayName = props.displayName ?: extractNameFromHref(href),
                description = props.calendarDescription,
                color = props.calendarColor?.let { normalizeColor(it) },
                ctag = props.ctag,
                syncToken = props.syncToken,
                supportedComponents = supportedComponents
            )
        }

        /**
         * Extract calendar name from URL path.
         */
        private fun extractNameFromHref(href: String): String {
            return href.trimEnd('/')
                .substringAfterLast('/')
                .ifEmpty { "Calendar" }
        }

        /**
         * Normalize color to hex format.
         */
        private fun normalizeColor(color: String): String {
            val trimmed = color.trim()
            // Handle various formats: #RGB, #RRGGBB, #RRGGBBAA
            return when {
                trimmed.startsWith("#") -> trimmed.take(7)  // Keep #RRGGBB only
                trimmed.matches(Regex("[0-9A-Fa-f]{6}")) -> "#$trimmed"
                else -> trimmed
            }
        }
    }
}

/**
 * CalDAV account containing discovered calendar resources.
 */
data class CalDavAccount(
    /** Base URL for the CalDAV server */
    val serverUrl: String,

    /** Principal URL for the user */
    val principalUrl: String,

    /** Calendar home URL where calendars are stored */
    val calendarHomeUrl: String,

    /** List of discovered calendars */
    val calendars: List<Calendar>,

    /** Scheduling URLs (RFC 6638) - null if scheduling not supported */
    val schedulingUrls: SchedulingUrls? = null
) {
    /**
     * Find a calendar by its display name.
     */
    fun findByName(name: String): Calendar? =
        calendars.find { it.displayName.equals(name, ignoreCase = true) }

    /**
     * Find a calendar by its URL.
     */
    fun findByHref(href: String): Calendar? =
        calendars.find { it.href == href || it.href.endsWith(href) }

    /**
     * Get the default calendar (first in list).
     */
    val defaultCalendar: Calendar? get() = calendars.firstOrNull()

    /**
     * Get only calendars that support events.
     */
    val eventCalendars: List<Calendar> get() = calendars.filter { it.supportsEvents() }
}

/**
 * CalDAV server discovery result.
 */
data class DiscoveryResult(
    val principalUrl: String,
    val calendarHomeUrl: String
)

/**
 * Credentials for CalDAV authentication.
 */
data class CalDavCredentials(
    val username: String,
    val password: String
) {
    /**
     * Create a masked version for logging.
     */
    fun masked(): String = "$username:****"
}
