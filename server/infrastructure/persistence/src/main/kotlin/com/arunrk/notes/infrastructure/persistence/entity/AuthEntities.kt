package com.arunrk.notes.infrastructure.persistence.entity

import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.model.PasswordResetToken
import com.arunrk.notes.domain.model.RefreshToken
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Associations are modelled as raw foreign-key columns rather than JPA
 * relationships. These aggregates are always loaded by id and never navigated,
 * so mapping them as @ManyToOne would buy nothing and cost lazy-loading
 * surprises outside a transaction.
 */

@Entity
@Table(name = "devices")
class DeviceEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "platform", nullable = false)
    var platform: String,

    @Column(name = "display_name")
    var displayName: String? = null,

    @Column(name = "app_version")
    var appVersion: String? = null,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
) {
    fun toDomain(): Device = Device(
        id = id,
        userId = userId,
        platform = DevicePlatform.parse(platform),
        displayName = displayName,
        appVersion = appVersion,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(device: Device) = DeviceEntity(
            id = device.id,
            userId = device.userId,
            platform = device.platform.name,
            displayName = device.displayName,
            appVersion = device.appVersion,
            lastSeenAt = device.lastSeenAt,
            createdAt = device.createdAt,
        )
    }
}

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "device_id")
    var deviceId: UUID? = null,

    @Column(name = "token_hash", nullable = false)
    var tokenHash: String,

    @Column(name = "family_id", nullable = false)
    var familyId: UUID,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "replaced_by")
    var replacedBy: UUID? = null,

    @Column(name = "user_agent")
    var userAgent: String? = null,

    @Column(name = "ip_address")
    var ipAddress: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
) {
    fun toDomain(): RefreshToken = RefreshToken(
        id = id,
        userId = userId,
        deviceId = deviceId,
        tokenHash = tokenHash,
        familyId = familyId,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        replacedBy = replacedBy,
        userAgent = userAgent,
        ipAddress = ipAddress,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(token: RefreshToken) = RefreshTokenEntity(
            id = token.id,
            userId = token.userId,
            deviceId = token.deviceId,
            tokenHash = token.tokenHash,
            familyId = token.familyId,
            expiresAt = token.expiresAt,
            revokedAt = token.revokedAt,
            replacedBy = token.replacedBy,
            userAgent = token.userAgent,
            ipAddress = token.ipAddress,
            createdAt = token.createdAt,
        )
    }
}

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetTokenEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "token_hash", nullable = false)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
) {
    fun toDomain(): PasswordResetToken = PasswordResetToken(
        id = id,
        userId = userId,
        tokenHash = tokenHash,
        expiresAt = expiresAt,
        usedAt = usedAt,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(token: PasswordResetToken) = PasswordResetTokenEntity(
            id = token.id,
            userId = token.userId,
            tokenHash = token.tokenHash,
            expiresAt = token.expiresAt,
            usedAt = token.usedAt,
            createdAt = token.createdAt,
        )
    }
}
