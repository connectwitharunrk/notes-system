package com.arunrk.notes.domain.policy

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode

/**
 * Limits on note size and synchronisation batches.
 *
 * Bounded on purpose: an unbounded push batch or note body is a trivial way to
 * exhaust server memory, and the client needs a predictable ceiling to chunk
 * against.
 */
data class NotePolicy(
    val maxTitleLength: Int = 512,
    val maxContentBytes: Int = 512 * 1024,
    val maxPushBatch: Int = 500,
    val maxPullPage: Int = 200,
    val tombstoneRetentionDays: Long = 90,
) {

    fun requireBatchWithinLimit(size: Int) {
        if (size > maxPushBatch) {
            throw AppException(
                ErrorCode.SYNC_BATCH_TOO_LARGE,
                "A push may contain at most $maxPushBatch changes (received $size)",
            )
        }
    }

    fun clampPullLimit(requested: Int?): Int {
        if (requested == null || requested <= 0) return maxPullPage
        return minOf(requested, maxPullPage)
    }

    /** Returns null when acceptable, or the reason it was rejected. */
    fun rejectionReasonFor(title: String, content: String): Pair<ErrorCode, String>? {
        if (title.length > maxTitleLength) {
            return ErrorCode.NOTE_TOO_LARGE to
                "Title may be at most $maxTitleLength characters"
        }
        // Bytes, not characters: the storage and transfer cost is in bytes, and
        // a 512k-character note of CJK text is roughly 1.5 MB on the wire.
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        if (contentBytes > maxContentBytes) {
            return ErrorCode.NOTE_TOO_LARGE to
                "Content may be at most $maxContentBytes bytes (received $contentBytes)"
        }
        return null
    }
}
