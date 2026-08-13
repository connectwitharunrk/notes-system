package com.arunrk.note.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.common.platform.currentTimeMillis
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** How often a cycle may test a network we believe to be down. */
private const val OFFLINE_PROBE_INTERVAL_MILLIS = 60_000L

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
     * Requests collapse rather than queue: extra capacity would only mean running
     * the same cycle several times in a row.
     *
     * A channel rather than a SharedFlow because a shared flow with no collector
     * discards what it is given, and [start] necessarily asks for a sync before
     * the consumer it just launched is running - so the sync at app start was
     * being dropped roughly whenever the dispatcher was busy.
     */
    private val requests = Channel<SyncReason>(Channel.CONFLATED)

    private var triggersJob: Job? = null
    private var retryJob: Job? = null
    private var consecutiveFailures = 0

    /** No automatic attempt before this instant: a retry is already scheduled. */
    private var nextAttemptAt = 0L
    private var lastOfflineProbeAt = 0L

    private val currentUserId: String?
        get() = (authRepository.authState.value as? AuthState.Authenticated)?.user?.id

    override fun start() {
        if (triggersJob?.isActive == true) return

        // A request left over from the previous session would otherwise fire a
        // cycle for the user who just signed out.
        drainRequests()

        triggersJob = scope.launch {
            observeCounts()
            observeLocalChanges()
            consumeRequests()
            observeConnectivity()
            runPeriodically()
        }

        requestSync(SyncReason.APP_START)
    }

    override suspend fun stop() {
        triggersJob?.cancel()
        triggersJob = null
        retryJob?.cancel()
        retryJob = null
        drainRequests()
        _summary.value = SyncSummary()
        consecutiveFailures = 0
        nextAttemptAt = 0
        lastOfflineProbeAt = 0
    }

    override fun requestSync(reason: SyncReason) {
        requests.trySend(reason)
    }

    override suspend fun syncNow(reason: SyncReason): SyncResult {
        val userId = currentUserId
            ?: return SyncResult(error = "not signed in")

        if (!networkMonitor.isOnline.value && !shouldProbeWhileOffline(reason)) {
            // Not an error the user caused, and their notes are already safe -
            // say so rather than showing a failure.
            _summary.value = _summary.value.copy(
                state = SyncState.Failed("You're offline", isOffline = true),
            )
            return SyncResult(error = "offline")
        }

        _summary.value = _summary.value.copy(state = SyncState.Syncing(reason))

        val result = engine.sync(userId, reason)

        if (result.isSuccess) {
            consecutiveFailures = 0
            nextAttemptAt = 0
            retryJob?.cancel()
        } else {
            consecutiveFailures++
            scheduleRetry()
        }

        _summary.value = _summary.value.copy(
            state = if (result.isSuccess) {
                SyncState.Idle
            } else {
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
            // Sequential on purpose. Collecting these with collectLatest would
            // cancel a cycle that is already running, and a push cancelled
            // mid-request leaves the server's answer unknown: the note stays
            // PENDING, is pushed again carrying a baseVersion the server has
            // already superseded, and the server correctly reports a conflict -
            // of the note against itself. Requests arriving during a cycle are
            // conflated by the channel and run once it finishes.
            for (reason in requests) {
                if (reason == SyncReason.LOCAL_CHANGE) {
                    delay(LOCAL_CHANGE_DEBOUNCE_MILLIS)
                    // Anything asked for while the burst settled is covered by
                    // the cycle about to run.
                    drainRequests()
                }
                if (shouldAttempt(reason)) syncNow(reason)
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
                    nextAttemptAt = 0
                    retryJob?.cancel()
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

    /**
     * Nothing else would retry a failed cycle: the periodic tick is fifteen
     * minutes away, and a device whose push just failed has, by definition, work
     * still waiting. So a failure schedules its own next attempt - exponential
     * and capped, because a device that has been failing for an hour should not
     * still be retrying every two seconds.
     */
    private fun scheduleRetry() {
        // Signed out: there is nothing left to retry for.
        if (triggersJob?.isActive != true) return

        val wait = (BACKOFF_INITIAL_MILLIS shl (consecutiveFailures - 1).coerceAtMost(8))
            .coerceAtMost(BACKOFF_MAX_MILLIS)
        nextAttemptAt = currentTimeMillis() + wait

        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            Log.d(TAG, "Retrying after ${wait}ms ($consecutiveFailures failures)")
            // Cleared first: the window this request waited out has now passed.
            nextAttemptAt = 0
            requestSync(SyncReason.RETRY)
        }
    }

    /**
     * A failing cycle already has a retry scheduled for the end of its backoff
     * window; running again inside that window would only add load to a server
     * that is already failing. The user asking always wins.
     */
    private fun shouldAttempt(reason: SyncReason): Boolean =
        reason == SyncReason.MANUAL || currentTimeMillis() >= nextAttemptAt

    /**
     * We believe we are offline - but that belief can be both wrong and stuck.
     * It is only ever corrected by a request that completes, and on a platform
     * with no OS connectivity signal (desktop) nothing else can clear it, so
     * refusing to try would be permanent. An occasional attempt is therefore
     * allowed to serve as the probe, and the user asking always gets a real one.
     */
    private fun shouldProbeWhileOffline(reason: SyncReason): Boolean {
        val now = currentTimeMillis()
        if (reason != SyncReason.MANUAL &&
            now - lastOfflineProbeAt < OFFLINE_PROBE_INTERVAL_MILLIS
        ) {
            return false
        }
        lastOfflineProbeAt = now
        return true
    }

    private fun drainRequests() {
        while (requests.tryReceive().isSuccess) Unit
    }

    // -----------------------------------------------------------------------
    // Observed state
    // -----------------------------------------------------------------------

    /**
     * A local write marks its note PENDING, so the dirty set *is* the signal that
     * something needs pushing. Deriving the trigger from state the database
     * already publishes keeps the domain free of any dependency on the sync
     * engine - no use case has to remember to announce that it wrote something,
     * and no future write path can forget to.
     *
     * The fingerprint rather than the count: an edit to a note that is already
     * waiting leaves the count untouched, and that is precisely the edit made
     * while the note's upload was in flight - the one the compare-and-set guard
     * deliberately leaves PENDING for the next cycle to carry.
     */
    private fun CoroutineScope.observeLocalChanges() {
        authRepository.authState
            .filterIsInstance<AuthState.Authenticated>()
            .map { it.user.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                database.noteEntityQueries.dirtyFingerprint(userId)
                    .asFlow()
                    .mapToOne(dispatchers.io)
                    // Every write to the table wakes this query, most of them the
                    // sync engine's own. Only a change in the backlog is news.
                    .distinctUntilChanged()
            }
            .onEach { dirty ->
                if (dirty.total > 0) requestSync(SyncReason.LOCAL_CHANGE)
            }
            .launchIn(this)
    }

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
                _summary.value = _summary.value.copy(
                    pendingCount = counts["PENDING"] ?: 0,
                    failedCount = counts["FAILED"] ?: 0,
                    conflictCount = counts["CONFLICT"] ?: 0,
                    lastSuccessfulSyncAt = store.lastSuccessfulSyncAt(userId),
                )
            }
            .launchIn(this)
    }
}
