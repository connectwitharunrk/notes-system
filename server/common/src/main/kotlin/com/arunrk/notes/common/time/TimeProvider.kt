package com.arunrk.notes.common.time

import java.time.Instant

/**
 * The server's authoritative clock.
 *
 * Injected rather than called statically so tests can freeze or advance time -
 * token expiry, tombstone retention and rate limiting are all time-dependent
 * and untestable against a real `Instant.now()`.
 */
fun interface TimeProvider {
    fun now(): Instant
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}

/**
 * Test double. Not test-only code by accident - it lives here so every module
 * can use it without a test-fixtures dependency.
 */
class MutableTimeProvider(private var current: Instant) : TimeProvider {
    override fun now(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(millis: Long) {
        current = current.plusMillis(millis)
    }
}
