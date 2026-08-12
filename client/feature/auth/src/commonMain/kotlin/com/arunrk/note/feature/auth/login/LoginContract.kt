package com.arunrk.note.feature.auth.login

import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState

data class LoginState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    /** Not field-specific: wrong credentials, offline, rate limited. */
    val formError: String? = null,
    val isSubmitting: Boolean = false,
) : UiState {

    /**
     * Enabled whenever both fields have something in them, rather than only when
     * they pass validation. A button that stays greyed out with no explanation
     * is a dead end; letting the tap through surfaces the actual reason.
     */
    val canSubmit: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting
}

sealed interface LoginIntent : UiIntent {
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object Submit : LoginIntent
    data object RegisterClicked : LoginIntent
    data object ForgotPasswordClicked : LoginIntent
    data object DismissError : LoginIntent
}

sealed interface LoginEffect : UiEffect {
    data object NavigateToNotes : LoginEffect
    data object NavigateToRegister : LoginEffect
    data object NavigateToForgotPassword : LoginEffect
}
