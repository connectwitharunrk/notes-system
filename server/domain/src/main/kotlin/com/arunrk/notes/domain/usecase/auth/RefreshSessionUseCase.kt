package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.hash.Hashing
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.AuthenticatedSession
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.RefreshToken
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository
import java.time.Instant

data class RefreshCommand(
    val refreshToken: String,
    val context: SessionContext,
)

/**
 * Rotating refresh with reuse detection.
 *
 * Each refresh consumes its token and issues a successor in the same family.
 * A token that has already been rotated away can only be presented by someone
 * who kept a copy - i.e. it leaked - so the entire family is revoked, logging
 * out both the attacker and the victim's device. The victim re-authenticates;
 * the attacker cannot.
 */
class RefreshSessionUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val sessionIssuer: SessionIssuer,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {

    fun execute(command: RefreshCommand): AuthenticatedSession {
        val presentedHash = Hashing.sha256Hex(command.refreshToken)
        val stored = refreshTokens.findByTokenHash(presentedHash)
            ?: throw AppException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")

        val now = time.now()

        if (stored.isRevoked()) {
            // The revocation gets its own committed transaction, deliberately.
            // Run inside the same transaction as the throw below and the
            // rollback would silently undo it - the endpoint would report a
            // breach while leaving the leaked family fully usable.
            transactor.inTransaction { refreshTokens.revokeFamily(stored.familyId, now) }
            throw AppException(
                ErrorCode.TOKEN_REUSE_DETECTED,
                "Refresh token reuse detected. All sessions in this family have been revoked.",
            )
        }

        if (stored.isExpired(now)) {
            throw AppException(ErrorCode.TOKEN_EXPIRED, "Refresh token has expired")
        }

        return rotateWithin(stored, command, now)
    }

    private fun rotateWithin(
        stored: RefreshToken,
        command: RefreshCommand,
        now: Instant,
    ): AuthenticatedSession = transactor.inTransaction {
        val user = users.findById(stored.userId)
            ?.takeIf { it.isActive }
            ?: throw AppException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")

        // The new refresh token references this device by foreign key, so the
        // device row has to exist before it is written. A client can legitimately
        // present a device id the server has never stored - a reinstall, a
        // restored backup, or a device the user removed from their session list -
        // and refreshing must keep working in all of those cases rather than
        // failing with a constraint violation. Doubles as the last-seen update.
        command.context.deviceId?.let { deviceId ->
            devices.upsert(
                Device(
                    id = deviceId,
                    userId = user.id,
                    platform = command.context.platform,
                    lastSeenAt = now,
                    createdAt = now,
                )
            )
        }

        AuthenticatedSession(
            user = user,
            tokens = sessionIssuer.rotate(user, stored, command.context),
        )
    }
}
