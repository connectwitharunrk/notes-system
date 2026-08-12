package com.arunrk.note.feature.auth.register

import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.domain.usecase.auth.RegisterUseCase
import kotlinx.coroutines.launch

class RegisterViewModel(
    private val register: RegisterUseCase,
) : MviViewModel<RegisterIntent, RegisterState, RegisterEffect>(RegisterState()) {

    override fun handleIntent(intent: RegisterIntent) {
        when (intent) {
            is RegisterIntent.NameChanged -> setState {
                copy(name = intent.value, nameError = null, formError = null)
            }

            is RegisterIntent.EmailChanged -> setState {
                copy(email = intent.value, emailError = null, formError = null)
            }

            is RegisterIntent.PasswordChanged -> setState {
                copy(
                    password = intent.value,
                    passwordError = null,
                    // Re-check the confirmation against the new password, so the
                    // mismatch warning disappears as soon as they agree again.
                    confirmPasswordError = confirmPasswordError?.takeIf {
                        confirmPassword.isNotEmpty() && confirmPassword != intent.value
                    },
                    formError = null,
                )
            }

            is RegisterIntent.ConfirmPasswordChanged -> setState {
                copy(confirmPassword = intent.value, confirmPasswordError = null, formError = null)
            }

            RegisterIntent.Submit -> submit()
            RegisterIntent.SignInClicked -> sendEffect(RegisterEffect.NavigateToLogin)
        }
    }

    private fun submit() {
        if (currentState.isSubmitting) return

        setState {
            copy(
                isSubmitting = true,
                nameError = null,
                emailError = null,
                passwordError = null,
                confirmPasswordError = null,
                formError = null,
            )
        }

        viewModelScope.launch {
            val snapshot = currentState
            val result = register(
                name = snapshot.name,
                email = snapshot.email,
                password = snapshot.password,
                confirmPassword = snapshot.confirmPassword,
            )

            when (result) {
                is Outcome.Success -> sendEffect(RegisterEffect.NavigateToNotes)

                is Outcome.Failure -> setState {
                    copy(
                        isSubmitting = false,
                        nameError = result.error.fieldError("name"),
                        emailError = result.error.fieldError("email"),
                        passwordError = result.error.fieldError("password"),
                        confirmPasswordError = result.error.fieldError("confirmPassword"),
                        formError = result.error.formError(),
                    )
                }
            }
        }
    }

    private fun AppError.fieldError(field: String): String? =
        (this as? AppError.Validation)?.fieldErrors?.get(field)

    private fun AppError.formError(): String? = when {
        this is AppError.Validation && fieldErrors.isNotEmpty() -> null
        // The server returns 409 for a duplicate address; phrase it as guidance
        // rather than an accusation.
        this is AppError.Conflict && code == "EMAIL_ALREADY_EXISTS" ->
            "An account already exists for this email. Try signing in instead."
        else -> toUserMessage()
    }
}
