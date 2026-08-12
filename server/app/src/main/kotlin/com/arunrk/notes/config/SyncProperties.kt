package com.arunrk.notes.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "notes.sync")
data class SyncProperties(
    val maxTitleLength: Int = 512,
    val maxNoteContentBytes: Int = 512 * 1024,
    val maxPushBatch: Int = 500,
    val maxPullPage: Int = 200,
    /**
     * How long a deleted note survives as a tombstone. A device offline for
     * longer than this is told to full-resync, because the deletions it never
     * saw no longer exist to be delivered.
     */
    val tombstoneRetentionDays: Long = 90,
    val purgeEnabled: Boolean = true,
)
