package com.arunrk.note.feature.auth.forgotpassword

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.core.designsystem.component.LoadingButton
import com.arunrk.note.core.designsystem.component.NoteTextField
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.usecase.auth.RequestPasswordResetUseCase
import com.arunrk.note.feature.auth.component.AuthScaffold
import com.arunrk.note.feature.auth.component.FormErrorBanner
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

// ---------------------------------------------------------------------------
// Contract
// ---------------------------------------------------------------------------

data class ForgotPasswordState(
    val email: String = "",
    val emailError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    /** Set once the request is accepted; the form is replaced by confirmation. */
    val requestSent: Boolean = false,
) : UiState {
    val canSubmit: Boolean get() = email.isNotBlank() && !isSubmitting
}

sealed interface ForgotPasswordIntent : UiIntent {
    data class EmailChanged(val value: String) : ForgotPasswordIntent
    data object Submit : ForgotPasswordIntent
    data object BackToSignInClicked : ForgotPasswordIntent
}

sealed interface ForgotPasswordEffect : UiEffect {
    data object NavigateBack : ForgotPasswordEffect
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class ForgotPasswordViewModel(
    private val requestPasswordReset: RequestPasswordResetUseCase,
) : MviViewModel<ForgotPasswordIntent, ForgotPasswordState, ForgotPasswordEffect>(
    ForgotPasswordState(),
) {

    override fun handleIntent(intent: ForgotPasswordIntent) {
        when (intent) {
            is ForgotPasswordIntent.EmailChanged -> setState {
                copy(email = intent.value, emailError = null, formError = null)
            }

            ForgotPasswordIntent.Submit -> submit()

            ForgotPasswordIntent.BackToSignInClicked ->
                sendEffect(ForgotPasswordEffect.NavigateBack)
        }
    }

    private fun submit() {
        if (currentState.isSubmitting) return
        setState { copy(isSubmitting = true, emailError = null, formError = null) }

        viewModelScope.launch {
            when (val result = requestPasswordReset(currentState.email)) {
                is Outcome.Success -> setState { copy(isSubmitting = false, requestSent = true) }

                is Outcome.Failure -> setState {
                    copy(
                        isSubmitting = false,
                        emailError = (result.error as? AppError.Validation)?.fieldErrors?.get("email"),
                        formError = if (result.error is AppError.Validation) {
                            null
                        } else {
                            result.error.toUserMessage()
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun ForgotPasswordScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForgotPasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ForgotPasswordEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    ForgotPasswordContent(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

@Composable
fun ForgotPasswordContent(
    state: ForgotPasswordState,
    onIntent: (ForgotPasswordIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = if (state.requestSent) "Check your email" else "Reset your password",
        subtitle = if (state.requestSent) {
            "If an account exists for that address, we've sent a link to reset your password."
        } else {
            "Enter your email and we'll send you a link to set a new password."
        },
        modifier = modifier,
    ) {
        if (state.requestSent) {
            // Worded identically whether or not the address is registered. The
            // server behaves the same way for the same reason: saying "no such
            // account" would turn this into a free user-enumeration oracle.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "The link expires in 30 minutes. If it doesn't arrive, check your " +
                        "spam folder before requesting another.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(Spacing.md),
                )
            }

            TextButton(
                onClick = { onIntent(ForgotPasswordIntent.BackToSignInClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Back to sign in")
            }
            return@AuthScaffold
        }

        state.formError?.let { FormErrorBanner(it) }

        NoteTextField(
            value = state.email,
            onValueChange = { onIntent(ForgotPasswordIntent.EmailChanged(it)) },
            label = "Email",
            error = state.emailError,
            enabled = !state.isSubmitting,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        )

        LoadingButton(
            text = "Send reset link",
            onClick = { onIntent(ForgotPasswordIntent.Submit) },
            loading = state.isSubmitting,
            enabled = state.canSubmit,
        )

        TextButton(
            onClick = { onIntent(ForgotPasswordIntent.BackToSignInClicked) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
        ) {
            Text("Back to sign in")
        }
    }
}
