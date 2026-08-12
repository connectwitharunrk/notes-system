package com.arunrk.notes.domain.model

import java.time.Instant
import java.util.UUID

/**
 * One note as a client wants it to be, plus the preconditions needed to work
 * out whether anyone else changed it first.
 */
data class NoteChange(
    val id: UUID,
    /**
     * The server [Note.version] this edit was derived from. 0 means "I created
     * this offline and the server has never seen it".
     */
    val baseVersion: Long,

    val title: String,
    val content: String,
    val contentType: NoteContentType = NoteContentType.PLAIN,
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,

    val clientCreatedAt: Instant,
    val clientUpdatedAt: Instant,

    /**
     * Content hash at the client's last successful sync.
     *
     * Without it the server cannot distinguish "I only toggled a flag, leave my
     * content alone" from "we both rewrote this paragraph" - the two cases have
     * opposite correct answers, and guessing wrong destroys someone's writing.
     * Null is treated as "assume the client edited", which is the safe default
     * because it degrades to a conflict copy rather than an overwrite.
     */
    val baseContentHash: String? = null,
)

/** Which rung of the conflict ladder decided the outcome. Reported to clients. */
enum class SyncResolution {
    /** Clean apply - no other device had touched it. */
    APPLIED,

    /** Content already matched; nothing to write. */
    IDENTICAL,

    /** Content matched; only flags differed and were merged. */
    METADATA_MERGED,

    /** Both sides deleted it. */
    BOTH_DELETED,

    /** One side deleted while the other edited. The edit won and the note is alive. */
    EDIT_WINS_OVER_DELETE,

    /** One side deleted and nobody else had edited. The delete stands. */
    DELETE_APPLIED,

    /** The client had not touched the content, so the server's text was kept. */
    SERVER_CONTENT_WINS,

    /** The server had not moved since the client's base, so the client's text was kept. */
    CLIENT_WINS,

    /** Both sides rewrote the content. The server copy stands and the client's became a new note. */
    CONFLICT_COPY_CREATED,
}

/** The field values a merge decided on, before they are given a version and sequence. */
data class MergedFields(
    val title: String,
    val content: String,
    val contentType: NoteContentType,
    val color: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isDeleted: Boolean,
    val clientCreatedAt: Instant,
    val clientUpdatedAt: Instant,
)

/** What the resolver decided. Pure data - it performs no writes itself. */
sealed interface ConflictOutcome {

    /** Write these values over the server note as a new version. */
    data class Merge(val fields: MergedFields, val resolution: SyncResolution) : ConflictOutcome

    /** The server note is already correct; the client just needs to catch up. */
    data class FastForward(val resolution: SyncResolution) : ConflictOutcome

    /**
     * Both sides rewrote the content. The server copy is left untouched and the
     * client's version is materialised as a separate note, so neither piece of
     * writing is lost.
     */
    data class ConflictCopy(val fields: MergedFields) : ConflictOutcome
}

/** Per-item result of a push, returned to the client. */
sealed interface PushResult {
    val noteId: UUID

    data class Applied(
        override val noteId: UUID,
        val version: Long,
        val changeSeq: Long,
        val resolution: SyncResolution,
    ) : PushResult

    data class Conflicted(
        override val noteId: UUID,
        val resolution: SyncResolution,
        val server: Note,
        val conflictCopy: Note?,
    ) : PushResult

    data class Rejected(
        override val noteId: UUID,
        val code: String,
        val message: String,
    ) : PushResult
}

data class PushOutcome(
    val results: List<PushResult>,
    val serverCursor: Long,
    val serverTime: Instant,
)

data class PullPage(
    val notes: List<Note>,
    val nextCursor: Long,
    val hasMore: Boolean,
    /**
     * The client's cursor predates purged tombstones, so an incremental pull
     * would silently miss deletions. It must wipe and re-download.
     */
    val resyncRequired: Boolean,
    val serverTime: Instant,
)

data class SyncStatus(
    val serverCursor: Long,
    val tombstoneFloor: Long,
    val serverTime: Instant,
    val pendingForCursor: Long,
)
