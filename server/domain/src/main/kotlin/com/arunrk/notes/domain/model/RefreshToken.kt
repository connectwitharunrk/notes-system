package com.arunrk.notes.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A persisted refresh token. [tokenHash] is a SHA-256 of the opaque value that
 * was handed to the client - the plaintext is never stored, so a database dump
 * does not yield usable sessions.
 *
 * [familyId] links every token in a rotation chain. Presenting a token that has
 * already been rotated away proves it leaked, and the whole family is killed.
 */
data class RefreshToken(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val tokenHash: String,
    val familyId: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val replacedBy: UUID? = null,
    val userAgent: String? = null,
    val ipAddress: String? = null,
    val createdAt: Instant,
) {
    fun isRevoked(): Boolean = revokedAt != null

    fun isExpired(at: Instant): Boolean = !expiresAt.isAfter(at)

    fun isUsable(at: Instant): Boolean = !isRevoked() && !isExpired(at)
}

/**
 * A newly issued access + refresh pair. [refreshToken] is the only place the
 * plaintext refresh value exists; it goes straight to the client.
 */
data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

/** The result of authenticating: tokens plus the user they belong to. */
data class AuthenticatedSession(
    val user: User,
    val tokens: AuthTokens,
)
