package org.onekash.icaldav.client

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest
import org.onekash.icaldav.model.*
import org.onekash.icaldav.parser.ICalGenerator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for CalDavClient VTODO and VJOURNAL CRUD operations.
 */
@DisplayName("CalDavClient VTODO/VJOURNAL Tests")
class TodoJournalClientTest {

    private lateinit var server: MockWebServer
    private lateinit var webDavClient: WebDavClient
    private lateinit var calDavClient: CalDavClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()

        webDavClient = WebDavClient(
            httpClient = WebDavClient.testHttpClient(),
            auth = DavAuth.Basic("testuser", "testpass")
        )
        calDavClient = CalDavClient(webDavClient)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun serverUrl(path: String = "/"): String {
        return server.url(path).toString()
    }

    @Nested
    @DisplayName("TodoWithMetadata Tests")
    inner class TodoWithMetadataTests {

        @Test
        fun `TodoWithMetadata stores todo and metadata`() {
            val todo = ICalTodo(
                uid = "test-todo-123",
                summary = "Test Task",
                status = TodoStatus.NEEDS_ACTION
            )

            val metadata = TodoWithMetadata(
                todo = todo,
                href = "/calendars/user/calendar/todo.ics",
                etag = "\"abc123\""
            )

            assertEquals(todo, metadata.todo)
            assertEquals("/calendars/user/calendar/todo.ics", metadata.href)
            assertEquals("\"abc123\"", metadata.etag)
        }

        @Test
        fun `TodoWithMetadata with null etag`() {
            val todo = ICalTodo(uid = "test-todo")

            val metadata = TodoWithMetadata(
                todo = todo,
                href = "/calendars/todo.ics",
                etag = null
            )

            assertEquals(null, metadata.etag)
        }
    }

    @Nested
    @DisplayName("TodoCreateResult Tests")
    inner class TodoCreateResultTests {

        @Test
        fun `TodoCreateResult stores href and etag`() {
            val result = TodoCreateResult(
                href = "/calendars/user/calendar/new-todo.ics",
                etag = "\"newetag123\""
            )

            assertEquals("/calendars/user/calendar/new-todo.ics", result.href)
            assertEquals("\"newetag123\"", result.etag)
        }
    }

    @Nested
    @DisplayName("JournalWithMetadata Tests")
    inner class JournalWithMetadataTests {

        @Test
        fun `JournalWithMetadata stores journal and metadata`() {
            val journal = ICalJournal(
                uid = "test-journal-123",
                summary = "Test Journal",
                status = JournalStatus.DRAFT
            )

            val metadata = JournalWithMetadata(
                journal = journal,
                href = "/calendars/user/calendar/journal.ics",
                etag = "\"journal-etag\""
            )

            assertEquals(journal, metadata.journal)
            assertEquals("/calendars/user/calendar/journal.ics", metadata.href)
            assertEquals("\"journal-etag\"", metadata.etag)
        }
    }

    @Nested
    @DisplayName("JournalCreateResult Tests")
    inner class JournalCreateResultTests {

        @Test
        fun `JournalCreateResult stores href and etag`() {
            val result = JournalCreateResult(
                href = "/calendars/user/calendar/new-journal.ics",
                etag = "\"newjournaletag\""
            )

            assertEquals("/calendars/user/calendar/new-journal.ics", result.href)
            assertEquals("\"newjournaletag\"", result.etag)
        }
    }

    @Nested
    @DisplayName("fetchTodos Tests")
    inner class FetchTodosTests {

        @Test
        fun `fetchTodos parses VTODO from REPORT response`() = runTest {
            val vtodoIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VTODO
                UID:todo-fetch-test
                DTSTAMP:20231215T120000Z
                SUMMARY:Test Todo
                STATUS:NEEDS-ACTION
                END:VTODO
                END:VCALENDAR
            """.trimIndent()

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <d:href>/calendars/user/tasks/todo.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"todo-etag-123"</d:getetag>
                                <c:calendar-data>$vtodoIcs</c:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchTodos(serverUrl("/calendars/user/tasks/"), null, null)

            assertTrue(result.isSuccess)
            val todos = result.getOrNull()!!
            assertEquals(1, todos.size)
            assertEquals("todo-fetch-test", todos[0].todo.uid)
            assertEquals("Test Todo", todos[0].todo.summary)
            assertEquals(TodoStatus.NEEDS_ACTION, todos[0].todo.status)
        }

        @Test
        fun `fetchTodos handles empty response`() = runTest {
            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchTodos(serverUrl("/calendars/user/tasks/"), null, null)

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()!!.size)
        }
    }

    @Nested
    @DisplayName("fetchJournals Tests")
    inner class FetchJournalsTests {

        @Test
        fun `fetchJournals parses VJOURNAL from REPORT response`() = runTest {
            val vjournalIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VJOURNAL
                UID:journal-fetch-test
                DTSTAMP:20231215T120000Z
                SUMMARY:Test Journal Entry
                STATUS:FINAL
                END:VJOURNAL
                END:VCALENDAR
            """.trimIndent()

            val xmlResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:response>
                        <d:href>/calendars/user/journal/entry.ics</d:href>
                        <d:propstat>
                            <d:prop>
                                <d:getetag>"journal-etag-456"</d:getetag>
                                <c:calendar-data>$vjournalIcs</c:calendar-data>
                            </d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status>
                        </d:propstat>
                    </d:response>
                </d:multistatus>
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml")
                    .setBody(xmlResponse)
            )

            val result = calDavClient.fetchJournals(serverUrl("/calendars/user/journal/"), null, null)

            assertTrue(result.isSuccess)
            val journals = result.getOrNull()!!
            assertEquals(1, journals.size)
            assertEquals("journal-fetch-test", journals[0].journal.uid)
            assertEquals("Test Journal Entry", journals[0].journal.summary)
            assertEquals(JournalStatus.FINAL, journals[0].journal.status)
        }
    }

    @Nested
    @DisplayName("createTodo Tests")
    inner class CreateTodoTests {

        @Test
        fun `createTodo sends PUT request with generated ICS`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"new-todo-etag\"")
            )

            val todo = ICalTodo(
                uid = "new-todo-123",
                summary = "New Task",
                status = TodoStatus.NEEDS_ACTION,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val result = calDavClient.createTodo(serverUrl("/calendars/user/tasks/"), todo)

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.body.readUtf8().contains("BEGIN:VTODO"))
        }
    }

    @Nested
    @DisplayName("createJournal Tests")
    inner class CreateJournalTests {

        @Test
        fun `createJournal sends PUT request with generated ICS`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("ETag", "\"new-journal-etag\"")
            )

            val journal = ICalJournal(
                uid = "new-journal-123",
                summary = "New Journal Entry",
                status = JournalStatus.DRAFT,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val result = calDavClient.createJournal(serverUrl("/calendars/user/journal/"), journal)

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.body.readUtf8().contains("BEGIN:VJOURNAL"))
        }
    }

    @Nested
    @DisplayName("updateTodo Tests")
    inner class UpdateTodoTests {

        @Test
        fun `updateTodo sends PUT with If-Match header`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
                    .setHeader("ETag", "\"updated-todo-etag\"")
            )

            val todo = ICalTodo(
                uid = "update-todo-123",
                summary = "Updated Task",
                status = TodoStatus.IN_PROCESS,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val result = calDavClient.updateTodo(
                serverUrl("/calendars/user/tasks/todo.ics"),
                todo,
                "\"original-etag\""
            )

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("\"original-etag\"", request.getHeader("If-Match"))
        }
    }

    @Nested
    @DisplayName("updateJournal Tests")
    inner class UpdateJournalTests {

        @Test
        fun `updateJournal sends PUT with If-Match header`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
                    .setHeader("ETag", "\"updated-journal-etag\"")
            )

            val journal = ICalJournal(
                uid = "update-journal-123",
                summary = "Updated Journal",
                status = JournalStatus.FINAL,
                dtstamp = ICalDateTime.parse("20231215T120000Z")
            )

            val result = calDavClient.updateJournal(
                serverUrl("/calendars/user/journal/entry.ics"),
                journal,
                "\"original-journal-etag\""
            )

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("\"original-journal-etag\"", request.getHeader("If-Match"))
        }
    }

    @Nested
    @DisplayName("deleteTodo Tests")
    inner class DeleteTodoTests {

        @Test
        fun `deleteTodo sends DELETE request`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
            )

            val result = calDavClient.deleteTodo(
                serverUrl("/calendars/user/tasks/todo.ics"),
                "\"todo-etag\""
            )

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("\"todo-etag\"", request.getHeader("If-Match"))
        }

        @Test
        fun `deleteTodo without etag`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
            )

            val result = calDavClient.deleteTodo(
                serverUrl("/calendars/user/tasks/todo.ics"),
                null
            )

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        }
    }

    @Nested
    @DisplayName("deleteJournal Tests")
    inner class DeleteJournalTests {

        @Test
        fun `deleteJournal sends DELETE request`() = runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
            )

            val result = calDavClient.deleteJournal(
                serverUrl("/calendars/user/journal/entry.ics"),
                "\"journal-etag\""
            )

            assertTrue(result.isSuccess)

            // Verify request
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        }
    }

    @Nested
    @DisplayName("RequestBuilder Query Tests")
    inner class RequestBuilderQueryTests {

        @Test
        fun `todoQuery generates VTODO comp-filter`() {
            val xml = org.onekash.icaldav.xml.RequestBuilder.todoQuery()

            assertTrue(xml.contains("comp-filter"))
            assertTrue(xml.contains("VTODO"))
        }

        @Test
        fun `todoQuery with time range`() {
            val xml = org.onekash.icaldav.xml.RequestBuilder.todoQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z"
            )

            assertTrue(xml.contains("time-range"))
            assertTrue(xml.contains("20231201T000000Z"))
            assertTrue(xml.contains("20231231T235959Z"))
        }

        @Test
        fun `journalQuery generates VJOURNAL comp-filter`() {
            val xml = org.onekash.icaldav.xml.RequestBuilder.journalQuery()

            assertTrue(xml.contains("comp-filter"))
            assertTrue(xml.contains("VJOURNAL"))
        }

        @Test
        fun `journalQuery with time range`() {
            val xml = org.onekash.icaldav.xml.RequestBuilder.journalQuery(
                start = "20231201T000000Z",
                end = "20231231T235959Z"
            )

            assertTrue(xml.contains("time-range"))
        }
    }
}
