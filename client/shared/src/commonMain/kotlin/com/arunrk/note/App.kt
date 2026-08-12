package com.arunrk.note

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.core.designsystem.layout.WindowSize
import com.arunrk.note.core.designsystem.theme.AppThemeMode
import com.arunrk.note.core.designsystem.theme.NoteTheme
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.UserPreferences
import com.arunrk.note.domain.repository.PreferencesRepository
import com.arunrk.note.navigation.AuthNavHost
import com.arunrk.note.navigation.MainNavHost
import com.arunrk.note.session.SessionViewModel
import com.arunrk.note.ui.SplashScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Root composable.
 *
 * Window size is measured once here and published through a CompositionLocal, so
 * every screen adapts from one measurement rather than each querying the
 * platform separately.
 *
 * Sign-in and sign-out are expressed as a swap between two entirely separate
 * navigation graphs rather than as navigation with back-stack clearing. The
 * back stack cannot end up half-cleared, and a back press can never return to a
 * login form for an account that is already signed in.
 */
@Composable
fun App() {
    val preferences: PreferencesRepository = koinInject()
    val userPreferences by preferences.preferences.collectAsState(initial = UserPreferences())

    NoteTheme(themeMode = userPreferences.theme.toAppThemeMode()) {
        BoxWithConstraints {
            CompositionLocalProvider(
                LocalWindowSize provides WindowSize.fromWidth(maxWidth),
            ) {
                RootContent()
            }
        }
    }
}

@Composable
private fun RootContent(
    sessionViewModel: SessionViewModel = koinViewModel(),
) {
    val authState by sessionViewModel.authState.collectAsStateWithLifecycle()

    // Crossfade rather than an instant swap: the transition between the splash
    // and the first real screen is otherwise a jarring flash on a fast device.
    Crossfade(targetState = authState, label = "authState") { state ->
        when (state) {
            AuthState.Unknown -> SplashScreen()

            AuthState.Unauthenticated -> AuthNavHost()

            is AuthState.Authenticated -> MainNavHost(
                onSignOut = sessionViewModel::signOut,
            )
        }
    }
}

private fun ThemePreference.toAppThemeMode(): AppThemeMode = when (this) {
    ThemePreference.LIGHT -> AppThemeMode.LIGHT
    ThemePreference.DARK -> AppThemeMode.DARK
    ThemePreference.SYSTEM -> AppThemeMode.SYSTEM
}
