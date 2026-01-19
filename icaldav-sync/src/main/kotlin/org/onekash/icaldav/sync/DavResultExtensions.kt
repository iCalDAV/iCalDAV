package org.onekash.icaldav.sync

import org.onekash.icaldav.model.DavResult

/**
 * Extract error message from any DavResult failure.
 * Internal visibility - shared across sync module.
 */
internal fun DavResult<*>.errorMessage(): String = when (this) {
    is DavResult.HttpError -> "HTTP $code: $message"
    is DavResult.NetworkError -> exception.message ?: "Network error"
    is DavResult.ParseError -> message
    is DavResult.Success -> "Success"
}

/**
 * Determine if a DavResult error is retryable.
 *
 * Retryable: Network errors, 5xx server errors, 429 rate limiting
 * Not retryable: 4xx client errors (except 429), parse errors
 */
internal fun DavResult<*>.isRetryable(): Boolean = when (this) {
    is DavResult.NetworkError -> true
    is DavResult.HttpError -> when (code) {
        in 500..599 -> true  // Server errors
        429 -> true          // Rate limited
        else -> false        // 4xx client errors are not retryable
    }
    is DavResult.ParseError -> false
    is DavResult.Success -> false
}
