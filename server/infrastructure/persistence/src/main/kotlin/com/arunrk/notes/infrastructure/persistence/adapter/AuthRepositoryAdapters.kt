package com.arunrk.notes.infrastructure.persistence.adapter

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.PasswordResetToken
import com.arunrk.notes.domain.model.RefreshToken
import com.arunrk.notes.domain.model.User
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.PasswordResetTokenRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import com.arunrk.notes.domain.port.UserRepository
import com.arunrk.notes.infrastructure.persistence.entity.DeviceEntity
import com.arunrk.notes.infrastructure.persistence.entity.PasswordResetTokenEntity
import com.arunrk.notes.infrastructure.persistence.entity.RefreshTokenEntity
import com.arunrk.notes.infrastructure.persistence.entity.UserEntity
import com.arunrk.notes.infrastructure.persistence.jpa.DeviceJpaRepository
import com.arunrk.notes.infrastructure.persistence.jpa.PasswordResetTokenJpaRepository
import com.arunrk.notes.infrastructure.persistence.jpa.RefreshTokenJpaRepository
import com.arunrk.notes.infrastructure.persistence.jpa.UserJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class UserRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun findById(id: UUID): User? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findActiveByEmail(email: String): User? =
        jpa.findActiveByEmail(email)?.toDomain()

    override fun existsActiveByEmail(email: String): Boolean = jpa.existsActiveByEmail(email)

    override fun save(user: User): User = try {
        jpa.save(UserEntity.fromDomain(user)).toDomain()
    } catch (e: DataIntegrityViolationException) {
        // The partial unique index on lower(email) is the real guard against two
        // concurrent registrations for the same address; the use case's
        // existence check can always be raced. Translate it into the same error
        // the caller would have got from that check.
        throw AppException(
            ErrorCode.EMAIL_ALREADY_EXISTS,
            "An account with this email already exists",
            cause = e,
        )
    }

    @Transactional
    override fun updatePasswordHash(userId: UUID, passwordHash: String, updatedAt: Instant) {
        jpa.updatePasswordHash(userId, passwordHash, updatedAt)
    }

    @Transactional
    override fun updateProfile(userId: UUID, name: String, updatedAt: Instant): User {
        jpa.updateProfile(userId, name, updatedAt)
        return jpa.findById(userId).orElse(null)?.toDomain()
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "User not found")
    }
}

@Repository
class DeviceRepositoryAdapter(
    private val jpa: DeviceJpaRepository,
) : DeviceRepository {

    override fun findById(id: UUID): Device? = jpa.findById(id).orElse(null)?.toDomain()

    @Transactional
    override fun upsert(device: Device): Device {
        val existing = jpa.findById(device.id).orElse(null)
        if (existing == null) {
            return jpa.save(DeviceEntity.fromDomain(device)).toDomain()
        }

        // A device id identifies an *installation*, not a person, and one phone
        // or laptop is routinely used by more than one account - sign out of one,
        // sign in to another. So a change of owner is reassignment, not an
        // attack: the row follows whoever is currently signed in.
        //
        // An earlier version rejected this with 403. That guarded against
        // nothing real - device ids are never exposed to other users and are
        // never an authorization input, they only carry display metadata - while
        // breaking the ordinary case of a second account on the same device.
        //
        // The cost is that a stale refresh_tokens.device_id may point at a row
        // now owned by someone else, so a future "your sessions" screen must
        // read device metadata scoped by user, not follow that link blindly.
        existing.userId = device.userId
        existing.platform = device.platform.name
        existing.lastSeenAt = device.lastSeenAt
        device.displayName?.let { existing.displayName = it }
        device.appVersion?.let { existing.appVersion = it }
        return jpa.save(existing).toDomain()
    }

    override fun findAllByUser(userId: UUID): List<Device> =
        jpa.findAllByUserId(userId).map { it.toDomain() }
}

@Repository
class RefreshTokenRepositoryAdapter(
    private val jpa: RefreshTokenJpaRepository,
) : RefreshTokenRepository {

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun save(token: RefreshToken): RefreshToken =
        jpa.save(RefreshTokenEntity.fromDomain(token)).toDomain()

    @Transactional
    override fun revoke(id: UUID, at: Instant, replacedBy: UUID?) {
        jpa.revoke(id, at, replacedBy)
    }

    @Transactional
    override fun revokeFamily(familyId: UUID, at: Instant) {
        jpa.revokeFamily(familyId, at)
    }

    @Transactional
    override fun revokeAllForUser(userId: UUID, at: Instant) {
        jpa.revokeAllForUser(userId, at)
    }

    @Transactional
    override fun revokeAllForUserExcept(userId: UUID, keepTokenId: UUID, at: Instant) {
        jpa.revokeAllForUserExcept(userId, keepTokenId, at)
    }

    @Transactional
    override fun deleteExpiredBefore(cutoff: Instant): Int = jpa.deleteExpiredBefore(cutoff)
}

@Repository
class PasswordResetTokenRepositoryAdapter(
    private val jpa: PasswordResetTokenJpaRepository,
) : PasswordResetTokenRepository {

    override fun findByTokenHash(tokenHash: String): PasswordResetToken? =
        jpa.findByTokenHash(tokenHash)?.toDomain()

    override fun save(token: PasswordResetToken): PasswordResetToken =
        jpa.save(PasswordResetTokenEntity.fromDomain(token)).toDomain()

    @Transactional
    override fun markUsed(id: UUID, at: Instant) {
        jpa.markUsed(id, at)
    }

    @Transactional
    override fun invalidateAllForUser(userId: UUID, at: Instant) {
        jpa.invalidateAllForUser(userId, at)
    }

    @Transactional
    override fun deleteExpiredBefore(cutoff: Instant): Int = jpa.deleteExpiredBefore(cutoff)
}
