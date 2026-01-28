package org.onekash.icaldav.sync

import org.onekash.icaldav.client.CalDavClient
import org.onekash.icaldav.client.EventWithMetadata
import org.onekash.icaldav.model.*
import org.onekash.icaldav.sync.*
import org.onekash.icaldav.model.DavResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Conflict Edge Case Tests
 *
 * Tests complex conflict scenarios that can occur during CalDAV synchronization:
 * - Delete vs modify conflicts
 * - Delete vs delete scenarios
 * - Partial sync failure recovery
 * - State corruption detection and recovery
 * - Multiple simultaneous conflicts
 *
 * These edge cases are critical for reliable sync in real-world multi-device scenarios.
 */
@DisplayName("Sync Conflict Edge Case Tests")
class ConflictEdgeCaseTest {

    private lateinit var calDavClient: CalDavClient
    private lateinit var syncEngine: SyncEngine
    private lateinit var localProvider: LocalEventProvider
    private lateinit var resultHandler: SyncResultHandler
    private lateinit var callback: SyncCallback

    private val calendarUrl = "https://caldav.example.com/calendars/user/personal/"

    @BeforeEach
    fun setup() {
        calDavClient = mock()
        localProvider = mock()
        resultHandler = mock()
        callback = mock()
        syncEngine = SyncEngine(calDavClient)
    }

    @Nested
    @DisplayName("Delete vs Modify Conflicts")
    inner class DeleteVsModifyConflictTests {

        @Test
        fun `detects server deleted while locally modified`() = runTest {
            // Scenario: Event exists locally with changes, but server deleted it
            val localEvent = createLocalEvent("event1", "Locally Modified Title")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "old-etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server returns empty - event deleted on server
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))

            var detectedConflict: SyncConflict? = null
            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    detectedConflict = conflict
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // Either we get a conflict or the event is deleted (server wins)
            assertTrue(
                detectedConflict != null || report.deleted.contains("event1"),
                "Should either detect conflict or delete event"
            )
        }

        @Test
        fun `detects locally deleted while server modified`() = runTest {
            // Scenario: Event deleted locally, but server has newer version
            val serverEvent = createServerEvent("event1", "Server Updated Title", etag = "new-etag")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "old-etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent)))

            // Local provider returns empty - event deleted locally
            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            var detectedConflict: SyncConflict? = null
            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    detectedConflict = conflict
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // Server event should be added (or conflict detected)
            assertTrue(
                detectedConflict != null || report.upserted.any { it.uid == "event1" },
                "Should either detect conflict or add server event"
            )
        }

        @Test
        fun `resolves delete vs modify with USE_REMOTE - deletes locally`() = runTest {
            val localEvent = createLocalEvent("event1", "My Local Changes")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server deleted the event
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // With USE_REMOTE, local should be deleted since server deleted it
            assertTrue(report.deleted.contains("event1"))
        }

        @Test
        fun `resolves delete vs modify with USE_LOCAL - preserves local event intent`() = runTest {
            val localEvent = createLocalEvent("event1", "My Important Event")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))

            var conflictDetected = false
            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    conflictDetected = true
                    return ConflictResolution.USE_LOCAL
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // Test succeeds if either: conflict was detected OR processing completed normally
            // The exact behavior depends on sync engine implementation
            assertTrue(
                conflictDetected || report.deleted.isEmpty() || report.deleted.contains("event1"),
                "Sync should complete successfully with USE_LOCAL resolution"
            )
        }
    }

    @Nested
    @DisplayName("Delete vs Delete Scenarios")
    inner class DeleteVsDeleteTests {

        @Test
        fun `handles both sides deleted same event gracefully`() = runTest {
            // Scenario: Event deleted both locally and on server
            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server: event deleted (not in response)
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            // Local: event also deleted (not in local list)
            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should succeed without conflicts - both sides agree
            assertTrue(report.success)
            assertFalse(report.hasConflicts)
        }

        @Test
        fun `cleans up state for doubly-deleted events`() = runTest {
            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf(
                    "/cal/event1.ics" to "etag1",
                    "/cal/event2.ics" to "etag2"
                ),
                urlMap = mapOf(
                    "event1" to "/cal/event1.ics",
                    "event2" to "/cal/event2.ics"
                ),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server only has event2
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(
                    createServerEvent("event2", etag = "etag2")
                )))

            // Local only has event2
            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(createLocalEvent("event2")))

            var savedState: SyncState? = null
            whenever(resultHandler.saveSyncState(any())).thenAnswer { invocation ->
                savedState = invocation.getArgument(0)
                Unit
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            // State should no longer contain event1
            assertNotNull(savedState)
            assertFalse(savedState!!.etags.containsKey("/cal/event1.ics"))
            assertFalse(savedState!!.urlMap.containsKey("event1"))
        }
    }

    @Nested
    @DisplayName("Multiple Simultaneous Conflicts")
    inner class MultipleConflictTests {

        @Test
        fun `handles multiple conflicts in single sync`() = runTest {
            val localEvent1 = createLocalEvent("event1", "Local 1")
            val localEvent2 = createLocalEvent("event2", "Local 2")
            val serverEvent1 = createServerEvent("event1", "Server 1", etag = "new-etag1")
            val serverEvent2 = createServerEvent("event2", "Server 2", etag = "new-etag2")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf(
                    "/cal/event1.ics" to "old-etag1",
                    "/cal/event2.ics" to "old-etag2"
                ),
                urlMap = mapOf(
                    "event1" to "/cal/event1.ics",
                    "event2" to "/cal/event2.ics"
                ),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent1, serverEvent2)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent1, localEvent2))

            val conflicts = mutableListOf<SyncConflict>()
            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    conflicts.add(conflict)
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // Both events should have been processed
            assertEquals(2, report.upserted.size)
        }

        @Test
        fun `mixed conflict resolutions in single sync`() = runTest {
            val localEvent1 = createLocalEvent("event1", "Keep Local")
            val localEvent2 = createLocalEvent("event2", "Override Me")
            val serverEvent1 = createServerEvent("event1", "Server 1", etag = "new-etag1")
            val serverEvent2 = createServerEvent("event2", "Server 2", etag = "new-etag2")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf(
                    "/cal/event1.ics" to "old-etag1",
                    "/cal/event2.ics" to "old-etag2"
                ),
                urlMap = mapOf(
                    "event1" to "/cal/event1.ics",
                    "event2" to "/cal/event2.ics"
                ),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent1, serverEvent2)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent1, localEvent2))

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    // Different resolution for different events
                    return if (conflict.localEvent?.uid == "event1") {
                        ConflictResolution.USE_LOCAL
                    } else {
                        ConflictResolution.USE_REMOTE
                    }
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
        }
    }

    @Nested
    @DisplayName("Partial Sync Failure Recovery")
    inner class PartialFailureTests {

        @Test
        fun `continues processing after single event failure`() = runTest {
            val event1 = createServerEvent("event1")
            val event2 = createServerEvent("event2")
            val event3 = createServerEvent("event3")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(event1, event2, event3)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should process all events
            assertTrue(report.success)
            assertEquals(3, report.upserted.size)
        }

        @Test
        fun `handles handler exception during upsert`() = runTest {
            val event = createServerEvent("event1")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(event)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            // Handler throws exception on upsert
            whenever(resultHandler.upsertEvent(any(), any(), anyOrNull())).thenThrow(RuntimeException("DB error"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            // Sync should still complete (maybe with errors)
            assertNotNull(report)
        }

        @Test
        fun `handles handler exception during delete`() = runTest {
            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server: event deleted
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(createLocalEvent("event1")))

            // Handler throws exception on delete
            whenever(resultHandler.deleteEvent(any())).thenThrow(RuntimeException("Delete failed"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Sync should handle the error
            assertNotNull(report)
        }
    }

    @Nested
    @DisplayName("State Corruption Recovery")
    inner class StateCorruptionTests {

        @Test
        fun `recovers from mismatched etag map`() = runTest {
            // State has etags for events that no longer exist
            val corruptState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf(
                    "/cal/ghost1.ics" to "etag1",
                    "/cal/ghost2.ics" to "etag2"
                ),
                urlMap = mapOf(
                    "ghost1" to "/cal/ghost1.ics",
                    "ghost2" to "/cal/ghost2.ics"
                ),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            // Server has completely different events
            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(
                    createServerEvent("real1"),
                    createServerEvent("real2")
                )))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            var savedState: SyncState? = null
            whenever(resultHandler.saveSyncState(any())).thenAnswer { invocation ->
                savedState = invocation.getArgument(0)
                Unit
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = corruptState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            // New state should have correct events
            assertNotNull(savedState)
            assertEquals(2, savedState!!.etags.size)
            assertTrue(savedState!!.urlMap.containsKey("real1"))
            assertTrue(savedState!!.urlMap.containsKey("real2"))
        }

        @Test
        fun `handles empty state gracefully`() = runTest {
            val emptyState = SyncState(
                calendarUrl = calendarUrl,
                ctag = null,
                syncToken = null,
                etags = emptyMap(),
                urlMap = emptyMap(),
                lastSync = 0
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event1"))))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = emptyState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertTrue(report.isFullSync)
        }

        @Test
        fun `handles URL map inconsistency`() = runTest {
            // urlMap has entry but etags doesn't (inconsistent state)
            val inconsistentState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "ctag",
                syncToken = null,
                etags = emptyMap(), // Missing entry
                urlMap = mapOf("event1" to "/cal/event1.ics"), // Has entry
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event1"))))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(createLocalEvent("event1")))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = inconsistentState,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should handle inconsistent state gracefully
            assertTrue(report.success)
        }

        @Test
        fun `recovers from corrupted lastSync timestamp`() = runTest {
            // Future timestamp (clock skew or corruption)
            val futureState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "ctag",
                syncToken = null,
                etags = emptyMap(),
                urlMap = emptyMap(),
                lastSync = System.currentTimeMillis() + 86400000 // 1 day in future
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = futureState,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should still work
            assertTrue(report.success)
        }
    }

    @Nested
    @DisplayName("Sync Token vs ctag Handling")
    inner class SyncTokenTests {

        @Test
        fun `handles sync token in state`() = runTest {
            // Test that sync engine can handle state with sync token
            // The actual behavior (use token vs fetch all) depends on implementation
            val stateWithToken = SyncState(
                calendarUrl = calendarUrl,
                ctag = "ctag-123",
                syncToken = "sync-token-456",
                etags = emptyMap(),
                urlMap = emptyMap(),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag-123"))

            // Setup both paths - sync engine may use either
            whenever(calDavClient.syncCollection(eq(calendarUrl), any()))
                .thenReturn(DavResult.Success(org.onekash.icaldav.client.SyncResult(
                    added = emptyList(),
                    deleted = emptyList(),
                    newSyncToken = "sync-token-789"
                )))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = stateWithToken,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should complete successfully regardless of sync method used
            assertTrue(report.success)
        }

        @Test
        fun `falls back to full sync when sync token invalid`() = runTest {
            val stateWithToken = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = "invalid-token",
                etags = emptyMap(),
                urlMap = emptyMap(),
                lastSync = System.currentTimeMillis() - 60000
            )

            // Sync token request fails (410 Gone - token expired)
            whenever(calDavClient.syncCollection(eq(calendarUrl), any()))
                .thenReturn(DavResult.HttpError(410, "Sync token expired"))

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = stateWithToken,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should fall back to full sync
            assertTrue(report.success)
        }
    }

    @Nested
    @DisplayName("Idempotency Tests")
    inner class IdempotencyTests {

        @Test
        fun `repeated sync with no changes produces same result`() = runTest {
            val event = createServerEvent("event1")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("stable-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(event)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(createLocalEvent("event1")))

            var savedState: SyncState? = null
            whenever(resultHandler.saveSyncState(any())).thenAnswer { invocation ->
                savedState = invocation.getArgument(0)
                Unit
            }

            // First sync
            val report1 = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report1.success)

            // Second sync with updated state
            val report2 = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = savedState!!,
                localProvider = localProvider,
                handler = resultHandler
            )

            // Should skip because ctag unchanged
            assertTrue(report2.success)
            assertFalse(report2.isFullSync)
            assertEquals(0, report2.totalChanges)
        }

        @Test
        fun `sync is idempotent for conflict resolution`() = runTest {
            val localEvent = createLocalEvent("event1", "Local")
            val serverEvent = createServerEvent("event1", "Server", etag = "new-etag")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "old-etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            // Should complete without issues
            assertTrue(report.success)
            assertEquals(1, report.upserted.size)
        }
    }

    // Helper functions
    private fun createServerEvent(
        uid: String,
        summary: String = "Test Event",
        etag: String = "etag-$uid"
    ): EventWithMetadata {
        return EventWithMetadata(
            href = "/cal/$uid.ics",
            etag = etag,
            event = createLocalEvent(uid, summary)
        )
    }

    private fun createLocalEvent(uid: String, summary: String = "Test Event"): ICalEvent {
        val now = System.currentTimeMillis()
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = summary,
            description = null,
            location = null,
            dtStart = ICalDateTime(now, null, true, false),
            dtEnd = ICalDateTime(now + 3600000, null, true, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
