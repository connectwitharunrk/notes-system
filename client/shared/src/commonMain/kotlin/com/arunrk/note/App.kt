package com.arunrk.note

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.ThemeMode
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.core.designsystem.layout.WindowSize
import com.arunrk.note.core.designsystem.theme.AppThemeMode
import com.arunrk.note.core.designsystem.theme.NoteTheme
import com.arunrk.note.ui.CoreStatusScreen
import org.koin.compose.koinInject

/**
 * Root composable.
 *
 * Window size is measured here with [BoxWithConstraints] and published through a
 * CompositionLocal, so every screen adapts from one measurement rather than each
 * querying the platform separately. Using the measured width also means a
 * list-detail pane can re-derive its own size class for its slice of the window.
 */
@Composable
fun App() {
    val preferences: AppPreferences = koinInject()
    val themeMode by preferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    NoteTheme(themeMode = themeMode.toAppThemeMode()) {
        BoxWithConstraints {
            CompositionLocalProvider(
                LocalWindowSize provides WindowSize.fromWidth(maxWidth),
            ) {
                CoreStatusScreen()
            }
        }
    }
}

private fun ThemeMode.toAppThemeMode(): AppThemeMode = when (this) {
    ThemeMode.LIGHT -> AppThemeMode.LIGHT
    ThemeMode.DARK -> AppThemeMode.DARK
    ThemeMode.SYSTEM -> AppThemeMode.SYSTEM
}
