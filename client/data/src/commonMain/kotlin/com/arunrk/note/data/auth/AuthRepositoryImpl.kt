package com.arunrk.note.data.auth

import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.error.map
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.datastore.SecureStorage
import com.arunrk.note.core.network.AuthSessionInvalidator
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.core.network.api.AuthApi
import com.arunrk.note.core.network.dto.AuthResponseDto
import com.arunrk.note.core.network.dto.UserDto
import com.arunrk.note.core.network.parseIsoToEpochMillis
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.User
import com.arunrk.note.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AuthRepository"

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val secureStorage: SecureStorage,
    private val tokenStore: TokenStore,
    private val sessionInvalidator: AuthSessionInvalidator,
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Startup session restore, written to survive being offline.
     *
     * The rule: only a *definitive* rejection clears the session. A network
     * failure must never sign anyone out - this is an offline-first notes app,
     * and a user opening it on a plane has to reach their notes.
     */
    override suspend fun restoreSession(): AuthState {
        val refreshToken = tokenStore.refreshToken()
        if (refreshToken.isNullOrBlank()) {
            return AuthState.Unauthenticated.also { _authState.value = it }
        }

        val cachedUser = readCachedUser()

        // Triggers the Ktor auth plugin's refresh flow if the access token has
        // expired, so this single call both validates and renews the session.
        val state = when (val result = authApi.me()) {
            is Outcome.Success -> {
                cacheUser(result.value)
                AuthState.Authenticated(result.value.toDomain())
            }

            is Outcome.Failure -> when (result.error) {
                // The server has spoken: the refresh token is expired, revoked,
                // or was replayed. Nothing local can rescue this.
                AppError.Unauthenticated -> {
                    Log.i(TAG, "Stored session rejected by the server; signing out")
                    clearLocalSession()
                    AuthState.Unauthenticated
                }

                // Anything else is a transport or server problem. Trust the
                // cached session and let the sync engine catch up later.
                else -> cachedUser
                    ?.let { AuthState.Authenticated(it) }
                    ?: AuthState.Unauthenticated
            }
        }

        _authState.value = state
        return state
    }

    override suspend fun register(name: String, email: String, password: String): Outcome<User> =
        authApi.register(name, email, password).map { it.persist() }

    override suspend fun login(email: String, password: String): Outcome<User> =
        authApi.login(email, password).map { it.persist() }

    /**
     * Local state is cleared first and unconditionally. Telling the server is
     * best effort: if it fails, the refresh token outlives this device but
     * expires on its own, and the alternative - refusing to sign out while
     * offline - would be far worse.
     */
    override suspend fun logout(): Outcome<Unit> {
        val refreshToken = tokenStore.refreshToken()
        clearLocalSession()
        _authState.value = AuthState.Unauthenticated

        return when (val result = authApi.logout(refreshToken)) {
            is Outcome.Success -> result
            is Outcome.Failure -> {
                Log.w(TAG, "Server logout failed; local session already cleared")
                Outcome.Success(Unit)
            }
        }
    }

    override suspend fun requestPasswordReset(email: String): Outcome<Unit> =
        authApi.forgotPassword(email)

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): Outcome<Unit> = authApi.changePassword(currentPassword, newPassword)

    override suspend fun refreshProfile(): Outcome<User> =
        authApi.me().map { dto ->
            cacheUser(dto)
            dto.toDomain().also { _authState.value = AuthState.Authenticated(it) }
        }

    override suspend fun updateProfile(name: String): Outcome<User> =
        authApi.updateProfile(name).map { dto ->
            cacheUser(dto)
            dto.toDomain().also { _authState.value = AuthState.Authenticated(it) }
        }

    // -----------------------------------------------------------------------

    private suspend fun AuthResponseDto.persist(): User {
        tokenStore.updateTokens(
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = parseIsoToEpochMillis(tokens.accessTokenExpiresAt),
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = parseIsoToEpochMillis(tokens.refreshTokenExpiresAt),
        )
        // The Auth plugin caches tokens in memory and never re-reads storage on
        // its own. Without this, every request after signing in would go out
        // with the tokens cached at startup instead of the ones we just stored.
        sessionInvalidator.invalidate()
        cacheUser(user)
        return user.toDomain().also { _authState.value = AuthState.Authenticated(it) }
    }

    /**
     * A copy of the profile kept beside the tokens, so a cold start with no
     * network can still show who is signed in rather than an empty header.
     */
    private suspend fun cacheUser(dto: UserDto) {
        secureStorage.putString(SecureStorage.Keys.USER_ID, dto.id)
        secureStorage.putString(SecureStorage.Keys.USER_EMAIL, dto.email)
        secureStorage.putString(SecureStorage.Keys.USER_NAME, dto.name)
    }

    private suspend fun readCachedUser(): User? {
        val id = secureStorage.getString(SecureStorage.Keys.USER_ID) ?: return null
        return User(
            id = id,
            email = secureStorage.getString(SecureStorage.Keys.USER_EMAIL).orEmpty(),
            name = secureStorage.getString(SecureStorage.Keys.USER_NAME).orEmpty(),
        )
    }

    private suspend fun clearLocalSession() {
        secureStorage.clear()
        // Critical on sign-out: the plugin would otherwise keep the old access
        // token and authenticate the next account's requests as the previous user.
        sessionInvalidator.invalidate()
    }
}

private fun UserDto.toDomain(): User = User(
    id = id,
    email = email,
    name = name,
    emailVerified = emailVerified,
    createdAt = parseIsoToEpochMillis(createdAt),
    updatedAt = parseIsoToEpochMillis(updatedAt),
)
