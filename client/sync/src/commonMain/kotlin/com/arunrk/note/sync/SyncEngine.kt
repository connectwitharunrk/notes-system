package com.arunrk.note.sync

import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.common.platform.currentTimeMillis
import com.arunrk.note.core.network.api.SyncApi
import com.arunrk.note.core.network.dto.PullResponseDto
import com.arunrk.note.core.network.dto.PushResultDto
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SyncEngine"

/** Matches the server's `notes.sync.max-push-batch`, with headroom. */
private const val PUSH_BATCH_SIZE = 100
private const val PULL_PAGE_SIZE = 200

/**
 * Runs one synchronisation cycle: push local changes, then pull remote ones.
 *
 * ### Why push comes first
 *
 * If the server asks for a full resync, the client has to wipe its synced rows.
 * Anything not yet pushed would be destroyed. Pushing first means the server
 * already holds every local change before we throw anything away.
 *
 * ### Why the whole cycle is single-flight
 *
 * Two overlapping cycles would push the same dirty notes twice. The second push
 * carries a `baseVersion` the first has already superseded, so the server
 * correctly reports a conflict and creates a copy - of the note against itself.
 * The mutex is what stops the app manufacturing its own conflicts.
 */
class SyncEngine(
    private val api: SyncApi,
    private val store: SyncLocalStore,
) {

    private val mutex = Mutex()
    private var lastPurgeAt = 0L

    suspend fun sync(userId: String, reason: SyncReason): SyncResult = mutex.withLock {
        Log.i(TAG, "Sync started ($reason)")

        val pushOutcome = push(userId)
        val pushError = pushOutcome.error
        if (pushError != null) {
            // Stop here rather than pulling anyway: a pull that lands while local
            // changes are still unsent can only make the divergence larger.
            store.recordFailure(userId, pushError)
            return@withLock pushOutcome
        }

        val pullOutcome = pull(userId)
        val combined = SyncResult(
            pushed = pushOutcome.pushed,
            pulled = pullOutcome.pulled,
            conflicts = pushOutcome.conflicts + pullOutcome.conflicts,
            rejected = pushOutcome.rejected,
            fullResync = pullOutcome.fullResync,
            error = pullOutcome.error,
        )

        if (combined.isSuccess) {
            val now = currentTimeMillis()
            store.recordSuccess(userId, now)
            purgeOldTombstones(userId, now)
            Log.i(
                TAG,
                "Sync finished: pushed=${combined.pushed} pulled=${combined.pulled} " +
                    "conflicts=${combined.conflicts} rejected=${combined.rejected}",
            )
        } else {
            store.recordFailure(userId, combined.error.orEmpty())
        }

        combined
    }

    /**
     * Asks, cheaply, whether the server holds anything this device has not seen.
     *
     * A full cycle is a push, a pull, and a database write per page. Running one
     * every half minute only to discover that nothing changed would be wasteful
     * at both ends. `/sync/status` answers the same question in one small GET
     * carrying no note payloads, so the expensive cycle runs only when there is
     * genuinely something to fetch.
     *
     * Never answers true while a cycle is running: that cycle is already pulling
     * whatever is there, and saying yes would just queue a redundant one.
     */
    suspend fun hasRemoteChanges(userId: String): Boolean {
        if (mutex.isLocked) return false

        val cursor = store.cursor(userId)

        return when (val outcome = api.status(cursor)) {
            // Best-effort by design: the probe stays silent about failures and
            // lets the next real cycle surface them to the user.
            is Outcome.Failure -> {
                Log.d(TAG, "Status probe failed: ${outcome.error.describe()}")
                false
            }

            is Outcome.Success -> {
                val status = outcome.value
                // Below the floor, the deletions we missed have been purged and
                // can no longer be delivered incrementally - only a full cycle,
                // which pushes before it rebuilds, recovers from that.
                val needsResync = cursor < status.tombstoneFloor
                val hasChanges = status.pendingForCursor > 0

                if (hasChanges || needsResync) {
                    Log.d(
                        TAG,
                        "Server is ahead: ${status.pendingForCursor} change(s) since $cursor",
                    )
                }
                hasChanges || needsResync
            }
        }
    }

    // -----------------------------------------------------------------------
    // Push
    // -----------------------------------------------------------------------

    private suspend fun push(userId: String): SyncResult {
        var pushed = 0
        var conflicts = 0
        var rejected = 0

        while (true) {
            val dirty = store.dirtyNotes(userId, PUSH_BATCH_SIZE)
            if (dirty.isEmpty()) break

            // Snapshot the revision of every note BEFORE the request. The
            // response is only allowed to mark a note synced if its revision is
            // still this value - see SyncLocalStore.confirmPushed.
            val snapshots = dirty.associate { entity ->
                entity.id to PushedSnapshot(
                    noteId = entity.id,
                    localRevision = entity.localRevision,
                    contentHash = entity.contentHash,
                )
            }

            when (val response = api.push(dirty.map { it.toChangeDto() })) {
                is Outcome.Failure -> return SyncResult(
                    pushed = pushed,
                    conflicts = conflicts,
                    rejected = rejected,
                    error = response.error.describe(),
                )

                is Outcome.Success -> {
                    var appliedThisRound = 0

                    response.value.results.forEach { result ->
                        val snapshot = snapshots[result.id] ?: return@forEach
                        when (applyPushResult(userId, result, snapshot)) {
                            PushOutcome.APPLIED -> {
                                pushed++
                                appliedThisRound++
                            }

                            PushOutcome.CONFLICTED -> {
                                conflicts++
                                appliedThisRound++
                            }

                            PushOutcome.REJECTED -> {
                                rejected++
                                appliedThisRound++
                            }

                            PushOutcome.SKIPPED -> Unit
                        }
                    }

                    // Nothing in this batch could be settled - every note was
                    // edited mid-flight. Pushing the same rows again immediately
                    // would spin forever, so stop and let the next cycle handle
                    // the newer content.
                    if (appliedThisRound == 0) {
                        Log.d(TAG, "All ${dirty.size} notes changed during push; deferring")
                        break
                    }

                    if (dirty.size < PUSH_BATCH_SIZE) break
                }
            }
        }

        return SyncResult(pushed = pushed, conflicts = conflicts, rejected = rejected)
    }

    private enum class PushOutcome { APPLIED, CONFLICTED, REJECTED, SKIPPED }

    private suspend fun applyPushResult(
        userId: String,
        result: PushResultDto,
        snapshot: PushedSnapshot,
    ): PushOutcome = when (result.status) {

        "APPLIED" -> {
            val confirmed = store.confirmPushed(
                noteId = result.id,
                expectedLocalRevision = snapshot.localRevision,
                serverVersion = result.version ?: 0,
                contentHash = snapshot.contentHash,
            )
            // Not confirmed means the note was edited while the request was in
            // flight. It stays PENDING and goes again - that is the guard doing
            // its job, not an error.
            if (confirmed) PushOutcome.APPLIED else PushOutcome.SKIPPED
        }

        "CONFLICT" -> {
            val server = result.server
            if (server == null) {
                PushOutcome.SKIPPED
            } else {
                // Adopt the server's version of this note, but only if we have
                // not edited it since pushing.
                store.applyServerNote(
                    note = server.toServerNote(userId),
                    expectedLocalRevision = snapshot.localRevision,
                )

                // The losing version comes back as a separate note so neither
                // piece of writing is destroyed. Marked CONFLICT so it is
                // visible rather than silently appearing in the list.
                result.conflictCopy?.let { copy ->
                    store.applyServerNote(
                        note = copy.toServerNote(userId),
                        expectedLocalRevision = null,
                        markAsConflict = true,
                    )
                }
                PushOutcome.CONFLICTED
            }
        }

        "REJECTED" -> {
            val message = result.error?.message ?: "The server rejected this note"
            store.markFailed(result.id, message)
            Log.w(TAG, "Note ${result.id} rejected: ${result.error?.code} $message")
            PushOutcome.REJECTED
        }

        else -> PushOutcome.SKIPPED
    }

    // -----------------------------------------------------------------------
    // Pull
    // -----------------------------------------------------------------------

    private suspend fun pull(userId: String): SyncResult {
        var pulled = 0
        var conflicts = 0
        var fullResync = false
        var cursor = store.cursor(userId)
        var guard = 0

        while (true) {
            // A runaway loop here would hammer the server forever. The bound is
            // generous but finite.
            if (guard++ > MAX_PULL_PAGES) {
                Log.w(TAG, "Pull exceeded $MAX_PULL_PAGES pages; stopping")
                break
            }

            val response = when (val outcome = api.pull(cursor, PULL_PAGE_SIZE)) {
                is Outcome.Failure -> return SyncResult(
                    pulled = pulled,
                    conflicts = conflicts,
                    fullResync = fullResync,
                    error = outcome.error.describe(),
                )

                is Outcome.Success -> outcome.value
            }

            if (response.resyncRequired) {
                // Our cursor predates purged tombstones, so an incremental pull
                // would never learn about those deletions and would keep showing
                // notes the user deleted months ago on another device.
                //
                // Safe at this point precisely because push already ran: the
                // server holds every local change before anything is discarded.
                Log.w(TAG, "Server requested a full resync; rebuilding local copy")
                store.prepareFullResync(userId, currentTimeMillis())
                cursor = 0
                fullResync = true
                continue
            }

            val applied = applyPulledNotes(userId, response)
            pulled += applied

            cursor = response.nextCursor
            // Persisted after every page, so an interrupted pull resumes where
            // it stopped instead of starting over.
            store.setCursor(userId, cursor)

            if (!response.hasMore) break
        }

        return SyncResult(pulled = pulled, conflicts = conflicts, fullResync = fullResync)
    }

    private suspend fun applyPulledNotes(userId: String, response: PullResponseDto): Int {
        var applied = 0
        response.notes.forEach { dto ->
            // No expected revision: applyServerNote will refuse to overwrite a
            // row that has unsynced local edits. Those notes are pushed on the
            // next cycle and reconciled properly by the server.
            val written = store.applyServerNote(
                note = dto.toServerNote(userId),
                expectedLocalRevision = null,
            )
            if (written) applied++
        }
        return applied
    }

    /**
     * A tombstone exists only to carry a deletion to the other devices. Once the
     * server has confirmed it and long enough has passed for every device to have
     * pulled it, keeping the row does nothing but grow the database for the rest
     * of the account's life.
     *
     * Rate-limited because it is a write: running it on every cycle would wake
     * every observing query in the app - the note list included - fifteen minutes
     * apart, forever, to delete nothing.
     */
    private suspend fun purgeOldTombstones(userId: String, now: Long) {
        if (now - lastPurgeAt < PURGE_INTERVAL_MILLIS) return
        lastPurgeAt = now
        store.purgeConfirmedTombstones(userId, olderThan = now - TOMBSTONE_RETENTION_MILLIS)
    }

    private fun AppError.describe(): String = when (this) {
        AppError.Offline -> "offline"
        AppError.Timeout -> "timed out"
        AppError.Unauthenticated -> "session expired"
        is AppError.Server -> "server error: $code"
        else -> this::class.simpleName ?: "unknown error"
    }

    private companion object {
        const val MAX_PULL_PAGES = 500

        /**
         * Only rows the server has already confirmed are dropped, and the server
         * keeps its own tombstone for 90 days, so this is a grace period rather
         * than a deadline: shorter than the server's on purpose, because after
         * this long the row is doing nothing but taking up space.
         */
        const val TOMBSTONE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
