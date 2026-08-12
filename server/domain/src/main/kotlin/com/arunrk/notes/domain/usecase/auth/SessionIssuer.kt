package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.hash.Hashing
import com.arunrk.notes.common.id.SecureTokens
import com.arunrk.notes.common.id.UuidV7
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.AuthTokens
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.model.RefreshToken
import com.arunrk.notes.domain.model.User
import com.arunrk.notes.domain.policy.AuthPolicy
import com.arunrk.notes.domain.port.AccessTokenIssuer
import com.arunrk.notes.domain.port.RefreshTokenRepository
import java.util.UUID

/**
 * Context describing where a session is being created from. Purely descriptive -
 * none of it is trusted for authorisation.
 */
data class SessionContext(
    val deviceId: UUID?,
    val platform: DevicePlatform,
    val userAgent: String? = null,
    val ipAddress: String? = null,
)

/**
 * Creates and rotates token pairs.
 *
 * Shared by register, login and refresh so that all three produce identically
 * shaped sessions - a divergence here is how "logout doesn't work on one code
 * path" bugs happen.
 */
class SessionIssuer(
    private val accessTokenIssuer: AccessTokenIssuer,
    private val refreshTokens: RefreshTokenRepository,
    private val time: TimeProvider,
    private val policy: AuthPolicy,
) {

    /** Starts a brand-new rotation family: register, login, or password reset. */
    fun issueNewSession(user: User, context: SessionContext): AuthTokens =
        issue(user, context, familyId = UUID.randomUUID(), rotatedFrom = null)

    /**
     * Rotates an existing token. The old one is revoked and linked to its
     * successor, so a later replay is detectable as reuse rather than merely
     * "unknown".
     */
    fun rotate(user: User, previous: RefreshToken, context: SessionContext): AuthTokens {
        val tokens = issue(
            user = user,
            context = context.copy(deviceId = context.deviceId ?: previous.deviceId),
            familyId = previous.familyId,
            rotatedFrom = previous,
        )
        return tokens
    }

    private fun issue(
        user: User,
        context: SessionContext,
        familyId: UUID,
        rotatedFrom: RefreshToken?,
    ): AuthTokens {
        val now = time.now()
        val access = accessTokenIssuer.issue(user.id, user.email, now)

        val rawRefresh = SecureTokens.generate()
        val refreshExpiresAt = now.plus(policy.refreshTtlFor(context.platform))

        val saved = refreshTokens.save(
            RefreshToken(
                id = UuidV7.generate(now.toEpochMilli()),
                userId = user.id,
                deviceId = context.deviceId,
                // Only the hash is persisted; the plaintext below is the sole copy
                // and goes straight back to the client.
                tokenHash = Hashing.sha256Hex(rawRefresh),
                familyId = familyId,
                expiresAt = refreshExpiresAt,
                userAgent = context.userAgent,
                ipAddress = context.ipAddress,
                createdAt = now,
            )
        )

        if (rotatedFrom != null) {
            refreshTokens.revoke(rotatedFrom.id, now, replacedBy = saved.id)
        }

        return AuthTokens(
            accessToken = access.token,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = rawRefresh,
            refreshTokenExpiresAt = refreshExpiresAt,
        )
    }
}
