package com.arunrk.notes.domain.fake

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.PasswordResetToken
import com.arunrk.notes.domain.model.RefreshToken
import com.arunrk.notes.domain.model.User
import com.arunrk.notes.domain.port.AccessTokenIssuer
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.EmailSender
import com.arunrk.notes.domain.port.IssuedAccessToken
import com.arunrk.notes.domain.port.PasswordHasher
import com.arunrk.notes.domain.port.PasswordResetTokenRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory port implementations.
 *
 * The domain depends only on interfaces, so its entire behaviour - including
 * every security-critical branch - is testable without a database, a container
 * or a Spring context.
 */

class InMemoryUserRepository : UserRepository {
    val rows = ConcurrentHashMap<UUID, User>()

    override fun findById(id: UUID): User? = rows[id]

    override fun findActiveByEmail(email: String): User? =
        rows.values.firstOrNull { it.email.equals(email, ignoreCase = true) && it.deletedAt == null }

    override fun existsActiveByEmail(email: String): Boolean = findActiveByEmail(email) != null

    override fun save(user: User): User {
        if (rows.values.any {
                it.id != user.id && it.email.equals(user.email, ignoreCase = true) && it.deletedAt == null
            }
        ) {
            // Mirrors the unique-index violation the real adapter translates.
            throw AppException(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists")
        }
        rows[user.id] = user
        return user
    }

    override fun updatePasswordHash(userId: UUID, passwordHash: String, updatedAt: Instant) {
        rows[userId]?.let { rows[userId] = it.copy(passwordHash = passwordHash, updatedAt = updatedAt) }
    }

    override fun updateProfile(userId: UUID, name: String, updatedAt: Instant): User {
        val existing = rows[userId] ?: throw AppException(ErrorCode.USER_NOT_FOUND, "User not found")
        val updated = existing.copy(name = name, updatedAt = updatedAt)
        rows[userId] = updated
        return updated
    }
}

class InMemoryDeviceRepository : DeviceRepository {
    val rows = ConcurrentHashMap<UUID, Device>()

    override fun findById(id: UUID): Device? = rows[id]

    override fun upsert(device: Device): Device {
        val existing = rows[device.id]
        val merged = existing?.copy(
            platform = device.platform,
            lastSeenAt = device.lastSeenAt,
        ) ?: device
        rows[device.id] = merged
        return merged
    }

    override fun findAllByUser(userId: UUID): List<Device> = rows.values.filter { it.userId == userId }
}

class InMemoryRefreshTokenRepository : RefreshTokenRepository {
    val rows = ConcurrentHashMap<UUID, RefreshToken>()

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun save(token: RefreshToken): RefreshToken {
        rows[token.id] = token
        return token
    }

    override fun revoke(id: UUID, at: Instant, replacedBy: UUID?) {
        rows[id]?.takeIf { it.revokedAt == null }?.let {
            rows[id] = it.copy(revokedAt = at, replacedBy = replacedBy)
        }
    }

    override fun revokeFamily(familyId: UUID, at: Instant) {
        rows.values.filter { it.familyId == familyId && it.revokedAt == null }
            .forEach { rows[it.id] = it.copy(revokedAt = at) }
    }

    override fun revokeAllForUser(userId: UUID, at: Instant) {
        rows.values.filter { it.userId == userId && it.revokedAt == null }
            .forEach { rows[it.id] = it.copy(revokedAt = at) }
    }

    override fun revokeAllForUserExcept(userId: UUID, keepTokenId: UUID, at: Instant) {
        rows.values.filter { it.userId == userId && it.id != keepTokenId && it.revokedAt == null }
            .forEach { rows[it.id] = it.copy(revokedAt = at) }
    }

    override fun deleteExpiredBefore(cutoff: Instant): Int {
        val doomed = rows.values.filter { it.expiresAt.isBefore(cutoff) }
        doomed.forEach { rows.remove(it.id) }
        return doomed.size
    }

    fun activeCountFor(userId: UUID): Int =
        rows.values.count { it.userId == userId && it.revokedAt == null }
}

class InMemoryPasswordResetTokenRepository : PasswordResetTokenRepository {
    val rows = ConcurrentHashMap<UUID, PasswordResetToken>()

    override fun findByTokenHash(tokenHash: String): PasswordResetToken? =
        rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun save(token: PasswordResetToken): PasswordResetToken {
        rows[token.id] = token
        return token
    }

    override fun markUsed(id: UUID, at: Instant) {
        rows[id]?.takeIf { it.usedAt == null }?.let { rows[id] = it.copy(usedAt = at) }
    }

    override fun invalidateAllForUser(userId: UUID, at: Instant) {
        rows.values.filter { it.userId == userId && it.usedAt == null }
            .forEach { rows[it.id] = it.copy(usedAt = at) }
    }

    override fun deleteExpiredBefore(cutoff: Instant): Int {
        val doomed = rows.values.filter { it.expiresAt.isBefore(cutoff) }
        doomed.forEach { rows.remove(it.id) }
        return doomed.size
    }
}

/**
 * Reversible stand-in for BCrypt. [dummyCalls] is what proves the login path
 * equalises timing on the unknown-email branch instead of returning early.
 */
class FakePasswordHasher : PasswordHasher {
    var dummyCalls = 0
        private set

    override fun hash(rawPassword: String): String = "hashed:$rawPassword"

    override fun matches(rawPassword: String, hash: String): Boolean = hash == "hashed:$rawPassword"

    override fun matchesDummy(rawPassword: String) {
        dummyCalls++
    }
}

class FakeAccessTokenIssuer : AccessTokenIssuer {
    private var counter = 0

    override fun issue(userId: UUID, email: String, at: Instant): IssuedAccessToken {
        counter++
        return IssuedAccessToken(
            token = "access-$userId-$counter",
            expiresAt = at.plus(Duration.ofMinutes(15)),
        )
    }
}

class RecordingEmailSender : EmailSender {
    data class Sent(val to: String, val name: String, val token: String, val expiresAt: Instant)

    val sent = mutableListOf<Sent>()

    override fun sendPasswordReset(to: String, name: String, resetToken: String, expiresAt: Instant) {
        sent += Sent(to, name, resetToken, expiresAt)
    }
}

/**
 * Runs the block inline with no rollback semantics.
 *
 * Prefer [RollingBackTransactor] for anything that throws inside a transaction -
 * this one will happily let a use case "commit" writes that a real database
 * would discard.
 */
class DirectTransactor : Transactor {
    override fun <T> inTransaction(block: () -> T): T = block()
}

/**
 * Simulates real transaction semantics: writes made inside the block are undone
 * if it throws.
 *
 * This exists because a forgiving fake hid a genuine security bug. Reuse
 * detection revoked the leaked token family and then threw to report the
 * breach - and the throw rolled the revocation back, so the API announced a
 * compromise while leaving every token in that family usable. The unit test
 * passed because [DirectTransactor] never rolled anything back.
 *
 * A test double that is more permissive than production is not a simplification,
 * it is a blind spot.
 */
class RollingBackTransactor(
    private val stores: List<MutableMap<out Any, out Any>>,
) : Transactor {

    override fun <T> inTransaction(block: () -> T): T {
        val snapshots = stores.map { LinkedHashMap(it) }
        try {
            return block()
        } catch (e: Throwable) {
            stores.forEachIndexed { index, store ->
                @Suppress("UNCHECKED_CAST")
                val target = store as MutableMap<Any, Any>
                target.clear()
                @Suppress("UNCHECKED_CAST")
                target.putAll(snapshots[index] as Map<Any, Any>)
            }
            throw e
        }
    }
}
