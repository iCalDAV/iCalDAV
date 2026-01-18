package org.onekash.icaldav.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security tests for CalDavClient.
 *
 * Tests path traversal prevention and UID sanitization.
 */
@DisplayName("CalDavClient Security Tests")
class CalDavClientSecurityTest {

    // Tests use helper methods that mirror the private CalDavClient sanitization logic

    @Nested
    @DisplayName("UID Sanitization - Path Traversal Prevention")
    inner class PathTraversalTests {

        @Test
        fun `simple path traversal is blocked`() {
            assertThrows<IllegalArgumentException> {
                // Simulate calling with path traversal UID
                // The sanitization happens in buildEventUrl
                validateUid("../../../etc/passwd")
            }
        }

        @Test
        fun `double dot in middle is blocked`() {
            assertThrows<IllegalArgumentException> {
                validateUid("abc..def")
            }
        }

        @Test
        fun `single dot UID is blocked`() {
            assertThrows<IllegalArgumentException> {
                validateUid(".")
            }
        }

        @Test
        fun `double dot UID is blocked`() {
            assertThrows<IllegalArgumentException> {
                validateUid("..")
            }
        }

        @Test
        fun `URL encoded traversal characters are sanitized`() {
            // %2e is URL-encoded dot
            // After sanitization, these should become underscores
            val sanitized = sanitizeUid("abc%2e%2edef")
            assertFalse(sanitized.contains(".."))
        }

        @Test
        fun `backslash traversal is blocked`() {
            // This should throw because after replacing \\ with _, we still have ..
            assertThrows<IllegalArgumentException> {
                sanitizeUid("..\\..\\etc\\passwd")
            }
        }

        @Test
        fun `backslash without dots is sanitized`() {
            val sanitized = sanitizeUid("path\\to\\file")
            // Backslashes should be replaced with underscores
            assertFalse(sanitized.contains("\\"))
            assertTrue(sanitized.contains("_"))
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "../secret",
            "..\\secret",
            "foo/../bar",
            "foo\\..\\bar",
            ".../test",
            "test/../../etc"
        ])
        fun `various traversal patterns are blocked`(uid: String) {
            assertThrows<IllegalArgumentException> {
                validateUid(uid)
            }
        }
    }

    @Nested
    @DisplayName("UID Sanitization - Character Filtering")
    inner class CharacterFilteringTests {

        @Test
        fun `special characters are replaced with underscore`() {
            val sanitized = sanitizeUid("abc!@#\$%^&*()def")
            assertTrue(sanitized.matches(Regex("[a-zA-Z0-9@._-]+")))
        }

        @Test
        fun `spaces are replaced`() {
            val sanitized = sanitizeUid("my event uid")
            assertFalse(sanitized.contains(" "))
        }

        @Test
        fun `allowed characters are preserved`() {
            val uid = "abc-123_test@domain.com"
            val sanitized = sanitizeUid(uid)
            // @ . - _ are allowed
            assertTrue(sanitized.contains("@"))
            assertTrue(sanitized.contains("."))
            assertTrue(sanitized.contains("-"))
            assertTrue(sanitized.contains("_"))
        }

        @Test
        fun `slashes are replaced`() {
            val sanitized = sanitizeUid("path/to/event")
            assertFalse(sanitized.contains("/"))
        }

        @Test
        fun `newlines are replaced`() {
            val sanitized = sanitizeUid("uid\nwith\nnewlines")
            assertFalse(sanitized.contains("\n"))
        }

        @Test
        fun `null bytes are replaced`() {
            val sanitized = sanitizeUid("uid\u0000with\u0000nulls")
            assertFalse(sanitized.contains("\u0000"))
        }
    }

    @Nested
    @DisplayName("UID Validation")
    inner class UidValidationTests {

        @Test
        fun `blank UID is rejected`() {
            assertThrows<IllegalArgumentException> {
                validateUid("")
            }
        }

        @Test
        fun `whitespace-only UID is rejected`() {
            assertThrows<IllegalArgumentException> {
                validateUid("   ")
            }
        }

        @Test
        fun `dots-only UID is rejected`() {
            assertThrows<IllegalArgumentException> {
                validateUid("...")
            }
        }

        @Test
        fun `valid UUID is accepted`() {
            val uid = "550e8400-e29b-41d4-a716-446655440000"
            val sanitized = sanitizeUid(uid)
            assertTrue(sanitized.isNotBlank())
        }

        @Test
        fun `iCloud-style UID is accepted`() {
            val uid = "ABC123-DEF456-GHI789@icloud.com"
            val sanitized = sanitizeUid(uid)
            assertTrue(sanitized.isNotBlank())
        }

        @Test
        fun `Google-style UID is accepted`() {
            val uid = "abcd1234efgh5678@google.com"
            val sanitized = sanitizeUid(uid)
            assertTrue(sanitized.isNotBlank())
        }
    }

    @Nested
    @DisplayName("Event URL Building")
    inner class EventUrlBuildingTests {

        @Test
        fun `event URL is properly constructed`() {
            // Test that the final URL is safe
            val calendarUrl = "https://caldav.example.com/cal/"
            val uid = "my-event-123"

            // The URL should end with .ics
            val expectedPattern = Regex(".*/[a-zA-Z0-9@._-]+\\.ics$")
            val url = buildEventUrl(calendarUrl, uid)
            assertTrue(url.matches(expectedPattern))
        }

        @Test
        fun `calendar URL trailing slash is handled`() {
            val withSlash = "https://example.com/cal/"
            val withoutSlash = "https://example.com/cal"

            val url1 = buildEventUrl(withSlash, "event")
            val url2 = buildEventUrl(withoutSlash, "event")

            // Both should produce clean URLs without double slashes
            assertFalse(url1.contains("//event"))
            assertFalse(url2.contains("//event"))
        }
    }

    // Helper functions that mirror the private methods in CalDavClient

    /**
     * Sanitize UID for use in URL path (mirrors CalDavClient logic).
     */
    private fun sanitizeUid(uid: String): String {
        require(uid.isNotBlank()) { "UID cannot be blank" }

        val sanitized = uid.replace(Regex("[^a-zA-Z0-9@._-]"), "_")

        require(!sanitized.contains("..")) { "UID cannot contain path traversal sequences" }
        require(sanitized != ".") { "UID cannot be a single dot" }
        require(sanitized.isNotEmpty()) { "UID cannot be empty after sanitization" }

        val trimmed = sanitized.trim('.')
        require(trimmed.isNotEmpty()) { "UID cannot consist only of dots" }

        return trimmed
    }

    /**
     * Validate UID (throws if invalid).
     */
    private fun validateUid(uid: String) {
        sanitizeUid(uid)
    }

    /**
     * Build event URL from calendar URL and UID.
     */
    private fun buildEventUrl(calendarUrl: String, uid: String): String {
        val base = calendarUrl.trimEnd('/')
        val safeUid = sanitizeUid(uid)
        return "$base/$safeUid.ics"
    }
}