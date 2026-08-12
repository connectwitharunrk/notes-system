package com.arunrk.notes.domain.port

import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.PasswordResetToken
import com.arunrk.notes.domain.model.RefreshToken
import com.arunrk.notes.domain.model.User
import java.time.Instant
import java.util.UUID

/**
 * Outbound ports. The domain declares what it needs; :infrastructure supplies
 * how. Nothing here mentions JPA, Spring or SQL.
 */

interface UserRepository {
    fun findById(id: UUID): User?

    /** Matches on the normalised email and ignores soft-deleted accounts. */
    fun findActiveByEmail(email: String): User?

    fun existsActiveByEmail(email: String): Boolean

    fun save(user: User): User

    fun updatePasswordHash(userId: UUID, passwordHash: String, updatedAt: Instant)

    fun updateProfile(userId: UUID, name: String, updatedAt: Instant): User
}

interface DeviceRepository {
    fun findById(id: UUID): Device?

    /**
     * Registers the device if unknown, otherwise refreshes its last-seen
     * metadata. Called on every login, so it must be idempotent.
     */
    fun upsert(device: Device): Device

    fun findAllByUser(userId: UUID): List<Device>
}

interface RefreshTokenRepository {
    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun save(token: RefreshToken): RefreshToken

    fun revoke(id: UUID, at: Instant, replacedBy: UUID? = null)

    /**
     * Kills an entire rotation chain. Called when a rotated-away token is
     * replayed, which means it leaked.
     */
    fun revokeFamily(familyId: UUID, at: Instant)

    fun revokeAllForUser(userId: UUID, at: Instant)

    /** Used by "log out everywhere except here" on password change. */
    fun revokeAllForUserExcept(userId: UUID, keepTokenId: UUID, at: Instant)

    fun deleteExpiredBefore(cutoff: Instant): Int
}

interface PasswordResetTokenRepository {
    fun findByTokenHash(tokenHash: String): PasswordResetToken?

    fun save(token: PasswordResetToken): PasswordResetToken

    fun markUsed(id: UUID, at: Instant)

    /** Issuing a new reset invalidates any outstanding ones for that user. */
    fun invalidateAllForUser(userId: UUID, at: Instant)

    fun deleteExpiredBefore(cutoff: Instant): Int
}
