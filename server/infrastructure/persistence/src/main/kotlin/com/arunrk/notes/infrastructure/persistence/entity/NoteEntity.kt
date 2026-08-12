package com.arunrk.notes.infrastructure.persistence.entity

import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteContentType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Note: `version` is a plain column, deliberately NOT annotated `@Version`.
 *
 * Hibernate's optimistic locking would bump it on every flush and throw its own
 * exception type on mismatch. We need the value to be part of the sync
 * protocol - it is the precondition a client sends back as `baseVersion` - so
 * the domain owns it and increments it explicitly.
 */
@Entity
@Table(name = "notes")
class NoteEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "title", nullable = false)
    var title: String,

    @Column(name = "content", nullable = false)
    var content: String,

    @Column(name = "content_type", nullable = false)
    var contentType: String,

    @Column(name = "color")
    var color: String? = null,

    @Column(name = "is_pinned", nullable = false)
    var isPinned: Boolean = false,

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,

    @Column(name = "sort_index")
    var sortIndex: Double? = null,

    @Column(name = "client_created_at", nullable = false)
    var clientCreatedAt: Instant,

    @Column(name = "client_updated_at", nullable = false)
    var clientUpdatedAt: Instant,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 1,

    @Column(name = "change_seq", nullable = false)
    var changeSeq: Long,

    @Column(name = "content_hash", nullable = false)
    var contentHash: String,

    @Column(name = "last_modified_by")
    var lastModifiedBy: UUID? = null,

    @Column(name = "conflict_of")
    var conflictOf: UUID? = null,
) {
    fun toDomain(): Note = Note(
        id = id,
        userId = userId,
        title = title,
        content = content,
        contentType = NoteContentType.parse(contentType),
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isDeleted = isDeleted,
        sortIndex = sortIndex,
        clientCreatedAt = clientCreatedAt,
        clientUpdatedAt = clientUpdatedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version,
        changeSeq = changeSeq,
        contentHash = contentHash,
        lastModifiedBy = lastModifiedBy,
        conflictOf = conflictOf,
    )

    companion object {
        fun fromDomain(note: Note) = NoteEntity(
            id = note.id,
            userId = note.userId,
            title = note.title,
            content = note.content,
            contentType = note.contentType.name,
            color = note.color,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            isDeleted = note.isDeleted,
            sortIndex = note.sortIndex,
            clientCreatedAt = note.clientCreatedAt,
            clientUpdatedAt = note.clientUpdatedAt,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            deletedAt = note.deletedAt,
            version = note.version,
            changeSeq = note.changeSeq,
            contentHash = note.contentHash,
            lastModifiedBy = note.lastModifiedBy,
            conflictOf = note.conflictOf,
        )
    }
}
