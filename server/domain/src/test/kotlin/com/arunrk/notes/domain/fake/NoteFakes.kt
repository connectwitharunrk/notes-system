package com.arunrk.notes.domain.fake

import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteSortOrder
import com.arunrk.notes.domain.model.PageResult
import com.arunrk.notes.domain.port.ChangeSequencer
import com.arunrk.notes.domain.port.NoteRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryNoteRepository : NoteRepository {

    val rows = ConcurrentHashMap<UUID, Note>()

    override fun findById(id: UUID): Note? = rows[id]

    override fun findByIdForUser(id: UUID, userId: UUID): Note? =
        rows[id]?.takeIf { it.userId == userId }

    override fun findAllByIdsForUser(ids: Collection<UUID>, userId: UUID): List<Note> =
        ids.mapNotNull { rows[it] }.filter { it.userId == userId }

    override fun list(
        userId: UUID,
        archived: Boolean,
        pinnedOnly: Boolean,
        sort: NoteSortOrder,
        page: Int,
        size: Int,
    ): PageResult<Note> {
        val filtered = rows.values
            .filter { it.userId == userId && !it.isDeleted && it.isArchived == archived }
            .filter { !pinnedOnly || it.isPinned }
            .sortedWith(comparatorFor(sort))
        return paginate(filtered, page, size)
    }

    override fun search(userId: UUID, query: String, page: Int, size: Int): PageResult<Note> {
        val needle = query.trim().lowercase()
        val filtered = rows.values
            .filter { it.userId == userId && !it.isDeleted }
            .filter {
                it.title.lowercase().contains(needle) || it.content.lowercase().contains(needle)
            }
            .sortedWith(comparatorFor(NoteSortOrder.UPDATED_DESC))
        return paginate(filtered, page, size)
    }

    override fun save(note: Note): Note {
        rows[note.id] = note
        return note
    }

    override fun saveAll(notes: List<Note>): List<Note> = notes.map { save(it) }

    override fun findChangesSince(userId: UUID, cursor: Long, limit: Int): List<Note> =
        rows.values
            .filter { it.userId == userId && it.changeSeq > cursor }
            .sortedBy { it.changeSeq }
            .take(limit)

    override fun countChangesSince(userId: UUID, cursor: Long): Long =
        rows.values.count { it.userId == userId && it.changeSeq > cursor }.toLong()

    override fun findUserIdsWithExpiredTombstones(cutoff: Instant): List<UUID> =
        rows.values
            .filter { it.isDeleted && it.deletedAt != null && it.deletedAt!!.isBefore(cutoff) }
            .map { it.userId }
            .distinct()

    override fun maxChangeSeqOfTombstonesOlderThan(userId: UUID, cutoff: Instant): Long? =
        rows.values
            .filter {
                it.userId == userId && it.isDeleted &&
                    it.deletedAt != null && it.deletedAt!!.isBefore(cutoff)
            }
            .maxOfOrNull { it.changeSeq }

    override fun deleteTombstonesOlderThan(userId: UUID, cutoff: Instant): Int {
        val doomed = rows.values.filter {
            it.userId == userId && it.isDeleted &&
                it.deletedAt != null && it.deletedAt!!.isBefore(cutoff)
        }
        doomed.forEach { rows.remove(it.id) }
        return doomed.size
    }

    private fun comparatorFor(sort: NoteSortOrder): Comparator<Note> {
        val then = when (sort) {
            NoteSortOrder.UPDATED_DESC -> compareByDescending<Note> { it.clientUpdatedAt }
            NoteSortOrder.CREATED_DESC -> compareByDescending<Note> { it.clientCreatedAt }
            NoteSortOrder.TITLE_ASC -> compareBy<Note> { it.title.lowercase() }
        }
        return compareByDescending<Note> { it.isPinned }.then(then).thenBy { it.id }
    }

    private fun paginate(all: List<Note>, page: Int, size: Int): PageResult<Note> {
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        return PageResult(all.subList(from, to), page, size, all.size.toLong())
    }
}

/**
 * Mirrors the real sequencer's contract: strictly increasing, gapless, and
 * scoped per user. It cannot reproduce the locking behaviour - that guarantee
 * only exists against a real database and is covered by the integration run.
 */
class InMemoryChangeSequencer : ChangeSequencer {

    private val counters = ConcurrentHashMap<UUID, Long>()
    private val floors = ConcurrentHashMap<UUID, Long>()

    override fun reserve(userId: UUID, count: Int): LongRange {
        require(count > 0) { "count must be positive, was $count" }
        val current = counters.getOrDefault(userId, 0L)
        val last = current + count
        counters[userId] = last
        return (current + 1)..last
    }

    override fun current(userId: UUID): Long = counters.getOrDefault(userId, 0L)

    override fun raiseTombstoneFloor(userId: UUID, floor: Long) {
        floors[userId] = maxOf(floors.getOrDefault(userId, 0L), floor)
    }

    override fun tombstoneFloor(userId: UUID): Long = floors.getOrDefault(userId, 0L)
}
