package com.arunrk.notes.api.error

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.error.FieldViolation
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException

/**
 * The one place that turns an exception into an HTTP response.
 *
 * Keeping this exhaustive is what stops Spring's default error page from
 * leaking stack traces or internal messages to clients.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AppException::class)
    fun handleAppException(e: AppException): ResponseEntity<ErrorResponse> {
        // 5xx is our bug; 4xx is the caller's. Log accordingly so real problems
        // are not buried under routine validation noise.
        if (e.code.httpStatus >= 500) {
            log.error("Unhandled application error: {}", e.message, e)
        } else {
            log.debug("Request rejected: {} - {}", e.code, e.message)
        }
        return respond(e.code, e.message, e.details)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.map {
            FieldViolation(it.field, it.defaultMessage ?: "is invalid")
        }
        return respond(ErrorCode.VALIDATION_ERROR, "Request validation failed", details)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val details = e.constraintViolations.map {
            FieldViolation(it.propertyPath.toString(), it.message)
        }
        return respond(ErrorCode.VALIDATION_ERROR, "Request validation failed", details)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        // The underlying Jackson message can echo payload fragments, so it is
        // logged rather than returned.
        log.debug("Malformed request body", e)
        return respond(ErrorCode.MALFORMED_REQUEST, "Request body is malformed or missing")
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ErrorResponse> =
        respond(
            ErrorCode.VALIDATION_ERROR,
            "Required header is missing",
            listOf(FieldViolation(e.headerName, "is required")),
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(
            ErrorCode.VALIDATION_ERROR,
            "Request parameter has the wrong type",
            listOf(FieldViolation(e.name, "is not a valid value")),
        )

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.FORBIDDEN, "You do not have access to this resource")

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandler(e: NoHandlerFoundException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.NOT_FOUND, "No such endpoint")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", e)
        // Deliberately generic: e.message may contain SQL, file paths or other
        // internals that must not reach a client.
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred")
    }

    private fun respond(
        code: ErrorCode,
        message: String,
        details: List<FieldViolation> = emptyList(),
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.valueOf(code.httpStatus))
            .body(ErrorResponse.of(code, message, details))
}
