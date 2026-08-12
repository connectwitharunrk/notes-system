package com.arunrk.note.feature.notes.list

import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.repository.PreferencesRepository
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.note.CreateNoteUseCase
import com.arunrk.note.domain.usecase.note.DeleteNoteUseCase
import com.arunrk.note.domain.usecase.note.ObserveNotesUseCase
import com.arunrk.note.domain.usecase.note.ObserveSyncCountsUseCase
import com.arunrk.note.domain.usecase.note.RestoreNoteUseCase
import com.arunrk.note.domain.usecase.note.SetArchivedUseCase
import com.arunrk.note.domain.usecase.note.TogglePinUseCase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class NotesListViewModel(
    private val showArchived: Boolean,
    observeAuthState: ObserveAuthStateUseCase,
    private val observeNotes: ObserveNotesUseCase,
    private val observeSyncCounts: ObserveSyncCountsUseCase,
    private val createNote: CreateNoteUseCase,
    private val togglePin: TogglePinUseCase,
    private val setArchived: SetArchivedUseCase,
    private val deleteNote: DeleteNoteUseCase,
    private val restoreNote: RestoreNoteUseCase,
    private val preferences: PreferencesRepository,
    private val networkMonitor: NetworkMonitor,
) : MviViewModel<NotesListIntent, NotesListState, NotesListEffect>(
    NotesListState(showArchived = showArchived),
) {

    private val userId = observeAuthState()
        .filterIsInstance<AuthState.Authenticated>()
        .map { it.user.id }
        .distinctUntilChanged()

    private val query = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(NoteSortOrder.UPDATED_DESC)

    init {
        observePreferences()
        observeNoteList()
        observeStatus()
    }

    override fun handleIntent(intent: NotesListIntent) {
        when (intent) {
            is NotesListIntent.QueryChanged -> {
                // State updates immediately so typing feels instant; the debounce
                // below is what stops a database query per keystroke.
                setState { copy(query = intent.value) }
                query.value = intent.value
            }

            is NotesListIntent.SearchActiveChanged -> {
                setState { copy(isSearchActive = intent.active, query = if (intent.active) query else "") }
                if (!intent.active) query.value = ""
            }

            is NotesListIntent.SortOrderChanged -> {
                sortOrder.value = intent.order
                viewModelScope.launch { preferences.setSortOrder(intent.order) }
            }

            NotesListIntent.ToggleViewMode -> viewModelScope.launch {
                // Persisted rather than held in UI state: a view mode that resets
                // on every launch is a setting the user has to keep re-applying.
                preferences.setViewMode(currentState.viewMode.toggled())
            }

            is NotesListIntent.NoteClicked -> sendEffect(NotesListEffect.OpenNote(intent.noteId))

            NotesListIntent.CreateNoteClicked -> sendEffect(NotesListEffect.OpenNote(null))

            is NotesListIntent.TogglePin -> runWrite { togglePin(intent.noteId, intent.pinned) }

            is NotesListIntent.SetArchived -> runWrite {
                setArchived(intent.noteId, intent.archived)
            }

            is NotesListIntent.Delete -> viewModelScope.launch {
                when (val result = deleteNote(intent.noteId)) {
                    is Outcome.Success -> sendEffect(
                        // Soft delete makes undo a restore, so the note keeps its
                        // id, its history and its place in the sync protocol.
                        NotesListEffect.ShowUndoDelete(intent.noteId, "Note deleted")
                    )

                    is Outcome.Failure -> setState {
                        copy(errorMessage = result.error.toUserMessage())
                    }
                }
            }

            is NotesListIntent.UndoDelete -> runWrite { restoreNote(intent.noteId) }

            NotesListIntent.DismissError -> setState { copy(errorMessage = null) }
        }
    }

    private fun observePreferences() {
        preferences.preferences
            .onEach { prefs ->
                sortOrder.value = prefs.sortOrder
                setState { copy(sortOrder = prefs.sortOrder, viewMode = prefs.viewMode) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeNoteList() {
        combine(
            userId,
            // Debounced so a burst of keystrokes produces one query, not one per
            // character. 250ms is below the threshold where typing feels laggy.
            query.debounce(250).distinctUntilChanged(),
            sortOrder,
        ) { user, text, sort ->
            Triple(user, text, sort)
        }
            .flatMapLatest { (user, text, sort) ->
                observeNotes(user, NoteFilter(archived = showArchived, query = text, sortOrder = sort))
            }
            .onEach { notes -> setState { withNotes(notes) } }
            .launchIn(viewModelScope)
    }

    private fun observeStatus() {
        userId
            .flatMapLatest { observeSyncCounts(it) }
            .onEach { counts ->
                setState {
                    copy(
                        pendingCount = counts[SyncStatus.PENDING] ?: 0,
                        failedCount = counts[SyncStatus.FAILED] ?: 0,
                        conflictCount = counts[SyncStatus.CONFLICT] ?: 0,
                    )
                }
            }
            .launchIn(viewModelScope)

        networkMonitor.isOnline
            .onEach { online -> setState { copy(isOffline = !online) } }
            .launchIn(viewModelScope)
    }

    private fun runWrite(block: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            val result = block()
            if (result is Outcome.Failure) {
                setState { copy(errorMessage = result.error.toUserMessage()) }
            }
        }
    }

    private fun NotesListState.withNotes(notes: List<Note>) = copy(
        // Pinned notes are split out here rather than in the UI so the rule
        // lives in one place and the screen just renders two sections.
        pinned = notes.filter { it.isPinned },
        others = notes.filterNot { it.isPinned },
        isLoading = false,
    )
}
