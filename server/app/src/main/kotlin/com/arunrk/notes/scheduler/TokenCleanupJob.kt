package com.arunrk.notes.scheduler

import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.port.PasswordResetTokenRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Removes tokens that can no longer authenticate anything.
 *
 * Expired rows are already rejected at verification time, so this is purely
 * housekeeping - but without it `refresh_tokens` grows forever and its unique
 * hash index degrades.
 */
@Component
class TokenCleanupJob(
    private val refreshTokens: RefreshTokenRepository,
    private val passwordResetTokens: PasswordResetTokenRepository,
    private val time: TimeProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = CRON_DAILY_0330)
    fun purgeExpiredTokens() {
        // Kept past expiry so that a support question like "why was I logged
        // out on Tuesday" is still answerable for a week.
        val cutoff = time.now().minus(GRACE_PERIOD)

        val refreshDeleted = runCatching { refreshTokens.deleteExpiredBefore(cutoff) }
            .onFailure { log.error("Failed to purge expired refresh tokens", it) }
            .getOrDefault(0)

        val resetDeleted = runCatching { passwordResetTokens.deleteExpiredBefore(cutoff) }
            .onFailure { log.error("Failed to purge expired password reset tokens", it) }
            .getOrDefault(0)

        if (refreshDeleted > 0 || resetDeleted > 0) {
            log.info(
                "Token cleanup removed {} refresh and {} password-reset tokens",
                refreshDeleted, resetDeleted,
            )
        }
    }

    private companion object {
        const val CRON_DAILY_0330 = "0 30 3 * * *"
        val GRACE_PERIOD: Duration = Duration.ofDays(7)
    }
}
