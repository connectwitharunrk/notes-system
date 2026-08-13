package com.arunrk.note.sync

import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncResult

/**
 * One synchronisation cycle, plus the cheap question of whether running one is
 * worth it.
 *
 * Split out from [SyncEngine] so the two halves can be judged separately, since
 * they fail in completely different ways. The engine's failure is silent data
 * loss, and only a real server can prove it does not happen - that is
 * `SyncFlowIntegrationTest`. The scheduler's failure is a change that simply
 * never leaves the device: no error, no lost data, just a note that quietly
 * fails to appear anywhere else. Proving *that* needs no server at all - it
 * needs control of the clock, the network flag and the app's lifecycle, which
 * this interface is what makes possible.
 */
interface SyncCycle {

    suspend fun sync(userId: String, reason: SyncReason): SyncResult

    suspend fun hasRemoteChanges(userId: String): Boolean
}
