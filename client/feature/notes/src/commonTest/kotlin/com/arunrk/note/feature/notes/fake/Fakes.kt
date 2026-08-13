package com.arunrk.note.feature.notes.fake

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.platform.currentTimeMillis
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteFilter
import com.arunrk.note.domain.model.NoteSortOrder
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.User
import com.arunrk.note.domain.model.UserPreferences
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.repository.NoteRepository
import com.arunrk.note.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

const val TEST_USER_ID = "test-user"

/**
 * In-memory note storage.
 *
 * Backed by a StateFlow so observers see writes immediately, mirroring how the
 * real repository behaves over SQLDelight - a fake that only returns snapshots
 * would hide every reactivity bug.
 */
class FakeNoteRepository : NoteRepository {

    private val notes = MutableStateFlow<Map<String, Note>>(emptyMap())

    /** Set to make the next write fail, for testing the error paths. */
    var failNextWrite: com.arunrk.note.core.common.error.AppError? = null

    var createCount = 0
        private set

    fun seed(vararg note: Note) {
        notes.value = notes.value + note.associateBy { it.id }
    }

    fun current(noteId: String): Note? = notes.value[noteId]

    fun all(): List<Note> = notes.value.values.toList()

    override fun observeNotes(userId: String, filter: NoteFilter): Flow<List<Note>> =
        notes.map { map ->
            map.values
                .filter { it.userId == userId && !it.isDeleted && it.isArchived == filter.archived }
                .filter {
                    filter.query.isBlank() ||
                        it.title.contains(filter.query, ignoreCase = true) ||
                        it.content.contains(filter.query, ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<Note> { it.isPinned }
                        .then(
                            when (filter.sortOrder) {
                                NoteSortOrder.UPDATED_DESC -> compareByDescending { it.updatedAt }
                                NoteSortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
                                NoteSortOrder.TITLE_ASC -> compareBy { it.displayTitle.lowercase() }
                            }
                        )
                        .thenBy { it.id }
                )
        }

    override fun observeNote(noteId: String): Flow<Note?> = notes.map { it[noteId] }

    override fun observeSyncCounts(userId: String): Flow<Map<SyncStatus, Int>> =
        notes.map { map ->
            map.values.filter { it.userId == userId }
                .groupingBy { it.syncStatus }
                .eachCount()
        }

    override suspend fun getNote(noteId: String): Note? = notes.value[noteId]

    override suspend fun create(userId: String, title: String, content: String): Outcome<Note> =
        guarded {
            createCount++
            val now = currentTimeMillis()
            val note = Note(
                id = "note-$createCount",
                userId = userId,
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
            )
            notes.value = notes.value + (note.id to note)
            note
        }

    override suspend fun updateContent(
        noteId: String,
        title: String,
        content: String,
    ): Outcome<Unit> = guarded {
        mutate(noteId) {
            it.copy(
                title = title,
                content = content,
                updatedAt = currentTimeMillis(),
                syncStatus = SyncStatus.PENDING,
            )
        }
    }

    override suspend fun setPinned(noteId: String, pinned: Boolean): Outcome<Unit> = guarded {
        mutate(noteId) { it.copy(isPinned = pinned, syncStatus = SyncStatus.PENDING) }
    }

    override suspend fun setArchived(noteId: String, archived: Boolean): Outcome<Unit> = guarded {
        mutate(noteId) { it.copy(isArchived = archived, syncStatus = SyncStatus.PENDING) }
    }

    override suspend fun delete(noteId: String): Outcome<Unit> = guarded {
        mutate(noteId) {
            it.copy(
                isDeleted = true,
                deletedAt = currentTimeMillis(),
                syncStatus = SyncStatus.PENDING,
            )
        }
    }

    override suspend fun restore(noteId: String): Outcome<Unit> = guarded {
        mutate(noteId) { it.copy(isDeleted = false, deletedAt = null) }
    }

    override suspend fun discardBlank(noteId: String): Outcome<Unit> = guarded {
        notes.value = notes.value - noteId
    }

    override suspend fun clearAllForUser(userId: String): Outcome<Unit> = guarded {
        notes.value = notes.value.filterValues { it.userId != userId }
    }

    private inline fun <T> guarded(block: () -> T): Outcome<T> {
        failNextWrite?.let {
            failNextWrite = null
            return Outcome.Failure(it)
        }
        return Outcome.Success(block())
    }

    private fun mutate(noteId: String, transform: (Note) -> Note) {
        val existing = notes.value[noteId] ?: return
        notes.value = notes.value + (noteId to transform(existing))
    }
}

class FakeAuthRepository(
    user: User = User(id = TEST_USER_ID, email = "test@example.com", name = "Test User"),
) : AuthRepository {

    override val authState: StateFlow<AuthState> =
        MutableStateFlow(AuthState.Authenticated(user))

    override suspend fun restoreSession(): AuthState = authState.value
    override suspend fun register(name: String, email: String, password: String) =
        Outcome.Failure(com.arunrk.note.core.common.error.AppError.Offline)

    override suspend fun login(email: String, password: String) =
        Outcome.Failure(com.arunrk.note.core.common.error.AppError.Offline)

    override suspend fun logout(): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun requestPasswordReset(email: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        Outcome.Success(Unit)

    override suspend fun refreshProfile() =
        Outcome.Failure(com.arunrk.note.core.common.error.AppError.Offline)

    override suspend fun updateProfile(name: String) =
        Outcome.Failure(com.arunrk.note.core.common.error.AppError.Offline)
}

class FakePreferencesRepository : PreferencesRepository {

    private val state = MutableStateFlow(UserPreferences())
    override val preferences: Flow<UserPreferences> = state

    override suspend fun setTheme(theme: ThemePreference) {
        state.value = state.value.copy(theme = theme)
    }

    override suspend fun setSortOrder(order: NoteSortOrder) {
        state.value = state.value.copy(sortOrder = order)
    }

    override suspend fun setViewMode(mode: NoteViewMode) {
        state.value = state.value.copy(viewMode = mode)
    }
}
