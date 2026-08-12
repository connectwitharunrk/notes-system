package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.domain.model.ConflictOutcome
import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteChange
import com.arunrk.notes.domain.model.SyncResolution
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises every rung of the conflict ladder.
 *
 * The resolver is a pure function, so these are exact assertions about which
 * text survives - which is the property that actually matters. Every case where
 * both sides wrote prose must end in a conflict copy, never an overwrite.
 */
class ConflictResolverTest {

    private val resolver = ConflictResolver()
    private val userId = UUID.randomUUID()
    private val noteId = UUID.randomUUID()
    private val t0: Instant = Instant.parse("2026-08-12T10:00:00Z")

    private fun serverNote(
        title: String = "Groceries",
        content: String = "milk",
        isPinned: Boolean = false,
        isArchived: Boolean = false,
        isDeleted: Boolean = false,
        clientUpdatedAt: Instant = t0,
        version: Long = 5,
    ) = Note(
        id = noteId,
        userId = userId,
        title = title,
        content = content,
        isPinned = isPinned,
        isArchived = isArchived,
        isDeleted = isDeleted,
        clientCreatedAt = t0,
        clientUpdatedAt = clientUpdatedAt,
        createdAt = t0,
        updatedAt = t0,
        deletedAt = if (isDeleted) t0 else null,
        version = version,
        changeSeq = 42,
        contentHash = Note.hashOf(title, content),
    )

    private fun change(
        title: String = "Groceries",
        content: String = "milk",
        isPinned: Boolean = false,
        isArchived: Boolean = false,
        isDeleted: Boolean = false,
        clientUpdatedAt: Instant = t0,
        baseContentHash: String? = null,
        baseVersion: Long = 4,
    ) = NoteChange(
        id = noteId,
        baseVersion = baseVersion,
        title = title,
        content = content,
        isPinned = isPinned,
        isArchived = isArchived,
        isDeleted = isDeleted,
        clientCreatedAt = t0,
        clientUpdatedAt = clientUpdatedAt,
        baseContentHash = baseContentHash,
    )

    // ---- T0: both deleted -------------------------------------------------

    @Test
    fun `both sides deleted converges with nothing to write`() {
        val outcome = resolver.resolve(
            change(isDeleted = true, content = "whatever"),
            serverNote(isDeleted = true),
        )

        val fastForward = assertIs<ConflictOutcome.FastForward>(outcome)
        assertEquals(SyncResolution.BOTH_DELETED, fastForward.resolution)
    }

    // ---- T1: identical content -------------------------------------------

    @Test
    fun `identical content and identical flags writes nothing`() {
        val outcome = resolver.resolve(change(), serverNote())

        val fastForward = assertIs<ConflictOutcome.FastForward>(outcome)
        assertEquals(SyncResolution.IDENTICAL, fastForward.resolution)
    }

    /**
     * Pinning is unioned rather than decided by timestamp: it is cheap,
     * reversible and visible, so gaining a pin is a far smaller annoyance than
     * silently losing one.
     */
    @Test
    fun `identical content merges pin state as a union`() {
        val outcome = resolver.resolve(
            change(isPinned = true),
            serverNote(isPinned = false),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.METADATA_MERGED, merge.resolution)
        assertTrue(merge.fields.isPinned)
    }

    /**
     * The union means an unpinned client cannot clear a server-side pin. Since
     * the merged result already equals what the server holds, nothing is
     * written - burning a version and a sequence number here would make every
     * other device re-download a note that did not change.
     */
    @Test
    fun `a pin set on the server survives a client that never had it`() {
        val outcome = resolver.resolve(
            change(isPinned = false),
            serverNote(isPinned = true),
        )

        val fastForward = assertIs<ConflictOutcome.FastForward>(outcome)
        assertEquals(SyncResolution.IDENTICAL, fastForward.resolution)
    }

    @Test
    fun `identical content resolves archive state by the later client edit`() {
        val outcome = resolver.resolve(
            change(isArchived = true, clientUpdatedAt = t0.plusSeconds(60)),
            serverNote(isArchived = false, clientUpdatedAt = t0),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertTrue(merge.fields.isArchived)
    }

    @Test
    fun `identical content keeps the server archive state when the client is older`() {
        val outcome = resolver.resolve(
            change(isArchived = false, clientUpdatedAt = t0),
            serverNote(isArchived = true, clientUpdatedAt = t0.plusSeconds(60)),
        )

        // The server's newer archive state wins, so the merge is a no-op and
        // nothing is written.
        val fastForward = assertIs<ConflictOutcome.FastForward>(outcome)
        assertEquals(SyncResolution.IDENTICAL, fastForward.resolution)
    }

    @Test
    fun `an older client cannot un-archive a note the server archived later`() {
        val outcome = resolver.resolve(
            change(isArchived = false, content = "milk", clientUpdatedAt = t0),
            serverNote(isArchived = true, content = "milk", clientUpdatedAt = t0.plusSeconds(60)),
        )

        // Whatever the rung, the archived state must not be cleared.
        val stillArchived = when (outcome) {
            is ConflictOutcome.FastForward -> true
            is ConflictOutcome.Merge -> outcome.fields.isArchived
            is ConflictOutcome.ConflictCopy -> false
        }
        assertTrue(stillArchived)
    }

    // ---- T2: delete versus edit ------------------------------------------

    /**
     * The asymmetry is deliberate. Re-deleting a note costs one tap; recovering
     * text the user never saw again costs everything.
     */
    @Test
    fun `an edit on one device beats a delete on another and the note comes back`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk, eggs, bread", isDeleted = false, baseContentHash = base),
            serverNote(content = "milk", isDeleted = true),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.EDIT_WINS_OVER_DELETE, merge.resolution)
        assertFalse(merge.fields.isDeleted, "the note must be undeleted")
        assertEquals("milk, eggs, bread", merge.fields.content)
    }

    @Test
    fun `a server edit beats a client delete and keeps the server text`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk", isDeleted = true, baseContentHash = base),
            serverNote(content = "milk, eggs, bread", isDeleted = false),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.EDIT_WINS_OVER_DELETE, merge.resolution)
        assertFalse(merge.fields.isDeleted)
        assertEquals("milk, eggs, bread", merge.fields.content)
    }

    @Test
    fun `a delete stands when the deleting side is also the one that edited`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk, eggs", isDeleted = true, baseContentHash = base),
            serverNote(content = "milk", isDeleted = false),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.DELETE_APPLIED, merge.resolution)
        assertTrue(merge.fields.isDeleted)
    }

    // ---- T3: client did not touch the content ----------------------------

    @Test
    fun `a client that only toggled a flag keeps the server text`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk", isPinned = true, baseContentHash = base),
            serverNote(content = "milk, eggs, bread"),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.SERVER_CONTENT_WINS, merge.resolution)
        assertEquals("milk, eggs, bread", merge.fields.content, "server text must survive")
        assertTrue(merge.fields.isPinned, "the client's flag change must still apply")
    }

    // ---- T4: server has not moved ----------------------------------------

    @Test
    fun `a client edit wins cleanly when the server text never changed`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk, eggs", baseContentHash = base),
            // Same text as the base, so the version bump was a flag write.
            serverNote(content = "milk", isArchived = true),
        )

        val merge = assertIs<ConflictOutcome.Merge>(outcome)
        assertEquals(SyncResolution.CLIENT_WINS, merge.resolution)
        assertEquals("milk, eggs", merge.fields.content)
    }

    // ---- T5: genuine divergence ------------------------------------------

    /**
     * The case last-writer-wins gets wrong. Both people wrote prose from a
     * common ancestor; there is no correct automatic answer, so both survive.
     */
    @Test
    fun `both sides rewriting the content produces a conflict copy`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(content = "milk, eggs", baseContentHash = base),
            serverNote(content = "milk, bread"),
        )

        val copy = assertIs<ConflictOutcome.ConflictCopy>(outcome)
        assertEquals("milk, eggs", copy.fields.content, "the client's text must be preserved")
    }

    /**
     * A client that cannot say what it started from gets the safe answer. A
     * conflict copy is recoverable with one tap; an overwrite is not
     * recoverable at all.
     */
    @Test
    fun `a missing base hash degrades to a conflict copy rather than an overwrite`() {
        val outcome = resolver.resolve(
            change(content = "milk, eggs", baseContentHash = null),
            serverNote(content = "milk, bread"),
        )

        assertIs<ConflictOutcome.ConflictCopy>(outcome)
    }

    @Test
    fun `a title-only divergence is still a divergence`() {
        val base = Note.hashOf("Groceries", "milk")

        val outcome = resolver.resolve(
            change(title = "Shopping", content = "milk", baseContentHash = base),
            serverNote(title = "Errands", content = "milk"),
        )

        assertIs<ConflictOutcome.ConflictCopy>(outcome)
    }

    /**
     * Guards the 0x00 separator in the content hash. Without it ("ab","c") and
     * ("a","bc") collide, and a real divergence would be classified as
     * IDENTICAL - silently discarding one side's edit.
     */
    @Test
    fun `title and content boundaries cannot be confused for identical notes`() {
        val outcome = resolver.resolve(
            change(title = "ab", content = "c"),
            serverNote(title = "a", content = "bc"),
        )

        assertTrue(
            outcome !is ConflictOutcome.FastForward,
            "different notes must never be treated as identical",
        )
    }
}
