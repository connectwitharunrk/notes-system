package com.arunrk.notes.infrastructure.persistence.adapter

import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteSortOrder
import com.arunrk.notes.domain.model.PageResult
import com.arunrk.notes.domain.port.NoteRepository
import com.arunrk.notes.infrastructure.persistence.entity.NoteEntity
import com.arunrk.notes.infrastructure.persistence.jpa.NoteJpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class NoteRepositoryAdapter(
    private val jpa: NoteJpaRepository,
) : NoteRepository {

    override fun findById(id: UUID): Note? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByIdForUser(id: UUID, userId: UUID): Note? =
        jpa.findByIdAndUserId(id, userId)?.toDomain()

    override fun findAllByIdsForUser(ids: Collection<UUID>, userId: UUID): List<Note> {
        // An empty IN list is a SQL syntax error, and asking the database
        // anything at all here is pointless.
        if (ids.isEmpty()) return emptyList()
        return jpa.findAllByIdInAndUserId(ids.toSet(), userId).map { it.toDomain() }
    }

    override fun list(
        userId: UUID,
        archived: Boolean,
        pinnedOnly: Boolean,
        sort: NoteSortOrder,
        page: Int,
        size: Int,
    ): PageResult<Note> {
        val pageable = PageRequest.of(page, size, sortFor(sort))
        val result: Page<NoteEntity> = if (pinnedOnly) {
            jpa.findByUserIdAndIsDeletedFalseAndIsArchivedAndIsPinned(userId, archived, true, pageable)
        } else {
            jpa.findByUserIdAndIsDeletedFalseAndIsArchived(userId, archived, pageable)
        }
        return result.toPageResult(page, size)
    }

    override fun search(userId: UUID, query: String, page: Int, size: Int): PageResult<Note> {
        // Ordering is fixed inside the native query, so no Sort is passed here -
        // Spring Data would otherwise append a second ORDER BY and fail.
        val pageable = PageRequest.of(page, size)
        return jpa.search(userId, query, pageable).toPageResult(page, size)
    }

    @Transactional
    override fun save(note: Note): Note = jpa.save(NoteEntity.fromDomain(note)).toDomain()

    @Transactional
    override fun saveAll(notes: List<Note>): List<Note> =
        jpa.saveAll(notes.map { NoteEntity.fromDomain(it) }).map { it.toDomain() }

    override fun findChangesSince(userId: UUID, cursor: Long, limit: Int): List<Note> =
        jpa.findChangesSince(userId, cursor, PageRequest.of(0, limit)).map { it.toDomain() }

    override fun countChangesSince(userId: UUID, cursor: Long): Long =
        jpa.countChangesSince(userId, cursor)

    override fun findUserIdsWithExpiredTombstones(cutoff: Instant): List<UUID> =
        jpa.findUserIdsWithExpiredTombstones(cutoff)

    override fun maxChangeSeqOfTombstonesOlderThan(userId: UUID, cutoff: Instant): Long? =
        jpa.maxChangeSeqOfTombstonesOlderThan(userId, cutoff)

    @Transactional
    override fun deleteTombstonesOlderThan(userId: UUID, cutoff: Instant): Int =
        jpa.deleteTombstonesOlderThan(userId, cutoff)

    // -----------------------------------------------------------------------

    /** Pinned notes always float to the top, whatever the chosen ordering. */
    private fun sortFor(sort: NoteSortOrder): Sort {
        val pinnedFirst = Sort.by(Sort.Order.desc("isPinned"))
        val then = when (sort) {
            NoteSortOrder.UPDATED_DESC -> Sort.by(Sort.Order.desc("clientUpdatedAt"))
            NoteSortOrder.CREATED_DESC -> Sort.by(Sort.Order.desc("clientCreatedAt"))
            NoteSortOrder.TITLE_ASC -> Sort.by(Sort.Order.asc("title").ignoreCase())
        }
        // id last so paging is stable when the sort key ties - without a total
        // order, rows can repeat or vanish between pages.
        return pinnedFirst.and(then).and(Sort.by(Sort.Order.asc("id")))
    }

    private fun Page<NoteEntity>.toPageResult(page: Int, size: Int) = PageResult(
        items = content.map { it.toDomain() },
        page = page,
        size = size,
        totalElements = totalElements,
    )
}
