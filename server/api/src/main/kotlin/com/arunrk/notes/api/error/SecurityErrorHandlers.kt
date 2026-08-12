package com.arunrk.notes.api.error

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.infrastructure.security.JwtAuthenticationFilter
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * Rejections raised inside the Spring Security filter chain never reach
 * @RestControllerAdvice, so without these two handlers the API would return
 * Spring's default HTML error page for exactly the responses clients most need
 * to parse.
 */

@Component
class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // The filter stashes the real reason (expired vs malformed) so the
        // client can tell "refresh your token" from "you are signed out".
        val cause = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR) as? AppException

        val code = cause?.code ?: ErrorCode.UNAUTHENTICATED
        val message = cause?.message ?: "Authentication required"

        response.status = code.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, ErrorResponse.of(code, message))
    }
}

@Component
class RestAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.status = ErrorCode.FORBIDDEN.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse.of(ErrorCode.FORBIDDEN, "You do not have access to this resource"),
        )
    }
}
