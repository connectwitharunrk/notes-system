package com.arunrk.note.feature.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import com.arunrk.note.core.designsystem.component.EmptyState
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.feature.notes.editor.NoteEditorScreen
import com.arunrk.note.feature.notes.list.NotesListScreen

/**
 * The notes tab, in whichever shape the window allows.
 *
 * On a wide window the list and the editor sit side by side, so opening a note
 * does not hide the list - on a tablet or desktop, replacing the whole screen to
 * read one note wastes most of the display and makes moving between notes a
 * navigate-and-return round trip.
 *
 * On a phone the editor is a separate full-screen destination, because two panes
 * in 400dp gives two unusable columns.
 */
@Composable
fun NotesDestination(
    showArchived: Boolean,
    onOpenNoteFullScreen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSize.current

    if (!windowSize.supportsTwoPane) {
        NotesListScreen(
            showArchived = showArchived,
            onOpenNote = onOpenNoteFullScreen,
            modifier = modifier,
        )
        return
    }

    // Survive rotation and process death so the open note is still open.
    var selectedNoteId by rememberSaveable(showArchived) { mutableStateOf<String?>(null) }
    var hasSelection by rememberSaveable(showArchived) { mutableStateOf(false) }
    // Incremented per "new note" tap so a second tap builds a fresh editor
    // instead of reusing the first one's. Scoped to the composable rather than
    // a top-level var, which would be shared across every screen in the process.
    var newNoteToken by rememberSaveable(showArchived) { mutableStateOf(0) }

    Row(modifier = modifier.fillMaxSize()) {
        NotesListScreen(
            showArchived = showArchived,
            onOpenNote = { noteId ->
                if (noteId == null) newNoteToken++
                selectedNoteId = noteId
                hasSelection = true
            },
            modifier = Modifier.weight(1f),
        )

        VerticalDivider()

        Box(modifier = Modifier.weight(1.3f)) {
            if (!hasSelection) {
                EmptyState(
                    icon = NoteIcons.Description,
                    title = "Nothing selected",
                    description = "Pick a note from the list, or start a new one.",
                )
            } else {
                NoteEditorScreen(
                    noteId = selectedNoteId,
                    // Distinct per selection so switching notes builds a new
                    // editor rather than showing the previous note's text.
                    viewModelKey = selectedNoteId ?: "new-note-$newNoteToken",
                    onNavigateBack = { hasSelection = false },
                )
            }
        }
    }
}
