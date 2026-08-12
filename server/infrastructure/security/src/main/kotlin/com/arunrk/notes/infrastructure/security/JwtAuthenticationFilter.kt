package com.arunrk.notes.infrastructure.security

import com.arunrk.notes.common.error.AppException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Populates the security context from a Bearer access token.
 *
 * A malformed or expired token is NOT rejected here - the filter simply leaves
 * the context anonymous and lets the authorisation rules produce a 401 through
 * the normal entry point. That keeps every error response shaped by
 * GlobalExceptionHandler instead of some going through the filter chain and
 * others through the controller advice.
 */
@Component
class JwtAuthenticationFilter(
    private val tokenIssuer: JwtAccessTokenIssuer,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)

        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            try {
                val principal = tokenIssuer.verify(token)
                val authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    emptyList(),
                )
                SecurityContextHolder.getContext().authentication = authentication
                request.setAttribute(ATTR_AUTH_ERROR, null)
            } catch (e: AppException) {
                // Remembered so the entry point can report *why* (expired vs
                // malformed) rather than a generic 401.
                SecurityContextHolder.clearContext()
                request.setAttribute(ATTR_AUTH_ERROR, e)
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HEADER_AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val ATTR_AUTH_ERROR = "notes.authError"
    }
}
