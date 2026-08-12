package com.arunrk.notes.api.v1.note

import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.PageResult
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Full note representation.
 *
 * [version] is part of the contract, not an implementation detail: clients send
 * it back as `If-Match` or as `baseVersion` in a push, and it is how the server
 * detects that someone else wrote first.
 */
data class NoteDto(
    val id: UUID,
    val title: String,
    val content: String,
    val contentType: String,
    val color: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isDeleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val clientCreatedAt: Instant,
    val clientUpdatedAt: Instant,
    val version: Long,
    val changeSeq: Long,
    val contentHash: String,
    val conflictOf: UUID?,
) {
    companion object {
        fun from(note: Note) = NoteDto(
            id = note.id,
            title = note.title,
            content = note.content,
            contentType = note.contentType.name,
            color = note.color,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            isDeleted = note.isDeleted,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            deletedAt = note.deletedAt,
            clientCreatedAt = note.clientCreatedAt,
            clientUpdatedAt = note.clientUpdatedAt,
            version = note.version,
            changeSeq = note.changeSeq,
            contentHash = note.contentHash,
            conflictOf = note.conflictOf,
        )
    }
}

data class CreateNoteRequest(
    /**
     * Client-generated UUIDv7. Required, not optional: the same id must work
     * whether the note was created offline or online, so the server never mints
     * note ids.
     */
    @field:NotNull(message = "must be provided by the client")
    val id: UUID?,

    @field:Size(max = 512, message = "must be at most 512 characters")
    val title: String = "",

    val content: String = "",
    val contentType: String? = null,
    val color: String? = null,
    val isPinned: Boolean = false,
    val clientCreatedAt: Instant? = null,
    val clientUpdatedAt: Instant? = null,
)

data class UpdateNoteRequest(
    @field:Size(max = 512, message = "must be at most 512 characters")
    val title: String = "",

    val content: String = "",
    val contentType: String? = null,
    val color: String? = null,
)

data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
) {
    companion object {
        fun of(result: PageResult<Note>) = PageDto(
            items = result.items.map { NoteDto.from(it) },
            page = result.page,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            hasNext = result.hasNext,
        )
    }
}
