package com.arunrk.notes.infrastructure.persistence.jpa

import com.arunrk.notes.infrastructure.persistence.entity.NoteEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NoteJpaRepository : JpaRepository<NoteEntity, UUID> {

    fun findByIdAndUserId(id: UUID, userId: UUID): NoteEntity?

    fun findAllByIdInAndUserId(ids: Collection<UUID>, userId: UUID): List<NoteEntity>

    // Tombstones are excluded from every user-facing listing; only the sync
    // endpoints ever see deleted rows.
    fun findByUserIdAndIsDeletedFalseAndIsArchived(
        userId: UUID,
        isArchived: Boolean,
        pageable: Pageable,
    ): Page<NoteEntity>

    fun findByUserIdAndIsDeletedFalseAndIsArchivedAndIsPinned(
        userId: UUID,
        isArchived: Boolean,
        isPinned: Boolean,
        pageable: Pageable,
    ): Page<NoteEntity>

    /**
     * Full-text search backed by the GIN index on
     * `to_tsvector('simple', title || ' ' || content)`.
     *
     * The 'simple' configuration matches the index exactly - using 'english'
     * here would silently bypass it and fall back to a sequential scan, besides
     * stemming notes that may not be in English.
     */
    @Query(
        value = """
            SELECT * FROM notes n
            WHERE n.user_id = :userId
              AND n.is_deleted = false
              AND to_tsvector('simple', coalesce(n.title, '') || ' ' || coalesce(n.content, ''))
                  @@ plainto_tsquery('simple', :query)
            ORDER BY n.is_pinned DESC, n.client_updated_at DESC
        """,
        countQuery = """
            SELECT count(*) FROM notes n
            WHERE n.user_id = :userId
              AND n.is_deleted = false
              AND to_tsvector('simple', coalesce(n.title, '') || ' ' || coalesce(n.content, ''))
                  @@ plainto_tsquery('simple', :query)
        """,
        nativeQuery = true,
    )
    fun search(
        @Param("userId") userId: UUID,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<NoteEntity>

    // ---- synchronisation ---------------------------------------------------

    /**
     * Ordered by changeSeq, which is hole-free because sequence numbers are
     * allocated under the user row lock. Tombstones are included on purpose:
     * a device learns of a deletion by receiving the deleted note, since
     * absence is indistinguishable from "not pulled yet".
     */
    @Query(
        """
        SELECT n FROM NoteEntity n
        WHERE n.userId = :userId AND n.changeSeq > :cursor
        ORDER BY n.changeSeq ASC
        """
    )
    fun findChangesSince(
        @Param("userId") userId: UUID,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): List<NoteEntity>

    @Query("SELECT count(n) FROM NoteEntity n WHERE n.userId = :userId AND n.changeSeq > :cursor")
    fun countChangesSince(@Param("userId") userId: UUID, @Param("cursor") cursor: Long): Long

    // ---- tombstone purge ---------------------------------------------------

    @Query(
        """
        SELECT DISTINCT n.userId FROM NoteEntity n
        WHERE n.isDeleted = true AND n.deletedAt < :cutoff
        """
    )
    fun findUserIdsWithExpiredTombstones(@Param("cutoff") cutoff: Instant): List<UUID>

    @Query(
        """
        SELECT max(n.changeSeq) FROM NoteEntity n
        WHERE n.userId = :userId AND n.isDeleted = true AND n.deletedAt < :cutoff
        """
    )
    fun maxChangeSeqOfTombstonesOlderThan(
        @Param("userId") userId: UUID,
        @Param("cutoff") cutoff: Instant,
    ): Long?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        DELETE FROM NoteEntity n
        WHERE n.userId = :userId AND n.isDeleted = true AND n.deletedAt < :cutoff
        """
    )
    fun deleteTombstonesOlderThan(
        @Param("userId") userId: UUID,
        @Param("cutoff") cutoff: Instant,
    ): Int
}
