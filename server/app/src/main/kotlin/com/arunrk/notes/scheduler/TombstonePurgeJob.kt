package com.arunrk.notes.scheduler

import com.arunrk.notes.config.SyncProperties
import com.arunrk.notes.domain.usecase.sync.PurgeTombstonesUseCase
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Permanently removes deleted notes past their retention window and raises each
 * affected user's tombstone floor in the same transaction.
 *
 * Deliberately infrequent and conservative. Purging early would strand devices
 * that were merely offline for a while, forcing needless full resyncs; never
 * purging would let deleted notes accumulate forever.
 */
@Component
class TombstonePurgeJob(
    private val purgeTombstones: PurgeTombstonesUseCase,
    private val properties: SyncProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = CRON_DAILY_0400)
    fun purge() {
        if (!properties.purgeEnabled) return

        val report = runCatching { purgeTombstones.execute() }
            .onFailure { log.error("Tombstone purge failed", it) }
            .getOrNull()
            ?: return

        if (report.tombstonesDeleted > 0) {
            log.info(
                "Purged {} tombstones across {} users (retention {} days)",
                report.tombstonesDeleted,
                report.usersProcessed,
                properties.tombstoneRetentionDays,
            )
        }
    }

    private companion object {
        // Half an hour after the token cleanup, so the two never contend.
        const val CRON_DAILY_0400 = "0 0 4 * * *"
    }
}
