package com.arunrk.note.feature.auth.login

import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.launch

class LoginViewModel(
    private val login: LoginUseCase,
) : MviViewModel<LoginIntent, LoginState, LoginEffect>(LoginState()) {

    override fun handleIntent(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.EmailChanged -> setState {
                // Clearing the error as soon as the user edits the field avoids
                // scolding them while they are already fixing it.
                copy(email = intent.value, emailError = null, formError = null)
            }

            is LoginIntent.PasswordChanged -> setState {
                copy(password = intent.value, passwordError = null, formError = null)
            }

            LoginIntent.Submit -> submit()
            LoginIntent.RegisterClicked -> sendEffect(LoginEffect.NavigateToRegister)
            LoginIntent.ForgotPasswordClicked -> sendEffect(LoginEffect.NavigateToForgotPassword)
            LoginIntent.DismissError -> setState { copy(formError = null) }
        }
    }

    private fun submit() {
        if (currentState.isSubmitting) return

        setState { copy(isSubmitting = true, emailError = null, passwordError = null, formError = null) }

        viewModelScope.launch {
            when (val result = login(currentState.email, currentState.password)) {
                is Outcome.Success -> {
                    // Deliberately no isSubmitting = false here: navigation is
                    // about to happen, and re-enabling the button first lets a
                    // fast double-tap fire a second login.
                    sendEffect(LoginEffect.NavigateToNotes)
                }

                is Outcome.Failure -> setState {
                    copy(
                        isSubmitting = false,
                        emailError = result.error.fieldError("email"),
                        passwordError = result.error.fieldError("password"),
                        formError = result.error.formError(),
                    )
                }
            }
        }
    }

    private fun AppError.fieldError(field: String): String? =
        (this as? AppError.Validation)?.fieldErrors?.get(field)

    /**
     * Field-level validation errors are shown on the field itself; everything
     * else - wrong credentials, offline, rate limited - goes to the form banner.
     */
    private fun AppError.formError(): String? =
        if (this is AppError.Validation && fieldErrors.isNotEmpty()) null else toUserMessage()
}
