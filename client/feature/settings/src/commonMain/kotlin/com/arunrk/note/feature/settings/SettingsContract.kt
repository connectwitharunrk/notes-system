package com.arunrk.note.feature.settings

import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.domain.model.SyncState
import com.arunrk.note.domain.model.SyncSummary
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.User

data class SettingsState(
    val user: User? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val sync: SyncSummary = SyncSummary(),
    val isOffline: Boolean = false,
    val isSigningOut: Boolean = false,
    val message: String? = null,
) : UiState {

    val isSyncing: Boolean get() = sync.state.isRunning

    /**
     * Sync Now is pointless while offline or already running, and a button that
     * does nothing when tapped is worse than one that is visibly unavailable.
     */
    val canSyncNow: Boolean get() = !isSyncing && !isOffline

    val syncFailure: String?
        get() = (sync.state as? SyncState.Failed)?.takeUnless { it.isOffline }?.message
}

sealed interface SettingsIntent : UiIntent {
    data class ThemeSelected(val theme: ThemePreference) : SettingsIntent
    data object SyncNowClicked : SettingsIntent
    data object ProfileClicked : SettingsIntent
    data object ChangePasswordClicked : SettingsIntent
    data object SignOutClicked : SettingsIntent
    data object DismissMessage : SettingsIntent
}

sealed interface SettingsEffect : UiEffect {
    data object NavigateToProfile : SettingsEffect
    data object NavigateToChangePassword : SettingsEffect
    data class ShowMessage(val message: String) : SettingsEffect
}
