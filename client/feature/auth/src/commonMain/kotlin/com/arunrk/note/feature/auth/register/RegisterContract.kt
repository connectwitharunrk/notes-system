package com.arunrk.note.feature.auth.register

import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.domain.validation.CredentialValidation

data class RegisterState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
) : UiState {

    val canSubmit: Boolean
        get() = name.isNotBlank() && email.isNotBlank() &&
            password.isNotBlank() && confirmPassword.isNotBlank() && !isSubmitting

    /**
     * Live requirements list, shown under the password field while typing.
     *
     * Requirements are displayed up front rather than revealed one rejection at
     * a time - being told "needs a number" only after fixing "too short" is the
     * most annoying way to design a password field.
     */
    val passwordRequirements: List<PasswordRequirement>
        get() = listOf(
            PasswordRequirement(
                "At least ${CredentialValidation.PASSWORD_MIN_LENGTH} characters",
                password.length >= CredentialValidation.PASSWORD_MIN_LENGTH,
            ),
            PasswordRequirement("Contains a letter", password.any { it.isLetter() }),
            PasswordRequirement("Contains a number", password.any { it.isDigit() }),
        )
}

data class PasswordRequirement(val label: String, val satisfied: Boolean)

sealed interface RegisterIntent : UiIntent {
    data class NameChanged(val value: String) : RegisterIntent
    data class EmailChanged(val value: String) : RegisterIntent
    data class PasswordChanged(val value: String) : RegisterIntent
    data class ConfirmPasswordChanged(val value: String) : RegisterIntent
    data object Submit : RegisterIntent
    data object SignInClicked : RegisterIntent
}

sealed interface RegisterEffect : UiEffect {
    data object NavigateToNotes : RegisterEffect
    data object NavigateToLogin : RegisterEffect
}
