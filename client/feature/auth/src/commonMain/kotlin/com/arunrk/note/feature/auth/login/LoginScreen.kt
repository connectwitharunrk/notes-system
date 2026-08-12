package com.arunrk.note.feature.auth.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.feature.auth.component.AuthFooterAction
import com.arunrk.note.feature.auth.component.AuthScaffold
import com.arunrk.note.feature.auth.component.FormErrorBanner
import com.arunrk.note.core.designsystem.component.LoadingButton
import com.arunrk.note.core.designsystem.component.NoteTextField
import com.arunrk.note.core.designsystem.component.PasswordTextField
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    onNavigateToNotes: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                LoginEffect.NavigateToNotes -> onNavigateToNotes()
                LoginEffect.NavigateToRegister -> onNavigateToRegister()
                LoginEffect.NavigateToForgotPassword -> onNavigateToForgotPassword()
            }
        }
    }

    LoginContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

/**
 * Stateless so it can be previewed and tested without a ViewModel or Koin.
 */
@Composable
fun LoginContent(
    state: LoginState,
    onIntent: (LoginIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AuthScaffold(
        title = "Welcome back",
        subtitle = "Sign in to reach your notes on every device.",
        modifier = modifier,
    ) {
        state.formError?.let { FormErrorBanner(it) }

        NoteTextField(
            value = state.email,
            onValueChange = { onIntent(LoginIntent.EmailChanged(it)) },
            label = "Email",
            error = state.emailError,
            enabled = !state.isSubmitting,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        )

        PasswordTextField(
            value = state.password,
            onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
            label = "Password",
            error = state.passwordError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onIntent(LoginIntent.ForgotPasswordClicked) },
                enabled = !state.isSubmitting,
            ) {
                Text("Forgot password?")
            }
        }

        LoadingButton(
            text = "Sign in",
            onClick = { onIntent(LoginIntent.Submit) },
            loading = state.isSubmitting,
            enabled = state.canSubmit,
        )

        AuthFooterAction(
            prompt = "New here?",
            actionLabel = "Create an account",
            onAction = { onIntent(LoginIntent.RegisterClicked) },
        )
    }
}
