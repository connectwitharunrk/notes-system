package com.arunrk.note.domain.repository

import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {

    val preferences: Flow<UserPreferences>

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setSortOrder(order: NoteSortOrder)

    suspend fun setViewMode(mode: NoteViewMode)
}
