package com.arunrk.note.core.network.dto

import kotlinx.serialization.Serializable

/**
 * Wire models mirroring the backend contract in docs/ARCHITECTURE.md section 8.
 *
 * Timestamps are carried as ISO-8601 strings rather than a date type, so that
 * serialization does not depend on which experimental Instant API the current
 * Kotlin and kotlinx-datetime versions agree on. Conversion to epoch
 * milliseconds - the client's internal representation - happens in one place,
 * the mappers.
 */

@Serializable
data class RegisterRequestDto(
    val name: String,
    val email: String,
    val password: String,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestDto(
    val refreshToken: String? = null,
)

@Serializable
data class ForgotPasswordRequestDto(
    val email: String,
)

@Serializable
data class ResetPasswordRequestDto(
    val token: String,
    val newPassword: String,
)

@Serializable
data class ChangePasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
    /** Supplying this keeps the caller's session alive while others are revoked. */
    val refreshToken: String? = null,
)

@Serializable
data class UpdateProfileRequestDto(
    val name: String,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val emailVerified: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TokensDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
)

@Serializable
data class AuthResponseDto(
    val user: UserDto,
    val tokens: TokensDto,
)
