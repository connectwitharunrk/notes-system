package com.arunrk.note.feature.settings

import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.repository.PreferencesRepository
import com.arunrk.note.domain.repository.SyncManager
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.auth.SignOutUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SettingsViewModel(
    observeAuthState: ObserveAuthStateUseCase,
    private val preferences: PreferencesRepository,
    private val syncManager: SyncManager,
    private val signOut: SignOutUseCase,
    private val networkMonitor: NetworkMonitor,
) : MviViewModel<SettingsIntent, SettingsState, SettingsEffect>(SettingsState()) {

    init {
        observeAuthState()
            .onEach { state -> setState { copy(user = state.userOrNull) } }
            .launchIn(viewModelScope)

        preferences.preferences
            .onEach { prefs -> setState { copy(theme = prefs.theme) } }
            .launchIn(viewModelScope)

        syncManager.summary
            .onEach { summary -> setState { copy(sync = summary) } }
            .launchIn(viewModelScope)

        networkMonitor.isOnline
            .onEach { online -> setState { copy(isOffline = !online) } }
            .launchIn(viewModelScope)
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ThemeSelected -> viewModelScope.launch {
                // Applied through the repository rather than held in UI state, so
                // the whole app re-themes from one source and the choice survives
                // a restart.
                preferences.setTheme(intent.theme)
            }

            SettingsIntent.SyncNowClicked -> viewModelScope.launch {
                val result = syncManager.syncNow(SyncReason.MANUAL)
                val message = when {
                    !result.isSuccess -> "Couldn't sync. Your notes are safe on this device."
                    result.pushed == 0 && result.pulled == 0 -> "Everything is already up to date"
                    else -> buildString {
                        append("Synced")
                        if (result.pushed > 0) append(" · sent ${result.pushed}")
                        if (result.pulled > 0) append(" · received ${result.pulled}")
                        if (result.conflicts > 0) append(" · ${result.conflicts} kept as copies")
                    }
                }
                sendEffect(SettingsEffect.ShowMessage(message))
            }

            SettingsIntent.ProfileClicked -> sendEffect(SettingsEffect.NavigateToProfile)

            SettingsIntent.ChangePasswordClicked ->
                sendEffect(SettingsEffect.NavigateToChangePassword)

            SettingsIntent.SignOutClicked -> viewModelScope.launch {
                setState { copy(isSigningOut = true) }
                // SignOutUseCase pushes pending work before clearing anything
                // local, so this can take a moment - hence the busy state.
                signOut()
                setState { copy(isSigningOut = false) }
            }

            SettingsIntent.DismissMessage -> setState { copy(message = null) }
        }
    }
}
