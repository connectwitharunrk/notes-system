package com.arunrk.notes.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "notes.auth")
data class AuthProperties(
    /**
     * HMAC signing key. Must be at least 32 bytes for HS256 - validated at
     * startup rather than on first login, so a misconfigured deployment fails
     * immediately instead of at 3am.
     */
    val jwtSecret: String,
    val jwtIssuer: String = "notes-system",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(60),
    val desktopRefreshTokenTtl: Duration = Duration.ofDays(7),
    val passwordResetTtl: Duration = Duration.ofMinutes(30),
    val bcryptStrength: Int = 12,
)

@ConfigurationProperties(prefix = "notes.rate-limit")
data class RateLimitProperties(
    val loginAttemptsPerMinute: Long = 5,
    val forgotPasswordPerHour: Long = 3,
)
