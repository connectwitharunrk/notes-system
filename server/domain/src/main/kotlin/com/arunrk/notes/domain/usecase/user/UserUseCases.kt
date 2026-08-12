package com.arunrk.notes.domain.usecase.user

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.User
import com.arunrk.notes.domain.policy.NamePolicy
import com.arunrk.notes.domain.port.UserRepository
import java.util.UUID

class GetCurrentUserUseCase(
    private val users: UserRepository,
) {
    fun execute(userId: UUID): User =
        users.findById(userId)?.takeIf { it.isActive }
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "User not found")
}

data class UpdateProfileCommand(
    val userId: UUID,
    val name: String,
)

/**
 * Updates mutable profile fields.
 *
 * Email is deliberately not changeable here: it is the login identity and the
 * password-reset destination, so changing it needs a verification round trip
 * that does not exist yet. Better to omit the capability than to ship one that
 * can lock a user out of their own account.
 */
class UpdateProfileUseCase(
    private val users: UserRepository,
    private val time: TimeProvider,
) {
    fun execute(command: UpdateProfileCommand): User {
        NamePolicy.validate(command.name)
        users.findById(command.userId)?.takeIf { it.isActive }
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "User not found")

        return users.updateProfile(command.userId, command.name.trim(), time.now())
    }
}
