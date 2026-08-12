package com.arunrk.note.data.auth

import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.SecureStorage
import com.arunrk.note.core.network.TokenStore

/**
 * Backs the network layer's [TokenStore] with platform-secure storage.
 *
 * This is where the two halves meet: :core:network declares what it needs to
 * authenticate a request, :core:datastore knows how to keep a secret on each
 * platform, and neither has to know about the other.
 */
class SecureTokenStore(
    private val secureStorage: SecureStorage,
    private val preferences: AppPreferences,
) : TokenStore {

    override suspend fun accessToken(): String? =
        secureStorage.getString(SecureStorage.Keys.ACCESS_TOKEN)

    override suspend fun refreshToken(): String? =
        secureStorage.getString(SecureStorage.Keys.REFRESH_TOKEN)

    override suspend fun updateTokens(
        accessToken: String,
        accessTokenExpiresAt: Long,
        refreshToken: String,
        refreshTokenExpiresAt: Long,
    ) {
        secureStorage.putString(SecureStorage.Keys.ACCESS_TOKEN, accessToken)
        secureStorage.putString(SecureStorage.Keys.ACCESS_TOKEN_EXPIRES_AT, accessTokenExpiresAt.toString())
        // Written last: if the process dies mid-write, a stale-but-valid refresh
        // token is recoverable, whereas a new refresh token paired with a
        // half-written access token is not.
        secureStorage.putString(SecureStorage.Keys.REFRESH_TOKEN, refreshToken)
    }

    override suspend fun clearSession() {
        secureStorage.clear()
    }

    /**
     * Survives sign-out on purpose - it identifies the installation, not the
     * user. Regenerating it would fill the account's session list with ghost
     * devices and break conflict attribution.
     */
    override suspend fun deviceId(): String = preferences.deviceId()
}
