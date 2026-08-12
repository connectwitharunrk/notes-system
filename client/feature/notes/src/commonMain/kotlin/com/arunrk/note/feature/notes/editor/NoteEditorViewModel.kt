package com.arunrk.note.feature.notes.editor

import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.note.CreateNoteUseCase
import com.arunrk.note.domain.usecase.note.DeleteNoteUseCase
import com.arunrk.note.domain.usecase.note.DiscardBlankNoteUseCase
import com.arunrk.note.domain.usecase.note.GetNoteUseCase
import com.arunrk.note.domain.usecase.note.SetArchivedUseCase
import com.arunrk.note.domain.usecase.note.TogglePinUseCase
import com.arunrk.note.domain.usecase.note.UpdateNoteUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTOSAVE_DEBOUNCE_MILLIS = 600L

/**
 * The note editor.
 *
 * There is no save button, deliberately. Everything is written to the local
 * database as you type, debounced, and flushed on the way out - the same
 * guarantee the offline-first design already makes everywhere else. A save
 * button would imply that unsaved work can be lost, which is exactly what this
 * architecture exists to prevent.
 */
class NoteEditorViewModel(
    private val noteId: String?,
    observeAuthState: ObserveAuthStateUseCase,
    private val getNote: GetNoteUseCase,
    private val createNote: CreateNoteUseCase,
    private val updateNote: UpdateNoteUseCase,
    private val togglePin: TogglePinUseCase,
    private val setArchived: SetArchivedUseCase,
    private val deleteNote: DeleteNoteUseCase,
    private val discardBlank: DiscardBlankNoteUseCase,
) : MviViewModel<NoteEditorIntent, NoteEditorState, NoteEditorEffect>(NoteEditorState()) {

    private val authState = observeAuthState()

    private var autosaveJob: Job? = null

    /**
     * True when this editor created the note itself. Used to decide whether an
     * empty note should be discarded on exit - we only ever throw away
     * something the user created in this session and never typed into.
     */
    private var createdInThisSession = false

    init {
        loadNote()
    }

    override fun handleIntent(intent: NoteEditorIntent) {
        when (intent) {
            is NoteEditorIntent.TitleChanged -> {
                setState { copy(title = intent.value, hasUnsavedChanges = true) }
                scheduleAutosave()
            }

            is NoteEditorIntent.ContentChanged -> {
                setState { copy(content = intent.value, hasUnsavedChanges = true) }
                scheduleAutosave()
            }

            NoteEditorIntent.TogglePin -> viewModelScope.launch {
                val id = ensureNoteExists() ?: return@launch
                val next = !currentState.isPinned
                setState { copy(isPinned = next) }
                togglePin(id, next)
            }

            NoteEditorIntent.ToggleArchive -> viewModelScope.launch {
                val id = ensureNoteExists() ?: return@launch
                val next = !currentState.isArchived
                setArchived(id, next)
                setState { copy(isArchived = next) }
                sendEffect(
                    NoteEditorEffect.ShowMessage(if (next) "Note archived" else "Note unarchived")
                )
                sendEffect(NoteEditorEffect.NavigateBack)
            }

            NoteEditorIntent.Delete -> viewModelScope.launch {
                autosaveJob?.cancel()
                val id = currentState.noteId
                if (id != null) deleteNote(id)
                sendEffect(NoteEditorEffect.ShowMessage("Note deleted"))
                sendEffect(NoteEditorEffect.NavigateBack)
            }

            NoteEditorIntent.BackPressed -> viewModelScope.launch {
                finishEditing()
                sendEffect(NoteEditorEffect.NavigateBack)
            }

            NoteEditorIntent.DismissError -> setState { copy(errorMessage = null) }
        }
    }

    private fun loadNote() {
        viewModelScope.launch {
            if (noteId == null) {
                // A brand-new note is not written to the database until the user
                // actually types. Creating it up front would leave an empty note
                // behind every time someone opens the editor and changes their
                // mind.
                setState { copy(isLoading = false) }
                return@launch
            }

            val note = getNote(noteId)
            if (note == null) {
                sendEffect(NoteEditorEffect.ShowMessage("That note no longer exists"))
                sendEffect(NoteEditorEffect.NavigateBack)
                return@launch
            }

            setState {
                copy(
                    noteId = note.id,
                    title = note.title,
                    content = note.content,
                    isPinned = note.isPinned,
                    isArchived = note.isArchived,
                    syncStatus = note.syncStatus,
                    updatedAt = note.updatedAt,
                    conflictOfNoteId = note.conflictOfNoteId,
                    isLoading = false,
                )
            }
        }
    }

    private fun scheduleAutosave() {
        // Restarting the timer on each keystroke means a burst of typing costs
        // one write, not one per character.
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MILLIS)
            save()
        }
    }

    private suspend fun save() {
        val snapshot = currentState
        if (snapshot.isBlank && snapshot.noteId == null) return

        setState { copy(isSaving = true) }

        val id = ensureNoteExists()
        if (id == null) {
            setState { copy(isSaving = false) }
            return
        }

        when (val result = updateNote(id, snapshot.title, snapshot.content)) {
            is Outcome.Success -> setState {
                // Only clear the flag if nothing changed while the write was in
                // flight; otherwise the newer keystrokes would look saved when
                // they are not.
                copy(
                    isSaving = false,
                    hasUnsavedChanges = title != snapshot.title || content != snapshot.content,
                )
            }

            is Outcome.Failure -> setState {
                copy(isSaving = false, errorMessage = result.error.toUserMessage())
            }
        }
    }

    /** Creates the note on first real content, and returns its id. */
    private suspend fun ensureNoteExists(): String? {
        currentState.noteId?.let { return it }

        val userId = (authState.value as? AuthState.Authenticated)?.user?.id ?: return null

        return when (val result = createNote(userId, currentState.title, currentState.content)) {
            is Outcome.Success -> {
                createdInThisSession = true
                setState { copy(noteId = result.value.id, syncStatus = result.value.syncStatus) }
                result.value.id
            }

            is Outcome.Failure -> {
                setState { copy(errorMessage = result.error.toUserMessage()) }
                null
            }
        }
    }

    /**
     * Flushes on the way out.
     *
     * The pending autosave is cancelled and the write performed immediately,
     * because leaving the screen must not race a 600ms timer - that race is
     * precisely how "I typed it and it vanished" bugs happen.
     */
    private suspend fun finishEditing() {
        autosaveJob?.cancel()

        val snapshot = currentState

        if (snapshot.isBlank) {
            // Opened, nothing typed. Discard it rather than leaving an empty
            // note in the list - but only if we created it here and the server
            // has never seen it.
            val id = snapshot.noteId
            if (id != null && createdInThisSession) discardBlank(id)
            return
        }

        save()
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
    }
}
