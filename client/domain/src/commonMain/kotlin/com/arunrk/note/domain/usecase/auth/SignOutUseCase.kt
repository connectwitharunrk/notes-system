package com.arunrk.note.domain.usecase.auth

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.repository.NoteRepository
import com.arunrk.note.domain.repository.SyncManager

/**
 * Signs the user out and removes their notes from this device.
 *
 * The order matters, and the first step is the one that is easy to leave out:
 * a final push is attempted before anything local is deleted. Wiping first
 * would destroy any note the user wrote since the last sync - and the moment
 * someone signs out is exactly when they stop being able to get it back.
 *
 * That push is best effort. If it fails, sign-out still proceeds, because
 * refusing to sign someone out because the network is down is worse than the
 * alternative - and this is a decision the user has already made.
 */
class SignOutUseCase(
    private val authRepository: AuthRepository,
    private val noteRepository: NoteRepository,
    private val syncManager: SyncManager,
) {

    suspend operator fun invoke(): Outcome<Unit> {
        val userId = (authRepository.authState.value as? AuthState.Authenticated)?.user?.id

        if (userId != null) {
            runCatching { syncManager.syncNow(SyncReason.MANUAL) }
        }

        syncManager.stop()

        // Local notes go before the session does. Leaving them behind would show
        // one account's notes to whoever signs in next on this device.
        if (userId != null) {
            noteRepository.clearAllForUser(userId)
        }

        return authRepository.logout()
    }
}
