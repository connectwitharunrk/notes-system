package com.arunrk.note.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.usecase.auth.LogoutUseCase
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.auth.RestoreSessionUseCase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the app-wide session.
 *
 * Session restore runs once in `init` rather than from a composable: a
 * LaunchedEffect would re-run on process death and restoration, firing a second
 * restore against a session that is already being refreshed.
 */
class SessionViewModel(
    observeAuthState: ObserveAuthStateUseCase,
    private val restoreSession: RestoreSessionUseCase,
    private val logout: LogoutUseCase,
) : ViewModel() {

    val authState: StateFlow<AuthState> = observeAuthState()

    init {
        viewModelScope.launch { restoreSession() }
    }

    fun signOut() {
        viewModelScope.launch { logout() }
    }
}
