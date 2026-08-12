package com.arunrk.note.domain.model

/**
 * How this note stands relative to the server.
 *
 * Per note, not per app: one failed note must not make the whole list look
 * broken, and one pending note must not hide that everything else is safely
 * synced.
 */
enum class SyncStatus {
    /** Local copy matches the server. */
    SYNCED,

    /** Edited locally, waiting to be pushed. */
    PENDING,

    /** Currently being pushed. */
    SYNCING,

    /** The push was rejected or errored. Retryable. */
    FAILED,

    /** Diverged from another device; a conflict copy was created. */
    CONFLICT;

    companion object {
        fun parse(raw: String?): SyncStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

enum class NoteContentType {
    PLAIN,
    MARKDOWN,
    RICH;

    companion object {
        fun parse(raw: String?): NoteContentType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PLAIN
    }
}

enum class NoteSortOrder {
    UPDATED_DESC,
    CREATED_DESC,
    TITLE_ASC,
}

/**
 * A note as the app works with it.
 *
 * [id] is generated on this device so notes can be created with no network at
 * all. Timestamps are epoch milliseconds: that is what SQLite stores, and it
 * sorts and compares without ceremony.
 */
data class Note(
    val id: String,
    val userId: String,
    val title: String = "",
    val content: String = "",
    val contentType: NoteContentType = NoteContentType.PLAIN,
    val color: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    /** Set on a conflict copy; points at the note it diverged from. */
    val conflictOfNoteId: String? = null,
) {
    /** A note with neither a title nor a body is not worth keeping. */
    val isBlank: Boolean get() = title.isBlank() && content.isBlank()

    /**
     * What the list shows when there is no title. Falling back to the first line
     * of the body beats rendering "Untitled" over and over.
     */
    val displayTitle: String
        get() = title.trim().ifEmpty {
            content.trim().lineSequence().firstOrNull()?.take(80).orEmpty()
        }

    val preview: String
        get() {
            val body = if (title.isBlank()) {
                content.trim().lineSequence().drop(1).joinToString(" ")
            } else {
                content.trim().replace('\n', ' ')
            }
            return body.trim().take(200)
        }

    val hasUnsyncedChanges: Boolean
        get() = syncStatus == SyncStatus.PENDING || syncStatus == SyncStatus.FAILED
}

/** What the notes list is currently showing. */
data class NoteFilter(
    val archived: Boolean = false,
    val query: String = "",
    val sortOrder: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
)
