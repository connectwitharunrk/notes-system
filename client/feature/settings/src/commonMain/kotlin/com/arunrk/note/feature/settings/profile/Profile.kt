package com.arunrk.note.feature.settings.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
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
import com.arunrk.note.core.designsystem.component.NoteTextField
import com.arunrk.note.core.designsystem.error.toUserMessage
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.auth.UpdateProfileUseCase
import com.arunrk.note.feature.settings.component.FormErrorBanner
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class ProfileState(
    val name: String = "",
    val email: String = "",
    val nameError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    val loaded: Boolean = false,
    /** Compared against the loaded value so Save is only offered for real edits. */
    val originalName: String = "",
) : UiState {
    val hasChanges: Boolean get() = name.trim() != originalName.trim()
    val canSubmit: Boolean get() = hasChanges && name.isNotBlank() && !isSubmitting
}

sealed interface ProfileIntent : UiIntent {
    data class NameChanged(val value: String) : ProfileIntent
    data object Submit : ProfileIntent
    data object BackClicked : ProfileIntent
}

sealed interface ProfileEffect : UiEffect {
    data object NavigateBack : ProfileEffect
    data class ShowMessage(val message: String) : ProfileEffect
}

class ProfileViewModel(
    observeAuthState: ObserveAuthStateUseCase,
    private val updateProfile: UpdateProfileUseCase,
) : MviViewModel<ProfileIntent, ProfileState, ProfileEffect>(ProfileState()) {

    init {
        observeAuthState()
            .onEach { state ->
                val user = (state as? AuthState.Authenticated)?.user ?: return@onEach
                setState {
                    // Only seed the field on first load; re-seeding on every
                    // emission would wipe what the user is currently typing.
                    if (loaded) {
                        copy(email = user.email)
                    } else {
                        copy(
                            name = user.name,
                            originalName = user.name,
                            email = user.email,
                            loaded = true,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    override fun handleIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.NameChanged -> setState {
                copy(name = intent.value, nameError = null, formError = null)
            }

            ProfileIntent.BackClicked -> sendEffect(ProfileEffect.NavigateBack)

            ProfileIntent.Submit -> viewModelScope.launch {
                if (!currentState.canSubmit) return@launch
                setState { copy(isSubmitting = true, nameError = null, formError = null) }

                when (val result = updateProfile(currentState.name)) {
                    is Outcome.Success -> {
                        setState {
                            copy(
                                isSubmitting = false,
                                originalName = result.value.name,
                                name = result.value.name,
                            )
                        }
                        sendEffect(ProfileEffect.ShowMessage("Profile updated"))
                    }

                    is Outcome.Failure -> setState {
                        copy(
                            isSubmitting = false,
                            nameError = (result.error as? AppError.Validation)
                                ?.fieldErrors?.get("name"),
                            formError = result.error.takeIf { it !is AppError.Validation }
                                ?.toUserMessage(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                ProfileEffect.NavigateBack -> onNavigateBack()
                is ProfileEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onIntent(ProfileIntent.BackClicked) }) {
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
                .padding(Spacing.md)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            state.formError?.let { FormErrorBanner(it) }

            NoteTextField(
                value = state.name,
                onValueChange = { viewModel.onIntent(ProfileIntent.NameChanged(it)) },
                label = "Name",
                error = state.nameError,
                enabled = !state.isSubmitting,
                imeAction = ImeAction.Done,
            )

            NoteTextField(
                value = state.email,
                onValueChange = {},
                label = "Email",
                enabled = false,
                // Email is the login identity and the password-reset
                // destination, so changing it needs a verification round trip
                // that does not exist yet. Better to show it as fixed than to
                // offer an edit that could lock someone out.
                supportingText = "Your email is used to sign in and can't be changed here.",
            )

            LoadingButton(
                text = "Save changes",
                onClick = { viewModel.onIntent(ProfileIntent.Submit) },
                loading = state.isSubmitting,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
