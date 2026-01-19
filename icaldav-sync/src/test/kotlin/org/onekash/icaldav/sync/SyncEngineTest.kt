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

/**
 * Comprehensive SyncEngine tests.
 *
 * Tests sync flow, conflict detection, error handling,
 * and edge cases per ctag-based synchronization.
 */
@DisplayName("SyncEngine Tests")
class SyncEngineTest {

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
    @DisplayName("ctag-Based Change Detection")
    inner class CtagDetectionTests {

        @Test
        fun `skips sync when ctag unchanged`() {
            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "ctag-123",
                syncToken = null,
                etags = emptyMap(),
                urlMap = emptyMap(),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag-123"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertFalse(report.isFullSync)
            assertEquals(0, report.totalChanges)
            assertEquals("ctag-123", report.newCtag)

            // Should NOT fetch events when ctag unchanged
            verify(calDavClient, never()).fetchEvents(any(), anyOrNull(), anyOrNull())
        }

        @Test
        fun `performs full sync when ctag changed`() {
            val previousState = SyncState.initial(calendarUrl).copy(ctag = "old-ctag")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(createServerEvent("event1"))))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertTrue(report.isFullSync)
            assertEquals("new-ctag", report.newCtag)

            verify(calDavClient).fetchEvents(eq(calendarUrl), anyOrNull(), anyOrNull())
        }

        @Test
        fun `performs full sync on first sync - null ctag`() {
            val initialState = SyncState.initial(calendarUrl)

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("first-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = initialState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertTrue(report.isFullSync)
            assertNull(report.previousCtag)
            assertEquals("first-ctag", report.newCtag)
        }
    }

    @Nested
    @DisplayName("Event Processing")
    inner class EventProcessingTests {

        @Test
        fun `adds new events from server`() {
            val previousState = SyncState.initial(calendarUrl)

            val serverEvent1 = createServerEvent("event1", "Meeting")
            val serverEvent2 = createServerEvent("event2", "Lunch")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent1, serverEvent2)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertEquals(2, report.upserted.size)
            assertEquals(0, report.deleted.size)
        }

        @Test
        fun `detects deleted events`() {
            val localEvent = createLocalEvent("event1", "Meeting")

            val previousState = SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "etag1"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList())) // Event no longer on server

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertEquals(0, report.upserted.size)
            assertEquals(1, report.deleted.size)
            assertEquals("event1", report.deleted[0])
        }

        @Test
        fun `updates modified events`() {
            val localEvent = createLocalEvent("event1", "Old Title")
            val serverEvent = createServerEvent("event1", "New Title", etag = "new-etag")

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

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = previousState,
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertEquals(1, report.upserted.size)
            assertEquals("New Title", report.upserted[0].summary)
        }
    }

    @Nested
    @DisplayName("Conflict Detection")
    inner class ConflictDetectionTests {

        @Test
        fun `detects both-modified conflict`() {
            val localEvent = createLocalEvent("event1", "Local Changes")
            val serverEvent = createServerEvent("event1", "Server Changes", etag = "new-etag")

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

            // Use callback to detect conflict
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
            assertNotNull(detectedConflict)
            assertEquals(ConflictType.BOTH_MODIFIED, detectedConflict?.type)
        }

        @Test
        fun `resolves conflict with USE_REMOTE`() {
            val localEvent = createLocalEvent("event1", "Local Version")
            val serverEvent = createServerEvent("event1", "Server Version", etag = "new-etag")

            setupConflictScenario(localEvent, serverEvent)

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    return ConflictResolution.USE_REMOTE
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = createPreviousState(),
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            assertEquals(1, report.upserted.size)
            assertEquals("Server Version", report.upserted[0].summary)
        }

        @Test
        fun `resolves conflict with USE_LOCAL`() {
            val localEvent = createLocalEvent("event1", "Local Version")
            val serverEvent = createServerEvent("event1", "Server Version", etag = "new-etag")

            setupConflictScenario(localEvent, serverEvent)

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    return ConflictResolution.USE_LOCAL
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = createPreviousState(),
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            // With USE_LOCAL, server version should NOT be applied
            val upsertedIds = report.upserted.map { it.importId }
            assertFalse(upsertedIds.contains("event1") && report.upserted.any { it.summary == "Server Version" })
        }

        @Test
        fun `resolves conflict with SKIP`() {
            val localEvent = createLocalEvent("event1", "Local Version")
            val serverEvent = createServerEvent("event1", "Server Version", etag = "new-etag")

            setupConflictScenario(localEvent, serverEvent)

            val conflictCallback = object : SyncCallback {
                override fun onConflict(conflict: SyncConflict): ConflictResolution {
                    return ConflictResolution.SKIP
                }
            }

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = createPreviousState(),
                localProvider = localProvider,
                handler = resultHandler,
                callback = conflictCallback
            )

            assertTrue(report.success)
            assertTrue(report.hasConflicts)
            assertEquals(1, report.conflicts.size)
        }

        private fun setupConflictScenario(localEvent: ICalEvent, serverEvent: EventWithMetadata) {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(listOf(localEvent))
        }

        private fun createPreviousState(): SyncState {
            return SyncState(
                calendarUrl = calendarUrl,
                ctag = "old-ctag",
                syncToken = null,
                etags = mapOf("/cal/event1.ics" to "old-etag"),
                urlMap = mapOf("event1" to "/cal/event1.ics"),
                lastSync = System.currentTimeMillis() - 60000
            )
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        fun `handles HTTP 401 authentication error`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.HttpError(401, "Unauthorized"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.AUTHENTICATION, report.errors[0].type)
        }

        @Test
        fun `handles HTTP 500 server error`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.HttpError(500, "Internal Server Error"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.SERVER_ERROR, report.errors[0].type)
        }

        @Test
        fun `handles network error`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.NetworkError(java.io.IOException("Connection timeout")))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.NETWORK, report.errors[0].type)
        }

        @Test
        fun `handles parse error`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.ParseError("Invalid XML", null))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.PARSE, report.errors[0].type)
        }

        @Test
        fun `handles exception during sync`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenThrow(RuntimeException("Unexpected error"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.UNKNOWN, report.errors[0].type)
        }

        @Test
        fun `handles fetch events error after successful ctag check`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.HttpError(403, "Forbidden"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertFalse(report.success)
            assertTrue(report.hasErrors)
        }
    }

    @Nested
    @DisplayName("Callback Notifications")
    inner class CallbackTests {

        @Test
        fun `notifies callback on sync start`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler,
                callback = callback
            )

            verify(callback).onSyncStarted(calendarUrl)
        }

        @Test
        fun `notifies callback on sync complete`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler,
                callback = callback
            )

            verify(callback).onSyncComplete(any())
        }

        @Test
        fun `notifies callback on start even when error occurs`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.HttpError(401, "Unauthorized"))

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler,
                callback = callback
            )

            // Callback should receive onSyncStarted
            verify(callback).onSyncStarted(calendarUrl)

            // Error should be in the report
            assertFalse(report.success)
            assertTrue(report.hasErrors)
            assertEquals(SyncErrorType.AUTHENTICATION, report.errors[0].type)
        }

        @Test
        fun `reports progress during sync`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler,
                callback = callback
            )

            verify(callback, atLeast(1)).onProgress(any(), any(), any())
        }
    }

    @Nested
    @DisplayName("State Management")
    inner class StateManagementTests {

        @Test
        fun `saves new sync state after successful sync`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("new-ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(
                    createServerEvent("event1", etag = "etag1")
                )))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            verify(resultHandler).saveSyncState(argThat { state ->
                state.ctag == "new-ctag" &&
                state.etags.isNotEmpty()
            })
        }

        @Test
        fun `preserves etag map in state`() {
            val serverEvent = createServerEvent("event1", etag = "etag-abc")

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(listOf(serverEvent)))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            var savedState: SyncState? = null
            whenever(resultHandler.saveSyncState(any())).thenAnswer { invocation ->
                savedState = invocation.getArgument(0)
                Unit
            }

            syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertNotNull(savedState)
            assertEquals("etag-abc", savedState?.etags?.get("/cal/event1.ics"))
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `handles empty server response`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertEquals(0, report.serverEventCount)
        }

        @Test
        fun `handles null ctag from server`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success(null))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertNull(report.newCtag)
        }

        @Test
        fun `handles large event count`() {
            val serverEvents = (1..100).map { createServerEvent("event$it") }

            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(serverEvents))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.success)
            assertEquals(100, report.serverEventCount)
            assertEquals(100, report.upserted.size)
        }

        @Test
        fun `tracks sync duration`() {
            whenever(calDavClient.getCtag(calendarUrl))
                .thenReturn(DavResult.Success("ctag"))

            whenever(calDavClient.fetchEvents(calendarUrl))
                .thenReturn(DavResult.Success(emptyList()))

            whenever(localProvider.getLocalEvents(calendarUrl))
                .thenReturn(emptyList())

            val report = syncEngine.sync(
                calendarUrl = calendarUrl,
                previousState = SyncState.initial(calendarUrl),
                localProvider = localProvider,
                handler = resultHandler
            )

            assertTrue(report.durationMs >= 0)
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