package com.arunrk.notes.common.error

/**
 * A field-level validation failure, surfaced in `error.details`.
 */
data class FieldViolation(
    val field: String,
    val message: String,
)

/**
 * The single exception type crossing layer boundaries.
 *
 * Domain code throws this; the web layer's exception handler is the only place
 * that knows how to turn it into a response. Nothing below the API layer should
 * ever import a Spring or servlet type to report a failure.
 */
open class AppException(
    val code: ErrorCode,
    override val message: String,
    val details: List<FieldViolation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        fun validation(message: String, details: List<FieldViolation> = emptyList()) =
            AppException(ErrorCode.VALIDATION_ERROR, message, details)

        fun notFound(what: String, id: Any? = null) =
            AppException(
                ErrorCode.NOT_FOUND,
                if (id == null) "$what not found" else "$what not found: $id",
            )

        fun forbidden(message: String = "You do not have access to this resource") =
            AppException(ErrorCode.FORBIDDEN, message)

        fun unauthenticated(message: String = "Authentication required") =
            AppException(ErrorCode.UNAUTHENTICATED, message)

        fun conflict(code: ErrorCode, message: String) = AppException(code, message)
    }
}
