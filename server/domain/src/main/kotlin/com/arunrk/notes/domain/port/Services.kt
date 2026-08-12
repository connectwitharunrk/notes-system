package com.arunrk.notes.domain.port

import java.time.Instant
import java.util.UUID

/**
 * Password hashing. Implemented with BCrypt in :infrastructure:security.
 */
interface PasswordHasher {
    fun hash(rawPassword: String): String

    fun matches(rawPassword: String, hash: String): Boolean

    /**
     * Burns roughly the same CPU as a real [matches] call without needing a
     * user record.
     *
     * Login must take the same time whether or not the email exists - otherwise
     * response timing becomes a user-enumeration oracle. Callers hash a dummy
     * value on the unknown-email path instead of returning early.
     */
    fun matchesDummy(rawPassword: String)
}

data class IssuedAccessToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * Issues and verifies stateless access tokens (JWT).
 *
 * Refresh tokens are deliberately NOT JWTs and are not handled here: a JWT
 * cannot be revoked, which would make "log out" a lie.
 */
interface AccessTokenIssuer {
    fun issue(userId: UUID, email: String, at: Instant): IssuedAccessToken
}

interface EmailSender {
    fun sendPasswordReset(to: String, name: String, resetToken: String, expiresAt: Instant)
}

/**
 * Transaction boundary as a port.
 *
 * Lets a use case span several repository calls atomically without importing
 * `@Transactional` into the domain. The persistence adapter backs this with a
 * Spring TransactionTemplate.
 */
interface Transactor {
    fun <T> inTransaction(block: () -> T): T
}
