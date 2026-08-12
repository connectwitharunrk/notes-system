package com.arunrk.note.feature.notes.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.domain.model.NoteViewMode
import com.arunrk.note.core.designsystem.component.EmptyState
import com.arunrk.note.core.designsystem.component.LoadingState
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.core.designsystem.layout.WindowSize
import com.arunrk.note.core.designsystem.layout.noteGridColumns
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.model.Note
import com.arunrk.note.feature.notes.component.NoteCard
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NotesListScreen(
    onOpenNote: (String?) -> Unit,
    modifier: Modifier = Modifier,
    showArchived: Boolean = false,
    viewModel: NotesListViewModel = koinViewModel { parametersOf(showArchived) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is NotesListEffect.OpenNote -> onOpenNote(effect.noteId)

                is NotesListEffect.ShowUndoDelete -> {
                    val result = snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Undo",
                        // Long, not short: undo is worthless if it disappears
                        // before the user has finished registering what happened.
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.onIntent(NotesListIntent.UndoDelete(effect.noteId))
                    }
                }

                is NotesListEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    NotesListContent(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
fun NotesListContent(
    state: NotesListState,
    onIntent: (NotesListIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSize.current

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Archived is a place to review things, not to write new ones.
            if (!state.showArchived) {
                ExtendedFloatingActionButton(
                    onClick = { onIntent(NotesListIntent.CreateNoteClicked) },
                    icon = { Icon(NoteIcons.Add, contentDescription = null) },
                    text = { Text("New note") },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            NotesTopBar(state = state, onIntent = onIntent)

            if (state.showStatusBanner) {
                StatusBanner(state = state)
            }

            when {
                state.isLoading -> LoadingState()

                state.isEmptySearch -> EmptyState(
                    icon = NoteIcons.Search,
                    title = "No matches",
                    description = "Nothing here contains \"${state.query}\". " +
                        "Try a different word or check the spelling.",
                )

                state.isEmpty && state.showArchived -> EmptyState(
                    icon = NoteIcons.Archive,
                    title = "Nothing archived",
                    description = "Notes you archive are kept here, out of your main list.",
                )

                state.isEmpty -> EmptyState(
                    icon = NoteIcons.Description,
                    title = "No notes yet",
                    description = "Write your first note. It saves on this device " +
                        "straight away and syncs when you're online.",
                    actionLabel = "New note",
                    onAction = { onIntent(NotesListIntent.CreateNoteClicked) },
                )

                else -> NotesGrid(
                    state = state,
                    onIntent = onIntent,
                    windowSize = windowSize,
                )
            }
        }
    }
}

@Composable
private fun NotesGrid(
    state: NotesListState,
    onIntent: (NotesListIntent) -> Unit,
    windowSize: WindowSize,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columnCount = if (state.viewMode == NoteViewMode.LIST) {
            1
        } else {
            // Measured from the pane's own width, not the window's, so a
            // list-detail layout sizes its list correctly.
            noteGridColumns(windowSize, maxWidth)
        }

        // Staggered so cards keep their natural height. A uniform grid either
        // truncates long notes or leaves large gaps under short ones.
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                // Clears the floating action button, which would otherwise cover
                // the last note.
                bottom = 96.dp,
            ),
            verticalItemSpacing = Spacing.sm,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.pinned.isNotEmpty()) {
                item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
                    SectionHeader("Pinned")
                }
                items(state.pinned, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onIntent(NotesListIntent.NoteClicked(note.id)) },
                        onTogglePin = { onIntent(NotesListIntent.TogglePin(note.id, !note.isPinned)) },
                    )
                }
            }

            if (state.others.isNotEmpty()) {
                if (state.pinned.isNotEmpty()) {
                    item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
                        SectionHeader("Others")
                    }
                }
                items(state.others, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onIntent(NotesListIntent.NoteClicked(note.id)) },
                        onTogglePin = { onIntent(NotesListIntent.TogglePin(note.id, !note.isPinned)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

/**
 * Shown only when there is something to say: offline, failed pushes, or an
 * unresolved conflict. A permanent "all synced" bar trains people to ignore
 * that part of the screen.
 */
@Composable
private fun StatusBanner(state: NotesListState) {
    val (icon, message, container) = when {
        state.conflictCount > 0 -> Triple(
            NoteIcons.Warning,
            "${state.conflictCount} note${plural(state.conflictCount)} " +
                "changed on another device. Both versions were kept.",
            MaterialTheme.colorScheme.tertiaryContainer,
        )

        state.failedCount > 0 -> Triple(
            NoteIcons.SyncProblem,
            "${state.failedCount} note${plural(state.failedCount)} couldn't sync. " +
                "They're safe on this device.",
            MaterialTheme.colorScheme.errorContainer,
        )

        else -> Triple(
            NoteIcons.CloudOff,
            if (state.pendingCount > 0) {
                "Offline — ${state.pendingCount} change${plural(state.pendingCount)} " +
                    "saved here, syncing when you're back."
            } else {
                "Offline — your notes are saved on this device."
            },
            MaterialTheme.colorScheme.secondaryContainer,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.sm),
            )
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NotesTopBar(
    state: NotesListState,
    onIntent: (NotesListIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.arunrk.note.core.designsystem.component.NoteTextField(
            value = state.query,
            onValueChange = { onIntent(NotesListIntent.QueryChanged(it)) },
            label = if (state.showArchived) "Search archived" else "Search notes",
            leadingIcon = NoteIcons.Search,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = { onIntent(NotesListIntent.ToggleViewMode) }) {
            Icon(
                imageVector = NoteIcons.MoreVert,
                contentDescription = if (state.viewMode == NoteViewMode.GRID) {
                    "Switch to list view"
                } else {
                    "Switch to grid view"
                },
            )
        }
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
