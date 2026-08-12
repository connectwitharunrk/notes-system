package com.arunrk.note.feature.notes.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.feature.notes.component.SyncStatusBadge
import com.arunrk.note.feature.notes.component.relativeTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NoteEditorScreen(
    noteId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteEditorViewModel = koinViewModel { parametersOf(noteId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                NoteEditorEffect.NavigateBack -> onNavigateBack()
                is NoteEditorEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    NoteEditorContent(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorContent(
    state: NoteEditorState,
    onIntent: (NoteEditorIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    // A brand-new note opens with the cursor already in the body. Making someone
    // tap into the field before typing is a step with no purpose.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && state.noteId == null) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { SaveStatusLabel(state) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(NoteEditorIntent.BackPressed) }) {
                        Icon(NoteIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(NoteEditorIntent.TogglePin) }) {
                        Icon(
                            imageVector = if (state.isPinned) {
                                NoteIcons.PushPin
                            } else {
                                NoteIcons.PushPinOutlined
                            },
                            contentDescription = if (state.isPinned) "Unpin note" else "Pin note",
                            tint = if (state.isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { onIntent(NoteEditorIntent.ToggleArchive) }) {
                        Icon(
                            imageVector = if (state.isArchived) {
                                NoteIcons.Unarchive
                            } else {
                                NoteIcons.Archive
                            },
                            contentDescription = if (state.isArchived) "Unarchive" else "Archive",
                        )
                    }
                    IconButton(onClick = { onIntent(NoteEditorIntent.Delete) }) {
                        Icon(NoteIcons.Delete, contentDescription = "Delete note")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
        ) {
            if (state.isConflictCopy) {
                ConflictNotice()
            }

            // BasicTextField rather than OutlinedTextField: a note editor should
            // look like a page, not like a form control with a box around it.
            BasicTextField(
                value = state.title,
                onValueChange = { onIntent(NoteEditorIntent.TitleChanged(it)) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                decorationBox = { inner ->
                    if (state.title.isEmpty()) {
                        Text(
                            text = "Title",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )

            BasicTextField(
                value = state.content,
                onValueChange = { onIntent(NoteEditorIntent.ContentChanged(it)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, bottom = Spacing.xxl)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (state.content.isEmpty()) {
                        Text(
                            text = "Start writing…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * Replaces a save button.
 *
 * Says "Saving…" only while a write is genuinely in flight, then falls back to
 * when the note was last changed. Claiming "Saved" permanently would be noise;
 * showing nothing would leave people wondering.
 */
@Composable
private fun SaveStatusLabel(state: NoteEditorState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = when {
            state.isSaving -> "Saving…"
            state.noteId == null -> "New note"
            state.updatedAt > 0 -> "Edited ${relativeTime(state.updatedAt).lowercase()}"
            else -> ""
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.syncStatus != SyncStatus.SYNCED) {
            Row(modifier = Modifier.padding(start = Spacing.sm)) {
                SyncStatusBadge(status = state.syncStatus, showLabel = false)
            }
        }
    }
}

@Composable
private fun ConflictNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = NoteIcons.Warning,
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.sm),
            )
            Text(
                text = "This is a copy. The same note was edited on another device, " +
                    "so both versions were kept rather than one overwriting the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
