package com.arunrk.note.core.network

/**
 * The network layer's view of the session.
 *
 * Declared here rather than depending on :core:datastore so that :core:network
 * stays unaware of how credentials are persisted - the data layer supplies an
 * implementation backed by SecureStorage.
 */
interface TokenStore {

    suspend fun accessToken(): String?

    suspend fun refreshToken(): String?

    suspend fun updateTokens(
        accessToken: String,
        accessTokenExpiresAt: Long,
        refreshToken: String,
        refreshTokenExpiresAt: Long,
    )

    /** Called when the session is unrecoverable; the user must sign in again. */
    suspend fun clearSession()

    /** Stable installation identifier, sent as `X-Device-Id`. */
    suspend fun deviceId(): String
}
