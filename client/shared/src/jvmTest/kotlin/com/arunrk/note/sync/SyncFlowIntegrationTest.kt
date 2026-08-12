package com.arunrk.note.sync

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.hash.Hashing
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.core.network.api.SyncApi
import com.arunrk.note.core.network.dto.NoteChangeDto
import com.arunrk.note.core.network.epochMillisToIso
import com.arunrk.note.di.initKoin
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.repository.NoteRepository
import com.arunrk.note.domain.repository.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync engine against the real backend and a real local database.
 *
 * This is the test that matters most in the project. Everything else can be
 * reasoned about; synchronisation cannot - its failure mode is silent data loss,
 * and the only way to know it works is to make two devices disagree and check
 * that both people's writing survives.
 *
 * Requires the server on 127.0.0.1:8080.
 */
class SyncFlowIntegrationTest {

    private companion object {
        val koin: org.koin.core.Koin by lazy {
            initKoin(PlatformContext(), baseUrl = "http://127.0.0.1:8080").koin
        }
    }

    private val auth: AuthRepository get() = koin.get()
    private val notes: NoteRepository get() = koin.get()
    private val sync: SyncManager get() = koin.get()
    private val syncApi: SyncApi get() = koin.get()
    private val database: NoteDatabase get() = koin.get()

    private lateinit var userId: String

    private val email =
        "sync-it-${System.currentTimeMillis()}-${(0..999_999).random()}@example.com"

    @BeforeTest
    fun signIn() = runTest {
        val result = auth.register("Sync Tester", email, "correct-horse-1")
        assertTrue(result is Outcome.Success, "registration failed: ${result.errorOrNull()}")
        userId = (auth.authState.value as AuthState.Authenticated).user.id
    }

    private suspend fun activeNotes() = notes.observeNotes(userId, NoteFilter()).first()

    private suspend fun row(noteId: String) =
        database.noteEntityQueries.selectById(noteId).executeAsOneOrNull()

    /** Pushes a change as if it came from a different device. */
    private suspend fun pushAsOtherDevice(
        noteId: String,
        baseVersion: Long,
        title: String,
        content: String,
        baseContentHash: String?,
        isDeleted: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val result = syncApi.push(
            listOf(
                NoteChangeDto(
                    id = noteId,
                    baseVersion = baseVersion,
                    title = title,
                    content = content,
                    isDeleted = isDeleted,
                    clientCreatedAt = epochMillisToIso(now),
                    clientUpdatedAt = epochMillisToIso(now),
                    baseContentHash = baseContentHash,
                )
            )
        )
        assertTrue(result is Outcome.Success, "other-device push failed")
    }

    // ---- the basic round trip ---------------------------------------------

    @Test
    fun `a locally created note is pushed and becomes synced`() = runTest {
        val note = (notes.create(userId, "Groceries", "milk") as Outcome.Success).value
        assertEquals(SyncStatus.PENDING, note.syncStatus)

        val result = sync.syncNow(SyncReason.MANUAL)

        assertTrue(result.isSuccess, "sync failed: ${result.error}")
        assertEquals(1, result.pushed)

        val stored = assertNotNull(row(note.id))
        assertEquals("SYNCED", stored.syncStatus)
        // The server has now seen it, so it has a version to build on.
        assertEquals(1, stored.baseVersion)
        assertEquals(stored.contentHash, stored.baseContentHash)
    }

    @Test
    fun `a note created on another device arrives on the next pull`() = runTest {
        // Establish a cursor first, so this is a genuine incremental pull.
        sync.syncNow(SyncReason.MANUAL)

        val remoteId = com.arunrk.note.core.common.id.UuidV7.generate()
        pushAsOtherDevice(remoteId, baseVersion = 0, title = "From elsewhere", content = "hello", baseContentHash = null)

        val result = sync.syncNow(SyncReason.MANUAL)

        assertTrue(result.pulled >= 1)
        val local = assertNotNull(row(remoteId), "the remote note should now exist locally")
        assertEquals("From elsewhere", local.title)
        assertEquals("SYNCED", local.syncStatus)
    }

    @Test
    fun `a deletion made elsewhere removes the note from this device`() = runTest {
        val note = (notes.create(userId, "Doomed", "text") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)
        val hash = assertNotNull(row(note.id)).contentHash

        pushAsOtherDevice(note.id, baseVersion = 1, title = "Doomed", content = "text", baseContentHash = hash, isDeleted = true)

        sync.syncNow(SyncReason.MANUAL)

        // Delivered as a tombstone rather than by disappearing - absence would be
        // indistinguishable from "not pulled yet".
        val tombstone = assertNotNull(row(note.id))
        assertTrue(tombstone.isDeleted)
        assertTrue(activeNotes().none { it.id == note.id })
    }

    // ---- conflict ----------------------------------------------------------

    /**
     * The case the whole design exists for: two devices rewrite the same note
     * from the same starting point. Neither piece of writing may be lost.
     */
    @Test
    fun `when two devices rewrite the same note both versions survive`() = runTest {
        val note = (notes.create(userId, "Shared", "milk") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)
        val baseHash = assertNotNull(row(note.id)).contentHash

        // The other device syncs first and wins the race.
        pushAsOtherDevice(note.id, baseVersion = 1, title = "Shared", content = "milk, bread", baseContentHash = baseHash)

        // Meanwhile this device edits from the same base.
        notes.updateContent(note.id, "Shared", "milk, eggs")

        val result = sync.syncNow(SyncReason.MANUAL)

        assertTrue(result.isSuccess, "sync failed: ${result.error}")
        assertEquals(1, result.conflicts)

        // This device adopts the server's version...
        val original = assertNotNull(row(note.id))
        assertEquals("milk, bread", original.content)

        // ...and our own text survives as a separate note rather than being
        // overwritten.
        val copy = activeNotes().singleOrNull { it.conflictOfNoteId == note.id }
        assertNotNull(copy, "a conflict copy should have been created")
        assertEquals("milk, eggs", copy.content)
        assertTrue(copy.title.contains("conflict copy"))
        // Marked CONFLICT so it is visible rather than quietly appearing.
        assertEquals(SyncStatus.CONFLICT, copy.syncStatus)
    }

    @Test
    fun `a metadata-only difference merges instead of creating a copy`() = runTest {
        val note = (notes.create(userId, "Meta", "same text") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)
        val baseHash = assertNotNull(row(note.id)).contentHash

        // Other device pins it; content untouched.
        pushAsOtherDevice(note.id, baseVersion = 1, title = "Meta", content = "same text", baseContentHash = baseHash)

        notes.setPinned(note.id, true)
        val result = sync.syncNow(SyncReason.MANUAL)

        assertTrue(result.isSuccess)
        assertTrue(
            activeNotes().none { it.conflictOfNoteId == note.id },
            "identical text must not produce a conflict copy",
        )
    }

    /**
     * An edit racing a delete keeps the edit. Re-deleting costs one tap;
     * recovering text the user never saw again costs everything.
     */
    @Test
    fun `an edit beats a delete made on another device`() = runTest {
        val note = (notes.create(userId, "Race", "original") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)
        val baseHash = assertNotNull(row(note.id)).contentHash

        pushAsOtherDevice(note.id, baseVersion = 1, title = "Race", content = "original", baseContentHash = baseHash, isDeleted = true)

        notes.updateContent(note.id, "Race", "an important paragraph")
        sync.syncNow(SyncReason.MANUAL)

        val survivor = assertNotNull(row(note.id))
        assertFalse(survivor.isDeleted, "the note must come back")
        assertEquals("an important paragraph", survivor.content)
    }

    // ---- the in-flight edit guard -----------------------------------------

    /**
     * The single worst bug this app could have.
     *
     * An edit made while a push is in flight must not be marked synced by the
     * response for the older revision. Simulated here by pushing, then editing,
     * then confirming with the stale revision - exactly what a slow network
     * produces.
     */
    @Test
    fun `an edit made during a push is not marked synced by the stale response`() = runTest {
        val note = (notes.create(userId, "Racing", "first") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)

        val beforeEdit = assertNotNull(row(note.id))
        val staleRevision = beforeEdit.localRevision

        // The user types while the request is still out.
        notes.updateContent(note.id, "Racing", "second, typed mid-flight")

        // The response for the OLD revision lands.
        val store: SyncLocalStore = koin.get()
        val confirmed = store.confirmPushed(
            noteId = note.id,
            expectedLocalRevision = staleRevision,
            serverVersion = 99,
            contentHash = "stale-hash",
        )

        assertFalse(confirmed, "a stale confirmation must be refused")

        val after = assertNotNull(row(note.id))
        assertEquals("PENDING", after.syncStatus, "the newer edit must still be queued")
        assertEquals("second, typed mid-flight", after.content, "the newer text must survive")
        assertTrue(after.baseVersion < 99, "a stale response must not advance the base version")
    }

    @Test
    fun `a pull never overwrites a note with unsynced local edits`() = runTest {
        val note = (notes.create(userId, "Local wins", "mine") as Outcome.Success).value
        sync.syncNow(SyncReason.MANUAL)
        val baseHash = assertNotNull(row(note.id)).contentHash

        pushAsOtherDevice(note.id, baseVersion = 1, title = "Local wins", content = "theirs", baseContentHash = baseHash)

        // Edit locally, then pull. The pull must leave this row alone.
        notes.updateContent(note.id, "Local wins", "mine, edited")
        val store: SyncLocalStore = koin.get()
        val serverCopy = assertNotNull(row(note.id))

        val written = store.applyServerNote(
            note = ServerNote(
                id = note.id,
                userId = userId,
                title = "Local wins",
                content = "theirs",
                contentType = "PLAIN",
                color = null,
                isPinned = false,
                isArchived = false,
                isDeleted = false,
                createdAt = serverCopy.createdAt,
                updatedAt = serverCopy.updatedAt,
                deletedAt = null,
                version = 2,
                changeSeq = 2,
                contentHash = Hashing.noteContentHash("Local wins", "theirs"),
                conflictOfNoteId = null,
            ),
            expectedLocalRevision = null,
        )

        assertFalse(written, "a pull must not clobber unsynced local work")
        assertEquals("mine, edited", assertNotNull(row(note.id)).content)
    }

    // ---- housekeeping ------------------------------------------------------

    @Test
    fun `syncing twice in a row is a no-op the second time`() = runTest {
        notes.create(userId, "Once", "content")

        val first = sync.syncNow(SyncReason.MANUAL)
        val second = sync.syncNow(SyncReason.MANUAL)

        assertEquals(1, first.pushed)
        assertEquals(0, second.pushed, "nothing should be dirty after a successful push")
        assertTrue(second.isSuccess)
    }

    @Test
    fun `the pull cursor advances and is persisted`() = runTest {
        val store: SyncLocalStore = koin.get()
        assertEquals(0, store.cursor(userId))

        notes.create(userId, "Cursor", "content")
        sync.syncNow(SyncReason.MANUAL)

        assertTrue(store.cursor(userId) > 0, "the cursor must move past the pushed note")
        assertNotNull(store.lastSuccessfulSyncAt(userId))
    }

    @Test
    fun `a rejected note is marked failed rather than lost`() = runTest {
        // Over the server's 512 KB content limit.
        val huge = "x".repeat(600_000)
        val note = (notes.create(userId, "Too big", huge) as Outcome.Success).value

        val result = sync.syncNow(SyncReason.MANUAL)

        assertEquals(1, result.rejected)
        val stored = assertNotNull(row(note.id))
        assertEquals("FAILED", stored.syncStatus)
        assertNotNull(stored.syncError, "the reason must be recorded for the UI")
        // Still on the device: a rejection is never a reason to delete someone's
        // writing.
        assertEquals(huge, stored.content)
    }
}
