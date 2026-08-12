package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.hash.Hashing
import com.arunrk.notes.common.id.SecureTokens
import com.arunrk.notes.common.id.UuidV7
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.PasswordResetToken
import com.arunrk.notes.domain.policy.AuthPolicy
import com.arunrk.notes.domain.policy.EmailPolicy
import com.arunrk.notes.domain.policy.PasswordPolicy
import com.arunrk.notes.domain.port.EmailSender
import com.arunrk.notes.domain.port.PasswordHasher
import com.arunrk.notes.domain.port.PasswordResetTokenRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository
import java.util.UUID

/**
 * Starts a password reset.
 *
 * Always completes successfully, whether or not the address has an account.
 * Reporting "no such user" here would turn this endpoint into a free user
 * enumeration oracle, and it is unauthenticated.
 */
class RequestPasswordResetUseCase(
    private val users: UserRepository,
    private val resetTokens: PasswordResetTokenRepository,
    private val emailSender: EmailSender,
    private val transactor: Transactor,
    private val time: TimeProvider,
    private val policy: AuthPolicy,
) {

    fun execute(rawEmail: String) {
        val email = EmailPolicy.normalise(rawEmail)
        val user = users.findActiveByEmail(email) ?: return

        val now = time.now()
        val rawToken = SecureTokens.generate()
        val expiresAt = now.plus(policy.passwordResetTtl)

        transactor.inTransaction {
            // Issuing a new link invalidates older ones, so a leaked older email
            // stops working the moment the user asks again.
            resetTokens.invalidateAllForUser(user.id, now)
            resetTokens.save(
                PasswordResetToken(
                    id = UuidV7.generate(now.toEpochMilli()),
                    userId = user.id,
                    tokenHash = Hashing.sha256Hex(rawToken),
                    expiresAt = expiresAt,
                    createdAt = now,
                )
            )
        }

        emailSender.sendPasswordReset(
            to = user.email,
            name = user.name,
            resetToken = rawToken,
            expiresAt = expiresAt,
        )
    }
}

data class ResetPasswordCommand(
    val token: String,
    val newPassword: String,
)

class ResetPasswordUseCase(
    private val users: UserRepository,
    private val resetTokens: PasswordResetTokenRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {

    fun execute(command: ResetPasswordCommand) {
        PasswordPolicy.validate(command.newPassword, field = "newPassword")

        transactor.inTransaction {
            val now = time.now()
            val stored = resetTokens.findByTokenHash(Hashing.sha256Hex(command.token))
                ?: throw invalidToken()

            if (!stored.isUsable(now)) throw invalidToken()

            val user = users.findById(stored.userId)?.takeIf { it.isActive } ?: throw invalidToken()

            users.updatePasswordHash(user.id, passwordHasher.hash(command.newPassword), now)
            resetTokens.markUsed(stored.id, now)

            // A reset is the response to a suspected compromise, so every
            // existing session dies - including any the attacker holds.
            refreshTokens.revokeAllForUser(user.id, now)
        }
    }

    private fun invalidToken() =
        AppException(ErrorCode.TOKEN_INVALID, "This password reset link is invalid or has expired")
}

data class ChangePasswordCommand(
    val userId: UUID,
    val currentPassword: String,
    val newPassword: String,
    /**
     * The caller's own refresh token, so its session survives while every other
     * device is signed out. Omitted means "sign out everywhere, including here".
     */
    val keepRefreshToken: String? = null,
)

class ChangePasswordUseCase(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {

    fun execute(command: ChangePasswordCommand) {
        PasswordPolicy.validate(command.newPassword, field = "newPassword")

        val user = users.findById(command.userId)?.takeIf { it.isActive }
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "User not found")

        // Knowing the current password is what authorises the change; an access
        // token alone is not enough, since a stolen one must not let an attacker
        // lock the owner out.
        if (!passwordHasher.matches(command.currentPassword, user.passwordHash)) {
            throw AppException(
                ErrorCode.INVALID_CREDENTIALS,
                "Current password is incorrect",
            )
        }

        if (command.currentPassword == command.newPassword) {
            throw AppException(
                ErrorCode.VALIDATION_ERROR,
                "New password must differ from the current password",
            )
        }

        transactor.inTransaction {
            val now = time.now()
            users.updatePasswordHash(user.id, passwordHasher.hash(command.newPassword), now)

            val keepId = command.keepRefreshToken
                ?.let { refreshTokens.findByTokenHash(Hashing.sha256Hex(it)) }
                ?.takeIf { it.userId == user.id }
                ?.id

            if (keepId != null) {
                refreshTokens.revokeAllForUserExcept(user.id, keepId, now)
            } else {
                refreshTokens.revokeAllForUser(user.id, now)
            }
        }
    }
}
