package com.arunrk.notes.domain.model

import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val email: String,
    val name: String,
    val passwordHash: String,
    val emailVerified: Boolean = false,
    /** Per-user monotonic sync sequence. Only the sync engine advances this. */
    val changeCounter: Long = 0,
    /** Cursors below this value have missed purged tombstones and need a full resync. */
    val tombstoneFloor: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    val isActive: Boolean get() = deletedAt == null
}
