package com.arunrk.notes.common.error

/**
 * Stable, machine-readable error identifiers.
 *
 * Clients branch on [name], never on the human-readable message, so these
 * values are part of the public API contract: rename one and you break every
 * deployed client. The HTTP status lives here rather than in the web layer so
 * that the mapping is defined once, next to the meaning.
 */
enum class ErrorCode(val httpStatus: Int) {
    // 400
    VALIDATION_ERROR(400),
    MALFORMED_REQUEST(400),

    // 401
    UNAUTHENTICATED(401),
    INVALID_CREDENTIALS(401),
    TOKEN_EXPIRED(401),
    TOKEN_INVALID(401),

    /**
     * A refresh token that was already rotated away was presented again. That
     * means it leaked, so the entire token family is revoked and every session
     * in it dies. Distinct from TOKEN_INVALID because it is a security event.
     */
    TOKEN_REUSE_DETECTED(401),

    // 403
    FORBIDDEN(403),

    // 404
    NOT_FOUND(404),
    USER_NOT_FOUND(404),
    NOTE_NOT_FOUND(404),

    // 409
    EMAIL_ALREADY_EXISTS(409),
    VERSION_CONFLICT(409),

    // 413
    PAYLOAD_TOO_LARGE(413),
    NOTE_TOO_LARGE(413),
    SYNC_BATCH_TOO_LARGE(413),

    // 429
    RATE_LIMITED(429),

    // 500
    INTERNAL_ERROR(500),
}
