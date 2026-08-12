package com.arunrk.notes.domain.port

import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteSortOrder
import com.arunrk.notes.domain.model.PageResult
import java.time.Instant
import java.util.UUID

interface NoteRepository {

    fun findById(id: UUID): Note?

    /** Scoped by user so a caller can never read another account's note. */
    fun findByIdForUser(id: UUID, userId: UUID): Note?

    fun findAllByIdsForUser(ids: Collection<UUID>, userId: UUID): List<Note>

    fun list(
        userId: UUID,
        archived: Boolean,
        pinnedOnly: Boolean,
        sort: NoteSortOrder,
        page: Int,
        size: Int,
    ): PageResult<Note>

    /** Postgres full-text search over title and content. */
    fun search(userId: UUID, query: String, page: Int, size: Int): PageResult<Note>

    fun save(note: Note): Note

    fun saveAll(notes: List<Note>): List<Note>

    // ---- synchronisation ---------------------------------------------------

    /**
     * Incremental pull. Ordered by [Note.changeSeq] ascending, which is
     * hole-free because sequence assignment happens under the user row lock.
     */
    fun findChangesSince(userId: UUID, cursor: Long, limit: Int): List<Note>

    fun countChangesSince(userId: UUID, cursor: Long): Long

    // ---- tombstone purge ---------------------------------------------------

    fun findUserIdsWithExpiredTombstones(cutoff: Instant): List<UUID>

    /**
     * Highest [Note.changeSeq] among the tombstones about to be purged.
     *
     * This becomes the user's new tombstone floor: any device whose cursor sits
     * below it would never learn about those deletions, so it must full-resync
     * instead of pulling incrementally.
     */
    fun maxChangeSeqOfTombstonesOlderThan(userId: UUID, cutoff: Instant): Long?

    fun deleteTombstonesOlderThan(userId: UUID, cutoff: Instant): Int
}

/**
 * Allocates positions in a user's change sequence.
 *
 * The implementation MUST take the user row lock and hold it until the
 * surrounding transaction commits. That is what guarantees sequence order
 * equals commit order - without it a transaction holding a lower sequence can
 * commit after a reader has already advanced past it, and that note is never
 * delivered to that device again. See docs/ARCHITECTURE.md section 7.
 */
interface ChangeSequencer {

    /**
     * Reserves [count] consecutive positions and returns them.
     * Must be called inside a transaction.
     */
    fun reserve(userId: UUID, count: Int): LongRange

    /** Current high-water mark, for reporting sync status. */
    fun current(userId: UUID): Long

    /**
     * Raises the tombstone floor after a purge. Cursors below it can no longer
     * be resolved, so those devices are told to perform a full resync.
     */
    fun raiseTombstoneFloor(userId: UUID, floor: Long)

    fun tombstoneFloor(userId: UUID): Long
}
