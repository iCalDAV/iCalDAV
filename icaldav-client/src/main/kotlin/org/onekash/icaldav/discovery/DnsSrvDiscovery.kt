package org.onekash.icaldav.discovery

import org.onekash.icaldav.model.DavResult
import org.xbill.DNS.Lookup
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Type

/**
 * DNS resolver interface for SRV record lookups.
 *
 * Abstracted for testing - allows mocking DNS responses.
 */
interface DnsResolver {
    /**
     * Look up SRV records for the given DNS name.
     *
     * @param name Full DNS name (e.g., "_caldavs._tcp.example.com")
     * @return List of SRV records sorted by priority/weight, or null if not found
     */
    fun lookupSrv(name: String): List<SrvRecord>?

    /**
     * Represents a DNS SRV record.
     */
    data class SrvRecord(
        val target: String,
        val port: Int,
        val priority: Int,
        val weight: Int
    )
}

/**
 * Production DNS resolver using dnsjava library.
 *
 * NOTE: Requires Android API 26+ (Android 8.0 Oreo) due to dnsjava's use of java.time.
 * For lower API levels, enable core library desugaring in your Android app's build.gradle.
 */
class DnsJavaResolver : DnsResolver {
    override fun lookupSrv(name: String): List<DnsResolver.SrvRecord>? {
        return try {
            val lookup = Lookup(name, Type.SRV)
            val records = lookup.run() ?: return null

            records.filterIsInstance<SRVRecord>().map { srv ->
                DnsResolver.SrvRecord(
                    target = srv.target.toString().trimEnd('.'),
                    port = srv.port,
                    priority = srv.priority,
                    weight = srv.weight
                )
            }.sortedWith(compareBy({ it.priority }, { -it.weight }))
        } catch (e: Exception) {
            // DNS errors return null, not exceptions
            // This includes NXDOMAIN, timeout, etc.
            null
        }
    }
}

/**
 * DNS-SRV based CalDAV server discovery (RFC 6764).
 *
 * Discovers CalDAV servers by looking up SRV records:
 * - _caldavs._tcp.{domain} - CalDAV over TLS (preferred)
 * - _caldav._tcp.{domain} - CalDAV over plain HTTP (fallback)
 *
 * Example usage:
 * ```kotlin
 * val discovery = DnsSrvDiscovery()
 * val result = discovery.discoverServerUrl("example.com")
 * if (result is DavResult.Success && result.value != null) {
 *     println("CalDAV server: ${result.value.toUrl()}")
 * }
 * ```
 *
 * @property dnsResolver DNS resolver to use (defaults to DnsJavaResolver)
 *
 * @see <a href="https://tools.ietf.org/html/rfc6764">RFC 6764 - Locating Services for Calendaring</a>
 */
class DnsSrvDiscovery(
    private val dnsResolver: DnsResolver = DnsJavaResolver()
) {
    /**
     * Result of DNS-SRV discovery.
     *
     * @property host Target hostname from SRV record
     * @property port Target port from SRV record
     * @property priority SRV record priority (lower = preferred)
     * @property weight SRV record weight (higher = preferred among same priority)
     * @property isSecure True if discovered via _caldavs (TLS)
     */
    data class SrvDiscoveryResult(
        val host: String,
        val port: Int,
        val priority: Int,
        val weight: Int,
        val isSecure: Boolean
    ) {
        /**
         * Convert to base URL for CalDAV operations.
         *
         * Omits port if it's the default for the scheme (443 for HTTPS, 80 for HTTP).
         */
        fun toUrl(): String {
            val scheme = if (isSecure) "https" else "http"
            val portSuffix = when {
                isSecure && port == 443 -> ""
                !isSecure && port == 80 -> ""
                else -> ":$port"
            }
            return "$scheme://$host$portSuffix"
        }
    }

    /**
     * Discover CalDAV server URL from domain using DNS-SRV.
     *
     * Tries _caldavs._tcp (secure) first, then falls back to _caldav._tcp.
     *
     * @param domain Domain to look up (e.g., "example.com")
     * @return DavResult containing SrvDiscoveryResult if found, null if no SRV records
     */
    fun discoverServerUrl(domain: String): DavResult<SrvDiscoveryResult?> {
        // Try secure first (_caldavs._tcp)
        val secureRecords = dnsResolver.lookupSrv("_caldavs._tcp.$domain")
        if (!secureRecords.isNullOrEmpty()) {
            val best = selectBestRecord(secureRecords)
            return DavResult.success(
                SrvDiscoveryResult(
                    host = best.target,
                    port = best.port,
                    priority = best.priority,
                    weight = best.weight,
                    isSecure = true
                )
            )
        }

        // Fallback to non-secure (_caldav._tcp)
        val insecureRecords = dnsResolver.lookupSrv("_caldav._tcp.$domain")
        if (!insecureRecords.isNullOrEmpty()) {
            val best = selectBestRecord(insecureRecords)
            return DavResult.success(
                SrvDiscoveryResult(
                    host = best.target,
                    port = best.port,
                    priority = best.priority,
                    weight = best.weight,
                    isSecure = false
                )
            )
        }

        // No SRV records found
        return DavResult.success(null)
    }

    /**
     * Select the best record from a list of SRV records.
     *
     * RFC 2782 specifies:
     * - Lower priority values are preferred
     * - Among equal priority, higher weight is preferred
     */
    private fun selectBestRecord(records: List<DnsResolver.SrvRecord>): DnsResolver.SrvRecord {
        return records.sortedWith(compareBy({ it.priority }, { -it.weight })).first()
    }

    companion object {
        /**
         * Extract domain from email address.
         *
         * @param email Email address (e.g., "user@example.com")
         * @return Domain portion, or null if invalid email format
         */
        fun extractDomain(email: String): String? {
            val atIndex = email.lastIndexOf('@')
            if (atIndex < 0 || atIndex >= email.length - 1) return null
            return email.substring(atIndex + 1).lowercase()
        }
    }
}
