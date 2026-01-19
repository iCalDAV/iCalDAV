package org.onekash.icaldav.model

import java.time.Duration

/**
 * Calendar-level metadata from VCALENDAR properties.
 *
 * Includes RFC 5545 standard properties and RFC 7986 extended properties:
 * - NAME: Human-readable calendar name
 * - SOURCE: URL where calendar can be refreshed from
 * - COLOR: Calendar color for UI display
 * - REFRESH-INTERVAL: Suggested refresh interval for subscriptions
 *
 * Also handles non-standard but widely-used properties:
 * - X-WR-CALNAME: Apple/Google calendar name
 * - X-APPLE-CALENDAR-COLOR: Apple calendar color
 *
 * Example iCalendar format:
 * ```
 * BEGIN:VCALENDAR
 * VERSION:2.0
 * PRODID:-//Example//Calendar//EN
 * NAME:Work Calendar
 * COLOR:crimson
 * SOURCE:https://example.com/calendar.ics
 * REFRESH-INTERVAL;VALUE=DURATION:P1D
 * BEGIN:VEVENT
 * ...
 * END:VEVENT
 * END:VCALENDAR
 * ```
 *
 * @see <a href="https://tools.ietf.org/html/rfc7986">RFC 7986 - iCalendar Extensions</a>
 */
data class ICalCalendar(
    /** PRODID - Product identifier that created this calendar */
    val prodId: String?,

    /** VERSION - iCalendar version (usually "2.0") */
    val version: String = "2.0",

    /** CALSCALE - Calendar scale (usually "GREGORIAN") */
    val calscale: String = "GREGORIAN",

    /** METHOD - iTIP method for scheduling (PUBLISH, REQUEST, REPLY, etc.) */
    val method: String? = null,

    /** NAME - Human-readable calendar name (RFC 7986) */
    val name: String? = null,

    /** SOURCE - URL where calendar can be fetched/refreshed (RFC 7986) */
    val source: String? = null,

    /** COLOR - Calendar color for display (RFC 7986) */
    val color: String? = null,

    /** REFRESH-INTERVAL - Suggested subscription refresh interval (RFC 7986) */
    val refreshInterval: Duration? = null,

    /** X-WR-CALNAME - Non-standard calendar name (Apple/Google) */
    val xWrCalname: String? = null,

    /** X-APPLE-CALENDAR-COLOR - Non-standard calendar color (Apple) */
    val xAppleCalendarColor: String? = null,

    /** IMAGE - Calendar image/icon (RFC 7986) */
    val image: ICalImage? = null,

    /** All VEVENT components in this calendar */
    val events: List<ICalEvent> = emptyList(),

    /** All VTODO components in this calendar */
    val todos: List<ICalTodo> = emptyList()
) {
    /**
     * Get effective calendar name.
     * Prefers RFC 7986 NAME over X-WR-CALNAME.
     */
    val effectiveName: String?
        get() = name ?: xWrCalname

    /**
     * Get effective calendar color.
     * Prefers RFC 7986 COLOR over X-APPLE-CALENDAR-COLOR.
     */
    val effectiveColor: String?
        get() = color ?: xAppleCalendarColor

    /**
     * Check if this calendar has any events.
     */
    fun hasEvents(): Boolean = events.isNotEmpty()

    /**
     * Check if this calendar has any todos.
     */
    fun hasTodos(): Boolean = todos.isNotEmpty()

    /**
     * Get the number of components (events + todos).
     */
    val componentCount: Int
        get() = events.size + todos.size

    companion object {
        /**
         * Create a minimal calendar with default values.
         *
         * @param prodId Product identifier
         * @param name Calendar display name
         * @return ICalCalendar with defaults
         */
        fun create(
            prodId: String = "-//iCalDAV//EN",
            name: String? = null
        ): ICalCalendar {
            return ICalCalendar(
                prodId = prodId,
                name = name
            )
        }
    }
}

/**
 * Placeholder for VTODO support.
 * TODO: Implement full VTodo model in future version.
 */
data class ICalTodo(
    /** Unique identifier from UID property */
    val uid: String,

    /** Task summary/title */
    val summary: String?,

    /** Task description */
    val description: String?,

    /** Due date */
    val due: ICalDateTime?,

    /** Completion percentage (0-100) */
    val percentComplete: Int = 0,

    /** Task status: NEEDS-ACTION, IN-PROCESS, COMPLETED, CANCELLED */
    val status: TodoStatus = TodoStatus.NEEDS_ACTION,

    /** Priority (0=undefined, 1=highest, 9=lowest) */
    val priority: Int = 0
)

/**
 * VTODO status values per RFC 5545.
 */
enum class TodoStatus {
    NEEDS_ACTION,
    IN_PROCESS,
    COMPLETED,
    CANCELLED;

    fun toICalString(): String = name.replace("_", "-")

    companion object {
        fun fromString(value: String?): TodoStatus {
            val normalized = value?.uppercase()?.replace("-", "_")
            return entries.find { it.name == normalized } ?: NEEDS_ACTION
        }
    }
}
