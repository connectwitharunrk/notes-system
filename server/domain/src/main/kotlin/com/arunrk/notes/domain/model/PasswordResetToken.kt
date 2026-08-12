package com.arunrk.notes.domain.model

import java.time.Instant
import java.util.UUID

data class PasswordResetToken(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val createdAt: Instant,
) {
    fun isUsable(at: Instant): Boolean = usedAt == null && expiresAt.isAfter(at)
}
