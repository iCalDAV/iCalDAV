package org.onekash.icaldav.model

/**
 * WebDAV Access Control List per RFC 3744.
 *
 * @property aces List of Access Control Entries
 */
data class Acl(val aces: List<Ace>)

/**
 * Access Control Entry per RFC 3744 Section 5.5.
 *
 * @property principal The principal (user, group, or special) this ACE applies to
 * @property grant Set of privileges granted to the principal
 * @property deny Set of privileges denied to the principal
 * @property inherited URL of ancestor resource if this ACE is inherited, null if not
 */
data class Ace(
    val principal: Principal,
    val grant: Set<Privilege>,
    val deny: Set<Privilege> = emptySet(),
    val inherited: String? = null
) {
    /**
     * Check if this ACE grants a specific privilege.
     */
    fun grants(privilege: Privilege): Boolean =
        privilege in grant || Privilege.ALL in grant

    /**
     * Check if this ACE denies a specific privilege.
     */
    fun denies(privilege: Privilege): Boolean =
        privilege in deny || Privilege.ALL in deny
}

/**
 * Principal identifier per RFC 3744 Section 5.5.1.
 * Identifies who an ACE applies to.
 */
sealed class Principal {
    /**
     * A specific principal identified by URL.
     */
    data class Href(val url: String) : Principal()

    /**
     * All principals (everyone).
     */
    object All : Principal()

    /**
     * All authenticated principals.
     */
    object Authenticated : Principal()

    /**
     * All unauthenticated principals.
     */
    object Unauthenticated : Principal()

    /**
     * The current user making the request.
     */
    object Self : Principal()

    /**
     * A principal identified by a property (e.g., DAV:owner).
     */
    data class Property(val propertyName: String) : Principal()
}

/**
 * WebDAV privileges per RFC 3744 Section 3.
 *
 * @property davName The DAV: namespace element name for this privilege
 */
enum class Privilege(val davName: String) {
    /** Read the contents of a resource */
    READ("read"),

    /** Write to a resource (combination of write-content and write-properties) */
    WRITE("write"),

    /** Modify dead properties on a resource */
    WRITE_PROPERTIES("write-properties"),

    /** Modify the content of a resource */
    WRITE_CONTENT("write-content"),

    /** Remove a lock from a resource */
    UNLOCK("unlock"),

    /** Read the ACL of a resource */
    READ_ACL("read-acl"),

    /** Modify the ACL of a resource */
    WRITE_ACL("write-acl"),

    /** Read the current-user-privilege-set property */
    READ_CURRENT_USER_PRIVILEGE_SET("read-current-user-privilege-set"),

    /** Create new resources in a collection */
    BIND("bind"),

    /** Delete resources from a collection */
    UNBIND("unbind"),

    /** All privileges */
    ALL("all");

    companion object {
        /**
         * Find privilege by DAV name.
         *
         * @param name DAV element name (e.g., "read", "write")
         * @return Matching Privilege or null if not found
         */
        fun fromDavName(name: String): Privilege? =
            entries.find { it.davName.equals(name, ignoreCase = true) }
    }
}

/**
 * Current user's privileges on a resource per RFC 3744 Section 5.4.
 *
 * This property shows what the current authenticated user can do with a resource.
 * Use this to determine UI affordances (e.g., show/hide edit button).
 *
 * @property privileges Set of privileges the current user has
 */
data class CurrentUserPrivilegeSet(val privileges: Set<Privilege>) {

    /**
     * Check if user can read the resource.
     */
    fun canRead(): Boolean =
        Privilege.READ in privileges || Privilege.ALL in privileges

    /**
     * Check if user can write to the resource.
     */
    fun canWrite(): Boolean =
        Privilege.WRITE in privileges || Privilege.ALL in privileges

    /**
     * Check if user can modify properties.
     */
    fun canWriteProperties(): Boolean =
        Privilege.WRITE_PROPERTIES in privileges ||
            Privilege.WRITE in privileges ||
            Privilege.ALL in privileges

    /**
     * Check if user can modify content.
     */
    fun canWriteContent(): Boolean =
        Privilege.WRITE_CONTENT in privileges ||
            Privilege.WRITE in privileges ||
            Privilege.ALL in privileges

    /**
     * Check if user can modify the ACL.
     */
    fun canModifyAcl(): Boolean =
        Privilege.WRITE_ACL in privileges || Privilege.ALL in privileges

    /**
     * Check if user can read the ACL.
     */
    fun canReadAcl(): Boolean =
        Privilege.READ_ACL in privileges || Privilege.ALL in privileges

    /**
     * Check if user can create child resources.
     */
    fun canBind(): Boolean =
        Privilege.BIND in privileges ||
            Privilege.WRITE in privileges ||
            Privilege.ALL in privileges

    /**
     * Check if user can delete child resources.
     */
    fun canUnbind(): Boolean =
        Privilege.UNBIND in privileges ||
            Privilege.WRITE in privileges ||
            Privilege.ALL in privileges

    companion object {
        /**
         * Empty privilege set (no access).
         */
        val NONE = CurrentUserPrivilegeSet(emptySet())

        /**
         * Full access privilege set.
         */
        val ALL = CurrentUserPrivilegeSet(setOf(Privilege.ALL))
    }
}
