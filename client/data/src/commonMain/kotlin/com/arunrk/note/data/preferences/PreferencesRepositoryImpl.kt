package com.arunrk.note.data.preferences

import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.NoteSortOption
import com.arunrk.note.core.datastore.NoteViewMode as StoredViewMode
import com.arunrk.note.core.datastore.ThemeMode
import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.UserPreferences
import com.arunrk.note.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Adapts the DataStore-backed preferences to the domain model.
 *
 * The two enum sets look redundant, and that is the point: the stored names are
 * a persistence format that must not change casually, while the domain names are
 * free to evolve. Sharing one enum would make a rename a silent data migration.
 */
class PreferencesRepositoryImpl(
    private val appPreferences: AppPreferences,
) : PreferencesRepository {

    override val preferences: Flow<UserPreferences> = combine(
        appPreferences.themeMode,
        appPreferences.sortOption,
        appPreferences.viewMode,
    ) { theme, sort, view ->
        UserPreferences(
            theme = theme.toDomain(),
            sortOrder = sort.toDomain(),
            viewMode = view.toDomain(),
        )
    }

    override suspend fun setTheme(theme: ThemePreference) {
        appPreferences.setThemeMode(theme.toStored())
    }

    override suspend fun setSortOrder(order: NoteSortOrder) {
        appPreferences.setSortOption(order.toStored())
    }

    override suspend fun setViewMode(mode: NoteViewMode) {
        appPreferences.setViewMode(mode.toStored())
    }
}

private fun ThemeMode.toDomain() = when (this) {
    ThemeMode.LIGHT -> ThemePreference.LIGHT
    ThemeMode.DARK -> ThemePreference.DARK
    ThemeMode.SYSTEM -> ThemePreference.SYSTEM
}

private fun ThemePreference.toStored() = when (this) {
    ThemePreference.LIGHT -> ThemeMode.LIGHT
    ThemePreference.DARK -> ThemeMode.DARK
    ThemePreference.SYSTEM -> ThemeMode.SYSTEM
}

private fun NoteSortOption.toDomain() = when (this) {
    NoteSortOption.UPDATED_DESC -> NoteSortOrder.UPDATED_DESC
    NoteSortOption.CREATED_DESC -> NoteSortOrder.CREATED_DESC
    NoteSortOption.TITLE_ASC -> NoteSortOrder.TITLE_ASC
}

private fun NoteSortOrder.toStored() = when (this) {
    NoteSortOrder.UPDATED_DESC -> NoteSortOption.UPDATED_DESC
    NoteSortOrder.CREATED_DESC -> NoteSortOption.CREATED_DESC
    NoteSortOrder.TITLE_ASC -> NoteSortOption.TITLE_ASC
}

private fun StoredViewMode.toDomain() = when (this) {
    StoredViewMode.LIST -> NoteViewMode.LIST
    StoredViewMode.GRID -> NoteViewMode.GRID
}

private fun NoteViewMode.toStored() = when (this) {
    NoteViewMode.LIST -> StoredViewMode.LIST
    NoteViewMode.GRID -> StoredViewMode.GRID
}
