package org.onekash.icaldav.xml

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive tests for ACL XML parsing per RFC 3744.
 */
@DisplayName("AclParser Tests")
class AclParserTest {

    @Nested
    @DisplayName("parseAcl Tests")
    inner class ParseAclTests {

        @Test
        fun `parse ACL with single ACE`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:propstat>
                            <d:prop>
                                <d:acl>
                                    <d:ace>
                                        <d:principal>
                                            <d:href>/principals/users/john</d:href>
                                        </d:principal>
                                        <d:grant>
                                            <d:privilege><d:read/></d:privilege>
                                        </d:grant>
                                    </d:ace>
                                </d:acl>
                            </d:prop>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
            val ace = acl.aces[0]
            assertTrue(ace.principal is Principal.Href)
            assertEquals("/principals/users/john", (ace.principal as Principal.Href).url)
            assertTrue(Privilege.READ in ace.grant)
        }

        @Test
        fun `parse ACL with multiple ACEs`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:propstat>
                            <d:prop>
                                <d:acl>
                                    <d:ace>
                                        <d:principal>
                                            <d:href>/principals/users/alice</d:href>
                                        </d:principal>
                                        <d:grant>
                                            <d:privilege><d:read/></d:privilege>
                                            <d:privilege><d:write/></d:privilege>
                                        </d:grant>
                                    </d:ace>
                                    <d:ace>
                                        <d:principal>
                                            <d:href>/principals/users/bob</d:href>
                                        </d:principal>
                                        <d:grant>
                                            <d:privilege><d:read/></d:privilege>
                                        </d:grant>
                                    </d:ace>
                                </d:acl>
                            </d:prop>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(2, acl.aces.size)
        }

        @Test
        fun `parse ACL with grant and deny`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:href>/principals/users/limited</d:href>
                        </d:principal>
                        <d:grant>
                            <d:privilege><d:read/></d:privilege>
                        </d:grant>
                        <d:deny>
                            <d:privilege><d:write/></d:privilege>
                        </d:deny>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
            val ace = acl.aces[0]
            assertTrue(Privilege.READ in ace.grant)
            assertTrue(Privilege.WRITE in ace.deny)
        }

        @Test
        fun `parse ACL with inherited ACE`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:href>/principals/users/john</d:href>
                        </d:principal>
                        <d:grant>
                            <d:privilege><d:read/></d:privilege>
                        </d:grant>
                        <d:inherited>
                            <d:href>/calendars/</d:href>
                        </d:inherited>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
            assertEquals("/calendars/", acl.aces[0].inherited)
        }

        @Test
        fun `parse empty ACL`() {
            val xml = """<d:acl xmlns:d="DAV:"></d:acl>"""

            val acl = AclParser.parseAcl(xml)

            assertTrue(acl.aces.isEmpty())
        }
    }

    @Nested
    @DisplayName("Principal Parsing Tests")
    inner class PrincipalParsingTests {

        @Test
        fun `parse principal href`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:href>/principals/users/john</d:href>
                        </d:principal>
                        <d:grant><d:privilege><d:read/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.Href)
            assertEquals("/principals/users/john", (principal as Principal.Href).url)
        }

        @Test
        fun `parse principal all`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:all/>
                        </d:principal>
                        <d:grant><d:privilege><d:read/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.All)
        }

        @Test
        fun `parse principal authenticated`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:authenticated/>
                        </d:principal>
                        <d:grant><d:privilege><d:read/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.Authenticated)
        }

        @Test
        fun `parse principal unauthenticated`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:unauthenticated/>
                        </d:principal>
                        <d:grant><d:privilege><d:read/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.Unauthenticated)
        }

        @Test
        fun `parse principal self`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:self/>
                        </d:principal>
                        <d:grant><d:privilege><d:all/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.Self)
        }

        @Test
        fun `parse principal property`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:property><d:owner/></d:property>
                        </d:principal>
                        <d:grant><d:privilege><d:all/></d:privilege></d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val principal = acl.aces[0].principal

            assertTrue(principal is Principal.Property)
            assertEquals("owner", (principal as Principal.Property).propertyName)
        }
    }

    @Nested
    @DisplayName("Privilege Parsing Tests")
    inner class PrivilegeParsingTests {

        @Test
        fun `parse all privileges`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal><d:self/></d:principal>
                        <d:grant>
                            <d:privilege><d:read/></d:privilege>
                            <d:privilege><d:write/></d:privilege>
                            <d:privilege><d:write-properties/></d:privilege>
                            <d:privilege><d:write-content/></d:privilege>
                            <d:privilege><d:unlock/></d:privilege>
                            <d:privilege><d:read-acl/></d:privilege>
                            <d:privilege><d:write-acl/></d:privilege>
                            <d:privilege><d:bind/></d:privilege>
                            <d:privilege><d:unbind/></d:privilege>
                            <d:privilege><d:all/></d:privilege>
                        </d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val privileges = acl.aces[0].grant

            assertTrue(Privilege.READ in privileges)
            assertTrue(Privilege.WRITE in privileges)
            assertTrue(Privilege.WRITE_PROPERTIES in privileges)
            assertTrue(Privilege.WRITE_CONTENT in privileges)
            assertTrue(Privilege.UNLOCK in privileges)
            assertTrue(Privilege.READ_ACL in privileges)
            assertTrue(Privilege.WRITE_ACL in privileges)
            assertTrue(Privilege.BIND in privileges)
            assertTrue(Privilege.UNBIND in privileges)
            assertTrue(Privilege.ALL in privileges)
        }

        @Test
        fun `parse read-current-user-privilege-set privilege`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal><d:authenticated/></d:principal>
                        <d:grant>
                            <d:privilege><d:read-current-user-privilege-set/></d:privilege>
                        </d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)
            val privileges = acl.aces[0].grant

            assertTrue(Privilege.READ_CURRENT_USER_PRIVILEGE_SET in privileges)
        }
    }

    @Nested
    @DisplayName("Namespace Handling Tests")
    inner class NamespaceHandlingTests {

        @Test
        fun `parse ACL with uppercase D prefix`() {
            val xml = """
                <D:acl xmlns:D="DAV:">
                    <D:ace>
                        <D:principal>
                            <D:href>/principals/users/john</D:href>
                        </D:principal>
                        <D:grant>
                            <D:privilege><D:read/></D:privilege>
                        </D:grant>
                    </D:ace>
                </D:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
            assertTrue(acl.aces[0].principal is Principal.Href)
        }

        @Test
        fun `parse ACL with lowercase d prefix`() {
            val xml = """
                <d:acl xmlns:d="DAV:">
                    <d:ace>
                        <d:principal>
                            <d:href>/principals/users/john</d:href>
                        </d:principal>
                        <d:grant>
                            <d:privilege><d:read/></d:privilege>
                        </d:grant>
                    </d:ace>
                </d:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
        }

        @Test
        fun `parse ACL without namespace prefix`() {
            val xml = """
                <acl xmlns="DAV:">
                    <ace>
                        <principal>
                            <href>/principals/users/john</href>
                        </principal>
                        <grant>
                            <privilege><read/></privilege>
                        </grant>
                    </ace>
                </acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
        }

        @Test
        fun `parse ACL with mixed case prefix`() {
            val xml = """
                <Dav:acl xmlns:Dav="DAV:">
                    <Dav:ace>
                        <Dav:principal>
                            <Dav:self/>
                        </Dav:principal>
                        <Dav:grant>
                            <Dav:privilege><Dav:all/></Dav:privilege>
                        </Dav:grant>
                    </Dav:ace>
                </Dav:acl>
            """.trimIndent()

            val acl = AclParser.parseAcl(xml)

            assertEquals(1, acl.aces.size)
            assertTrue(acl.aces[0].principal is Principal.Self)
        }
    }

    @Nested
    @DisplayName("parseCurrentUserPrivilegeSet Tests")
    inner class ParseCurrentUserPrivilegeSetTests {

        @Test
        fun `parse current-user-privilege-set with multiple privileges`() {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:propstat>
                            <d:prop>
                                <d:current-user-privilege-set>
                                    <d:privilege><d:read/></d:privilege>
                                    <d:privilege><d:write/></d:privilege>
                                    <d:privilege><d:bind/></d:privilege>
                                    <d:privilege><d:unbind/></d:privilege>
                                </d:current-user-privilege-set>
                            </d:prop>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            val cups = AclParser.parseCurrentUserPrivilegeSet(xml)

            assertTrue(cups.canRead())
            assertTrue(cups.canWrite())
            assertTrue(cups.canBind())
            assertTrue(cups.canUnbind())
        }

        @Test
        fun `parse current-user-privilege-set with all privilege`() {
            val xml = """
                <d:current-user-privilege-set xmlns:d="DAV:">
                    <d:privilege><d:all/></d:privilege>
                </d:current-user-privilege-set>
            """.trimIndent()

            val cups = AclParser.parseCurrentUserPrivilegeSet(xml)

            assertTrue(cups.canRead())
            assertTrue(cups.canWrite())
            assertTrue(cups.canWriteProperties())
            assertTrue(cups.canWriteContent())
            assertTrue(cups.canModifyAcl())
            assertTrue(cups.canReadAcl())
            assertTrue(cups.canBind())
            assertTrue(cups.canUnbind())
        }

        @Test
        fun `parse current-user-privilege-set with read-only`() {
            val xml = """
                <d:current-user-privilege-set xmlns:d="DAV:">
                    <d:privilege><d:read/></d:privilege>
                </d:current-user-privilege-set>
            """.trimIndent()

            val cups = AclParser.parseCurrentUserPrivilegeSet(xml)

            assertTrue(cups.canRead())
            assertFalse(cups.canWrite())
            assertFalse(cups.canModifyAcl())
        }

        @Test
        fun `parse empty current-user-privilege-set`() {
            val xml = """<d:current-user-privilege-set xmlns:d="DAV:"></d:current-user-privilege-set>"""

            val cups = AclParser.parseCurrentUserPrivilegeSet(xml)

            assertFalse(cups.canRead())
            assertFalse(cups.canWrite())
        }
    }

    @Nested
    @DisplayName("parsePrincipalCollectionSet Tests")
    inner class ParsePrincipalCollectionSetTests {

        @Test
        fun `parse principal-collection-set with single href`() {
            val xml = """
                <d:principal-collection-set xmlns:d="DAV:">
                    <d:href>/principals/</d:href>
                </d:principal-collection-set>
            """.trimIndent()

            val collections = AclParser.parsePrincipalCollectionSet(xml)

            assertEquals(1, collections.size)
            assertEquals("/principals/", collections[0])
        }

        @Test
        fun `parse principal-collection-set with multiple hrefs`() {
            val xml = """
                <d:principal-collection-set xmlns:d="DAV:">
                    <d:href>/principals/users/</d:href>
                    <d:href>/principals/groups/</d:href>
                </d:principal-collection-set>
            """.trimIndent()

            val collections = AclParser.parsePrincipalCollectionSet(xml)

            assertEquals(2, collections.size)
            assertTrue(collections.contains("/principals/users/"))
            assertTrue(collections.contains("/principals/groups/"))
        }

        @Test
        fun `parse empty principal-collection-set`() {
            val xml = """<d:principal-collection-set xmlns:d="DAV:"></d:principal-collection-set>"""

            val collections = AclParser.parsePrincipalCollectionSet(xml)

            assertTrue(collections.isEmpty())
        }
    }

    @Nested
    @DisplayName("Ace Helper Methods Tests")
    inner class AceHelperMethodsTests {

        @Test
        fun `Ace grants method`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.READ, Privilege.WRITE)
            )

            assertTrue(ace.grants(Privilege.READ))
            assertTrue(ace.grants(Privilege.WRITE))
            assertFalse(ace.grants(Privilege.WRITE_ACL))
        }

        @Test
        fun `Ace grants ALL covers all privileges`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.ALL)
            )

            assertTrue(ace.grants(Privilege.READ))
            assertTrue(ace.grants(Privilege.WRITE))
            assertTrue(ace.grants(Privilege.WRITE_ACL))
        }

        @Test
        fun `Ace denies method`() {
            val ace = Ace(
                principal = Principal.All,
                grant = setOf(Privilege.READ),
                deny = setOf(Privilege.WRITE)
            )

            assertFalse(ace.denies(Privilege.READ))
            assertTrue(ace.denies(Privilege.WRITE))
        }
    }

    @Nested
    @DisplayName("CurrentUserPrivilegeSet Helper Methods Tests")
    inner class CurrentUserPrivilegeSetHelperMethodsTests {

        @Test
        fun `canWriteProperties with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canWriteProperties())
        }

        @Test
        fun `canWriteProperties with WRITE_PROPERTIES privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE_PROPERTIES))

            assertTrue(cups.canWriteProperties())
        }

        @Test
        fun `canWriteContent with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canWriteContent())
        }

        @Test
        fun `canWriteContent with WRITE_CONTENT privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE_CONTENT))

            assertTrue(cups.canWriteContent())
        }

        @Test
        fun `NONE constant has no privileges`() {
            val cups = CurrentUserPrivilegeSet.NONE

            assertFalse(cups.canRead())
            assertFalse(cups.canWrite())
        }

        @Test
        fun `ALL constant has all privileges`() {
            val cups = CurrentUserPrivilegeSet.ALL

            assertTrue(cups.canRead())
            assertTrue(cups.canWrite())
            assertTrue(cups.canModifyAcl())
        }
    }

    @Nested
    @DisplayName("Privilege Enum Tests")
    inner class PrivilegeEnumTests {

        @Test
        fun `Privilege fromDavName finds all values`() {
            assertEquals(Privilege.READ, Privilege.fromDavName("read"))
            assertEquals(Privilege.WRITE, Privilege.fromDavName("write"))
            assertEquals(Privilege.WRITE_PROPERTIES, Privilege.fromDavName("write-properties"))
            assertEquals(Privilege.WRITE_CONTENT, Privilege.fromDavName("write-content"))
            assertEquals(Privilege.UNLOCK, Privilege.fromDavName("unlock"))
            assertEquals(Privilege.READ_ACL, Privilege.fromDavName("read-acl"))
            assertEquals(Privilege.WRITE_ACL, Privilege.fromDavName("write-acl"))
            assertEquals(Privilege.READ_CURRENT_USER_PRIVILEGE_SET, Privilege.fromDavName("read-current-user-privilege-set"))
            assertEquals(Privilege.BIND, Privilege.fromDavName("bind"))
            assertEquals(Privilege.UNBIND, Privilege.fromDavName("unbind"))
            assertEquals(Privilege.ALL, Privilege.fromDavName("all"))
        }

        @Test
        fun `Privilege fromDavName is case-insensitive`() {
            assertEquals(Privilege.READ, Privilege.fromDavName("READ"))
            assertEquals(Privilege.READ, Privilege.fromDavName("Read"))
            assertEquals(Privilege.WRITE_ACL, Privilege.fromDavName("WRITE-ACL"))
        }

        @Test
        fun `Privilege fromDavName returns null for unknown`() {
            assertNull(Privilege.fromDavName("unknown"))
            assertNull(Privilege.fromDavName(""))
        }
    }
}
