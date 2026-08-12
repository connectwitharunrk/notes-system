package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.policy.NotePolicy
import com.arunrk.notes.domain.port.ChangeSequencer
import com.arunrk.notes.domain.port.NoteRepository
import com.arunrk.notes.domain.port.Transactor
import java.time.temporal.ChronoUnit

data class PurgeReport(
    val usersProcessed: Int,
    val tombstonesDeleted: Int,
)

/**
 * Permanently removes tombstones past their retention window.
 *
 * The floor is raised in the same transaction as the delete. Do it the other way
 * round - or not at all - and a device whose cursor predates the purge will pull
 * incrementally, never receive those deletions, and keep showing notes the user
 * deleted months ago on every other device.
 */
class PurgeTombstonesUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val transactor: Transactor,
    private val time: TimeProvider,
    private val policy: NotePolicy,
) {

    fun execute(): PurgeReport {
        val cutoff = time.now().minus(policy.tombstoneRetentionDays, ChronoUnit.DAYS)
        val userIds = notes.findUserIdsWithExpiredTombstones(cutoff)

        var deleted = 0
        var processed = 0

        for (userId in userIds) {
            transactor.inTransaction {
                val floor = notes.maxChangeSeqOfTombstonesOlderThan(userId, cutoff)
                if (floor != null) {
                    val removed = notes.deleteTombstonesOlderThan(userId, cutoff)
                    if (removed > 0) {
                        sequencer.raiseTombstoneFloor(userId, floor)
                        deleted += removed
                        processed++
                    }
                }
            }
        }

        return PurgeReport(usersProcessed = processed, tombstonesDeleted = deleted)
    }
}
