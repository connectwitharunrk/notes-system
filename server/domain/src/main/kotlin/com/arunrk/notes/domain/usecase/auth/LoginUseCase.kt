package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.AuthenticatedSession
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.policy.EmailPolicy
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.PasswordHasher
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository

data class LoginCommand(
    val email: String,
    val password: String,
    val context: SessionContext,
)

class LoginUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val passwordHasher: PasswordHasher,
    private val sessionIssuer: SessionIssuer,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {

    fun execute(command: LoginCommand): AuthenticatedSession {
        val email = EmailPolicy.normalise(command.email)
        val user = users.findActiveByEmail(email)

        if (user == null) {
            // Do NOT return early. An unknown email must cost the same wall
            // time as a wrong password, otherwise response latency tells an
            // attacker which addresses have accounts.
            passwordHasher.matchesDummy(command.password)
            throw invalidCredentials()
        }

        if (!passwordHasher.matches(command.password, user.passwordHash)) {
            throw invalidCredentials()
        }

        return transactor.inTransaction {
            val now = time.now()
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
                tokens = sessionIssuer.issueNewSession(user, command.context),
            )
        }
    }

    // One message for both branches - "no such user" and "wrong password" must
    // be indistinguishable to the caller.
    private fun invalidCredentials() =
        AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password")
}
