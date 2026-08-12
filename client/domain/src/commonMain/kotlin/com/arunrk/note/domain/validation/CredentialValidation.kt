package com.arunrk.note.domain.validation

/**
 * Client-side credential checks.
 *
 * These exist to give immediate, specific feedback while typing - not to be
 * trusted. The server enforces the same rules independently, because a client
 * check is only a courtesy.
 *
 * Kept deliberately in step with the backend's `PasswordPolicy` and
 * `EmailPolicy`: rules that disagree produce the worst possible experience,
 * where the form says a password is fine and the server then rejects it.
 */
object CredentialValidation {

    const val PASSWORD_MIN_LENGTH = 8
    const val PASSWORD_MAX_LENGTH = 128
    const val NAME_MAX_LENGTH = 100
    const val EMAIL_MAX_LENGTH = 254

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    fun emailError(email: String): String? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> "Enter your email address"
            trimmed.length > EMAIL_MAX_LENGTH -> "That email address is too long"
            !EMAIL_PATTERN.matches(trimmed.lowercase()) -> "Enter a valid email address"
            else -> null
        }
    }

    fun nameError(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Enter your name"
            trimmed.length > NAME_MAX_LENGTH -> "That name is too long"
            else -> null
        }
    }

    /**
     * Used on the login screen, where the only useful check is "did you type
     * anything". Applying the strength rules here would reject a valid password
     * created before the rules changed, and tell the user their own password is
     * invalid rather than simply wrong.
     */
    fun loginPasswordError(password: String): String? =
        if (password.isEmpty()) "Enter your password" else null

    /** Used when choosing a new password, where the rules do apply. */
    fun newPasswordError(password: String): String? = when {
        password.isEmpty() -> "Enter a password"
        password.length < PASSWORD_MIN_LENGTH ->
            "Use at least $PASSWORD_MIN_LENGTH characters"
        password.length > PASSWORD_MAX_LENGTH ->
            "Use at most $PASSWORD_MAX_LENGTH characters"
        password.none { it.isDigit() } -> "Include at least one number"
        password.none { it.isLetter() } -> "Include at least one letter"
        else -> null
    }

    fun confirmPasswordError(password: String, confirmation: String): String? = when {
        confirmation.isEmpty() -> "Re-enter your password"
        password != confirmation -> "Passwords do not match"
        else -> null
    }
}
