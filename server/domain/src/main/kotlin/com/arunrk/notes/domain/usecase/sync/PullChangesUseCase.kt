package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.PullPage
import com.arunrk.notes.domain.model.SyncStatus
import com.arunrk.notes.domain.policy.NotePolicy
import com.arunrk.notes.domain.port.ChangeSequencer
import com.arunrk.notes.domain.port.NoteRepository
import java.util.UUID

data class PullCommand(
    val userId: UUID,
    val cursor: Long,
    val limit: Int?,
)

/**
 * Incremental download, ordered by change sequence.
 *
 * Tombstones are included: a device learns about a deletion by receiving the
 * deleted note, not by noticing its absence. Absence is indistinguishable from
 * "not yet pulled".
 */
class PullChangesUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val time: TimeProvider,
    private val policy: NotePolicy,
) {

    fun execute(command: PullCommand): PullPage {
        val now = time.now()
        val cursor = command.cursor.coerceAtLeast(0)
        val limit = policy.clampPullLimit(command.limit)

        val floor = sequencer.tombstoneFloor(command.userId)

        // A cursor at or below the purge horizon means this device was offline
        // long enough that deletions it never saw have been erased. Pulling
        // incrementally would leave those notes on the device forever, so it has
        // to start over. Cursor 0 is already a full pull and needs no flag.
        if (cursor in 1..<floor) {
            return PullPage(
                notes = emptyList(),
                nextCursor = 0,
                hasMore = false,
                resyncRequired = true,
                serverTime = now,
            )
        }

        val page = notes.findChangesSince(command.userId, cursor, limit)

        return PullPage(
            notes = page,
            // Advance only as far as we actually delivered. Jumping to the
            // server high-water mark here would skip everything past this page.
            nextCursor = page.lastOrNull()?.changeSeq ?: cursor,
            hasMore = page.size == limit,
            resyncRequired = false,
            serverTime = now,
        )
    }
}

class SyncStatusUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val time: TimeProvider,
) {
    fun execute(userId: UUID, cursor: Long): SyncStatus = SyncStatus(
        serverCursor = sequencer.current(userId),
        tombstoneFloor = sequencer.tombstoneFloor(userId),
        serverTime = time.now(),
        pendingForCursor = notes.countChangesSince(userId, cursor.coerceAtLeast(0)),
    )
}
