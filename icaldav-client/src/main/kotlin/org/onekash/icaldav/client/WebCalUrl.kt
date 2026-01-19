package org.onekash.icaldav.client

import java.net.URI
import java.net.URL

/**
 * Utility for handling webcal:// URLs and ICS subscription URLs.
 *
 * webcal:// is a URI scheme that indicates a calendar subscription.
 * It should be converted to https:// (or http://) for actual HTTP requests.
 *
 * Examples:
 * - webcal://example.com/calendar.ics → https://example.com/calendar.ics
 * - webcals://example.com/calendar.ics → https://example.com/calendar.ics
 * - http://example.com/calendar.ics → http://example.com/calendar.ics
 * - https://example.com/calendar.ics → https://example.com/calendar.ics
 */
object WebCalUrl {

    /**
     * Convert any calendar URL to HTTP(S) URL.
     *
     * @param url Original URL (may be webcal://, webcals://, http://, https://)
     * @return HTTP(S) URL suitable for fetch
     * @throws IllegalArgumentException if URL is malformed
     */
    fun toHttpUrl(url: String): String {
        val trimmed = url.trim()

        return when {
            trimmed.startsWith("webcal://", ignoreCase = true) ->
                "https://" + trimmed.substring("webcal://".length)

            trimmed.startsWith("webcals://", ignoreCase = true) ->
                "https://" + trimmed.substring("webcals://".length)

            trimmed.startsWith("http://", ignoreCase = true) ->
                trimmed

            trimmed.startsWith("https://", ignoreCase = true) ->
                trimmed

            // No scheme - assume https
            !trimmed.contains("://") ->
                "https://$trimmed"

            else ->
                throw IllegalArgumentException("Unsupported URL scheme: $trimmed")
        }
    }

    /**
     * Check if a URL is a calendar subscription URL.
     *
     * @param url URL to check
     * @return true if URL is likely a calendar subscription
     */
    fun isCalendarUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("webcal://") ||
                lower.startsWith("webcals://") ||
                lower.endsWith(".ics") ||
                lower.contains("/calendar") ||
                lower.contains("ical")
    }

    /**
     * Extract calendar name from URL path.
     *
     * @param url Calendar URL
     * @return Suggested name based on URL, or null
     */
    fun suggestName(url: String): String? {
        return try {
            val httpUrl = toHttpUrl(url)
            val uri = URI(httpUrl)
            val path = uri.path ?: return null

            // Get filename without extension
            val filename = path.substringAfterLast('/').removeSuffix(".ics")

            if (filename.isNotBlank() && filename != "calendar") {
                // Convert URL-encoded and hyphenated names to readable format
                filename
                    .replace("%20", " ")
                    .replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
            } else {
                // Use hostname as fallback
                uri.host?.removePrefix("www.")?.substringBefore(".")?.replaceFirstChar { it.uppercase() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate a calendar URL.
     *
     * @param url URL to validate
     * @return Validation result
     */
    fun validate(url: String): ValidationResult {
        if (url.isBlank()) {
            return ValidationResult.Invalid("URL cannot be empty")
        }

        return try {
            val httpUrl = toHttpUrl(url)
            val uri = URI(httpUrl)

            if (uri.host.isNullOrBlank()) {
                return ValidationResult.Invalid("URL must have a host")
            }

            if (uri.scheme != "http" && uri.scheme != "https") {
                return ValidationResult.Invalid("URL must use HTTP or HTTPS")
            }

            ValidationResult.Valid(httpUrl)
        } catch (e: IllegalArgumentException) {
            ValidationResult.Invalid(e.message ?: "Invalid URL")
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed URL: ${e.message}")
        }
    }

    /**
     * Result of URL validation.
     */
    sealed class ValidationResult {
        data class Valid(val httpUrl: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Build a webcal:// URL from an HTTP(S) URL.
     * Useful for sharing/exporting subscriptions.
     *
     * @param httpUrl HTTP or HTTPS URL
     * @return webcal:// URL
     */
    fun toWebCalUrl(httpUrl: String): String {
        val trimmed = httpUrl.trim()

        return when {
            trimmed.startsWith("https://", ignoreCase = true) ->
                "webcal://" + trimmed.substring("https://".length)

            trimmed.startsWith("http://", ignoreCase = true) ->
                "webcal://" + trimmed.substring("http://".length)

            trimmed.startsWith("webcal://", ignoreCase = true) ->
                trimmed

            else ->
                "webcal://$trimmed"
        }
    }
}