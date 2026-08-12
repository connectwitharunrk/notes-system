package com.arunrk.note.domain.usecase.note

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow

/**
 * The notes list, already filtered and sorted.
 *
 * Pinned notes are separated here rather than in the UI so that the rule lives
 * in one place and the screen just renders two lists.
 */
data class NotesSnapshot(
    val pinned: List<Note> = emptyList(),
    val others: List<Note> = emptyList(),
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
    val total: Int get() = pinned.size + others.size
}

class ObserveNotesUseCase(private val repository: NoteRepository) {

    operator fun invoke(userId: String, filter: NoteFilter): Flow<List<Note>> =
        repository.observeNotes(userId, filter)
}

class ObserveNoteUseCase(private val repository: NoteRepository) {
    operator fun invoke(noteId: String): Flow<Note?> = repository.observeNote(noteId)
}

class ObserveSyncCountsUseCase(private val repository: NoteRepository) {
    operator fun invoke(userId: String): Flow<Map<SyncStatus, Int>> =
        repository.observeSyncCounts(userId)
}

class CreateNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(userId: String, title: String = "", content: String = ""): Outcome<Note> =
        repository.create(userId, title, content)
}

class UpdateNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String, title: String, content: String): Outcome<Unit> =
        repository.updateContent(noteId, title, content)
}

class TogglePinUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String, pinned: Boolean): Outcome<Unit> =
        repository.setPinned(noteId, pinned)
}

class SetArchivedUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String, archived: Boolean): Outcome<Unit> =
        repository.setArchived(noteId, archived)
}

class DeleteNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String): Outcome<Unit> = repository.delete(noteId)
}

class RestoreNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String): Outcome<Unit> = repository.restore(noteId)
}

/**
 * Removes a note that was opened and left empty.
 *
 * A hard delete rather than a tombstone: the note never had content and, if it
 * was never synced, the server has never heard of it. Creating a tombstone for
 * it would push a deletion for a note that never existed elsewhere.
 */
class DiscardBlankNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String): Outcome<Unit> = repository.discardBlank(noteId)
}

class GetNoteUseCase(private val repository: NoteRepository) {
    suspend operator fun invoke(noteId: String): Note? = repository.getNote(noteId)
}
