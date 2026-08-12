package com.arunrk.note.domain.repository

import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.User
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {

    /** Current session, observable so the navigation graph reacts to sign-out. */
    val authState: StateFlow<AuthState>

    /**
     * Re-establishes the session at startup.
     *
     * Must tolerate being offline: a user with a stored session who opens the
     * app on a plane has to reach their notes. Only a definitive rejection from
     * the server clears the session.
     */
    suspend fun restoreSession(): AuthState

    suspend fun register(name: String, email: String, password: String): Outcome<User>

    suspend fun login(email: String, password: String): Outcome<User>

    /**
     * Clears local credentials first and tells the server afterwards. A user who
     * taps "sign out" while offline must still be signed out on this device.
     */
    suspend fun logout(): Outcome<Unit>

    suspend fun requestPasswordReset(email: String): Outcome<Unit>

    suspend fun changePassword(currentPassword: String, newPassword: String): Outcome<Unit>

    suspend fun refreshProfile(): Outcome<User>

    suspend fun updateProfile(name: String): Outcome<User>
}
