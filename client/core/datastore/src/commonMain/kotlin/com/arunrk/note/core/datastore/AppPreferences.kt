package com.arunrk.note.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arunrk.note.core.common.id.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

enum class NoteSortOption {
    UPDATED_DESC,
    CREATED_DESC,
    TITLE_ASC;

    companion object {
        fun parse(raw: String?): NoteSortOption =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UPDATED_DESC
    }
}

enum class NoteViewMode {
    LIST,
    GRID;

    companion object {
        fun parse(raw: String?): NoteViewMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GRID
    }
}

/**
 * Non-sensitive user preferences.
 *
 * Credentials never come here - see [SecureStorage]. The device id does live
 * here rather than in the database, because it identifies the installation
 * rather than any user and must survive signing out.
 */
class AppPreferences(
    private val dataStore: DataStore<Preferences>,
) {

    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.parse(it[KEY_THEME_MODE]) }

    val sortOption: Flow<NoteSortOption> =
        dataStore.data.map { NoteSortOption.parse(it[KEY_SORT_OPTION]) }

    val viewMode: Flow<NoteViewMode> =
        dataStore.data.map { NoteViewMode.parse(it[KEY_VIEW_MODE]) }

    val syncOnlyOnUnmetered: Flow<Boolean> =
        dataStore.data.map { it[KEY_SYNC_UNMETERED_ONLY] ?: false }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setSortOption(option: NoteSortOption) {
        dataStore.edit { it[KEY_SORT_OPTION] = option.name }
    }

    suspend fun setViewMode(mode: NoteViewMode) {
        dataStore.edit { it[KEY_VIEW_MODE] = mode.name }
    }

    suspend fun setSyncOnlyOnUnmetered(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_UNMETERED_ONLY] = enabled }
    }

    /**
     * Stable per installation, generated on first use.
     *
     * Used for conflict attribution and per-device session management, so it
     * must not change between launches or after a sign-out - a rotating id would
     * fill the user's session list with ghosts.
     */
    suspend fun deviceId(): String {
        dataStore.data.first()[KEY_DEVICE_ID]?.let { return it }

        val generated = UuidV7.generate()
        // Only writes if still absent, so two concurrent callers agree.
        var winner = generated
        dataStore.edit { prefs ->
            val existing = prefs[KEY_DEVICE_ID]
            if (existing != null) {
                winner = existing
            } else {
                prefs[KEY_DEVICE_ID] = generated
            }
        }
        return winner
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_SYNC_UNMETERED_ONLY = booleanPreferencesKey("sync_unmetered_only")
    }
}
