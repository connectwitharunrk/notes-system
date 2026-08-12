package com.arunrk.note.feature.notes.list

import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteSortOrder

data class NotesListState(
    val pinned: List<Note> = emptyList(),
    val others: List<Note> = emptyList(),
    val query: String = "",
    val isSearchActive: Boolean = false,
    val sortOrder: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
    val viewMode: NoteViewMode = NoteViewMode.GRID,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val conflictCount: Int = 0,
    val errorMessage: String? = null,
    /** Set to true for the archived tab; changes the empty state and actions. */
    val showArchived: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = !isLoading && pinned.isEmpty() && others.isEmpty()

    /**
     * Distinguished from [isEmpty] because the two need completely different
     * empty states: "no notes yet, write one" versus "nothing matched, try
     * different words". Showing the first when someone is searching is useless.
     */
    val isEmptySearch: Boolean get() = isEmpty && query.isNotBlank()

    /**
     * The status banner appears only when there is something the user should
     * know about. A permanent "all synced" bar is chrome that teaches people to
     * ignore that region of the screen.
     */
    val showStatusBanner: Boolean get() = isOffline || failedCount > 0 || conflictCount > 0
}

sealed interface NotesListIntent : UiIntent {
    data class QueryChanged(val value: String) : NotesListIntent
    data class SearchActiveChanged(val active: Boolean) : NotesListIntent
    data class SortOrderChanged(val order: NoteSortOrder) : NotesListIntent
    data object ToggleViewMode : NotesListIntent
    data class NoteClicked(val noteId: String) : NotesListIntent
    data class TogglePin(val noteId: String, val pinned: Boolean) : NotesListIntent
    data class SetArchived(val noteId: String, val archived: Boolean) : NotesListIntent
    data class Delete(val noteId: String) : NotesListIntent
    data class UndoDelete(val noteId: String) : NotesListIntent
    data object CreateNoteClicked : NotesListIntent
    data object DismissError : NotesListIntent
}

sealed interface NotesListEffect : UiEffect {
    data class OpenNote(val noteId: String?) : NotesListEffect

    /**
     * Carries the note id so Undo can restore it. Deleting is soft, so undo is
     * a restore rather than a re-creation - the note keeps its id and history.
     */
    data class ShowUndoDelete(val noteId: String, val message: String) : NotesListEffect

    data class ShowMessage(val message: String) : NotesListEffect
}
