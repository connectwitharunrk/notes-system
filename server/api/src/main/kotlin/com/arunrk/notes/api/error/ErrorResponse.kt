package com.arunrk.notes.api.error

import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.error.FieldViolation
import java.time.Instant
import java.util.UUID

/**
 * The single error shape every failing endpoint returns.
 *
 * Wrapped in an `error` object rather than returned flat so a client can tell a
 * failure from a success by structure alone, without inspecting the status code
 * first.
 */
data class ErrorResponse(
    val error: ErrorBody,
) {
    companion object {
        fun of(
            code: ErrorCode,
            message: String,
            details: List<FieldViolation> = emptyList(),
            traceId: String = UUID.randomUUID().toString(),
            timestamp: Instant = Instant.now(),
        ) = ErrorResponse(
            ErrorBody(
                code = code.name,
                message = message,
                details = details.map { FieldErrorDto(it.field, it.message) },
                traceId = traceId,
                timestamp = timestamp,
            )
        )
    }
}

data class ErrorBody(
    val code: String,
    val message: String,
    val details: List<FieldErrorDto>,
    val traceId: String,
    val timestamp: Instant,
)

data class FieldErrorDto(
    val field: String,
    val message: String,
)
