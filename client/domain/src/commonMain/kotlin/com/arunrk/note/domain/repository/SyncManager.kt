package com.arunrk.note.domain.repository

import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncResult
import com.arunrk.note.domain.model.SyncSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives synchronisation.
 *
 * Declared in the domain so the UI can trigger and observe a sync without
 * depending on the engine that performs it - and so the whole thing can be
 * replaced with a fake in tests.
 *
 * Nothing here is on the interaction path. Notes are written locally and are
 * already safe before any of this runs.
 */
interface SyncManager {

    val summary: StateFlow<SyncSummary>

    /** Runs a cycle and waits for it. Used by "Sync now". */
    suspend fun syncNow(reason: SyncReason = SyncReason.MANUAL): SyncResult

    /**
     * Asks for a sync without waiting. Repeated calls collapse: a burst of edits
     * produces one cycle, not one per keystroke.
     */
    fun requestSync(reason: SyncReason)

    /** Starts the background triggers. Called once, after sign-in. */
    fun start()

    /** Stops triggers and clears state. Called on sign-out. */
    suspend fun stop()
}
