package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.time.MutableTimeProvider
import com.arunrk.notes.domain.fake.InMemoryChangeSequencer
import com.arunrk.notes.domain.fake.InMemoryDeviceRepository
import com.arunrk.notes.domain.fake.InMemoryNoteRepository
import com.arunrk.notes.domain.fake.RollingBackTransactor
import com.arunrk.notes.domain.usecase.device.DeviceResolver
import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteChange
import com.arunrk.notes.domain.model.PushResult
import com.arunrk.notes.domain.model.SyncResolution
import com.arunrk.notes.domain.policy.NotePolicy
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncFlowTest {

    private val t0: Instant = Instant.parse("2026-08-12T10:00:00Z")
    private val time = MutableTimeProvider(t0)
    private val notes = InMemoryNoteRepository()
    private val sequencer = InMemoryChangeSequencer()
    private val transactor = RollingBackTransactor(listOf(notes.rows))
    private val policy = NotePolicy(maxPushBatch = 10, maxPullPage = 3, maxContentBytes = 64)

    private val devices = InMemoryDeviceRepository()
    private val deviceResolver = DeviceResolver(devices, time)

    private val push =
        PushChangesUseCase(notes, sequencer, ConflictResolver(), deviceResolver, transactor, time, policy)
    private val pull = PullChangesUseCase(notes, sequencer, time, policy)
    private val status = SyncStatusUseCase(notes, sequencer, time)
    private val purge = PurgeTombstonesUseCase(notes, sequencer, transactor, time, policy)

    private val userId = UUID.randomUUID()
    private val deviceA = UUID.randomUUID()
    private val deviceB = UUID.randomUUID()

    private fun change(
        id: UUID = UUID.randomUUID(),
        baseVersion: Long = 0,
        title: String = "Note",
        content: String = "body",
        isDeleted: Boolean = false,
        isPinned: Boolean = false,
        baseContentHash: String? = null,
    ) = NoteChange(
        id = id,
        baseVersion = baseVersion,
        title = title,
        content = content,
        isDeleted = isDeleted,
        isPinned = isPinned,
        clientCreatedAt = t0,
        clientUpdatedAt = time.now(),
        baseContentHash = baseContentHash,
    )

    private fun push(vararg changes: NoteChange, device: UUID = deviceA) =
        push.execute(PushCommand(userId, device, changes.toList()))

    // ---- push basics ------------------------------------------------------

    @Test
    fun `a note created offline is applied with version 1`() {
        val id = UUID.randomUUID()

        val outcome = push(change(id = id, title = "Groceries", content = "milk"))

        val applied = assertIs<PushResult.Applied>(outcome.results.single())
        assertEquals(1, applied.version)
        assertEquals(1, applied.changeSeq)
        assertEquals(SyncResolution.APPLIED, applied.resolution)
        assertEquals("milk", notes.rows[id]?.content)
    }

    @Test
    fun `an edit whose base version matches applies cleanly`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))

        val outcome = push(change(id = id, baseVersion = 1, content = "milk, eggs"))

        val applied = assertIs<PushResult.Applied>(outcome.results.single())
        assertEquals(2, applied.version)
        assertEquals("milk, eggs", notes.rows[id]?.content)
    }

    @Test
    fun `sequence numbers are gapless and strictly increasing within a batch`() {
        val outcome = push(change(), change(), change())

        val seqs = outcome.results.filterIsInstance<PushResult.Applied>().map { it.changeSeq }
        assertEquals(listOf(1L, 2L, 3L), seqs)
        assertEquals(3, outcome.serverCursor)
    }

    @Test
    fun `an oversized note is rejected without consuming a sequence number`() {
        val outcome = push(
            change(content = "x".repeat(200)),
            change(content = "fine"),
        )

        val rejected = assertIs<PushResult.Rejected>(outcome.results[0])
        assertEquals(ErrorCode.NOTE_TOO_LARGE.name, rejected.code)

        val applied = assertIs<PushResult.Applied>(outcome.results[1])
        assertEquals(1, applied.changeSeq, "a rejected change must not burn a sequence number")
        assertEquals(1, outcome.serverCursor)
    }

    @Test
    fun `duplicate ids in one batch are rejected rather than silently folded`() {
        val id = UUID.randomUUID()

        val outcome = push(change(id = id, content = "first"), change(id = id, content = "second"))

        assertIs<PushResult.Applied>(outcome.results[0])
        val rejected = assertIs<PushResult.Rejected>(outcome.results[1])
        assertEquals(ErrorCode.VALIDATION_ERROR.name, rejected.code)
    }

    @Test
    fun `a batch larger than the limit is refused outright`() {
        val tooMany = (0..policy.maxPushBatch).map { change() }

        val error = assertFailsWith<AppException> {
            push.execute(PushCommand(userId, deviceA, tooMany))
        }
        assertEquals(ErrorCode.SYNC_BATCH_TOO_LARGE, error.code)
    }

    @Test
    fun `deleting a note the server has already purged is acknowledged, not resurrected`() {
        val id = UUID.randomUUID()

        val outcome = push(change(id = id, baseVersion = 7, isDeleted = true))

        val applied = assertIs<PushResult.Applied>(outcome.results.single())
        assertEquals(SyncResolution.BOTH_DELETED, applied.resolution)
        assertNull(notes.rows[id], "a purged tombstone must not come back")
    }

    /**
     * Regression: notes.last_modified_by is a foreign key into devices, so a
     * push attributed to a device the server had never registered failed the
     * constraint and surfaced as a 500. A second device that has only ever
     * synced (never logged in on this install) is an entirely normal case.
     */
    @Test
    fun `a push from a device the server has never seen registers it`() {
        val unknownDevice = UUID.randomUUID()
        assertNull(devices.rows[unknownDevice])

        val outcome = push(change(content = "from a new device"), device = unknownDevice)

        assertIs<PushResult.Applied>(outcome.results.single())
        val registered = assertNotNull(devices.rows[unknownDevice], "device should be registered")
        assertEquals(userId, registered.userId)
    }

    @Test
    fun `a push with no device id at all still succeeds`() {
        val outcome = push.execute(PushCommand(userId, null, listOf(change(content = "headless"))))

        assertIs<PushResult.Applied>(outcome.results.single())
    }

    // ---- two-device conflict ---------------------------------------------

    @Test
    fun `two devices rewriting the same note keeps both versions`() {
        val id = UUID.randomUUID()
        push(change(id = id, title = "Groceries", content = "milk"))
        val base = Note.hashOf("Groceries", "milk")

        // Device B syncs first and wins the race.
        push(change(id = id, baseVersion = 1, title = "Groceries", content = "milk, bread"), device = deviceB)

        // Device A pushes an edit derived from the same base.
        val outcome = push(
            change(id = id, baseVersion = 1, title = "Groceries", content = "milk, eggs", baseContentHash = base),
            device = deviceA,
        )

        val conflicted = assertIs<PushResult.Conflicted>(outcome.results.single())
        assertEquals(SyncResolution.CONFLICT_COPY_CREATED, conflicted.resolution)

        // The server copy is untouched.
        assertEquals("milk, bread", conflicted.server.content)
        assertEquals("milk, bread", notes.rows[id]?.content)

        // The losing edit survives as a separate note.
        val copy = assertNotNull(conflicted.conflictCopy)
        assertEquals("milk, eggs", copy.content)
        assertEquals(id, copy.conflictOf)
        assertTrue(copy.title.endsWith("(conflict copy)"))
        assertFalse(copy.isDeleted, "a conflict copy exists to be reviewed")
        assertEquals(2, notes.rows.size)
    }

    @Test
    fun `a conflict copy is delivered to other devices on the next pull`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        val base = Note.hashOf("Note", "milk")
        push(change(id = id, baseVersion = 1, content = "milk, bread"), device = deviceB)

        val cursorBefore = sequencer.current(userId)
        push(change(id = id, baseVersion = 1, content = "milk, eggs", baseContentHash = base))

        val page = pull.execute(PullCommand(userId, cursorBefore, null))
        assertEquals(1, page.notes.size)
        assertEquals("milk, eggs", page.notes.single().content)
    }

    // ---- pull -------------------------------------------------------------

    @Test
    fun `pull returns changes in sequence order and pages through them`() {
        repeat(7) { push(change(title = "n$it")) }

        val first = pull.execute(PullCommand(userId, 0, null))
        assertEquals(3, first.notes.size)
        assertTrue(first.hasMore)
        assertEquals(3, first.nextCursor)

        val second = pull.execute(PullCommand(userId, first.nextCursor, null))
        assertEquals(listOf(4L, 5L, 6L), second.notes.map { it.changeSeq })

        val third = pull.execute(PullCommand(userId, second.nextCursor, null))
        assertEquals(1, third.notes.size)
        assertFalse(third.hasMore)
    }

    /**
     * A device learns about a deletion by receiving the deleted note. Filtering
     * tombstones out of the pull would leave that note on the device forever,
     * because absence is indistinguishable from "not pulled yet".
     */
    @Test
    fun `pull delivers tombstones so other devices learn about deletions`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        val cursor = sequencer.current(userId)

        push(change(id = id, baseVersion = 1, content = "milk", isDeleted = true))

        val page = pull.execute(PullCommand(userId, cursor, null))
        assertEquals(1, page.notes.size)
        assertTrue(page.notes.single().isDeleted)
    }

    @Test
    fun `pull does not advance the cursor past what it delivered`() {
        repeat(7) { push(change()) }

        val page = pull.execute(PullCommand(userId, 0, null))

        assertEquals(3, page.nextCursor)
        assertTrue(page.nextCursor < sequencer.current(userId))
    }

    @Test
    fun `an empty pull leaves the cursor where it was`() {
        push(change())
        val cursor = sequencer.current(userId)

        val page = pull.execute(PullCommand(userId, cursor, null))

        assertEquals(cursor, page.nextCursor)
        assertTrue(page.notes.isEmpty())
    }

    // ---- tombstone purge and resync --------------------------------------

    @Test
    fun `purging tombstones raises the floor and forces stale devices to resync`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        push(change(id = id, baseVersion = 1, content = "milk", isDeleted = true))
        val staleCursor = 1L

        time.advance(java.time.Duration.ofDays(policy.tombstoneRetentionDays + 1).toMillis())
        val report = purge.execute()

        assertEquals(1, report.tombstonesDeleted)
        assertNull(notes.rows[id])

        val page = pull.execute(PullCommand(userId, staleCursor, null))
        assertTrue(page.resyncRequired, "a cursor below the floor would miss the deletion")
        assertTrue(page.notes.isEmpty())
    }

    @Test
    fun `a cursor at or above the floor still pulls incrementally`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        push(change(id = id, baseVersion = 1, content = "milk", isDeleted = true))

        time.advance(java.time.Duration.ofDays(policy.tombstoneRetentionDays + 1).toMillis())
        purge.execute()

        val page = pull.execute(PullCommand(userId, sequencer.current(userId), null))
        assertFalse(page.resyncRequired)
    }

    @Test
    fun `a full pull from zero is never asked to resync`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        push(change(id = id, baseVersion = 1, content = "milk", isDeleted = true))
        time.advance(java.time.Duration.ofDays(policy.tombstoneRetentionDays + 1).toMillis())
        purge.execute()

        val page = pull.execute(PullCommand(userId, 0, null))

        assertFalse(page.resyncRequired, "cursor 0 is already a full download")
    }

    @Test
    fun `recent tombstones are not purged`() {
        val id = UUID.randomUUID()
        push(change(id = id, content = "milk"))
        push(change(id = id, baseVersion = 1, content = "milk", isDeleted = true))

        time.advance(java.time.Duration.ofDays(1).toMillis())
        val report = purge.execute()

        assertEquals(0, report.tombstonesDeleted)
        assertNotNull(notes.rows[id])
    }

    // ---- status -----------------------------------------------------------

    @Test
    fun `status reports how far behind a cursor is`() {
        repeat(4) { push(change()) }

        val result = status.execute(userId, cursor = 2)

        assertEquals(4, result.serverCursor)
        assertEquals(2, result.pendingForCursor)
    }

    // ---- isolation --------------------------------------------------------

    @Test
    fun `one user's changes never appear in another user's pull`() {
        val other = UUID.randomUUID()
        push(change(content = "mine"))
        push.execute(PushCommand(other, deviceB, listOf(change(content = "theirs"))))

        val page = pull.execute(PullCommand(userId, 0, null))

        assertEquals(1, page.notes.size)
        assertEquals("mine", page.notes.single().content)
    }
}
