package com.arunrk.note.core.designsystem.error

import com.arunrk.note.core.common.error.AppError

/**
 * Turns an [AppError] into something worth showing a person.
 *
 * Two rules: never surface a raw server message for a 5xx (it can contain SQL
 * or stack traces), and always say what the user can do next. "Something went
 * wrong" with no suggested action is barely better than silence.
 */
fun AppError.toUserMessage(): String = when (this) {
    AppError.Offline ->
        "You're offline. Your changes are saved on this device and will sync automatically."

    AppError.Timeout ->
        "The server took too long to respond. Check your connection and try again."

    AppError.Unauthenticated ->
        "Your session has expired. Please sign in again."

    is AppError.InvalidCredentials -> message

    is AppError.Validation -> message

    is AppError.Conflict -> message

    is AppError.NotFound -> message

    is AppError.RateLimited -> message

    // Deliberately generic: the server's message may contain internals.
    is AppError.Server -> "Something went wrong on our end. Please try again in a moment."

    is AppError.Storage ->
        "Couldn't save to this device. Check that you have free storage space."

    is AppError.Unknown -> "Something went wrong. Please try again."
}

/**
 * Per-field message for a form, or null when the error is not field-specific.
 */
fun AppError.fieldMessage(field: String): String? =
    (this as? AppError.Validation)?.fieldErrors?.get(field)
