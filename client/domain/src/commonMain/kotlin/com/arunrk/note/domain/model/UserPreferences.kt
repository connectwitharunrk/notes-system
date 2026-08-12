package com.arunrk.note.domain.model

enum class ThemePreference {
    LIGHT,
    DARK,
    SYSTEM,
}

enum class NoteViewMode {
    LIST,
    GRID;

    fun toggled(): NoteViewMode = if (this == GRID) LIST else GRID
}

/**
 * User-visible display settings.
 *
 * Modelled in the domain rather than read straight from DataStore by the UI, so
 * feature modules never depend on :core:datastore. A feature that imports a
 * storage library is a feature that cannot be tested without one.
 */
data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val sortOrder: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
    val viewMode: NoteViewMode = NoteViewMode.GRID,
)
