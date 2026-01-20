package org.onekash.icaldav.android

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.Organizer

/**
 * Builder for batch CalendarContract operations using ContentProviderOperation.
 *
 * Enables atomic insert/update/delete of multiple events in a single transaction.
 * All operations succeed or all fail together, preventing inconsistent database state.
 *
 * ## Usage
 *
 * ```kotlin
 * val builder = CalendarBatchBuilder("user@example.com", "org.onekash.icaldav")
 *
 * // Insert event with reminders
 * builder.insertEvent(event, calendarId)
 * val eventRef = builder.operationCount - 1  // Index of event insert
 * builder.insertReminderWithBackRef(alarm, eventRef)
 *
 * // Apply batch
 * val operations = builder.build()
 * contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
 * ```
 *
 * ## Back-References
 *
 * When inserting an event and its reminders/attendees together, use back-references
 * to link them. The reminder/attendee insert references the event's insert operation
 * index to get the new event ID.
 *
 * @param accountName The account name for sync adapter URIs
 * @param accountType The account type for sync adapter URIs
 */
class CalendarBatchBuilder(
    private val accountName: String,
    private val accountType: String
) {
    private val operations = mutableListOf<ContentProviderOperation>()

    /**
     * Current number of operations in the batch.
     * Useful for getting operation indices for back-references.
     */
    val operationCount: Int get() = operations.size

    /**
     * Add an event insert operation.
     *
     * @param event The event to insert
     * @param calendarId The calendar ID to insert into
     * @return This builder for chaining
     */
    fun insertEvent(event: ICalEvent, calendarId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val values = CalendarContractMapper.toContentValues(event, calendarId)
        val uri = SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add an event update operation.
     *
     * @param eventId The event ID to update
     * @param event The updated event data
     * @param calendarId The calendar ID
     * @return This builder for chaining
     */
    fun updateEvent(eventId: Long, event: ICalEvent, calendarId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val values = CalendarContractMapper.toContentValues(event, calendarId)
        val uri = SyncAdapterUri.asSyncAdapter(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            accountName,
            accountType
        )

        operations.add(
            ContentProviderOperation.newUpdate(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add an event delete operation.
     *
     * @param eventId The event ID to delete
     * @return This builder for chaining
     */
    fun deleteEvent(eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            accountName,
            accountType
        )

        operations.add(
            ContentProviderOperation.newDelete(uri)
                .build()
        )
        return this
    }

    /**
     * Add a reminder insert operation for an existing event.
     *
     * @param alarm The alarm to insert
     * @param eventId The existing event ID
     * @return This builder for chaining
     */
    fun insertReminder(alarm: ICalAlarm, eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val values = ReminderMapper.toContentValues(alarm, eventId)
        val uri = SyncAdapterUri.asSyncAdapter(Reminders.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add a reminder insert operation with back-reference to a pending event insert.
     *
     * Use this when inserting reminders for an event that's being created in the same batch.
     * The eventIdRef is the index of the event insert operation in this batch.
     *
     * @param alarm The alarm to insert
     * @param eventIdRef Index of the event insert operation (0-based)
     * @return This builder for chaining
     */
    fun insertReminderWithBackRef(alarm: ICalAlarm, eventIdRef: Int): CalendarBatchBuilder {
        checkBatchSize()
        require(eventIdRef >= 0 && eventIdRef < operations.size) {
            "eventIdRef ($eventIdRef) must reference a valid operation index (0..${operations.size - 1})"
        }

        val values = ReminderMapper.toContentValues(alarm, eventId = 0) // Placeholder, overridden by back-ref
        values.remove(Reminders.EVENT_ID) // Remove placeholder, will use back-reference
        val uri = SyncAdapterUri.asSyncAdapter(Reminders.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .withValueBackReference(Reminders.EVENT_ID, eventIdRef)
                .build()
        )
        return this
    }

    /**
     * Add an attendee insert operation for an existing event.
     *
     * @param attendee The attendee to insert
     * @param eventId The existing event ID
     * @return This builder for chaining
     */
    fun insertAttendee(attendee: Attendee, eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val values = AttendeeMapper.toContentValues(attendee, eventId)
        val uri = SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add an attendee insert operation with back-reference to a pending event insert.
     *
     * @param attendee The attendee to insert
     * @param eventIdRef Index of the event insert operation (0-based)
     * @return This builder for chaining
     */
    fun insertAttendeeWithBackRef(attendee: Attendee, eventIdRef: Int): CalendarBatchBuilder {
        checkBatchSize()
        require(eventIdRef >= 0 && eventIdRef < operations.size) {
            "eventIdRef ($eventIdRef) must reference a valid operation index (0..${operations.size - 1})"
        }

        val values = AttendeeMapper.toContentValues(attendee, eventId = 0) // Placeholder
        values.remove(Attendees.EVENT_ID) // Remove placeholder, will use back-reference
        val uri = SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .withValueBackReference(Attendees.EVENT_ID, eventIdRef)
                .build()
        )
        return this
    }

    /**
     * Add an organizer insert operation for an existing event.
     *
     * @param organizer The organizer to insert
     * @param eventId The existing event ID
     * @return This builder for chaining
     */
    fun insertOrganizer(organizer: Organizer, eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val values = AttendeeMapper.organizerToContentValues(organizer, eventId)
        val uri = SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add an organizer insert operation with back-reference to a pending event insert.
     *
     * @param organizer The organizer to insert
     * @param eventIdRef Index of the event insert operation (0-based)
     * @return This builder for chaining
     */
    fun insertOrganizerWithBackRef(organizer: Organizer, eventIdRef: Int): CalendarBatchBuilder {
        checkBatchSize()
        require(eventIdRef >= 0 && eventIdRef < operations.size) {
            "eventIdRef ($eventIdRef) must reference a valid operation index (0..${operations.size - 1})"
        }

        val values = AttendeeMapper.organizerToContentValues(organizer, eventId = 0) // Placeholder
        values.remove(Attendees.EVENT_ID) // Remove placeholder, will use back-reference
        val uri = SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .withValueBackReference(Attendees.EVENT_ID, eventIdRef)
                .build()
        )
        return this
    }

    /**
     * Add a delete operation to clear all reminders for an event.
     *
     * @param eventId The event ID to clear reminders for
     * @return This builder for chaining
     */
    fun clearReminders(eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(Reminders.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newDelete(uri)
                .withSelection("${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
                .build()
        )
        return this
    }

    /**
     * Add a delete operation to clear all attendees for an event.
     *
     * @param eventId The event ID to clear attendees for
     * @return This builder for chaining
     */
    fun clearAttendees(eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(Attendees.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newDelete(uri)
                .withSelection("${Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()))
                .build()
        )
        return this
    }

    /**
     * Add an operation to update ETag for an event.
     *
     * @param eventId The event ID
     * @param etag The ETag value (or null to clear)
     * @return This builder for chaining
     */
    fun updateEtag(eventId: Long, etag: String?): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            accountName,
            accountType
        )

        val builder = ContentProviderOperation.newUpdate(uri)
        if (etag != null) {
            builder.withValue(Events.SYNC_DATA1, etag)
        } else {
            builder.withValue(Events.SYNC_DATA1, null)
        }

        operations.add(builder.build())
        return this
    }

    /**
     * Add an operation to clear the dirty flag for an event.
     *
     * @param eventId The event ID
     * @return This builder for chaining
     */
    fun clearDirtyFlag(eventId: Long): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            accountName,
            accountType
        )

        operations.add(
            ContentProviderOperation.newUpdate(uri)
                .withValue(Events.DIRTY, 0)
                .build()
        )
        return this
    }

    /**
     * Clear all operations from the builder.
     *
     * @return This builder for chaining
     */
    fun clear(): CalendarBatchBuilder {
        operations.clear()
        return this
    }

    /**
     * Build the list of operations for ContentResolver.applyBatch().
     *
     * @return ArrayList of ContentProviderOperation ready for applyBatch()
     */
    fun build(): ArrayList<ContentProviderOperation> {
        return ArrayList(operations)
    }

    /**
     * Check if the batch is empty.
     */
    fun isEmpty(): Boolean = operations.isEmpty()

    // ==================== Exception Operations ====================

    /**
     * Add an exception event insert operation.
     *
     * Exception events modify a single occurrence of a recurring event.
     * The exception is linked to the master via ORIGINAL_SYNC_ID.
     *
     * @param exception The exception event (must have recurrenceId set)
     * @param calendarId The calendar ID
     * @return This builder for chaining
     */
    fun insertException(exception: ICalEvent, calendarId: Long): CalendarBatchBuilder {
        require(exception.recurrenceId != null) {
            "Exception event must have recurrenceId set"
        }

        checkBatchSize()
        val values = CalendarContractMapper.toContentValues(exception, calendarId)
        val uri = SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newInsert(uri)
                .withValues(values)
                .build()
        )
        return this
    }

    /**
     * Add a delete operation for all exceptions of a master event.
     *
     * @param masterSyncId The master event's sync ID (UID)
     * @return This builder for chaining
     */
    fun deleteExceptionsForMaster(masterSyncId: String): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newDelete(uri)
                .withSelection("${Events.ORIGINAL_SYNC_ID} = ?", arrayOf(masterSyncId))
                .build()
        )
        return this
    }

    /**
     * Add a delete operation for a specific exception by recurrence ID.
     *
     * @param masterSyncId The master event's sync ID (UID)
     * @param originalInstanceTimeMs The ORIGINAL_INSTANCE_TIME as epoch milliseconds
     * @return This builder for chaining
     */
    fun deleteExceptionByRecurrenceId(
        masterSyncId: String,
        originalInstanceTimeMs: Long
    ): CalendarBatchBuilder {
        checkBatchSize()
        val uri = SyncAdapterUri.asSyncAdapter(Events.CONTENT_URI, accountName, accountType)

        operations.add(
            ContentProviderOperation.newDelete(uri)
                .withSelection(
                    "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?",
                    arrayOf(masterSyncId, originalInstanceTimeMs.toString())
                )
                .build()
        )
        return this
    }

    /**
     * Check batch size limit and throw if exceeded.
     */
    private fun checkBatchSize() {
        if (operations.size >= MAX_BATCH_SIZE) {
            throw IllegalStateException(
                "Batch size limit ($MAX_BATCH_SIZE) exceeded. " +
                "Split operations into multiple batches to avoid transaction failures."
            )
        }
    }

    companion object {
        /**
         * Maximum recommended batch size per Android documentation.
         * Large batches may cause ANRs or transaction failures.
         */
        const val MAX_BATCH_SIZE = 500
    }
}
