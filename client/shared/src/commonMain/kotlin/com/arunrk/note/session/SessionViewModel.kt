package com.arunrk.note.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncSummary
import com.arunrk.note.domain.repository.SyncManager
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.auth.RestoreSessionUseCase
import com.arunrk.note.domain.usecase.auth.SignOutUseCase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns the app-wide session and the lifetime of background synchronisation.
 *
 * Session restore runs once in `init` rather than from a composable: a
 * LaunchedEffect would re-run after process death and restoration, firing a
 * second restore against a session already being refreshed.
 */
class SessionViewModel(
    observeAuthState: ObserveAuthStateUseCase,
    private val restoreSession: RestoreSessionUseCase,
    private val signOut: SignOutUseCase,
    private val syncManager: SyncManager,
) : ViewModel() {

    val authState: StateFlow<AuthState> = observeAuthState()

    val syncSummary: StateFlow<SyncSummary> = syncManager.summary

    init {
        viewModelScope.launch { restoreSession() }

        // Sync starts when a session appears and stops when it goes, rather than
        // being started from a screen. The engine must keep running while the
        // user is on any screen, and must not survive sign-out.
        authState
            .map { it is AuthState.Authenticated }
            .distinctUntilChanged()
            .onEach { authenticated ->
                if (authenticated) {
                    syncManager.start()
                    syncManager.requestSync(SyncReason.SIGN_IN)
                }
            }
            .launchIn(viewModelScope)
    }

    fun signOutClicked() {
        viewModelScope.launch { signOut() }
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.syncNow(SyncReason.MANUAL) }
    }
}
