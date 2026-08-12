package com.arunrk.note.data.note

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.hash.Hashing
import com.arunrk.note.core.common.id.UuidV7
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.common.platform.currentTimeMillis
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "NoteRepository"

/**
 * Local-only note storage.
 *
 * The database is the source of truth for the UI. Every write lands here first
 * and marks the note PENDING; nothing waits for the network, and no method on
 * this class can fail because of it.
 */
class NoteRepositoryImpl(
    database: NoteDatabase,
    private val dispatchers: DispatcherProvider,
) : NoteRepository {

    private val notes = database.noteEntityQueries

    override fun observeNotes(userId: String, filter: NoteFilter): Flow<List<Note>> {
        val query = if (filter.archived) {
            notes.selectArchived(userId)
        } else {
            notes.selectActive(userId)
        }

        return query.asFlow()
            .mapToList(dispatchers.io)
            .map { rows ->
                rows.asSequence()
                    .map { it.toDomain() }
                    // Filtering and sorting in Kotlin rather than SQL keeps one
                    // query serving every sort option and every search term,
                    // instead of concatenating SQL per combination. Note counts
                    // per user are small enough that this is not the bottleneck.
                    .filter { it.matches(filter.query) }
                    .sortedWith(comparatorFor(filter.sortOrder))
                    .toList()
            }
    }

    override fun observeNote(noteId: String): Flow<Note?> =
        notes.selectById(noteId)
            .asFlow()
            .mapToOneOrNull(dispatchers.io)
            .map { it?.toDomain() }

    override fun observeSyncCounts(userId: String): Flow<Map<SyncStatus, Int>> =
        notes.countByStatus(userId)
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows ->
                rows.associate { SyncStatus.parse(it.syncStatus) to it.total.toInt() }
            }

    override suspend fun getNote(noteId: String): Note? = io {
        notes.selectById(noteId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun create(userId: String, title: String, content: String): Outcome<Note> =
        write("create") {
            val now = currentTimeMillis()
            val note = Note(
                // Generated here, not by the server: a note must be creatable
                // with no network at all.
                id = UuidV7.generate(now),
                userId = userId,
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
            )

            notes.insertNote(
                id = note.id,
                userId = note.userId,
                title = note.title,
                content = note.content,
                contentType = note.contentType.name,
                color = null,
                isPinned = false,
                isArchived = false,
                isDeleted = false,
                sortIndex = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                syncStatus = SyncStatus.PENDING.name,
                localRevision = 1,
                // 0 means "the server has never seen this note".
                baseVersion = 0,
                baseContentHash = null,
                contentHash = Hashing.noteContentHash(title, content),
                syncError = null,
                syncAttempts = 0,
                conflictOfNoteId = null,
            )
            note
        }

    override suspend fun updateContent(
        noteId: String,
        title: String,
        content: String,
    ): Outcome<Unit> = write("updateContent") {
        // updateContent bumps localRevision and sets PENDING in SQL, which is
        // what lets the sync engine detect an edit made while an upload was in
        // flight instead of marking that edit as synced.
        notes.updateContent(
            title = title,
            content = content,
            contentHash = Hashing.noteContentHash(title, content),
            updatedAt = currentTimeMillis(),
            id = noteId,
        )
    }

    override suspend fun setPinned(noteId: String, pinned: Boolean): Outcome<Unit> =
        write("setPinned") {
            notes.updatePinned(isPinned = pinned, updatedAt = currentTimeMillis(), id = noteId)
        }

    override suspend fun setArchived(noteId: String, archived: Boolean): Outcome<Unit> =
        write("setArchived") {
            notes.updateArchived(isArchived = archived, updatedAt = currentTimeMillis(), id = noteId)
        }

    override suspend fun delete(noteId: String): Outcome<Unit> = write("delete") {
        val now = currentTimeMillis()
        // Soft delete. The row stays as a tombstone so other devices learn about
        // the deletion - absence would be indistinguishable from "not synced yet".
        notes.softDelete(deletedAt = now, updatedAt = now, id = noteId)
    }

    override suspend fun restore(noteId: String): Outcome<Unit> = write("restore") {
        notes.restore(updatedAt = currentTimeMillis(), id = noteId)
    }

    override suspend fun discardBlank(noteId: String): Outcome<Unit> = write("discardBlank") {
        val existing = notes.selectById(noteId).executeAsOneOrNull()
        // Only ever hard-delete a note the server has never seen. Once it has a
        // baseVersion, removing it locally would hide it from this device while
        // leaving it alive everywhere else.
        if (existing != null && existing.baseVersion == 0L) {
            notes.deleteById(noteId)
        }
    }

    override suspend fun clearAllForUser(userId: String): Outcome<Unit> = write("clearAllForUser") {
        notes.deleteAllForUser(userId)
    }

    // -----------------------------------------------------------------------

    private suspend fun <T> io(block: () -> T): T = withContext(dispatchers.io) { block() }

    /**
     * Local writes fail only for storage reasons - a full disk, a corrupt file -
     * and those are worth surfacing distinctly rather than as a generic error,
     * because the user can actually act on them.
     */
    private suspend fun <T> write(operation: String, block: () -> T): Outcome<T> = try {
        Outcome.Success(io(block))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Local write failed: $operation", e)
        Outcome.Failure(AppError.Storage("Couldn't save your change to this device.", e))
    }

    private fun Note.matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim()
        return title.contains(needle, ignoreCase = true) ||
            content.contains(needle, ignoreCase = true)
    }

    /** Pinned notes always float to the top, whatever the chosen ordering. */
    private fun comparatorFor(sortOrder: NoteSortOrder): Comparator<Note> {
        val then = when (sortOrder) {
            NoteSortOrder.UPDATED_DESC -> compareByDescending<Note> { it.updatedAt }
            NoteSortOrder.CREATED_DESC -> compareByDescending<Note> { it.createdAt }
            NoteSortOrder.TITLE_ASC -> compareBy<Note> { it.displayTitle.lowercase() }
        }
        // id last so the order is total: without a tie-break, notes saved in the
        // same millisecond swap places on every recomposition.
        return compareByDescending<Note> { it.isPinned }.then(then).thenBy { it.id }
    }
}
