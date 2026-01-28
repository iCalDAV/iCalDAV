package org.onekash.icaldav.xml

import org.onekash.icaldav.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parser for WebDAV ACL XML responses per RFC 3744.
 *
 * Uses XmlPullParser for efficient parsing with automatic entity decoding.
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

        try {
            val parser = createParser(xml)
            var currentAce: AceBuilder? = null
            var inPrincipal = false
            var inGrant = false
            var inDeny = false
            var inInherited = false
            var inProperty = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "ace" -> currentAce = AceBuilder()
                            "principal" -> inPrincipal = true
                            "grant" -> inGrant = true
                            "deny" -> inDeny = true
                            "inherited" -> inInherited = true
                            "property" -> inProperty = true
                            "href" -> {
                                val href = readTextContent(parser)
                                when {
                                    inInherited && currentAce != null -> currentAce.inherited = href
                                    inPrincipal && currentAce != null -> currentAce.principal = Principal.Href(href)
                                }
                            }
                            "all" -> {
                                // "all" can be a principal (<principal><all/>) or a privilege (<privilege><all/>)
                                if (inPrincipal && currentAce != null) {
                                    currentAce.principal = Principal.All
                                } else {
                                    addPrivilege(currentAce, Privilege.ALL, inGrant, inDeny)
                                }
                            }
                            "authenticated" -> if (inPrincipal && currentAce != null) currentAce.principal = Principal.Authenticated
                            "unauthenticated" -> if (inPrincipal && currentAce != null) currentAce.principal = Principal.Unauthenticated
                            "self" -> if (inPrincipal && currentAce != null) currentAce.principal = Principal.Self
                            "privilege" -> {
                                // Next element inside privilege is the privilege name
                            }
                            // Standard privileges
                            "read" -> addPrivilege(currentAce, Privilege.READ, inGrant, inDeny)
                            "write" -> addPrivilege(currentAce, Privilege.WRITE, inGrant, inDeny)
                            "write-properties" -> addPrivilege(currentAce, Privilege.WRITE_PROPERTIES, inGrant, inDeny)
                            "write-content" -> addPrivilege(currentAce, Privilege.WRITE_CONTENT, inGrant, inDeny)
                            "unlock" -> addPrivilege(currentAce, Privilege.UNLOCK, inGrant, inDeny)
                            "read-acl" -> addPrivilege(currentAce, Privilege.READ_ACL, inGrant, inDeny)
                            "read-current-user-privilege-set" -> addPrivilege(currentAce, Privilege.READ_CURRENT_USER_PRIVILEGE_SET, inGrant, inDeny)
                            "write-acl" -> addPrivilege(currentAce, Privilege.WRITE_ACL, inGrant, inDeny)
                            "bind" -> addPrivilege(currentAce, Privilege.BIND, inGrant, inDeny)
                            "unbind" -> addPrivilege(currentAce, Privilege.UNBIND, inGrant, inDeny)
                            else -> {
                                // Handle property principal
                                if (inProperty && inPrincipal && currentAce != null) {
                                    currentAce.principal = Principal.Property(parser.name)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "ace" -> {
                                currentAce?.build()?.let { aces.add(it) }
                                currentAce = null
                            }
                            "principal" -> inPrincipal = false
                            "grant" -> inGrant = false
                            "deny" -> inDeny = false
                            "inherited" -> inInherited = false
                            "property" -> inProperty = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever we parsed
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

        try {
            val parser = createParser(xml)
            var inCups = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "current-user-privilege-set" -> inCups = true
                            "read" -> if (inCups) privileges.add(Privilege.READ)
                            "write" -> if (inCups) privileges.add(Privilege.WRITE)
                            "write-properties" -> if (inCups) privileges.add(Privilege.WRITE_PROPERTIES)
                            "write-content" -> if (inCups) privileges.add(Privilege.WRITE_CONTENT)
                            "unlock" -> if (inCups) privileges.add(Privilege.UNLOCK)
                            "read-acl" -> if (inCups) privileges.add(Privilege.READ_ACL)
                            "read-current-user-privilege-set" -> if (inCups) privileges.add(Privilege.READ_CURRENT_USER_PRIVILEGE_SET)
                            "write-acl" -> if (inCups) privileges.add(Privilege.WRITE_ACL)
                            "bind" -> if (inCups) privileges.add(Privilege.BIND)
                            "unbind" -> if (inCups) privileges.add(Privilege.UNBIND)
                            "all" -> if (inCups) privileges.add(Privilege.ALL)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "current-user-privilege-set") {
                            inCups = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever we parsed
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

        try {
            val parser = createParser(xml)
            var inPcs = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "principal-collection-set" -> inPcs = true
                            "href" -> {
                                if (inPcs) {
                                    val href = readTextContent(parser).trim()
                                    if (href.isNotEmpty()) {
                                        collections.add(href)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "principal-collection-set") {
                            inPcs = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Return whatever we parsed
        }

        return collections
    }

    private fun createParser(xml: String): XmlPullParser {
        val cleanXml = XmlParserUtils.stripXmlProlog(xml)
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(cleanXml))
        return parser
    }

    private fun readTextContent(parser: XmlPullParser): String {
        val content = StringBuilder()
        var depth = 1
        var eventType = parser.next()

        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> content.append(parser.text)
                XmlPullParser.CDSECT -> content.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) eventType = parser.next()
        }

        return content.toString().trim()
    }

    private fun addPrivilege(ace: AceBuilder?, privilege: Privilege, inGrant: Boolean, inDeny: Boolean) {
        if (ace == null) return
        when {
            inGrant -> ace.grant.add(privilege)
            inDeny -> ace.deny.add(privilege)
        }
    }

    private class AceBuilder {
        var principal: Principal? = null
        val grant = mutableSetOf<Privilege>()
        val deny = mutableSetOf<Privilege>()
        var inherited: String? = null

        fun build(): Ace? {
            val p = principal ?: return null
            return Ace(
                principal = p,
                grant = grant.toSet(),
                deny = deny.toSet(),
                inherited = inherited
            )
        }
    }
}
