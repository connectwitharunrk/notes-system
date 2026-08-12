package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.hash.Hashing
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.port.RefreshTokenRepository
import java.util.UUID

/**
 * Revokes the calling device's session.
 *
 * Idempotent by design: an unknown or already-revoked token succeeds silently.
 * Logging out is something a client may retry after a network failure, and it
 * must never be able to fail in a way that leaves the user stuck signed in.
 */
class LogoutUseCase(
    private val refreshTokens: RefreshTokenRepository,
    private val time: TimeProvider,
) {
    fun execute(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) return
        val stored = refreshTokens.findByTokenHash(Hashing.sha256Hex(refreshToken)) ?: return
        if (stored.isRevoked()) return
        refreshTokens.revoke(stored.id, time.now())
    }
}

/** Signs the user out of every device. */
class LogoutAllUseCase(
    private val refreshTokens: RefreshTokenRepository,
    private val time: TimeProvider,
) {
    fun execute(userId: UUID) {
        refreshTokens.revokeAllForUser(userId, time.now())
    }
}
