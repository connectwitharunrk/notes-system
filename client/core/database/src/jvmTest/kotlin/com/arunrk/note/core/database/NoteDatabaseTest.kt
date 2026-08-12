package com.arunrk.note.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arunrk.note.core.database.sql.NoteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real SQLite schema through the JDBC driver.
 *
 * The compare-and-set tests below are the important ones: they guard against
 * the single worst bug this app could have, where an edit made while its upload
 * is in flight is silently marked as synced and lost.
 */
class NoteDatabaseTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NoteDatabase

    private val userId = "user-1"

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NoteDatabase.Schema.create(driver)
        database = NoteDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private val notes get() = database.noteEntityQueries
    private val syncMeta get() = database.syncMetaEntityQueries

    private fun insert(
        id: String,
        title: String = "Note",
        content: String = "body",
        syncStatus: String = "PENDING",
        localRevision: Long = 1,
        isPinned: Boolean = false,
        isArchived: Boolean = false,
        isDeleted: Boolean = false,
        updatedAt: Long = 1_000,
    ) = notes.insertNote(
        id = id,
        userId = userId,
        title = title,
        content = content,
        contentType = "PLAIN",
        color = null,
        isPinned = isPinned,
        isArchived = isArchived,
        isDeleted = isDeleted,
        sortIndex = null,
        createdAt = 1_000,
        updatedAt = updatedAt,
        deletedAt = if (isDeleted) updatedAt else null,
        syncStatus = syncStatus,
        localRevision = localRevision,
        baseVersion = 0,
        baseContentHash = null,
        contentHash = "hash-of-$content",
        syncError = null,
        syncAttempts = 0,
        conflictOfNoteId = null,
    )

    @Test
    fun `insert and read back a note`() {
        insert("n1", title = "Groceries", content = "milk")

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals("Groceries", row.title)
        assertEquals("milk", row.content)
        assertEquals("PENDING", row.syncStatus)
        assertEquals(false, row.isPinned)
    }

    @Test
    fun `active notes exclude archived and deleted`() {
        insert("active")
        insert("archived", isArchived = true)
        insert("deleted", isDeleted = true)

        val active = notes.selectActive(userId).executeAsList().map { it.id }
        assertEquals(listOf("active"), active)

        val archived = notes.selectArchived(userId).executeAsList().map { it.id }
        assertEquals(listOf("archived"), archived)
    }

    @Test
    fun `editing content bumps the local revision and marks the note pending`() {
        insert("n1", syncStatus = "SYNCED", localRevision = 3)

        notes.updateContent(
            title = "Groceries",
            content = "milk, eggs",
            contentHash = "new-hash",
            updatedAt = 2_000,
            id = "n1",
        )

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals(4, row.localRevision)
        assertEquals("PENDING", row.syncStatus)
        assertEquals("milk, eggs", row.content)
    }

    /**
     * The happy path for the guard: nothing changed while the upload was in
     * flight, so the note is correctly marked synced.
     */
    @Test
    fun `markSynced applies when the local revision is unchanged`() {
        insert("n1", syncStatus = "SYNCING", localRevision = 5)

        notes.markSynced(
            baseVersion = 7,
            contentHash = "server-hash",
            id = "n1",
            expectedLocalRevision = 5,
        )

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals("SYNCED", row.syncStatus)
        assertEquals(7, row.baseVersion)
        assertEquals("server-hash", row.baseContentHash)
    }

    /**
     * THE critical test. The user typed while the push was in flight, so the
     * local revision moved on. Marking the note synced here would discard that
     * edit permanently and silently - no error, no retry, just missing text.
     */
    @Test
    fun `markSynced does nothing when the note was edited mid-flight`() {
        insert("n1", syncStatus = "SYNCING", localRevision = 5)

        // The user edits while the request is in flight.
        notes.updateContent(
            title = "Groceries",
            content = "milk, eggs, bread",
            contentHash = "newer-hash",
            updatedAt = 3_000,
            id = "n1",
        )

        // The response for the OLD revision arrives.
        notes.markSynced(
            baseVersion = 7,
            contentHash = "server-hash",
            id = "n1",
            expectedLocalRevision = 5,
        )

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals("PENDING", row.syncStatus, "the newer edit must still be queued")
        assertEquals(6, row.localRevision)
        assertEquals("milk, eggs, bread", row.content, "the newer text must survive")
        assertEquals(0, row.baseVersion, "the stale response must not advance the base version")
    }

    @Test
    fun `markSyncing also respects the revision guard`() {
        insert("n1", syncStatus = "PENDING", localRevision = 2)

        notes.markSyncing(id = "n1", expectedLocalRevision = 99)

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals("PENDING", row.syncStatus)
    }

    @Test
    fun `dirty notes are the pending and failed ones, oldest edit first`() {
        insert("synced", syncStatus = "SYNCED", updatedAt = 1)
        insert("failed", syncStatus = "FAILED", updatedAt = 2)
        insert("pending", syncStatus = "PENDING", updatedAt = 3)
        insert("conflict", syncStatus = "CONFLICT", updatedAt = 4)

        val dirty = notes.selectDirty(userId, limit = 10).executeAsList().map { it.id }
        assertEquals(listOf("failed", "pending"), dirty)
    }

    @Test
    fun `soft delete keeps the row as a tombstone`() {
        insert("n1", syncStatus = "SYNCED", localRevision = 1)

        notes.softDelete(deletedAt = 5_000, updatedAt = 5_000, id = "n1")

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertTrue(row.isDeleted)
        assertEquals(5_000, row.deletedAt)
        assertEquals("PENDING", row.syncStatus)
    }

    /**
     * The pull path must never overwrite an unsynced local edit; only rows
     * already SYNCED are replaced wholesale.
     */
    @Test
    fun `upsertFromServer replaces a row and marks it synced`() {
        insert("n1", content = "local", syncStatus = "SYNCED")

        notes.upsertFromServer(
            id = "n1",
            userId = userId,
            title = "Groceries",
            content = "from server",
            contentType = "PLAIN",
            color = null,
            isPinned = true,
            isArchived = false,
            isDeleted = false,
            sortIndex = null,
            createdAt = 1_000,
            updatedAt = 9_000,
            deletedAt = null,
            localRevision = 1,
            baseVersion = 4,
            contentHash = "server-hash",
            conflictOfNoteId = null,
        )

        val row = assertNotNull(notes.selectById("n1").executeAsOneOrNull())
        assertEquals("from server", row.content)
        assertEquals("SYNCED", row.syncStatus)
        assertEquals(4, row.baseVersion)
        assertEquals("server-hash", row.baseContentHash)
        assertTrue(row.isPinned)
    }

    @Test
    fun `status counts are grouped per sync state`() {
        insert("a", syncStatus = "PENDING")
        insert("b", syncStatus = "PENDING")
        insert("c", syncStatus = "SYNCED")

        val counts = notes.countByStatus(userId).executeAsList().associate { it.syncStatus to it.total }
        assertEquals(2, counts["PENDING"])
        assertEquals(1, counts["SYNCED"])
    }

    @Test
    fun `full resync keeps unsynced local work and drops synced rows`() {
        insert("synced", syncStatus = "SYNCED")
        insert("pending", syncStatus = "PENDING")

        notes.deleteSyncedForUser(userId)

        val remaining = notes.selectAllForUser(userId).executeAsList().map { it.id }
        assertEquals(listOf("pending"), remaining)
    }

    @Test
    fun `purge removes only confirmed tombstones older than the cutoff`() {
        insert("old-tombstone", syncStatus = "SYNCED", isDeleted = true, updatedAt = 1_000)
        insert("recent-tombstone", syncStatus = "SYNCED", isDeleted = true, updatedAt = 9_000)
        insert("unsynced-tombstone", syncStatus = "PENDING", isDeleted = true, updatedAt = 1_000)

        notes.purgeSyncedTombstones(userId = userId, olderThan = 5_000)

        val remaining = notes.selectAllForUser(userId).executeAsList().map { it.id }.sorted()
        assertEquals(listOf("recent-tombstone", "unsynced-tombstone"), remaining)
    }

    // ---- sync metadata ----------------------------------------------------

    @Test
    fun `sync metadata is created once per user and tracks the cursor`() {
        syncMeta.ensureRow(userId)
        syncMeta.ensureRow(userId)

        syncMeta.updateCursor(cursor = 42, userId = userId)
        syncMeta.markSyncSucceeded(at = 123_456, userId = userId)

        val row = assertNotNull(syncMeta.selectByUser(userId).executeAsOneOrNull())
        assertEquals(42, row.lastPullCursor)
        assertEquals(123_456, row.lastSuccessfulSyncAt)
        assertNull(row.lastSyncError)
    }

    @Test
    fun `a second account does not inherit the first account's cursor`() {
        syncMeta.ensureRow(userId)
        syncMeta.updateCursor(cursor = 42, userId = userId)

        syncMeta.ensureRow("user-2")

        assertEquals(0, syncMeta.selectByUser("user-2").executeAsOne().lastPullCursor)
    }

    @Test
    fun `a full resync resets the cursor to zero`() {
        syncMeta.ensureRow(userId)
        syncMeta.updateCursor(cursor = 99, userId = userId)

        syncMeta.markFullResync(at = 555, userId = userId)

        val row = syncMeta.selectByUser(userId).executeAsOne()
        assertEquals(0, row.lastPullCursor)
        assertEquals(555, row.lastFullResyncAt)
    }
}
