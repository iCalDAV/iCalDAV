package org.onekash.icaldav.sync

import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.client.ResourceHref

/**
 * Result of incremental sync fetch.
 *
 * Includes all change types from sync-collection REPORT (RFC 6578).
 */
data class IncrementalFetchResult(
    /** Events with parsed calendar data (added or modified) */
    val added: List<EventWithMetadata>,

    /** Hrefs of deleted resources */
    val deleted: List<String>,

    /**
     * Resources that exist but didn't include calendar-data.
     * Some servers like iCloud return hrefs without data in sync-collection.
     * These need to be fetched separately via calendar-multiget.
     */
    val addedHrefs: List<ResourceHref>,

    /** New sync token for subsequent delta syncs */
    val newSyncToken: String?,

    /** Whether this was a full sync (token was invalid) */
    val isFullSync: Boolean
)
