package com.arunrk.note.data.note

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notes layer against real SQLite, with no network of any kind in the graph.
 *
 * That absence is the point: if any of this needed a server, the offline-first
 * promise would already be broken.
 */
class NoteRepositoryImplTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NoteDatabase
    private lateinit var repository: NoteRepositoryImpl

    private val userId = "user-1"

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NoteDatabase.Schema.create(driver)
        database = NoteDatabase(driver)
        repository = NoteRepositoryImpl(database, testDispatchers)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private suspend fun createNote(title: String = "Note", content: String = "body") =
        (repository.create(userId, title, content) as Outcome.Success).value

    // ---- creation ---------------------------------------------------------

    @Test
    fun `a new note is created locally and starts pending`() = runTest {
        val note = createNote("Groceries", "milk")

        assertEquals("Groceries", note.title)
        assertEquals(SyncStatus.PENDING, note.syncStatus)
        assertTrue(note.id.isNotBlank())
        // Generated on this device, so notes can be created with no network.
        assertEquals(36, note.id.length)
    }

    @Test
    fun `a created note appears in the list immediately`() = runTest {
        createNote("Groceries", "milk")

        val notes = repository.observeNotes(userId, NoteFilter()).first()

        assertEquals(1, notes.size)
        assertEquals("Groceries", notes.single().title)
    }

    @Test
    fun `notes belonging to another user are never returned`() = runTest {
        createNote("Mine", "mine")
        repository.create("other-user", "Theirs", "theirs")

        val notes = repository.observeNotes(userId, NoteFilter()).first()

        assertEquals(listOf("Mine"), notes.map { it.title })
    }

    // ---- editing ----------------------------------------------------------

    @Test
    fun `editing keeps the note pending and updates the content`() = runTest {
        val note = createNote("Groceries", "milk")

        repository.updateContent(note.id, "Groceries", "milk, eggs")

        val updated = assertNotNull(repository.getNote(note.id))
        assertEquals("milk, eggs", updated.content)
        assertEquals(SyncStatus.PENDING, updated.syncStatus)
    }

    @Test
    fun `pin and archive are persisted`() = runTest {
        val note = createNote()

        repository.setPinned(note.id, true)
        assertTrue(assertNotNull(repository.getNote(note.id)).isPinned)

        repository.setArchived(note.id, true)
        assertTrue(assertNotNull(repository.getNote(note.id)).isArchived)
    }

    @Test
    fun `an archived note leaves the active list and joins the archived one`() = runTest {
        val note = createNote("Archived me")
        repository.setArchived(note.id, true)

        val active = repository.observeNotes(userId, NoteFilter(archived = false)).first()
        val archived = repository.observeNotes(userId, NoteFilter(archived = true)).first()

        assertTrue(active.isEmpty())
        assertEquals(listOf("Archived me"), archived.map { it.title })
    }

    // ---- deletion ---------------------------------------------------------

    /**
     * Deleting is soft. The row survives as a tombstone so other devices can
     * learn about the deletion - absence would be indistinguishable from
     * "not synced yet".
     */
    @Test
    fun `deleting hides the note but keeps a tombstone`() = runTest {
        val note = createNote("Doomed")

        repository.delete(note.id)

        assertTrue(repository.observeNotes(userId, NoteFilter()).first().isEmpty())

        val tombstone = assertNotNull(repository.getNote(note.id), "the row must survive")
        assertTrue(tombstone.isDeleted)
        assertNotNull(tombstone.deletedAt)
        assertEquals(SyncStatus.PENDING, tombstone.syncStatus)
    }

    @Test
    fun `undo restores the same note rather than making a new one`() = runTest {
        val note = createNote("Second thoughts")
        repository.delete(note.id)

        repository.restore(note.id)

        val restored = repository.observeNotes(userId, NoteFilter()).first()
        assertEquals(1, restored.size)
        // Same id, so its history and place in the sync protocol are intact.
        assertEquals(note.id, restored.single().id)
        assertFalse(restored.single().isDeleted)
    }

    // ---- blank notes ------------------------------------------------------

    @Test
    fun `an untouched new note is discarded outright`() = runTest {
        val note = repository.create(userId, "", "").let { (it as Outcome.Success).value }

        repository.discardBlank(note.id)

        assertNull(repository.getNote(note.id), "a never-synced blank note leaves no trace")
    }

    /**
     * Once the server has seen a note, removing it locally would hide it from
     * this device while leaving it alive on every other one. Only a soft delete
     * can express "this is gone" to the rest of the system.
     */
    @Test
    fun `a note the server already knows about is never hard deleted`() = runTest {
        val note = createNote("Synced already")
        database.noteEntityQueries.markSynced(
            baseVersion = 3,
            contentHash = "hash",
            id = note.id,
            expectedLocalRevision = 1,
        )

        repository.discardBlank(note.id)

        assertNotNull(repository.getNote(note.id), "a synced note must not vanish locally")
    }

    // ---- search and sort --------------------------------------------------

    @Test
    fun `search matches the title and the body, case insensitively`() = runTest {
        createNote("Groceries", "milk and eggs")
        createNote("Ideas", "write a book")

        val byTitle = repository.observeNotes(userId, NoteFilter(query = "groc")).first()
        val byBody = repository.observeNotes(userId, NoteFilter(query = "BOOK")).first()
        val noMatch = repository.observeNotes(userId, NoteFilter(query = "zzz")).first()

        assertEquals(listOf("Groceries"), byTitle.map { it.title })
        assertEquals(listOf("Ideas"), byBody.map { it.title })
        assertTrue(noMatch.isEmpty())
    }

    @Test
    fun `pinned notes come first whatever the sort order`() = runTest {
        val oldest = createNote("A oldest")
        createNote("B middle")
        createNote("C newest")
        repository.setPinned(oldest.id, true)

        NoteSortOrder.entries.forEach { order ->
            val notes = repository.observeNotes(userId, NoteFilter(sortOrder = order)).first()
            assertEquals(
                "A oldest",
                notes.first().title,
                "pinned note must lead with $order",
            )
        }
    }

    @Test
    fun `title sort is alphabetical and ignores case`() = runTest {
        createNote("banana")
        createNote("Apple")
        createNote("cherry")

        val notes = repository
            .observeNotes(userId, NoteFilter(sortOrder = NoteSortOrder.TITLE_ASC))
            .first()

        assertEquals(listOf("Apple", "banana", "cherry"), notes.map { it.title })
    }

    // ---- reactivity -------------------------------------------------------

    /**
     * The list is a Flow off SQLite, so a write shows up without anyone asking
     * the repository to reload. That is what makes the UI feel instant offline.
     */
    @Test
    fun `the list emits again when a note is added`() = runTest {
        repository.observeNotes(userId, NoteFilter()).test {
            assertTrue(awaitItem().isEmpty())

            createNote("Appears by itself")

            assertEquals(listOf("Appears by itself"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sync counts are reported per status`() = runTest {
        val first = createNote("One")
        createNote("Two")
        database.noteEntityQueries.markSynced(
            baseVersion = 1,
            contentHash = "hash",
            id = first.id,
            expectedLocalRevision = 1,
        )

        val counts = repository.observeSyncCounts(userId).first()

        assertEquals(1, counts[SyncStatus.SYNCED])
        assertEquals(1, counts[SyncStatus.PENDING])
    }

    @Test
    fun `signing out clears this user's notes and leaves others alone`() = runTest {
        createNote("Mine")
        repository.create("other-user", "Theirs", "theirs")

        repository.clearAllForUser(userId)

        assertTrue(repository.observeNotes(userId, NoteFilter()).first().isEmpty())
        assertEquals(1, repository.observeNotes("other-user", NoteFilter()).first().size)
    }
}
