package com.arunrk.notes.infrastructure.security

import com.arunrk.notes.common.error.AppException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * The authenticated caller, derived solely from a verified access token.
 */
data class AuthPrincipal(
    val userId: UUID,
    val email: String,
)

/**
 * Reads the current principal from the security context.
 *
 * Throws rather than returning null: every call site sits behind an
 * authenticated route, so a missing principal is a wiring bug that should be
 * loud, not a condition to branch on.
 */
object CurrentUser {

    fun principal(): AuthPrincipal {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        return authentication?.principal as? AuthPrincipal
            ?: throw AppException.unauthenticated()
    }

    fun id(): UUID = principal().userId
}
