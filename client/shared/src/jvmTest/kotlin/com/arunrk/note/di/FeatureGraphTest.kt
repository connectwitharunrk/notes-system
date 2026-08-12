package com.arunrk.note.di

import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.domain.repository.NoteRepository
import com.arunrk.note.domain.repository.PreferencesRepository
import com.arunrk.note.feature.auth.forgotpassword.ForgotPasswordViewModel
import com.arunrk.note.feature.auth.login.LoginViewModel
import com.arunrk.note.feature.auth.register.RegisterViewModel
import com.arunrk.note.feature.notes.editor.NoteEditorViewModel
import com.arunrk.note.feature.notes.list.NotesListViewModel
import com.arunrk.note.session.SessionViewModel
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Constructs every ViewModel through Koin.
 *
 * A missing or mis-declared dependency in a DI module is invisible to the
 * compiler - it fails at runtime, on the screen that needs it, usually in front
 * of the user. Resolving each one here turns that into a build failure.
 */
class FeatureGraphTest {

    private companion object {
        val koin: org.koin.core.Koin by lazy {
            initKoin(PlatformContext(), baseUrl = "http://127.0.0.1:8080").koin
        }
    }

    @Test
    fun `auth view models resolve`() {
        assertNotNull(koin.get<LoginViewModel>())
        assertNotNull(koin.get<RegisterViewModel>())
        assertNotNull(koin.get<ForgotPasswordViewModel>())
        assertNotNull(koin.get<SessionViewModel>())
    }

    @Test
    fun `the notes list resolves for both tabs`() {
        assertNotNull(koin.get<NotesListViewModel> { parametersOf(false) })
        assertNotNull(koin.get<NotesListViewModel> { parametersOf(true) })
    }

    /**
     * The editor takes a nullable id - null means "create a new note" - which is
     * exactly the case Koin's parameter destructuring cannot infer a type for.
     * Both paths are checked because only one of them is exercised by opening an
     * existing note.
     */
    @Test
    fun `the note editor resolves for an existing note and for a new one`() {
        assertNotNull(koin.get<NoteEditorViewModel> { parametersOf("some-note-id") })
        assertNotNull(koin.get<NoteEditorViewModel> { parametersOf(null) })
    }

    @Test
    fun `repositories resolve as singletons`() {
        val notes = koin.get<NoteRepository>()
        val preferences = koin.get<PreferencesRepository>()

        assertTrue(notes === koin.get<NoteRepository>())
        assertTrue(preferences === koin.get<PreferencesRepository>())
    }
}
