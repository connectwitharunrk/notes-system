package com.arunrk.note.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncResult
import com.arunrk.note.domain.model.SyncState
import com.arunrk.note.domain.model.SyncSummary
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.repository.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

private const val TAG = "SyncManager"

/** A burst of edits should produce one cycle, not one per keystroke. */
private const val LOCAL_CHANGE_DEBOUNCE_MILLIS = 3_000L
private const val PERIODIC_INTERVAL_MILLIS = 15 * 60 * 1000L

private const val BACKOFF_INITIAL_MILLIS = 2_000L
private const val BACKOFF_MAX_MILLIS = 5 * 60 * 1000L

class DefaultSyncManager(
    private val engine: SyncEngine,
    private val store: SyncLocalStore,
    private val database: NoteDatabase,
    private val authRepository: AuthRepository,
    private val networkMonitor: NetworkMonitor,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) : SyncManager {

    private val _summary = MutableStateFlow(SyncSummary())
    override val summary: StateFlow<SyncSummary> = _summary.asStateFlow()

    /**
     * Requests collapse rather than queue: extra capacity would only mean
     * running the same cycle several times in a row.
     */
    private val requests = MutableSharedFlow<SyncReason>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var triggersJob: Job? = null
    private var consecutiveFailures = 0
    private var lastPendingCount = 0

    private val currentUserId: String?
        get() = (authRepository.authState.value as? AuthState.Authenticated)?.user?.id

    override fun start() {
        if (triggersJob?.isActive == true) return

        triggersJob = scope.launch {
            observeCounts()
            consumeRequests()
            observeConnectivity()
            runPeriodically()
        }

        requestSync(SyncReason.APP_START)
    }

    override suspend fun stop() {
        triggersJob?.cancel()
        triggersJob = null
        _summary.value = SyncSummary()
        consecutiveFailures = 0
        lastPendingCount = 0
    }

    override fun requestSync(reason: SyncReason) {
        requests.tryEmit(reason)
    }

    override suspend fun syncNow(reason: SyncReason): SyncResult {
        val userId = currentUserId
            ?: return SyncResult(error = "not signed in")

        if (!networkMonitor.isOnline.value) {
            // Not an error the user caused, and their notes are already safe -
            // say so rather than showing a failure.
            _summary.value = _summary.value.copy(
                state = SyncState.Failed("You're offline", isOffline = true),
            )
            return SyncResult(error = "offline")
        }

        _summary.value = _summary.value.copy(state = SyncState.Syncing(reason))

        val result = engine.sync(userId, reason)

        _summary.value = _summary.value.copy(
            state = if (result.isSuccess) {
                consecutiveFailures = 0
                SyncState.Idle
            } else {
                consecutiveFailures++
                SyncState.Failed(
                    message = result.error ?: "Sync failed",
                    isOffline = result.error == "offline",
                )
            },
            lastSuccessfulSyncAt = store.lastSuccessfulSyncAt(userId),
        )

        return result
    }

    // -----------------------------------------------------------------------
    // Triggers
    // -----------------------------------------------------------------------

    private fun CoroutineScope.consumeRequests() {
        launch {
            // collectLatest so a newer request cancels a debounce that has not
            // fired yet - the point is to coalesce, not to queue.
            requests.collectLatest { reason ->
                if (reason == SyncReason.LOCAL_CHANGE) {
                    delay(LOCAL_CHANGE_DEBOUNCE_MILLIS)
                }
                runWithBackoff(reason)
            }
        }
    }

    private fun CoroutineScope.observeConnectivity() {
        // No distinctUntilChanged: StateFlow already conflates equal values.
        networkMonitor.isOnline
            .onEach { online ->
                if (online) {
                    // Reconnecting is the moment a queued change can finally go
                    // out, so reset the backoff rather than waiting it out.
                    consecutiveFailures = 0
                    requestSync(SyncReason.CONNECTIVITY_REGAINED)
                }
            }
            .launchIn(this)
    }

    private fun CoroutineScope.runPeriodically() {
        launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MILLIS)
                requestSync(SyncReason.PERIODIC)
            }
        }
    }

    private suspend fun runWithBackoff(reason: SyncReason) {
        if (consecutiveFailures > 0) {
            // Exponential, capped. A device that has been failing for an hour
            // should not still be retrying every two seconds.
            val wait = (BACKOFF_INITIAL_MILLIS shl (consecutiveFailures - 1).coerceAtMost(8))
                .coerceAtMost(BACKOFF_MAX_MILLIS)
            Log.d(TAG, "Backing off ${wait}ms after $consecutiveFailures failures")
            delay(wait)
        }
        syncNow(reason)
    }

    // -----------------------------------------------------------------------
    // Observed state
    // -----------------------------------------------------------------------

    /**
     * Keeps the pending/failed/conflict counts live off the database, so the
     * settings screen and the status banner never disagree with the note list.
     */
    private fun CoroutineScope.observeCounts() {
        authRepository.authState
            .filterIsInstance<AuthState.Authenticated>()
            .map { it.user.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                database.noteEntityQueries.countByStatus(userId)
                    .asFlow()
                    .mapToList(dispatchers.io)
                    .map { rows -> userId to rows.associate { it.syncStatus to it.total.toInt() } }
            }
            .onEach { (userId, counts) ->
                val pending = counts["PENDING"] ?: 0

                _summary.value = _summary.value.copy(
                    pendingCount = pending,
                    failedCount = counts["FAILED"] ?: 0,
                    conflictCount = counts["CONFLICT"] ?: 0,
                    lastSuccessfulSyncAt = store.lastSuccessfulSyncAt(userId),
                )

                // A local edit marks its note PENDING, so a rise in that count
                // *is* the signal that something needs pushing. Deriving the
                // trigger from state the database already publishes keeps the
                // domain free of any dependency on the sync engine - no use case
                // has to remember to announce that it wrote something.
                if (pending > lastPendingCount) {
                    requestSync(SyncReason.LOCAL_CHANGE)
                }
                lastPendingCount = pending
            }
            .launchIn(this)
    }
}
