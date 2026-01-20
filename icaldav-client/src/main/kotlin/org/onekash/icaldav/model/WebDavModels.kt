package org.onekash.icaldav.model

/**
 * WebDAV response from a PROPFIND/REPORT operation.
 * Represents a <D:response> element in multistatus XML.
 */
data class DavResponse(
    /** URL (href) of the resource */
    val href: String,

    /** HTTP status code (e.g., 200, 404) */
    val status: Int,

    /** Properties returned for this resource */
    val properties: DavProperties,

    /** ETag for conflict detection */
    val etag: String?,

    /** Raw calendar data (for calendar-query REPORT) */
    val calendarData: String?
)

/**
 * Collection of DAV properties from a response.
 *
 * @property properties Map of property names to their string values
 * @property propertyStatus Map of property names to their individual status (for per-property error handling)
 */
data class DavProperties(
    val properties: Map<String, String?> = emptyMap(),
    val propertyStatus: Map<String, PropertyStatus> = emptyMap()
) {
    fun get(name: String): String? = properties[name]

    /**
     * Get the status for a specific property.
     * @return PropertyStatus if available, null if status wasn't tracked
     */
    fun getStatus(name: String): PropertyStatus? = propertyStatus[name]

    /**
     * Check if a property was successfully retrieved (200 status).
     * Returns true if no status tracking is available (backward compat).
     */
    fun hasProperty(name: String): Boolean {
        val status = propertyStatus[name]
        return status == null || status.isSuccess
    }

    /**
     * Properties that failed to be retrieved (non-2xx status).
     */
    val failedProperties: Map<String, PropertyStatus>
        get() = propertyStatus.filter { !it.value.isSuccess }

    /**
     * Properties that were not found (404 status).
     */
    val notFoundProperties: Set<String>
        get() = propertyStatus.filter { it.value.isNotFound }.keys

    // Common WebDAV properties
    val displayName: String? get() = properties["displayname"]
    val resourceType: String? get() = properties["resourcetype"]
    val contentType: String? get() = properties["getcontenttype"]
    val contentLength: Long? get() = properties["getcontentlength"]?.toLongOrNull()
    val etag: String? get() = properties["getetag"]
    val lastModified: String? get() = properties["getlastmodified"]

    // CalDAV specific properties
    val calendarHomeSet: String? get() = properties["calendar-home-set"]
    val currentUserPrincipal: String? get() = properties["current-user-principal"]
    val ctag: String? get() = properties["getctag"] ?: properties["cs:getctag"]
    val syncToken: String? get() = properties["sync-token"]
    val calendarColor: String? get() = properties["calendar-color"]
    val calendarDescription: String? get() = properties["calendar-description"]

    // Resource type checks
    val isCalendar: Boolean get() = resourceType?.contains("calendar") == true
    val isCollection: Boolean get() = resourceType?.contains("collection") == true
    val isPrincipal: Boolean get() = resourceType?.contains("principal") == true

    companion object {
        fun from(map: Map<String, String?>): DavProperties = DavProperties(map)

        fun from(
            map: Map<String, String?>,
            status: Map<String, PropertyStatus>
        ): DavProperties = DavProperties(map, status)

        val EMPTY = DavProperties()
    }
}

/**
 * Parsed multistatus response from WebDAV/CalDAV server.
 */
data class MultiStatus(
    val responses: List<DavResponse>,
    val syncToken: String? = null
) {
    fun findByHref(href: String): DavResponse? =
        responses.find { it.href == href || it.href.endsWith(href) }

    fun filterCalendars(): List<DavResponse> =
        responses.filter { it.properties.isCalendar }

    fun filterWithCalendarData(): List<DavResponse> =
        responses.filter { it.calendarData != null }

    val isEmpty: Boolean get() = responses.isEmpty()
    val size: Int get() = responses.size

    companion object {
        val EMPTY = MultiStatus(emptyList())
    }
}

/**
 * WebDAV request depth header.
 */
enum class DavDepth(val value: String) {
    ZERO("0"),
    ONE("1"),
    INFINITY("infinity")
}

/**
 * Status of an individual property in a WebDAV propstat response.
 *
 * WebDAV servers return per-property status in propstat elements, allowing
 * different properties to have different success/failure states.
 *
 * @property statusCode HTTP status code for this property (e.g., 200, 404, 403)
 * @property statusText HTTP status text (e.g., "OK", "Not Found", "Forbidden")
 */
data class PropertyStatus(
    val statusCode: Int,
    val statusText: String
) {
    /** Whether the property was successfully retrieved (2xx status) */
    val isSuccess: Boolean get() = statusCode in 200..299

    /** Whether the property was not found (404) */
    val isNotFound: Boolean get() = statusCode == 404

    /** Whether the property access was forbidden (403) */
    val isForbidden: Boolean get() = statusCode == 403

    companion object {
        val OK = PropertyStatus(200, "OK")
        val NOT_FOUND = PropertyStatus(404, "Not Found")
        val FORBIDDEN = PropertyStatus(403, "Forbidden")

        /**
         * Parse status from HTTP status line (e.g., "HTTP/1.1 200 OK").
         */
        fun fromStatusLine(statusLine: String): PropertyStatus {
            val parts = statusLine.trim().split(Regex("\\s+"), limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val text = parts.getOrNull(2) ?: "Unknown"
            return PropertyStatus(code, text)
        }
    }
}

/**
 * Standard WebDAV/CalDAV property names.
 */
object DavPropertyNames {
    // Core WebDAV (RFC 4918)
    const val DISPLAY_NAME = "displayname"
    const val RESOURCE_TYPE = "resourcetype"
    const val GET_ETAG = "getetag"
    const val GET_LAST_MODIFIED = "getlastmodified"
    const val GET_CONTENT_TYPE = "getcontenttype"
    const val GET_CONTENT_LENGTH = "getcontentlength"

    // WebDAV Principal (RFC 3744)
    const val CURRENT_USER_PRINCIPAL = "current-user-principal"

    // CalDAV (RFC 4791)
    const val CALENDAR_HOME_SET = "calendar-home-set"
    const val CALENDAR_DATA = "calendar-data"
    const val SUPPORTED_CALENDAR_COMPONENT_SET = "supported-calendar-component-set"

    // CalDAV Extensions
    const val CALENDAR_COLOR = "calendar-color"
    const val CALENDAR_DESCRIPTION = "calendar-description"
    const val CTAG = "getctag"
    const val SYNC_TOKEN = "sync-token"
}

/**
 * WebDAV operation result.
 */
sealed class DavResult<out T> {
    data class Success<T>(val value: T) : DavResult<T>()
    data class HttpError(val code: Int, val message: String) : DavResult<Nothing>()
    data class NetworkError(val exception: Exception) : DavResult<Nothing>()
    data class ParseError(val message: String, val rawResponse: String?) : DavResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is HttpError -> throw DavException.HttpException(code, message)
        is NetworkError -> throw DavException.NetworkException(exception)
        is ParseError -> throw DavException.ParseException(message)
    }

    inline fun <R> map(transform: (T) -> R): DavResult<R> = when (this) {
        is Success -> Success(transform(value))
        is HttpError -> this
        is NetworkError -> this
        is ParseError -> this
    }

    companion object {
        fun <T> success(value: T): DavResult<T> = Success(value)
        fun <T> httpError(code: Int, message: String): DavResult<T> = HttpError(code, message)
        fun <T> networkError(e: Exception): DavResult<T> = NetworkError(e)
        fun <T> parseError(message: String, raw: String? = null): DavResult<T> = ParseError(message, raw)
    }
}

/**
 * WebDAV exceptions for error handling.
 */
sealed class DavException(message: String) : Exception(message) {
    class HttpException(val code: Int, message: String) : DavException("HTTP $code: $message")
    class NetworkException(cause: Exception) : DavException("Network error: ${cause.message}")
    class ParseException(message: String) : DavException("Parse error: $message")
}
