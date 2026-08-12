package com.arunrk.note.domain.model

/** What set a sync cycle off. Reported in logs and used to pick a backoff. */
enum class SyncReason {
    APP_START,
    CONNECTIVITY_REGAINED,
    LOCAL_CHANGE,
    PERIODIC,
    MANUAL,
    SIGN_IN,
}

sealed interface SyncState {

    data object Idle : SyncState

    data class Syncing(val reason: SyncReason) : SyncState

    /**
     * The last cycle failed. Notes are still safe locally - this is about the
     * transfer, never about the data.
     */
    data class Failed(val message: String, val isOffline: Boolean) : SyncState

    val isRunning: Boolean get() = this is Syncing
}

/**
 * Everything the settings screen and the status banner need, in one object so
 * they cannot disagree with each other.
 */
data class SyncSummary(
    val state: SyncState = SyncState.Idle,
    val lastSuccessfulSyncAt: Long? = null,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val conflictCount: Int = 0,
) {
    val hasUnsyncedWork: Boolean get() = pendingCount > 0 || failedCount > 0
}

/** Outcome of one cycle. */
data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val fullResync: Boolean = false,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}
