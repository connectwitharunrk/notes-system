package com.arunrk.note.feature.notes.editor

import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.domain.model.SyncStatus

data class NoteEditorState(
    val noteId: String? = null,
    val title: String = "",
    val content: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val updatedAt: Long = 0,
    val isLoading: Boolean = true,
    /** True while an autosave write is in flight; drives the "Saving…" label. */
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val conflictOfNoteId: String? = null,
    val errorMessage: String? = null,
) : UiState {

    val isBlank: Boolean get() = title.isBlank() && content.isBlank()

    /**
     * A conflict copy is a note the server created because this note and another
     * device's version had both been rewritten. The editor says so plainly
     * rather than leaving the user to wonder why there are two similar notes.
     */
    val isConflictCopy: Boolean get() = conflictOfNoteId != null
}

sealed interface NoteEditorIntent : UiIntent {
    data class TitleChanged(val value: String) : NoteEditorIntent
    data class ContentChanged(val value: String) : NoteEditorIntent
    data object TogglePin : NoteEditorIntent
    data object ToggleArchive : NoteEditorIntent
    data object Delete : NoteEditorIntent
    data object BackPressed : NoteEditorIntent
    data object DismissError : NoteEditorIntent
}

sealed interface NoteEditorEffect : UiEffect {
    data object NavigateBack : NoteEditorEffect
    data class ShowMessage(val message: String) : NoteEditorEffect
}
