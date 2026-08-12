package com.arunrk.note.core.common.error

/**
 * Every failure the app can show a user, as a closed set.
 *
 * Modelled as data rather than exceptions so the presentation layer can decide
 * what to say without string-matching, and so an offline failure is obviously
 * different from a rejection - the first is temporary and retryable, the second
 * is not.
 */
sealed interface AppError {

    /** No usable connection. Not an error the user did anything to cause. */
    data object Offline : AppError

    /** Reached the server, but it took too long. */
    data object Timeout : AppError

    /** The session is gone and cannot be refreshed; the user must sign in again. */
    data object Unauthenticated : AppError

    data class InvalidCredentials(val message: String) : AppError

    data class Validation(
        val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : AppError

    data class Conflict(val code: String, val message: String) : AppError

    data class NotFound(val message: String) : AppError

    data class RateLimited(val message: String) : AppError

    /** The server failed. Retryable, but not by doing anything different. */
    data class Server(val code: String, val message: String) : AppError

    /** Local storage failed - disk full, corrupt database, permissions. */
    data class Storage(val message: String, val cause: Throwable? = null) : AppError

    data class Unknown(val message: String, val cause: Throwable? = null) : AppError

    /**
     * Whether retrying the same request could plausibly succeed. Drives the sync
     * engine's backoff: a validation failure retried forever is a hot loop.
     */
    val isRetryable: Boolean
        get() = when (this) {
            Offline, Timeout -> true
            is Server, is RateLimited -> true
            else -> false
        }
}

/**
 * Result type for operations that can fail in a way worth showing the user.
 *
 * Preferred over exceptions across layer boundaries: a use case's failure modes
 * should be visible in its signature, not discovered at runtime.
 */
sealed interface Outcome<out T> {

    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Success) action(value)
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Failure) action(error)
}

fun <T> T.asSuccess(): Outcome<T> = Outcome.Success(this)

fun AppError.asFailure(): Outcome<Nothing> = Outcome.Failure(this)
