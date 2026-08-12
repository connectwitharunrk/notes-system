package com.arunrk.note.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val content: String = "",
    val contentType: String = "PLAIN",
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val clientCreatedAt: String,
    val clientUpdatedAt: String,
    /** Optimistic-concurrency precondition; sent back as `baseVersion` on push. */
    val version: Long,
    val changeSeq: Long,
    val contentHash: String,
    val conflictOf: String? = null,
)

@Serializable
data class NotePageDto(
    val items: List<NoteDto> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
)

@Serializable
data class CreateNoteRequestDto(
    val id: String,
    val title: String = "",
    val content: String = "",
    val contentType: String? = null,
    val color: String? = null,
    val isPinned: Boolean = false,
    val clientCreatedAt: String? = null,
    val clientUpdatedAt: String? = null,
)

@Serializable
data class UpdateNoteRequestDto(
    val title: String = "",
    val content: String = "",
    val contentType: String? = null,
    val color: String? = null,
)

// ---------------------------------------------------------------------------
// Synchronisation
// ---------------------------------------------------------------------------

@Serializable
data class NoteChangeDto(
    val id: String,
    val baseVersion: Long = 0,
    val title: String = "",
    val content: String = "",
    val contentType: String = "PLAIN",
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val clientCreatedAt: String,
    val clientUpdatedAt: String,
    /**
     * Content hash at our last successful sync. Sending it lets the server tell
     * a metadata-only change from a genuine rewrite; omitting it forces the
     * safe-but-noisy assumption that we edited, which produces a conflict copy
     * where a clean merge was possible.
     */
    val baseContentHash: String? = null,
)

@Serializable
data class PushRequestDto(
    val deviceId: String? = null,
    val changes: List<NoteChangeDto> = emptyList(),
)

@Serializable
data class PushErrorDto(
    val code: String,
    val message: String,
)

@Serializable
data class PushResultDto(
    val id: String,
    /** APPLIED, CONFLICT or REJECTED. */
    val status: String,
    val resolution: String? = null,
    val version: Long? = null,
    val changeSeq: Long? = null,
    /** Authoritative server note when our push did not land verbatim. */
    val server: NoteDto? = null,
    /** A new note we must insert locally so our version is not lost. */
    val conflictCopy: NoteDto? = null,
    val error: PushErrorDto? = null,
)

@Serializable
data class PushResponseDto(
    val serverTime: String,
    val serverCursor: Long,
    val results: List<PushResultDto> = emptyList(),
)

@Serializable
data class PullResponseDto(
    val serverTime: String,
    val nextCursor: Long,
    val hasMore: Boolean = false,
    /** Our cursor predates purged tombstones; wipe and re-download. */
    val resyncRequired: Boolean = false,
    /** Includes tombstones - a deletion arrives as a note with isDeleted. */
    val notes: List<NoteDto> = emptyList(),
)

@Serializable
data class SyncStatusResponseDto(
    val serverTime: String,
    val serverCursor: Long,
    val tombstoneFloor: Long,
    val pendingForCursor: Long,
)
