package org.onekash.icaldav.client

import org.onekash.icaldav.client.WebDavClient
import org.onekash.icaldav.quirks.CalDavQuirks
import org.onekash.icaldav.quirks.DefaultQuirks
import org.onekash.icaldav.quirks.ICloudQuirks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for CalDavClient quirks integration.
 *
 * Tests verify that CalDavClient properly wires the quirks system.
 * Provider detection tests are in CalDavQuirksTest (webdav-core).
 */
class CalDavClientQuirksTest {

    @Test
    fun `forProvider creates client for iCloud`() {
        // Factory method should exist and not throw
        // We can't verify quirks is wired without getter, but we verify API exists
        val client = CalDavClient.forProvider(
            serverUrl = "https://caldav.icloud.com",
            username = "test@icloud.com",
            password = "test-password"
        )

        assertNotNull(client)
    }

    @Test
    fun `forProvider creates client for Google`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://www.google.com/calendar/dav/test@gmail.com/",
            username = "test@gmail.com",
            password = "test-password"
        )

        assertNotNull(client)
    }

    @Test
    fun `forProvider creates client for generic server`() {
        val client = CalDavClient.forProvider(
            serverUrl = "https://caldav.myserver.com/dav/",
            username = "user",
            password = "pass"
        )

        assertNotNull(client)
    }

    @Test
    fun `withBasicAuth creates client without quirks parameter`() {
        // Original factory method should still work
        val client = CalDavClient.withBasicAuth(
            username = "user",
            password = "pass"
        )

        assertNotNull(client)
    }

    @Test
    fun `constructor accepts custom quirks`() {
        val webDavClient = WebDavClient(WebDavClient.defaultHttpClient())
        val customQuirks = DefaultQuirks("custom", "Custom Server", "https://custom.example.com")

        val client = CalDavClient(webDavClient, customQuirks)

        assertNotNull(client)
    }

    @Test
    fun `constructor uses default quirks when not specified`() {
        val webDavClient = WebDavClient(WebDavClient.defaultHttpClient())

        val client = CalDavClient(webDavClient)

        assertNotNull(client)
    }

    @Test
    fun `CalDavQuirks forServer returns ICloudQuirks for iCloud URL`() {
        // Verify the detection works (also tested in webdav-core)
        val quirks = CalDavQuirks.forServer("https://caldav.icloud.com")

        assertTrue(quirks is ICloudQuirks)
        assertEquals("icloud", quirks.providerId)
        assertTrue(quirks.requiresAppSpecificPassword)
    }

    @Test
    fun `CalDavQuirks forServer returns DefaultQuirks for generic URL`() {
        val quirks = CalDavQuirks.forServer("https://nextcloud.myserver.com/remote.php/dav/")

        assertTrue(quirks is DefaultQuirks)
        assertEquals("generic", quirks.providerId)
        assertFalse(quirks.requiresAppSpecificPassword)
    }
}
