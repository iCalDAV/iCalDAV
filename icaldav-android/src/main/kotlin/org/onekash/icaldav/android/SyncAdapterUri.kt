package org.onekash.icaldav.android

import android.net.Uri
import android.provider.CalendarContract

/**
 * Utility for creating sync adapter URIs.
 *
 * Sync adapters must use special URIs with CALLER_IS_SYNCADAPTER flag
 * and account parameters to properly interact with CalendarContract.
 *
 * ## Why Sync Adapter URIs?
 *
 * When a sync adapter modifies events:
 * - Changes are not marked as "dirty" (avoiding infinite sync loops)
 * - Deletes actually remove rows (vs setting DELETED=1)
 * - Account association is enforced
 *
 * Regular app modifications:
 * - Mark events as DIRTY=1 for sync adapter to process
 * - Deletes set DELETED=1 (sync adapter removes after server sync)
 *
 * @see <a href="https://developer.android.com/reference/android/provider/CalendarContract#CALLER_IS_SYNCADAPTER">CALLER_IS_SYNCADAPTER</a>
 */
object SyncAdapterUri {

    /**
     * Convert a CalendarContract URI to a sync adapter URI.
     *
     * Appends CALLER_IS_SYNCADAPTER=true and account parameters.
     *
     * @param uri Base CalendarContract URI (e.g., Events.CONTENT_URI)
     * @param accountName The account name
     * @param accountType The account type
     * @return URI with sync adapter parameters
     *
     * @sample
     * ```kotlin
     * val syncUri = SyncAdapterUri.asSyncAdapter(
     *     Events.CONTENT_URI,
     *     "user@example.com",
     *     "org.onekash.icaldav"
     * )
     * contentResolver.insert(syncUri, values)
     * ```
     */
    fun asSyncAdapter(
        uri: Uri,
        accountName: String,
        accountType: String
    ): Uri {
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }

    /**
     * Check if a URI is a sync adapter URI.
     */
    fun isSyncAdapterUri(uri: Uri): Boolean {
        return uri.getBooleanQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, false)
    }
}
