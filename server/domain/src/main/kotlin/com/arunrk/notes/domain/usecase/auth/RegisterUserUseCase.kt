package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.id.UuidV7
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.AuthenticatedSession
import com.arunrk.notes.domain.model.Device
import com.arunrk.notes.domain.model.User
import com.arunrk.notes.domain.policy.EmailPolicy
import com.arunrk.notes.domain.policy.NamePolicy
import com.arunrk.notes.domain.policy.PasswordPolicy
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.PasswordHasher
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository

data class RegisterCommand(
    val name: String,
    val email: String,
    val password: String,
    val context: SessionContext,
)

class RegisterUserUseCase(
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val passwordHasher: PasswordHasher,
    private val sessionIssuer: SessionIssuer,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {

    fun execute(command: RegisterCommand): AuthenticatedSession {
        NamePolicy.validate(command.name)
        EmailPolicy.validate(command.email)
        PasswordPolicy.validate(command.password)

        val email = EmailPolicy.normalise(command.email)
        val name = command.name.trim()

        return transactor.inTransaction {
            // Checked up front for a clean error message. The partial unique
            // index on users(email) is what actually guarantees uniqueness under
            // concurrent registration; the persistence adapter translates that
            // constraint violation into the same error code.
            if (users.existsActiveByEmail(email)) {
                throw AppException(
                    ErrorCode.EMAIL_ALREADY_EXISTS,
                    "An account with this email already exists",
                )
            }

            val now = time.now()
            val user = users.save(
                User(
                    id = UuidV7.generate(now.toEpochMilli()),
                    email = email,
                    name = name,
                    passwordHash = passwordHasher.hash(command.password),
                    createdAt = now,
                    updatedAt = now,
                )
            )

            registerDevice(user.id, command.context, now)

            AuthenticatedSession(
                user = user,
                tokens = sessionIssuer.issueNewSession(user, command.context),
            )
        }
    }

    private fun registerDevice(
        userId: java.util.UUID,
        context: SessionContext,
        now: java.time.Instant,
    ) {
        val deviceId = context.deviceId ?: return
        devices.upsert(
            Device(
                id = deviceId,
                userId = userId,
                platform = context.platform,
                lastSeenAt = now,
                createdAt = now,
            )
        )
    }
}
