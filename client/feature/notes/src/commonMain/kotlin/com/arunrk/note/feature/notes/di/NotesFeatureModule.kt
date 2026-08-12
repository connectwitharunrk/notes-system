package com.arunrk.note.feature.notes.di

import com.arunrk.note.feature.notes.editor.NoteEditorViewModel
import com.arunrk.note.feature.notes.list.NotesListViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Both ViewModels take a runtime parameter - the list needs to know whether it
 * is the archived tab, the editor needs the note id - so they are declared with
 * `parametersOf` rather than constructor references.
 */
val notesFeatureModule: Module = module {

    viewModel { (showArchived: Boolean) ->
        NotesListViewModel(
            showArchived = showArchived,
            observeAuthState = get(),
            observeNotes = get(),
            observeSyncCounts = get(),
            createNote = get(),
            togglePin = get(),
            setArchived = get(),
            deleteNote = get(),
            restoreNote = get(),
            preferences = get(),
            networkMonitor = get(),
        )
    }

    viewModel { params ->
        NoteEditorViewModel(
            // getOrNull rather than destructuring: the id is null when creating
            // a note, and Koin cannot infer a type parameter from a null literal.
            noteId = params.getOrNull<String>(),
            observeAuthState = get(),
            getNote = get(),
            createNote = get(),
            updateNote = get(),
            togglePin = get(),
            setArchived = get(),
            deleteNote = get(),
            discardBlank = get(),
        )
    }
}
