package com.arunrk.note.sync

import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.core.database.sql.NoteEntity
import kotlinx.coroutines.withContext

private const val TAG = "SyncStore"

/**
 * Everything the sync engine touches in the database, in one place.
 *
 * The engine deals in "apply this server state" and "confirm this push"; the
 * exact SQL and its transactional boundaries live here so the concurrency
 * reasoning is in one file rather than scattered through the pipelines.
 */
class SyncLocalStore(
    private val database: NoteDatabase,
    private val dispatchers: DispatcherProvider,
) {

    private val notes = database.noteEntityQueries
    private val meta = database.syncMetaEntityQueries

    suspend fun dirtyNotes(userId: String, limit: Int): List<NoteEntity> = io {
        notes.selectDirty(userId, limit.toLong()).executeAsList()
    }

    suspend fun noteById(noteId: String): NoteEntity? = io {
        notes.selectById(noteId).executeAsOneOrNull()
    }

    /**
     * Confirms a successful push.
     *
     * THE compare-and-set. If the user edited this note while its upload was in
     * flight, [expectedLocalRevision] no longer matches and zero rows change -
     * the note correctly stays PENDING and is pushed again next cycle.
     *
     * Without this guard that edit is marked synced and silently lost: no error,
     * no retry, just missing text the user is certain they typed.
     *
     * Returns true when the note really was marked synced.
     */
    suspend fun confirmPushed(
        noteId: String,
        expectedLocalRevision: Long,
        serverVersion: Long,
        contentHash: String,
    ): Boolean = io {
        database.transactionWithResult {
            notes.markSynced(
                baseVersion = serverVersion,
                contentHash = contentHash,
                id = noteId,
                expectedLocalRevision = expectedLocalRevision,
            )
            // SQLDelight's generated mutators do not report affected rows, so the
            // row is read back inside the same transaction to see whether the
            // guard let the write through.
            val row = notes.selectSyncStateById(noteId).executeAsOneOrNull()
            row != null && row.localRevision == expectedLocalRevision && row.syncStatus == "SYNCED"
        }
    }

    suspend fun markFailed(noteId: String, error: String) = io {
        notes.markFailed(error = error, id = noteId)
    }

    suspend fun markConflict(noteId: String, message: String) = io {
        notes.markConflict(error = message, id = noteId)
    }

    /**
     * Writes a server note over the local row.
     *
     * Guarded by [expectedLocalRevision] when the caller has one: adopting server
     * state must never overwrite an edit the user made while the request was in
     * flight. Pass null only for notes that have no local edits to lose - rows
     * arriving fresh from a pull.
     *
     * Returns true when the write was applied.
     */
    suspend fun applyServerNote(
        note: ServerNote,
        expectedLocalRevision: Long?,
        markAsConflict: Boolean = false,
    ): Boolean = io {
        database.transactionWithResult {
            val existing = notes.selectSyncStateById(note.id).executeAsOneOrNull()

            val allowed = when {
                existing == null -> true
                expectedLocalRevision != null -> existing.localRevision == expectedLocalRevision
                // No expectation given: only overwrite a row with nothing to lose.
                else -> existing.syncStatus == "SYNCED"
            }

            if (!allowed) {
                Log.d(TAG, "Skipped server write for ${note.id}: local edits are newer")
                return@transactionWithResult false
            }

            notes.upsertFromServer(
                id = note.id,
                userId = note.userId,
                title = note.title,
                content = note.content,
                contentType = note.contentType,
                color = note.color,
                isPinned = note.isPinned,
                isArchived = note.isArchived,
                isDeleted = note.isDeleted,
                sortIndex = null,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                deletedAt = note.deletedAt,
                // Reset: the local copy now descends directly from this server
                // version, so any earlier local revision is irrelevant.
                localRevision = 1,
                baseVersion = note.version,
                contentHash = note.contentHash,
                conflictOfNoteId = note.conflictOfNoteId,
            )

            if (markAsConflict) {
                // A conflict copy is fully synced, but the user has not seen it
                // yet. CONFLICT makes it visible in the list and in the counts;
                // editing it moves it to PENDING like any other change.
                notes.markConflict(
                    error = "Kept from another device's version of this note",
                    id = note.id,
                )
            }
            true
        }
    }

    // ---- cursor ------------------------------------------------------------

    suspend fun cursor(userId: String): Long = io {
        meta.ensureRow(userId)
        meta.selectByUser(userId).executeAsOneOrNull()?.lastPullCursor ?: 0L
    }

    suspend fun setCursor(userId: String, cursor: Long) = io {
        meta.ensureRow(userId)
        meta.updateCursor(cursor = cursor, userId = userId)
    }

    suspend fun lastSuccessfulSyncAt(userId: String): Long? = io {
        meta.selectByUser(userId).executeAsOneOrNull()?.lastSuccessfulSyncAt
    }

    suspend fun recordSuccess(userId: String, at: Long) = io {
        meta.ensureRow(userId)
        meta.markSyncSucceeded(at = at, userId = userId)
    }

    suspend fun recordFailure(userId: String, error: String) = io {
        meta.ensureRow(userId)
        meta.markSyncFailed(error = error, userId = userId)
    }

    /**
     * Discards everything the server owns and rewinds the cursor.
     *
     * Notes with unsynced local work are deliberately kept: this runs after a
     * push, so anything still dirty either failed or was edited since, and
     * throwing it away would destroy the only copy.
     */
    suspend fun prepareFullResync(userId: String, at: Long) = io {
        database.transaction {
            notes.deleteSyncedForUser(userId)
            meta.ensureRow(userId)
            meta.markFullResync(at = at, userId = userId)
        }
    }

    suspend fun clearForUser(userId: String) = io {
        database.transaction {
            notes.deleteAllForUser(userId)
            meta.deleteForUser(userId)
        }
    }

    /** Drops tombstones the server has already confirmed and long since purged. */
    suspend fun purgeConfirmedTombstones(userId: String, olderThan: Long) = io {
        notes.purgeSyncedTombstones(userId = userId, olderThan = olderThan)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatchers.io) { block() }
}

/** A note exactly as the server sent it, already parsed into local units. */
data class ServerNote(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val contentType: String,
    val color: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val version: Long,
    val changeSeq: Long,
    val contentHash: String,
    val conflictOfNoteId: String?,
)
