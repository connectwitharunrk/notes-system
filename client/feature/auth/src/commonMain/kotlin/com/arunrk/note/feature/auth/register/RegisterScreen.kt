package com.arunrk.note.feature.auth.register

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.core.designsystem.component.LoadingButton
import com.arunrk.note.core.designsystem.component.NoteTextField
import com.arunrk.note.core.designsystem.component.PasswordTextField
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.feature.auth.component.AuthFooterAction
import com.arunrk.note.feature.auth.component.AuthScaffold
import com.arunrk.note.feature.auth.component.FormErrorBanner
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RegisterScreen(
    onNavigateToNotes: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                RegisterEffect.NavigateToNotes -> onNavigateToNotes()
                RegisterEffect.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }

    RegisterContent(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

@Composable
fun RegisterContent(
    state: RegisterState,
    onIntent: (RegisterIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = "Create your account",
        subtitle = "Your notes stay on this device and sync when you're online.",
        modifier = modifier,
    ) {
        state.formError?.let { FormErrorBanner(it) }

        NoteTextField(
            value = state.name,
            onValueChange = { onIntent(RegisterIntent.NameChanged(it)) },
            label = "Name",
            error = state.nameError,
            enabled = !state.isSubmitting,
        )

        NoteTextField(
            value = state.email,
            onValueChange = { onIntent(RegisterIntent.EmailChanged(it)) },
            label = "Email",
            error = state.emailError,
            enabled = !state.isSubmitting,
            keyboardType = KeyboardType.Email,
        )

        PasswordTextField(
            value = state.password,
            onValueChange = { onIntent(RegisterIntent.PasswordChanged(it)) },
            label = "Password",
            error = state.passwordError,
            enabled = !state.isSubmitting,
        )

        // Requirements are stated up front and tick off live, rather than being
        // revealed one rejection at a time.
        PasswordRequirements(
            requirements = state.passwordRequirements,
            visible = state.password.isNotEmpty(),
        )

        PasswordTextField(
            value = state.confirmPassword,
            onValueChange = { onIntent(RegisterIntent.ConfirmPasswordChanged(it)) },
            label = "Confirm password",
            error = state.confirmPasswordError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
        )

        LoadingButton(
            text = "Create account",
            onClick = { onIntent(RegisterIntent.Submit) },
            loading = state.isSubmitting,
            enabled = state.canSubmit,
        )

        AuthFooterAction(
            prompt = "Already have an account?",
            actionLabel = "Sign in",
            onAction = { onIntent(RegisterIntent.SignInClicked) },
        )
    }
}

@Composable
private fun PasswordRequirements(
    requirements: List<PasswordRequirement>,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Column(
        modifier = modifier.fillMaxWidth().padding(start = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        requirements.forEach { requirement ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Satisfied requirements get a tick; unmet ones get a dot. The
                // shape carries the meaning, not just the colour.
                if (requirement.satisfied) {
                    Icon(
                        imageVector = NoteIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = requirement.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (requirement.satisfied) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}
