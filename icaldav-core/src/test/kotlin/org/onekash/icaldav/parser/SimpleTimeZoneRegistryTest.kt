package org.onekash.icaldav.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SimpleTimeZoneRegistry.
 *
 * Verifies the registry provides safe defaults that work on Android
 * without requiring ZoneRulesProvider.
 */
@DisplayName("SimpleTimeZoneRegistry")
class SimpleTimeZoneRegistryTest {

    private val registry = SimpleTimeZoneRegistry()

    @Test
    @DisplayName("getTimeZone returns null for any ID")
    fun `getTimeZone returns null`() {
        assertNull(registry.getTimeZone("America/New_York"))
        assertNull(registry.getTimeZone("Europe/London"))
        assertNull(registry.getTimeZone("UTC"))
        assertNull(registry.getTimeZone(null))
        assertNull(registry.getTimeZone(""))
    }

    @Test
    @DisplayName("register does not throw")
    fun `register is no-op`() {
        // Should not throw
        registry.register(null)
        registry.register(null, false)
        registry.register(null, true)
    }

    @Test
    @DisplayName("clear does not throw")
    fun `clear is no-op`() {
        // Should not throw
        registry.clear()
    }

    @Test
    @DisplayName("getZoneRules returns empty map")
    fun `getZoneRules returns empty`() {
        val rules = registry.getZoneRules()
        assertTrue(rules.isEmpty())
    }

    @Test
    @DisplayName("getZoneId returns valid ZoneId for standard timezone")
    fun `getZoneId works for standard timezones`() {
        val nyZone = registry.getZoneId("America/New_York")
        assertEquals(ZoneId.of("America/New_York"), nyZone)

        val utcZone = registry.getZoneId("UTC")
        assertEquals(ZoneId.of("UTC"), utcZone)

        val tokyoZone = registry.getZoneId("Asia/Tokyo")
        assertEquals(ZoneId.of("Asia/Tokyo"), tokyoZone)
    }

    @Test
    @DisplayName("getZoneId returns null for invalid timezone")
    fun `getZoneId returns null for invalid`() {
        assertNull(registry.getZoneId("Invalid/Timezone"))
        assertNull(registry.getZoneId(null))
        assertNull(registry.getZoneId(""))
        assertNull(registry.getZoneId("   "))
    }

    @Test
    @DisplayName("getZoneId normalizes Windows timezone names")
    fun `getZoneId normalizes Windows timezones`() {
        val pacific = registry.getZoneId("Pacific Standard Time")
        assertEquals(ZoneId.of("America/Los_Angeles"), pacific)

        val eastern = registry.getZoneId("Eastern Standard Time")
        assertEquals(ZoneId.of("America/New_York"), eastern)

        val central = registry.getZoneId("Central Standard Time")
        assertEquals(ZoneId.of("America/Chicago"), central)

        val mountain = registry.getZoneId("Mountain Standard Time")
        assertEquals(ZoneId.of("America/Denver"), mountain)
    }

    @Test
    @DisplayName("getTzId returns input as-is")
    fun `getTzId passes through`() {
        assertEquals("America/New_York", registry.getTzId("America/New_York"))
        assertEquals("UTC", registry.getTzId("UTC"))
        assertNull(registry.getTzId(null))
    }
}
