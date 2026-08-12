package com.arunrk.notes.infrastructure.persistence.entity

import com.arunrk.notes.domain.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "email", nullable = false)
    var email: String,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "change_counter", nullable = false)
    var changeCounter: Long = 0,

    @Column(name = "tombstone_floor", nullable = false)
    var tombstoneFloor: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    fun toDomain(): User = User(
        id = id,
        email = email,
        name = name,
        passwordHash = passwordHash,
        emailVerified = emailVerified,
        changeCounter = changeCounter,
        tombstoneFloor = tombstoneFloor,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    companion object {
        fun fromDomain(user: User) = UserEntity(
            id = user.id,
            email = user.email,
            name = user.name,
            passwordHash = user.passwordHash,
            emailVerified = user.emailVerified,
            changeCounter = user.changeCounter,
            tombstoneFloor = user.tombstoneFloor,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            deletedAt = user.deletedAt,
        )
    }
}
