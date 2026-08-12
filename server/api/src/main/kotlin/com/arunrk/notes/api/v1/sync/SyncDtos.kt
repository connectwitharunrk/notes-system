package com.arunrk.notes.api.v1.sync

import com.arunrk.notes.api.v1.note.NoteDto
import com.arunrk.notes.domain.model.NoteContentType
import com.arunrk.notes.domain.model.NoteChange
import com.arunrk.notes.domain.model.PullPage
import com.arunrk.notes.domain.model.PushOutcome
import com.arunrk.notes.domain.model.PushResult
import com.arunrk.notes.domain.model.SyncStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------------------
// Push
// ---------------------------------------------------------------------------

data class NoteChangeDto(
    @field:NotNull(message = "must be provided by the client")
    val id: UUID?,

    /**
     * The server version this edit descends from; 0 for a note created offline.
     * The server compares it against what it holds to detect a concurrent write.
     */
    val baseVersion: Long = 0,

    val title: String = "",
    val content: String = "",
    val contentType: String? = null,
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,

    @field:NotNull(message = "is required")
    val clientCreatedAt: Instant?,

    @field:NotNull(message = "is required")
    val clientUpdatedAt: Instant?,

    /**
     * Content hash at the client's last successful sync.
     *
     * Strongly recommended. Without it the server cannot tell "I only toggled a
     * flag, keep your text" from "we both rewrote this", and has to assume the
     * latter - which produces a conflict copy where a clean merge was possible.
     * Omitting it is safe but noisy; getting it wrong is not.
     */
    val baseContentHash: String? = null,
) {
    fun toDomain() = NoteChange(
        id = requireNotNull(id),
        baseVersion = baseVersion,
        title = title,
        content = content,
        contentType = NoteContentType.parse(contentType),
        color = color,
        isPinned = isPinned,
        isArchived = isArchived,
        isDeleted = isDeleted,
        clientCreatedAt = requireNotNull(clientCreatedAt),
        clientUpdatedAt = requireNotNull(clientUpdatedAt),
        baseContentHash = baseContentHash,
    )
}

data class PushRequest(
    val deviceId: UUID? = null,

    @field:Valid
    val changes: List<NoteChangeDto> = emptyList(),
)

data class PushErrorDto(val code: String, val message: String)

data class PushResultDto(
    val id: UUID,
    /** APPLIED, CONFLICT or REJECTED. */
    val status: String,
    /** Which rung of the conflict ladder decided this. Null when rejected. */
    val resolution: String? = null,
    val version: Long? = null,
    val changeSeq: Long? = null,
    /** The authoritative server note, when the client's push did not land verbatim. */
    val server: NoteDto? = null,
    /** A new note the client must insert locally so its own version survives. */
    val conflictCopy: NoteDto? = null,
    val error: PushErrorDto? = null,
) {
    companion object {
        fun from(result: PushResult): PushResultDto = when (result) {
            is PushResult.Applied -> PushResultDto(
                id = result.noteId,
                status = "APPLIED",
                resolution = result.resolution.name,
                version = result.version,
                changeSeq = result.changeSeq,
            )

            is PushResult.Conflicted -> PushResultDto(
                id = result.noteId,
                status = "CONFLICT",
                resolution = result.resolution.name,
                version = result.server.version,
                changeSeq = result.server.changeSeq,
                server = NoteDto.from(result.server),
                conflictCopy = result.conflictCopy?.let { NoteDto.from(it) },
            )

            is PushResult.Rejected -> PushResultDto(
                id = result.noteId,
                status = "REJECTED",
                error = PushErrorDto(result.code, result.message),
            )
        }
    }
}

data class PushResponse(
    val serverTime: Instant,
    val serverCursor: Long,
    val results: List<PushResultDto>,
) {
    companion object {
        fun from(outcome: PushOutcome) = PushResponse(
            serverTime = outcome.serverTime,
            serverCursor = outcome.serverCursor,
            results = outcome.results.map { PushResultDto.from(it) },
        )
    }
}

// ---------------------------------------------------------------------------
// Pull
// ---------------------------------------------------------------------------

data class PullResponse(
    val serverTime: Instant,
    /** Persist this after every page so an interrupted pull resumes. */
    val nextCursor: Long,
    val hasMore: Boolean,
    /**
     * True when the cursor predates purged tombstones. Push local changes first,
     * then wipe synced rows, reset the cursor to 0 and pull again - an
     * incremental pull would permanently miss those deletions.
     */
    val resyncRequired: Boolean,
    /** Includes tombstones: a deletion is delivered as a note with isDeleted. */
    val notes: List<NoteDto>,
) {
    companion object {
        fun from(page: PullPage) = PullResponse(
            serverTime = page.serverTime,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
            resyncRequired = page.resyncRequired,
            notes = page.notes.map { NoteDto.from(it) },
        )
    }
}

data class SyncStatusResponse(
    val serverTime: Instant,
    val serverCursor: Long,
    val tombstoneFloor: Long,
    val pendingForCursor: Long,
) {
    companion object {
        fun from(status: SyncStatus) = SyncStatusResponse(
            serverTime = status.serverTime,
            serverCursor = status.serverCursor,
            tombstoneFloor = status.tombstoneFloor,
            pendingForCursor = status.pendingForCursor,
        )
    }
}
