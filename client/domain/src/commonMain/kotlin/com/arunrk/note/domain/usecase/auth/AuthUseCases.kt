package com.arunrk.note.domain.usecase.auth

import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.User
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.validation.CredentialValidation
import kotlinx.coroutines.flow.StateFlow

/**
 * Use cases are thin here on purpose. They exist to give the presentation layer
 * a single verb per action and to hold the validation that must not live in a
 * ViewModel - not to add ceremony around a repository call.
 */

class ObserveAuthStateUseCase(private val repository: AuthRepository) {
    operator fun invoke(): StateFlow<AuthState> = repository.authState
}

class RestoreSessionUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(): AuthState = repository.restoreSession()
}

class LoginUseCase(private val repository: AuthRepository) {

    suspend operator fun invoke(email: String, password: String): Outcome<User> {
        CredentialValidation.emailError(email)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("email" to it)))
        }
        CredentialValidation.loginPasswordError(password)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("password" to it)))
        }
        return repository.login(email.trim(), password)
    }
}

class RegisterUseCase(private val repository: AuthRepository) {

    suspend operator fun invoke(
        name: String,
        email: String,
        password: String,
        confirmPassword: String,
    ): Outcome<User> {
        CredentialValidation.nameError(name)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("name" to it)))
        }
        CredentialValidation.emailError(email)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("email" to it)))
        }
        CredentialValidation.newPasswordError(password)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("password" to it)))
        }
        CredentialValidation.confirmPasswordError(password, confirmPassword)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("confirmPassword" to it)))
        }
        return repository.register(name.trim(), email.trim(), password)
    }
}

class LogoutUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(): Outcome<Unit> = repository.logout()
}

class RequestPasswordResetUseCase(private val repository: AuthRepository) {

    suspend operator fun invoke(email: String): Outcome<Unit> {
        CredentialValidation.emailError(email)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("email" to it)))
        }
        return repository.requestPasswordReset(email.trim())
    }
}

class ChangePasswordUseCase(private val repository: AuthRepository) {

    suspend operator fun invoke(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
    ): Outcome<Unit> {
        if (currentPassword.isEmpty()) {
            val message = "Enter your current password"
            return Outcome.Failure(AppError.Validation(message, mapOf("currentPassword" to message)))
        }
        CredentialValidation.newPasswordError(newPassword)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("newPassword" to it)))
        }
        CredentialValidation.confirmPasswordError(newPassword, confirmPassword)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("confirmPassword" to it)))
        }
        if (currentPassword == newPassword) {
            val message = "Choose a password different from your current one"
            return Outcome.Failure(AppError.Validation(message, mapOf("newPassword" to message)))
        }
        return repository.changePassword(currentPassword, newPassword)
    }
}

class UpdateProfileUseCase(private val repository: AuthRepository) {

    suspend operator fun invoke(name: String): Outcome<User> {
        CredentialValidation.nameError(name)?.let {
            return Outcome.Failure(AppError.Validation(it, mapOf("name" to it)))
        }
        return repository.updateProfile(name.trim())
    }
}
