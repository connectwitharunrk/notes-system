package com.arunrk.notes.infrastructure.security

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttles the unauthenticated endpoints.
 *
 * In-memory, so limits are per-instance: adequate for a single deployment and
 * honest about its scope. Horizontal scaling would need a shared backend
 * (bucket4j-redis) - the interface here would not change.
 */
@Component
class RateLimiter(
    private val properties: RateLimitProperties,
) {

    private val loginBuckets = ConcurrentHashMap<String, Bucket>()
    private val forgotPasswordBuckets = ConcurrentHashMap<String, Bucket>()

    fun checkLogin(key: String) = consumeOrThrow(
        buckets = loginBuckets,
        key = key,
        capacity = properties.loginAttemptsPerMinute,
        window = Duration.ofMinutes(1),
        message = "Too many login attempts. Please try again in a minute.",
    )

    fun checkForgotPassword(key: String) = consumeOrThrow(
        buckets = forgotPasswordBuckets,
        key = key,
        capacity = properties.forgotPasswordPerHour,
        window = Duration.ofHours(1),
        message = "Too many password reset requests. Please try again later.",
    )

    private fun consumeOrThrow(
        buckets: ConcurrentHashMap<String, Bucket>,
        key: String,
        capacity: Long,
        window: Duration,
        message: String,
    ) {
        val bucket = buckets.computeIfAbsent(key) {
            Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build()
        }
        if (!bucket.tryConsume(1)) {
            throw AppException(ErrorCode.RATE_LIMITED, message)
        }
    }

    /**
     * Clears accumulated state. Without this the maps grow unbounded across a
     * long uptime, since every distinct email or IP creates an entry.
     */
    fun evictIdle() {
        loginBuckets.clear()
        forgotPasswordBuckets.clear()
    }
}
