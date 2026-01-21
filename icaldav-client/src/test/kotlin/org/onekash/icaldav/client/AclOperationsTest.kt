package org.onekash.icaldav.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.*
import org.onekash.icaldav.xml.RequestBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ACL operations including RequestBuilder and model classes.
 */
@DisplayName("ACL Operations Tests")
class AclOperationsTest {

    @Nested
    @DisplayName("RequestBuilder ACL Methods")
    inner class RequestBuilderAclTests {

        @Test
        fun `propfindAcl generates correct XML`() {
            val xml = RequestBuilder.propfindAcl()

            assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertTrue(xml.contains("<D:propfind") || xml.contains("<d:propfind") || xml.contains("<propfind"))
            assertTrue(xml.contains("acl") || xml.contains("ACL"))
            assertTrue(xml.contains("current-user-privilege-set"))
        }

        @Test
        fun `propfindCurrentUserPrivilegeSet generates correct XML`() {
            val xml = RequestBuilder.propfindCurrentUserPrivilegeSet()

            assertTrue(xml.contains("propfind"))
            assertTrue(xml.contains("current-user-privilege-set"))
        }

        @Test
        fun `acl builder generates correct XML for simple ACE`() {
            val aces = listOf(
                Ace(
                    principal = Principal.Href("/principals/users/alice"),
                    grant = setOf(Privilege.READ)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("<?xml version=\"1.0\""))
            assertTrue(xml.contains("<D:acl") || xml.contains("<d:acl") || xml.contains("<acl"))
            assertTrue(xml.contains("D:ace") || xml.contains("d:ace") || xml.contains("ace"))
            assertTrue(xml.contains("/principals/users/alice"))
            assertTrue(xml.contains("read"))
        }

        @Test
        fun `acl builder generates correct XML for multiple ACEs`() {
            val aces = listOf(
                Ace(
                    principal = Principal.Href("/principals/users/alice"),
                    grant = setOf(Privilege.READ, Privilege.WRITE)
                ),
                Ace(
                    principal = Principal.All,
                    grant = setOf(Privilege.READ)
                )
            )

            val xml = RequestBuilder.acl(aces)

            // Should have two ACE elements
            val aceCount = xml.split(Regex("<[a-zA-Z]*:?ace>")).size - 1
            assertTrue(aceCount >= 2 || xml.contains("alice") && xml.contains("all"))
        }

        @Test
        fun `acl builder handles Principal All`() {
            val aces = listOf(
                Ace(
                    principal = Principal.All,
                    grant = setOf(Privilege.READ)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("<D:all") || xml.contains("<d:all") || xml.contains("<all"))
        }

        @Test
        fun `acl builder handles Principal Authenticated`() {
            val aces = listOf(
                Ace(
                    principal = Principal.Authenticated,
                    grant = setOf(Privilege.READ)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("authenticated"))
        }

        @Test
        fun `acl builder handles Principal Self`() {
            val aces = listOf(
                Ace(
                    principal = Principal.Self,
                    grant = setOf(Privilege.ALL)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("self"))
        }

        @Test
        fun `acl builder handles deny privileges`() {
            val aces = listOf(
                Ace(
                    principal = Principal.All,
                    grant = setOf(Privilege.READ),
                    deny = setOf(Privilege.WRITE)
                )
            )

            val xml = RequestBuilder.acl(aces)

            assertTrue(xml.contains("grant"))
            assertTrue(xml.contains("deny"))
            assertTrue(xml.contains("read"))
            assertTrue(xml.contains("write"))
        }
    }

    @Nested
    @DisplayName("Acl Model Tests")
    inner class AclModelTests {

        @Test
        fun `Acl with empty ACE list`() {
            val acl = Acl(emptyList())

            assertTrue(acl.aces.isEmpty())
        }

        @Test
        fun `Acl with single ACE`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.ALL)
            )
            val acl = Acl(listOf(ace))

            assertEquals(1, acl.aces.size)
            assertEquals(ace, acl.aces[0])
        }
    }

    @Nested
    @DisplayName("Ace Model Tests")
    inner class AceModelTests {

        @Test
        fun `Ace grants method returns true for granted privilege`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.READ, Privilege.WRITE)
            )

            assertTrue(ace.grants(Privilege.READ))
            assertTrue(ace.grants(Privilege.WRITE))
            assertFalse(ace.grants(Privilege.WRITE_ACL))
        }

        @Test
        fun `Ace grants with ALL privilege`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.ALL)
            )

            assertTrue(ace.grants(Privilege.READ))
            assertTrue(ace.grants(Privilege.WRITE))
            assertTrue(ace.grants(Privilege.WRITE_ACL))
            assertTrue(ace.grants(Privilege.BIND))
        }

        @Test
        fun `Ace denies method`() {
            val ace = Ace(
                principal = Principal.All,
                grant = emptySet(),
                deny = setOf(Privilege.WRITE)
            )

            assertTrue(ace.denies(Privilege.WRITE))
            assertFalse(ace.denies(Privilege.READ))
        }

        @Test
        fun `Ace denies with ALL privilege`() {
            val ace = Ace(
                principal = Principal.All,
                grant = emptySet(),
                deny = setOf(Privilege.ALL)
            )

            assertTrue(ace.denies(Privilege.READ))
            assertTrue(ace.denies(Privilege.WRITE))
            assertTrue(ace.denies(Privilege.WRITE_ACL))
        }

        @Test
        fun `Ace with inherited`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.READ),
                inherited = "/calendars/"
            )

            assertEquals("/calendars/", ace.inherited)
        }

        @Test
        fun `Ace default deny is empty`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.READ)
            )

            assertTrue(ace.deny.isEmpty())
        }

        @Test
        fun `Ace default inherited is null`() {
            val ace = Ace(
                principal = Principal.Self,
                grant = setOf(Privilege.READ)
            )

            assertEquals(null, ace.inherited)
        }
    }

    @Nested
    @DisplayName("Principal Sealed Class Tests")
    inner class PrincipalTests {

        @Test
        fun `Principal Href stores URL`() {
            val principal = Principal.Href("/principals/users/john")

            assertEquals("/principals/users/john", principal.url)
        }

        @Test
        fun `Principal All is singleton`() {
            val p1 = Principal.All
            val p2 = Principal.All

            assertTrue(p1 === p2)
        }

        @Test
        fun `Principal Authenticated is singleton`() {
            val p1 = Principal.Authenticated
            val p2 = Principal.Authenticated

            assertTrue(p1 === p2)
        }

        @Test
        fun `Principal Unauthenticated is singleton`() {
            val p1 = Principal.Unauthenticated
            val p2 = Principal.Unauthenticated

            assertTrue(p1 === p2)
        }

        @Test
        fun `Principal Self is singleton`() {
            val p1 = Principal.Self
            val p2 = Principal.Self

            assertTrue(p1 === p2)
        }

        @Test
        fun `Principal Property stores property name`() {
            val principal = Principal.Property("owner")

            assertEquals("owner", principal.propertyName)
        }

        @Test
        fun `Different Principal types are distinguishable`() {
            val href = Principal.Href("/test")
            val all = Principal.All
            val auth = Principal.Authenticated
            val unauth = Principal.Unauthenticated
            val self = Principal.Self
            val prop = Principal.Property("test")

            assertTrue(href is Principal.Href)
            assertTrue(all is Principal.All)
            assertTrue(auth is Principal.Authenticated)
            assertTrue(unauth is Principal.Unauthenticated)
            assertTrue(self is Principal.Self)
            assertTrue(prop is Principal.Property)
        }
    }

    @Nested
    @DisplayName("Privilege Enum Tests")
    inner class PrivilegeEnumTests {

        @Test
        fun `all Privilege values have davName`() {
            Privilege.values().forEach { privilege ->
                assertTrue(privilege.davName.isNotEmpty())
            }
        }

        @Test
        fun `Privilege fromDavName finds all values`() {
            val expected = mapOf(
                "read" to Privilege.READ,
                "write" to Privilege.WRITE,
                "write-properties" to Privilege.WRITE_PROPERTIES,
                "write-content" to Privilege.WRITE_CONTENT,
                "unlock" to Privilege.UNLOCK,
                "read-acl" to Privilege.READ_ACL,
                "write-acl" to Privilege.WRITE_ACL,
                "read-current-user-privilege-set" to Privilege.READ_CURRENT_USER_PRIVILEGE_SET,
                "bind" to Privilege.BIND,
                "unbind" to Privilege.UNBIND,
                "all" to Privilege.ALL
            )

            expected.forEach { (name, expected) ->
                assertEquals(expected, Privilege.fromDavName(name), "Failed for: $name")
            }
        }

        @Test
        fun `Privilege davName is correct`() {
            assertEquals("read", Privilege.READ.davName)
            assertEquals("write", Privilege.WRITE.davName)
            assertEquals("write-properties", Privilege.WRITE_PROPERTIES.davName)
            assertEquals("write-content", Privilege.WRITE_CONTENT.davName)
            assertEquals("unlock", Privilege.UNLOCK.davName)
            assertEquals("read-acl", Privilege.READ_ACL.davName)
            assertEquals("write-acl", Privilege.WRITE_ACL.davName)
            assertEquals("read-current-user-privilege-set", Privilege.READ_CURRENT_USER_PRIVILEGE_SET.davName)
            assertEquals("bind", Privilege.BIND.davName)
            assertEquals("unbind", Privilege.UNBIND.davName)
            assertEquals("all", Privilege.ALL.davName)
        }
    }

    @Nested
    @DisplayName("CurrentUserPrivilegeSet Tests")
    inner class CurrentUserPrivilegeSetTests {

        @Test
        fun `canRead with READ privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.READ))

            assertTrue(cups.canRead())
        }

        @Test
        fun `canRead with ALL privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.ALL))

            assertTrue(cups.canRead())
        }

        @Test
        fun `canWrite with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canWrite())
        }

        @Test
        fun `canWrite with ALL privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.ALL))

            assertTrue(cups.canWrite())
        }

        @Test
        fun `canWriteProperties with WRITE_PROPERTIES privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE_PROPERTIES))

            assertTrue(cups.canWriteProperties())
        }

        @Test
        fun `canWriteProperties with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canWriteProperties())
        }

        @Test
        fun `canWriteContent with WRITE_CONTENT privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE_CONTENT))

            assertTrue(cups.canWriteContent())
        }

        @Test
        fun `canWriteContent with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canWriteContent())
        }

        @Test
        fun `canModifyAcl with WRITE_ACL privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE_ACL))

            assertTrue(cups.canModifyAcl())
        }

        @Test
        fun `canReadAcl with READ_ACL privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.READ_ACL))

            assertTrue(cups.canReadAcl())
        }

        @Test
        fun `canBind with BIND privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.BIND))

            assertTrue(cups.canBind())
        }

        @Test
        fun `canBind with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canBind())
        }

        @Test
        fun `canUnbind with UNBIND privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.UNBIND))

            assertTrue(cups.canUnbind())
        }

        @Test
        fun `canUnbind with WRITE privilege`() {
            val cups = CurrentUserPrivilegeSet(setOf(Privilege.WRITE))

            assertTrue(cups.canUnbind())
        }

        @Test
        fun `NONE constant has no privileges`() {
            val cups = CurrentUserPrivilegeSet.NONE

            assertFalse(cups.canRead())
            assertFalse(cups.canWrite())
            assertFalse(cups.canModifyAcl())
            assertFalse(cups.canBind())
            assertFalse(cups.canUnbind())
        }

        @Test
        fun `ALL constant has all privileges`() {
            val cups = CurrentUserPrivilegeSet.ALL

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
        fun `empty privileges set denies all`() {
            val cups = CurrentUserPrivilegeSet(emptySet())

            assertFalse(cups.canRead())
            assertFalse(cups.canWrite())
            assertFalse(cups.canModifyAcl())
        }
    }

    @Nested
    @DisplayName("ServerCapabilities ACL Tests")
    inner class ServerCapabilitiesAclTests {

        @Test
        fun `supportsAcl returns true when access-control in DAV classes`() {
            val caps = ServerCapabilities(
                davClasses = setOf("1", "2", "access-control"),
                allowedMethods = setOf("OPTIONS", "PROPFIND"),
                rawDavHeader = "1, 2, access-control"
            )

            assertTrue(caps.supportsAcl)
        }

        @Test
        fun `supportsAcl returns false when access-control not in DAV classes`() {
            val caps = ServerCapabilities(
                davClasses = setOf("1", "2", "calendar-access"),
                allowedMethods = setOf("OPTIONS", "PROPFIND"),
                rawDavHeader = "1, 2, calendar-access"
            )

            assertFalse(caps.supportsAcl)
        }

        @Test
        fun `supportsAcl is case-insensitive`() {
            val caps = ServerCapabilities(
                davClasses = setOf("1", "ACCESS-CONTROL"),
                allowedMethods = emptySet(),
                rawDavHeader = "1, ACCESS-CONTROL"
            )

            assertTrue(caps.supportsAcl)
        }

        @Test
        fun `supportsAclMethod returns true when ACL in allowed methods`() {
            val caps = ServerCapabilities(
                davClasses = setOf("1", "access-control"),
                allowedMethods = setOf("OPTIONS", "PROPFIND", "ACL"),
                rawDavHeader = "1, access-control"
            )

            assertTrue(caps.supportsAclMethod)
        }

        @Test
        fun `supportsAclMethod returns false when ACL not in allowed methods`() {
            val caps = ServerCapabilities(
                davClasses = setOf("1", "access-control"),
                allowedMethods = setOf("OPTIONS", "PROPFIND"),
                rawDavHeader = "1, access-control"
            )

            assertFalse(caps.supportsAclMethod)
        }
    }
}
