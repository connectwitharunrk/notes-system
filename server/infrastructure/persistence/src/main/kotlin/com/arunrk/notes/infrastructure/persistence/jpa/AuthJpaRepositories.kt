package com.arunrk.notes.infrastructure.persistence.jpa

import com.arunrk.notes.infrastructure.persistence.entity.DeviceEntity
import com.arunrk.notes.infrastructure.persistence.entity.PasswordResetTokenEntity
import com.arunrk.notes.infrastructure.persistence.entity.RefreshTokenEntity
import com.arunrk.notes.infrastructure.persistence.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {

    // lower(email) matches the partial unique index, so this uses the index
    // rather than scanning. deletedAt IS NULL keeps soft-deleted accounts out.
    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE lower(u.email) = lower(:email) AND u.deletedAt IS NULL
        """
    )
    fun findActiveByEmail(@Param("email") email: String): UserEntity?

    @Query(
        """
        SELECT COUNT(u) > 0 FROM UserEntity u
        WHERE lower(u.email) = lower(:email) AND u.deletedAt IS NULL
        """
    )
    fun existsActiveByEmail(@Param("email") email: String): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.passwordHash = :hash, u.updatedAt = :now WHERE u.id = :id")
    fun updatePasswordHash(
        @Param("id") id: UUID,
        @Param("hash") hash: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.name = :name, u.updatedAt = :now WHERE u.id = :id")
    fun updateProfile(
        @Param("id") id: UUID,
        @Param("name") name: String,
        @Param("now") now: Instant,
    ): Int
}

interface DeviceJpaRepository : JpaRepository<DeviceEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<DeviceEntity>
}

interface RefreshTokenJpaRepository : JpaRepository<RefreshTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t
        SET t.revokedAt = :now, t.replacedBy = :replacedBy
        WHERE t.id = :id AND t.revokedAt IS NULL
        """
    )
    fun revoke(
        @Param("id") id: UUID,
        @Param("now") now: Instant,
        @Param("replacedBy") replacedBy: UUID?,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t SET t.revokedAt = :now
        WHERE t.familyId = :familyId AND t.revokedAt IS NULL
        """
    )
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t SET t.revokedAt = :now
        WHERE t.userId = :userId AND t.revokedAt IS NULL
        """
    )
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t SET t.revokedAt = :now
        WHERE t.userId = :userId AND t.id <> :keepId AND t.revokedAt IS NULL
        """
    )
    fun revokeAllForUserExcept(
        @Param("userId") userId: UUID,
        @Param("keepId") keepId: UUID,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}

interface PasswordResetTokenJpaRepository : JpaRepository<PasswordResetTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): PasswordResetTokenEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetTokenEntity t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    fun markUsed(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE PasswordResetTokenEntity t SET t.usedAt = :now
        WHERE t.userId = :userId AND t.usedAt IS NULL
        """
    )
    fun invalidateAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    @Modifying
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
