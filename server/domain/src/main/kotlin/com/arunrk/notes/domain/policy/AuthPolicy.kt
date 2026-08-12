package com.arunrk.notes.domain.policy

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.error.FieldViolation
import com.arunrk.notes.domain.model.DevicePlatform
import java.time.Duration

/**
 * Authentication tuning, supplied by configuration rather than hard-coded, so
 * the domain has no opinion on where the values come from.
 */
data class AuthPolicy(
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    /**
     * Desktop refresh tokens live far shorter than mobile ones. There is no OS
     * keychain in pure JVM, so a stolen desktop session file is readable by any
     * local process running as the user - shortening the window is the only
     * real mitigation available. See docs/ARCHITECTURE.md L1.
     */
    val desktopRefreshTokenTtl: Duration,
    val passwordResetTtl: Duration,
) {
    fun refreshTtlFor(platform: DevicePlatform): Duration =
        if (platform == DevicePlatform.DESKTOP) desktopRefreshTokenTtl else refreshTokenTtl
}

/**
 * Password rules, enforced in the domain so they hold regardless of which
 * adapter the request arrived through.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    fun validate(password: String, field: String = "password") {
        val violations = buildList {
            if (password.length < MIN_LENGTH) {
                add(FieldViolation(field, "must be at least $MIN_LENGTH characters"))
            }
            if (password.length > MAX_LENGTH) {
                // Bounded because BCrypt silently truncates long inputs and
                // because unbounded input is a cheap DoS against a cost-12 hash.
                add(FieldViolation(field, "must be at most $MAX_LENGTH characters"))
            }
            if (password.none { it.isDigit() }) {
                add(FieldViolation(field, "must contain at least one digit"))
            }
            if (password.none { it.isLetter() }) {
                add(FieldViolation(field, "must contain at least one letter"))
            }
        }
        if (violations.isNotEmpty()) {
            throw AppException(ErrorCode.VALIDATION_ERROR, "Password does not meet requirements", violations)
        }
    }
}

object EmailPolicy {

    const val MAX_LENGTH = 254

    // Deliberately permissive. Strict RFC 5322 regexes reject valid addresses
    // and give a false sense of validation; deliverability is proven by sending
    // mail, not by a pattern.
    private val PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    fun normalise(email: String): String = email.trim().lowercase()

    fun validate(email: String, field: String = "email") {
        val normalised = normalise(email)
        if (normalised.length > MAX_LENGTH || !PATTERN.matches(normalised)) {
            throw AppException(
                ErrorCode.VALIDATION_ERROR,
                "Invalid email address",
                listOf(FieldViolation(field, "must be a well-formed email address")),
            )
        }
    }
}

object NamePolicy {

    const val MAX_LENGTH = 100

    fun validate(name: String, field: String = "name") {
        val trimmed = name.trim()
        val violations = buildList {
            if (trimmed.isEmpty()) add(FieldViolation(field, "must not be blank"))
            if (trimmed.length > MAX_LENGTH) add(FieldViolation(field, "must be at most $MAX_LENGTH characters"))
        }
        if (violations.isNotEmpty()) {
            throw AppException(ErrorCode.VALIDATION_ERROR, "Invalid name", violations)
        }
    }
}
