package com.arunrk.notes.api.v1.note

import com.arunrk.notes.api.support.RequestContexts
import com.arunrk.notes.domain.model.NoteContentType
import com.arunrk.notes.domain.model.NoteSortOrder
import com.arunrk.notes.domain.usecase.note.ArchiveNoteUseCase
import com.arunrk.notes.domain.usecase.note.CreateNoteCommand
import com.arunrk.notes.domain.usecase.note.CreateNoteUseCase
import com.arunrk.notes.domain.usecase.note.DeleteNoteUseCase
import com.arunrk.notes.domain.usecase.note.GetNoteUseCase
import com.arunrk.notes.domain.usecase.note.ListNotesQuery
import com.arunrk.notes.domain.usecase.note.ListNotesUseCase
import com.arunrk.notes.domain.usecase.note.PinNoteUseCase
import com.arunrk.notes.domain.usecase.note.RestoreNoteUseCase
import com.arunrk.notes.domain.usecase.note.SearchNotesUseCase
import com.arunrk.notes.domain.usecase.note.UpdateNoteCommand
import com.arunrk.notes.domain.usecase.note.UpdateNoteUseCase
import com.arunrk.notes.infrastructure.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Direct note operations.
 *
 * Present for REST completeness and thin clients. The Kotlin Multiplatform
 * clients in this project mutate exclusively through `/sync/push`, so that
 * conflict resolution has exactly one implementation.
 */
@RestController
@RequestMapping("/api/v1/notes")
@Tag(name = "Notes")
class NoteController(
    private val createNote: CreateNoteUseCase,
    private val updateNote: UpdateNoteUseCase,
    private val deleteNote: DeleteNoteUseCase,
    private val restoreNote: RestoreNoteUseCase,
    private val archiveNote: ArchiveNoteUseCase,
    private val pinNote: PinNoteUseCase,
    private val getNote: GetNoteUseCase,
    private val listNotes: ListNotesUseCase,
    private val searchNotes: SearchNotesUseCase,
) {

    @GetMapping
    @Operation(summary = "List notes, pinned first")
    fun list(
        @RequestParam(defaultValue = "false") archived: Boolean,
        @RequestParam(defaultValue = "false") pinned: Boolean,
        @RequestParam(required = false) sort: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PageDto<NoteDto> = PageDto.of(
        listNotes.execute(
            ListNotesQuery(
                userId = CurrentUser.id(),
                archived = archived,
                pinnedOnly = pinned,
                sort = NoteSortOrder.parse(sort),
                page = page,
                size = size,
            )
        )
    )

    @GetMapping("/search")
    @Operation(summary = "Full-text search over title and content")
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PageDto<NoteDto> = PageDto.of(searchNotes.execute(CurrentUser.id(), q, page, size))

    @GetMapping("/{id}")
    @Operation(summary = "Get a note by id")
    fun get(@PathVariable id: UUID): NoteDto = NoteDto.from(getNote.execute(id, CurrentUser.id()))

    @PostMapping
    @Operation(
        summary = "Create a note",
        description = "Idempotent on the client-supplied id, so a retry after a " +
            "dropped response returns the existing note instead of failing.",
    )
    fun create(
        @Valid @RequestBody request: CreateNoteRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<NoteDto> {
        val note = createNote.execute(
            CreateNoteCommand(
                id = requireNotNull(request.id),
                userId = CurrentUser.id(),
                deviceId = RequestContexts.deviceId(httpRequest),
                title = request.title,
                content = request.content,
                contentType = NoteContentType.parse(request.contentType),
                color = request.color,
                isPinned = request.isPinned,
                clientCreatedAt = request.clientCreatedAt,
                clientUpdatedAt = request.clientUpdatedAt,
                platform = RequestContexts.platform(httpRequest),
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(NoteDto.from(note))
    }

    @PutMapping("/{id}")
    @Operation(
        summary = "Replace a note's content",
        description = "Send If-Match with the version you read to get a 409 instead " +
            "of silently overwriting another device's edit.",
    )
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateNoteRequest,
        @RequestHeader(value = "If-Match", required = false) ifMatch: String?,
        httpRequest: HttpServletRequest,
    ): NoteDto = NoteDto.from(
        updateNote.execute(
            UpdateNoteCommand(
                id = id,
                userId = CurrentUser.id(),
                deviceId = RequestContexts.deviceId(httpRequest),
                title = request.title,
                content = request.content,
                contentType = NoteContentType.parse(request.contentType),
                color = request.color,
                expectedVersion = ifMatch?.trim()?.trim('"')?.toLongOrNull(),
                platform = RequestContexts.platform(httpRequest),
            )
        )
    )

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, httpRequest: HttpServletRequest) {
        deleteNote.execute(
            id,
            CurrentUser.id(),
            RequestContexts.deviceId(httpRequest),
            RequestContexts.platform(httpRequest),
        )
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Undo a soft delete")
    fun restore(@PathVariable id: UUID, httpRequest: HttpServletRequest): NoteDto =
        NoteDto.from(
            restoreNote.execute(
                id,
                CurrentUser.id(),
                RequestContexts.deviceId(httpRequest),
                RequestContexts.platform(httpRequest),
            )
        )

    @PostMapping("/{id}/archive")
    fun archive(@PathVariable id: UUID, httpRequest: HttpServletRequest): NoteDto =
        NoteDto.from(setArchived(id, httpRequest, archived = true))

    @PostMapping("/{id}/unarchive")
    fun unarchive(@PathVariable id: UUID, httpRequest: HttpServletRequest): NoteDto =
        NoteDto.from(setArchived(id, httpRequest, archived = false))

    @PostMapping("/{id}/pin")
    fun pin(@PathVariable id: UUID, httpRequest: HttpServletRequest): NoteDto =
        NoteDto.from(setPinned(id, httpRequest, pinned = true))

    @PostMapping("/{id}/unpin")
    fun unpin(@PathVariable id: UUID, httpRequest: HttpServletRequest): NoteDto =
        NoteDto.from(setPinned(id, httpRequest, pinned = false))

    private fun setArchived(id: UUID, httpRequest: HttpServletRequest, archived: Boolean) =
        archiveNote.execute(
            id,
            CurrentUser.id(),
            RequestContexts.deviceId(httpRequest),
            archived,
            RequestContexts.platform(httpRequest),
        )

    private fun setPinned(id: UUID, httpRequest: HttpServletRequest, pinned: Boolean) =
        pinNote.execute(
            id,
            CurrentUser.id(),
            RequestContexts.deviceId(httpRequest),
            pinned,
            RequestContexts.platform(httpRequest),
        )
}
