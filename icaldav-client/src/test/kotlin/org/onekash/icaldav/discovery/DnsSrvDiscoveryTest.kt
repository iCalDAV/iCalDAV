package org.onekash.icaldav.discovery

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.DavResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for DNS-SRV based CalDAV discovery (RFC 6764).
 */
@DisplayName("DNS-SRV Discovery Tests")
class DnsSrvDiscoveryTest {

    /**
     * Mock DNS resolver for testing.
     */
    private class MockDnsResolver : DnsResolver {
        var secureRecords: List<DnsResolver.SrvRecord>? = null
        var insecureRecords: List<DnsResolver.SrvRecord>? = null

        override fun lookupSrv(name: String): List<DnsResolver.SrvRecord>? {
            return when {
                name.startsWith("_caldavs._tcp.") -> secureRecords
                name.startsWith("_caldav._tcp.") -> insecureRecords
                else -> null
            }
        }
    }

    @Nested
    @DisplayName("SRV Record Parsing")
    inner class SrvRecordParsingTests {

        @Test
        fun `returns first record sorted by priority`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = listOf(
                DnsResolver.SrvRecord("server2.example.com", 443, priority = 20, weight = 100),
                DnsResolver.SrvRecord("server1.example.com", 443, priority = 10, weight = 100)
            )

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            val srv = (result as DavResult.Success).value!!
            assertEquals("server1.example.com", srv.host)
            assertEquals(10, srv.priority)
        }

        @Test
        fun `prefers caldavs over caldav`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = listOf(
                DnsResolver.SrvRecord("secure.example.com", 443, 10, 100)
            )
            resolver.insecureRecords = listOf(
                DnsResolver.SrvRecord("insecure.example.com", 80, 10, 100)
            )

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            val srv = (result as DavResult.Success).value!!
            assertEquals("secure.example.com", srv.host)
            assertTrue(srv.isSecure)
        }

        @Test
        fun `falls back to caldav when caldavs not found`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = null
            resolver.insecureRecords = listOf(
                DnsResolver.SrvRecord("caldav.example.com", 80, 10, 100)
            )

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            val srv = (result as DavResult.Success).value!!
            assertEquals("caldav.example.com", srv.host)
            assertFalse(srv.isSecure)
        }

        @Test
        fun `sorts by priority then weight`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = listOf(
                DnsResolver.SrvRecord("low-weight.example.com", 443, priority = 10, weight = 50),
                DnsResolver.SrvRecord("high-weight.example.com", 443, priority = 10, weight = 100),
                DnsResolver.SrvRecord("high-priority.example.com", 443, priority = 20, weight = 200)
            )

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            val srv = (result as DavResult.Success).value!!
            // Should pick priority 10, highest weight (100)
            assertEquals("high-weight.example.com", srv.host)
        }
    }

    @Nested
    @DisplayName("Domain Extraction")
    inner class DomainExtractionTests {

        @Test
        fun `extracts domain from email`() {
            assertEquals("example.com", DnsSrvDiscovery.extractDomain("user@example.com"))
        }

        @Test
        fun `handles subdomain emails`() {
            assertEquals("mail.example.com", DnsSrvDiscovery.extractDomain("user@mail.example.com"))
        }

        @Test
        fun `rejects invalid email without @`() {
            assertNull(DnsSrvDiscovery.extractDomain("invalid-email"))
        }

        @Test
        fun `rejects email with @ at end`() {
            assertNull(DnsSrvDiscovery.extractDomain("user@"))
        }

        @Test
        fun `handles email with multiple @ signs`() {
            // Uses last @ sign
            assertEquals("example.com", DnsSrvDiscovery.extractDomain("weird@email@example.com"))
        }

        @Test
        fun `lowercases domain`() {
            assertEquals("example.com", DnsSrvDiscovery.extractDomain("User@EXAMPLE.COM"))
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `returns null when no SRV records`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = null
            resolver.insecureRecords = null

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            assertNull((result as DavResult.Success).value)
        }

        @Test
        fun `returns null when records are empty list`() {
            val resolver = MockDnsResolver()
            resolver.secureRecords = emptyList()
            resolver.insecureRecords = emptyList()

            val discovery = DnsSrvDiscovery(resolver)
            val result = discovery.discoverServerUrl("example.com")

            assertTrue(result.isSuccess)
            assertNull((result as DavResult.Success).value)
        }
    }

    @Nested
    @DisplayName("URL Generation")
    inner class UrlGenerationTests {

        @Test
        fun `generates https URL for secure`() {
            val srv = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 443,
                priority = 10,
                weight = 100,
                isSecure = true
            )
            assertEquals("https://caldav.example.com", srv.toUrl())
        }

        @Test
        fun `generates http URL for insecure`() {
            val srv = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 80,
                priority = 10,
                weight = 100,
                isSecure = false
            )
            assertEquals("http://caldav.example.com", srv.toUrl())
        }

        @Test
        fun `omits port 443 for https`() {
            val srv = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 443,
                priority = 10,
                weight = 100,
                isSecure = true
            )
            assertEquals("https://caldav.example.com", srv.toUrl())
        }

        @Test
        fun `omits port 80 for http`() {
            val srv = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 80,
                priority = 10,
                weight = 100,
                isSecure = false
            )
            assertEquals("http://caldav.example.com", srv.toUrl())
        }

        @Test
        fun `includes non-standard port`() {
            val srvHttps = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 8443,
                priority = 10,
                weight = 100,
                isSecure = true
            )
            assertEquals("https://caldav.example.com:8443", srvHttps.toUrl())

            val srvHttp = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 8080,
                priority = 10,
                weight = 100,
                isSecure = false
            )
            assertEquals("http://caldav.example.com:8080", srvHttp.toUrl())
        }
    }

    @Nested
    @DisplayName("SrvDiscoveryResult Model")
    inner class SrvDiscoveryResultTests {

        @Test
        fun `stores all properties correctly`() {
            val srv = DnsSrvDiscovery.SrvDiscoveryResult(
                host = "caldav.example.com",
                port = 443,
                priority = 10,
                weight = 100,
                isSecure = true
            )

            assertEquals("caldav.example.com", srv.host)
            assertEquals(443, srv.port)
            assertEquals(10, srv.priority)
            assertEquals(100, srv.weight)
            assertTrue(srv.isSecure)
        }
    }
}
