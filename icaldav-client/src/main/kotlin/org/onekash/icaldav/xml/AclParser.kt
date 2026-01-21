package org.onekash.icaldav.xml

import org.onekash.icaldav.model.*

/**
 * Parser for WebDAV ACL XML responses per RFC 3744.
 *
 * Uses regex-based parsing for consistency with other parsers in this codebase
 * and for reliability with namespace variations (D:, d:, DAV:, etc.).
 *
 * Handles parsing of:
 * - ACL property (list of ACEs)
 * - current-user-privilege-set property
 * - principal-collection-set property
 */
object AclParser {

    /**
     * Parse ACL from PROPFIND response.
     *
     * @param xml PROPFIND response XML
     * @return Parsed ACL with list of ACEs
     */
    fun parseAcl(xml: String): Acl {
        val aces = mutableListOf<Ace>()

        // Match <ace> or <D:ace> or <d:ace> elements
        val acePattern = Regex(
            """<(?:[a-zA-Z]+:)?ace[^>]*>(.*?)</(?:[a-zA-Z]+:)?ace>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        acePattern.findAll(xml).forEach { match ->
            parseAce(match.groupValues[1])?.let { aces.add(it) }
        }

        return Acl(aces)
    }

    /**
     * Parse current-user-privilege-set from PROPFIND response.
     *
     * @param xml PROPFIND response XML
     * @return CurrentUserPrivilegeSet with privileges
     */
    fun parseCurrentUserPrivilegeSet(xml: String): CurrentUserPrivilegeSet {
        val privileges = mutableSetOf<Privilege>()

        // Find current-user-privilege-set element
        val cupsPattern = Regex(
            """<(?:[a-zA-Z]+:)?current-user-privilege-set[^>]*>(.*?)</(?:[a-zA-Z]+:)?current-user-privilege-set>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val cupsMatch = cupsPattern.find(xml)
        if (cupsMatch != null) {
            val cupsContent = cupsMatch.groupValues[1]
            privileges.addAll(parsePrivileges(cupsContent))
        }

        return CurrentUserPrivilegeSet(privileges)
    }

    /**
     * Parse principal-collection-set from PROPFIND response.
     *
     * @param xml PROPFIND response XML
     * @return List of principal collection URLs
     */
    fun parsePrincipalCollectionSet(xml: String): List<String> {
        val collections = mutableListOf<String>()

        // Find principal-collection-set element
        val pcsPattern = Regex(
            """<(?:[a-zA-Z]+:)?principal-collection-set[^>]*>(.*?)</(?:[a-zA-Z]+:)?principal-collection-set>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val pcsMatch = pcsPattern.find(xml)
        if (pcsMatch != null) {
            val pcsContent = pcsMatch.groupValues[1]
            // Extract hrefs
            val hrefPattern = Regex(
                """<(?:[a-zA-Z]+:)?href[^>]*>([^<]*)</(?:[a-zA-Z]+:)?href>""",
                RegexOption.IGNORE_CASE
            )
            hrefPattern.findAll(pcsContent).forEach { match ->
                val href = match.groupValues[1].trim()
                if (href.isNotEmpty()) {
                    collections.add(href)
                }
            }
        }

        return collections
    }

    /**
     * Parse a single ACE element.
     */
    private fun parseAce(aceXml: String): Ace? {
        val principal = parsePrincipal(aceXml) ?: return null
        val grant = parseGrantOrDeny(aceXml, "grant")
        val deny = parseGrantOrDeny(aceXml, "deny")
        val inherited = parseInherited(aceXml)

        return Ace(
            principal = principal,
            grant = grant,
            deny = deny,
            inherited = inherited
        )
    }

    /**
     * Parse principal from ACE.
     */
    private fun parsePrincipal(aceXml: String): Principal? {
        // Find principal element
        val principalPattern = Regex(
            """<(?:[a-zA-Z]+:)?principal[^>]*>(.*?)</(?:[a-zA-Z]+:)?principal>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val principalMatch = principalPattern.find(aceXml) ?: return null
        val principalContent = principalMatch.groupValues[1]

        // Check for href
        val hrefPattern = Regex(
            """<(?:[a-zA-Z]+:)?href[^>]*>([^<]*)</(?:[a-zA-Z]+:)?href>""",
            RegexOption.IGNORE_CASE
        )
        val hrefMatch = hrefPattern.find(principalContent)
        if (hrefMatch != null) {
            return Principal.Href(hrefMatch.groupValues[1].trim())
        }

        // Check for all
        if (principalContent.contains(Regex("""<(?:[a-zA-Z]+:)?all\s*/?>""", RegexOption.IGNORE_CASE))) {
            return Principal.All
        }

        // Check for authenticated
        if (principalContent.contains(Regex("""<(?:[a-zA-Z]+:)?authenticated\s*/?>""", RegexOption.IGNORE_CASE))) {
            return Principal.Authenticated
        }

        // Check for unauthenticated
        if (principalContent.contains(Regex("""<(?:[a-zA-Z]+:)?unauthenticated\s*/?>""", RegexOption.IGNORE_CASE))) {
            return Principal.Unauthenticated
        }

        // Check for self
        if (principalContent.contains(Regex("""<(?:[a-zA-Z]+:)?self\s*/?>""", RegexOption.IGNORE_CASE))) {
            return Principal.Self
        }

        // Check for property
        val propertyPattern = Regex(
            """<(?:[a-zA-Z]+:)?property[^>]*>.*?<(?:[a-zA-Z]+:)?(\w+)[^>]*/?>.*?</(?:[a-zA-Z]+:)?property>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val propertyMatch = propertyPattern.find(principalContent)
        if (propertyMatch != null) {
            return Principal.Property(propertyMatch.groupValues[1])
        }

        return null
    }

    /**
     * Parse grant or deny privileges.
     */
    private fun parseGrantOrDeny(aceXml: String, elementName: String): Set<Privilege> {
        val pattern = Regex(
            """<(?:[a-zA-Z]+:)?$elementName[^>]*>(.*?)</(?:[a-zA-Z]+:)?$elementName>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val match = pattern.find(aceXml) ?: return emptySet()
        return parsePrivileges(match.groupValues[1])
    }

    /**
     * Parse privileges from XML content.
     */
    private fun parsePrivileges(xml: String): Set<Privilege> {
        val privileges = mutableSetOf<Privilege>()

        // Find privilege elements
        val privilegePattern = Regex(
            """<(?:[a-zA-Z]+:)?privilege[^>]*>(.*?)</(?:[a-zA-Z]+:)?privilege>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        privilegePattern.findAll(xml).forEach { match ->
            val privilegeContent = match.groupValues[1]
            // Extract the privilege name (first element inside privilege)
            val namePattern = Regex("""<(?:[a-zA-Z]+:)?([\w-]+)[^>]*/?>""", RegexOption.IGNORE_CASE)
            val nameMatch = namePattern.find(privilegeContent)
            if (nameMatch != null) {
                val privName = nameMatch.groupValues[1]
                Privilege.fromDavName(privName)?.let { privileges.add(it) }
            }
        }

        return privileges
    }

    /**
     * Parse inherited URL from ACE.
     */
    private fun parseInherited(aceXml: String): String? {
        val inheritedPattern = Regex(
            """<(?:[a-zA-Z]+:)?inherited[^>]*>(.*?)</(?:[a-zA-Z]+:)?inherited>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val inheritedMatch = inheritedPattern.find(aceXml) ?: return null
        val inheritedContent = inheritedMatch.groupValues[1]

        // Extract href
        val hrefPattern = Regex(
            """<(?:[a-zA-Z]+:)?href[^>]*>([^<]*)</(?:[a-zA-Z]+:)?href>""",
            RegexOption.IGNORE_CASE
        )
        val hrefMatch = hrefPattern.find(inheritedContent)
        return hrefMatch?.groupValues?.get(1)?.trim()
    }
}
