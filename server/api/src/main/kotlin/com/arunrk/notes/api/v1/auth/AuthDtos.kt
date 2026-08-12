package com.arunrk.notes.api.v1.auth

import com.arunrk.notes.domain.model.AuthTokens
import com.arunrk.notes.domain.model.AuthenticatedSession
import com.arunrk.notes.domain.model.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Bean Validation here is a cheap structural gate that produces good field-level
 * error messages. It does not replace the domain policies - those run again in
 * the use case, because the domain must not trust any particular adapter.
 */

data class RegisterRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 100, message = "must be at most 100 characters")
    val name: String,

    @field:NotBlank(message = "must not be blank")
    @field:Email(message = "must be a well-formed email address")
    @field:Size(max = 254, message = "must be at most 254 characters")
    val email: String,

    @field:NotBlank(message = "must not be blank")
    @field:Size(min = 8, max = 128, message = "must be between 8 and 128 characters")
    val password: String,
)

data class LoginRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Email(message = "must be a well-formed email address")
    val email: String,

    @field:NotBlank(message = "must not be blank")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "must not be blank")
    val refreshToken: String,
)

/** Refresh token is optional so a client that already lost it can still log out. */
data class LogoutRequest(
    val refreshToken: String? = null,
)

data class ForgotPasswordRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Email(message = "must be a well-formed email address")
    val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "must not be blank")
    val token: String,

    @field:NotBlank(message = "must not be blank")
    @field:Size(min = 8, max = 128, message = "must be between 8 and 128 characters")
    val newPassword: String,
)

data class ChangePasswordRequest(
    @field:NotBlank(message = "must not be blank")
    val currentPassword: String,

    @field:NotBlank(message = "must not be blank")
    @field:Size(min = 8, max = 128, message = "must be between 8 and 128 characters")
    val newPassword: String,

    /**
     * The caller's current refresh token. Supplying it keeps this session alive
     * while every other device is signed out; omitting it signs out everywhere.
     */
    val refreshToken: String? = null,
)

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------

data class UserDto(
    val id: UUID,
    val email: String,
    val name: String,
    val emailVerified: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(user: User) = UserDto(
            id = user.id,
            email = user.email,
            name = user.name,
            emailVerified = user.emailVerified,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
        )
    }
}

data class TokensDto(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
) {
    companion object {
        fun from(tokens: AuthTokens) = TokensDto(
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
        )
    }
}

data class AuthResponse(
    val user: UserDto,
    val tokens: TokensDto,
) {
    companion object {
        fun from(session: AuthenticatedSession) = AuthResponse(
            user = UserDto.from(session.user),
            tokens = TokensDto.from(session.tokens),
        )
    }
}
