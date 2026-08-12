package com.arunrk.note.domain.repository

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Notes, read from and written to the local database only.
 *
 * Every method here works with no network. Reads are Flows straight off SQLite,
 * so a write is visible in the UI on the next frame rather than after a round
 * trip — that is what makes the app usable offline rather than merely tolerant
 * of being offline.
 *
 * Writes mark the note PENDING; the sync engine (Phase 6) is what eventually
 * moves it to SYNCED. Nothing here waits for, or knows about, the server.
 */
interface NoteRepository {

    fun observeNotes(userId: String, filter: NoteFilter): Flow<List<Note>>

    fun observeNote(noteId: String): Flow<Note?>

    fun observeSyncCounts(userId: String): Flow<Map<SyncStatus, Int>>

    suspend fun getNote(noteId: String): Note?

    /** Returns the created note, whose id was generated on this device. */
    suspend fun create(
        userId: String,
        title: String = "",
        content: String = "",
    ): Outcome<Note>

    suspend fun updateContent(noteId: String, title: String, content: String): Outcome<Unit>

    suspend fun setPinned(noteId: String, pinned: Boolean): Outcome<Unit>

    suspend fun setArchived(noteId: String, archived: Boolean): Outcome<Unit>

    /** Soft delete: the row survives as a tombstone so other devices learn about it. */
    suspend fun delete(noteId: String): Outcome<Unit>

    suspend fun restore(noteId: String): Outcome<Unit>

    /** Hard delete, used only to discard a note that was never worth keeping. */
    suspend fun discardBlank(noteId: String): Outcome<Unit>

    /** Wipes local notes on sign-out. */
    suspend fun clearAllForUser(userId: String): Outcome<Unit>
}
