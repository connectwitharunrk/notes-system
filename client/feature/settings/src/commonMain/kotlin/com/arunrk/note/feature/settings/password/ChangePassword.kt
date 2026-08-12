package com.arunrk.note.feature.settings.password

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.mvi.MviViewModel
import com.arunrk.note.core.common.mvi.UiEffect
import com.arunrk.note.core.common.mvi.UiIntent
import com.arunrk.note.core.common.mvi.UiState
import com.arunrk.note.core.designsystem.component.LoadingButton
import com.arunrk.note.core.designsystem.component.PasswordTextField
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.usecase.auth.ChangePasswordUseCase
import com.arunrk.note.feature.settings.component.FormErrorBanner
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class ChangePasswordState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val currentPasswordError: String? = null,
    val newPasswordError: String? = null,
    val confirmPasswordError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
) : UiState {
    val canSubmit: Boolean
        get() = currentPassword.isNotBlank() && newPassword.isNotBlank() &&
            confirmPassword.isNotBlank() && !isSubmitting
}

sealed interface ChangePasswordIntent : UiIntent {
    data class CurrentPasswordChanged(val value: String) : ChangePasswordIntent
    data class NewPasswordChanged(val value: String) : ChangePasswordIntent
    data class ConfirmPasswordChanged(val value: String) : ChangePasswordIntent
    data object Submit : ChangePasswordIntent
    data object BackClicked : ChangePasswordIntent
}

sealed interface ChangePasswordEffect : UiEffect {
    data object NavigateBack : ChangePasswordEffect
    data class ShowMessage(val message: String) : ChangePasswordEffect
}

class ChangePasswordViewModel(
    private val changePassword: ChangePasswordUseCase,
) : MviViewModel<ChangePasswordIntent, ChangePasswordState, ChangePasswordEffect>(
    ChangePasswordState(),
) {

    override fun handleIntent(intent: ChangePasswordIntent) {
        when (intent) {
            is ChangePasswordIntent.CurrentPasswordChanged -> setState {
                copy(currentPassword = intent.value, currentPasswordError = null, formError = null)
            }

            is ChangePasswordIntent.NewPasswordChanged -> setState {
                copy(newPassword = intent.value, newPasswordError = null, formError = null)
            }

            is ChangePasswordIntent.ConfirmPasswordChanged -> setState {
                copy(confirmPassword = intent.value, confirmPasswordError = null, formError = null)
            }

            ChangePasswordIntent.BackClicked -> sendEffect(ChangePasswordEffect.NavigateBack)

            ChangePasswordIntent.Submit -> viewModelScope.launch {
                if (currentState.isSubmitting) return@launch
                setState {
                    copy(
                        isSubmitting = true,
                        currentPasswordError = null,
                        newPasswordError = null,
                        confirmPasswordError = null,
                        formError = null,
                    )
                }

                val snapshot = currentState
                val result = changePassword(
                    currentPassword = snapshot.currentPassword,
                    newPassword = snapshot.newPassword,
                    confirmPassword = snapshot.confirmPassword,
                )

                when (result) {
                    is Outcome.Success -> {
                        sendEffect(
                            ChangePasswordEffect.ShowMessage(
                                "Password changed. Your other devices have been signed out.",
                            )
                        )
                        sendEffect(ChangePasswordEffect.NavigateBack)
                    }

                    is Outcome.Failure -> setState {
                        val validation = result.error as? AppError.Validation
                        copy(
                            isSubmitting = false,
                            currentPasswordError = validation?.fieldErrors?.get("currentPassword")
                                // The server reports a wrong current password as
                                // invalid credentials; show it on the field it
                                // belongs to rather than as a vague banner.
                                ?: (result.error as? AppError.InvalidCredentials)?.message,
                            newPasswordError = validation?.fieldErrors?.get("newPassword"),
                            confirmPasswordError = validation?.fieldErrors?.get("confirmPassword"),
                            formError = when (result.error) {
                                is AppError.Validation, is AppError.InvalidCredentials -> null
                                else -> result.error.toUserMessage()
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangePasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ChangePasswordEffect.NavigateBack -> onNavigateBack()
                is ChangePasswordEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Change password") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onIntent(ChangePasswordIntent.BackClicked) }) {
                        Icon(NoteIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeContentPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            state.formError?.let { FormErrorBanner(it) }

            PasswordTextField(
                value = state.currentPassword,
                onValueChange = {
                    viewModel.onIntent(ChangePasswordIntent.CurrentPasswordChanged(it))
                },
                label = "Current password",
                error = state.currentPasswordError,
                enabled = !state.isSubmitting,
            )

            PasswordTextField(
                value = state.newPassword,
                onValueChange = { viewModel.onIntent(ChangePasswordIntent.NewPasswordChanged(it)) },
                label = "New password",
                error = state.newPasswordError,
                enabled = !state.isSubmitting,
                supportingText = "At least 8 characters, with a letter and a number.",
            )

            PasswordTextField(
                value = state.confirmPassword,
                onValueChange = {
                    viewModel.onIntent(ChangePasswordIntent.ConfirmPasswordChanged(it))
                },
                label = "Confirm new password",
                error = state.confirmPasswordError,
                enabled = !state.isSubmitting,
                imeAction = ImeAction.Done,
            )

            // Stated before they commit, not discovered afterwards on another
            // device that has silently signed itself out.
            Text(
                text = "Changing your password signs out your other devices. " +
                    "This one stays signed in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LoadingButton(
                text = "Change password",
                onClick = { viewModel.onIntent(ChangePasswordIntent.Submit) },
                loading = state.isSubmitting,
                enabled = state.canSubmit,
            )
        }
    }
}
