package com.arunrk.note.feature.notes.editor

import app.cash.turbine.test
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.SyncStatus
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.note.CreateNoteUseCase
import com.arunrk.note.domain.usecase.note.DeleteNoteUseCase
import com.arunrk.note.domain.usecase.note.DiscardBlankNoteUseCase
import com.arunrk.note.domain.usecase.note.GetNoteUseCase
import com.arunrk.note.domain.usecase.note.SetArchivedUseCase
import com.arunrk.note.domain.usecase.note.TogglePinUseCase
import com.arunrk.note.domain.usecase.note.UpdateNoteUseCase
import com.arunrk.note.feature.notes.fake.FakeAuthRepository
import com.arunrk.note.feature.notes.fake.FakeNoteRepository
import com.arunrk.note.feature.notes.fake.TEST_USER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's autosave behaviour.
 *
 * This is where silent data loss would hide: a debounce that never fires, an
 * exit that races the timer, or a blank-note cleanup that deletes the wrong
 * thing. Virtual time makes the debounce boundaries exact rather than flaky.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var notes: FakeNoteRepository
    private lateinit var auth: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        notes = FakeNoteRepository()
        auth = FakeAuthRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(noteId: String? = null) = NoteEditorViewModel(
        noteId = noteId,
        observeAuthState = ObserveAuthStateUseCase(auth),
        getNote = GetNoteUseCase(notes),
        createNote = CreateNoteUseCase(notes),
        updateNote = UpdateNoteUseCase(notes),
        togglePin = TogglePinUseCase(notes),
        setArchived = SetArchivedUseCase(notes),
        deleteNote = DeleteNoteUseCase(notes),
        discardBlank = DiscardBlankNoteUseCase(notes),
    )

    private fun seedNote(id: String = "note-1", title: String = "Existing", content: String = "body") {
        notes.seed(
            Note(
                id = id,
                userId = TEST_USER_ID,
                title = title,
                content = content,
                createdAt = 1_000,
                updatedAt = 1_000,
                syncStatus = SyncStatus.SYNCED,
            )
        )
    }

    // ---- creation ----------------------------------------------------------

    /**
     * Opening the editor must not create anything. Otherwise every abandoned
     * "New note" tap leaves an empty note in the list.
     */
    @Test
    fun `opening a new note writes nothing until the user types`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(0, notes.createCount)
        assertTrue(notes.all().isEmpty())
    }

    @Test
    fun `typing creates the note once the debounce elapses`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(NoteEditorIntent.ContentChanged("first words"))
        advanceUntilIdle()

        assertEquals(1, notes.createCount)
        assertEquals("first words", notes.all().single().content)
    }

    @Test
    fun `a burst of keystrokes produces a single note, not one per character`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            "hello".forEachIndexed { index, _ ->
                vm.onIntent(NoteEditorIntent.ContentChanged("hello".take(index + 1)))
                // Well inside the 600ms window, so each keystroke restarts it.
                advanceTimeBy(50)
            }
            advanceUntilIdle()

            assertEquals(1, notes.createCount, "the debounce must coalesce the burst")
            assertEquals("hello", notes.all().single().content)
        }

    @Test
    fun `nothing is written before the debounce window closes`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(NoteEditorIntent.ContentChanged("typing"))
        advanceTimeBy(300)

        assertEquals(0, notes.createCount)
    }

    // ---- leaving the screen ------------------------------------------------

    /**
     * Leaving must not race the 600ms timer. That race is exactly how
     * "I typed it and it vanished" happens.
     */
    @Test
    fun `going back flushes immediately rather than waiting for the timer`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(NoteEditorIntent.ContentChanged("unsaved words"))
            // Deliberately less than the debounce: the pending write has not run.
            advanceTimeBy(100)

            vm.onIntent(NoteEditorIntent.BackPressed)
            advanceUntilIdle()

            assertEquals(1, notes.createCount)
            assertEquals("unsaved words", notes.all().single().content)
        }

    @Test
    fun `an untouched new note is discarded on the way out`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(NoteEditorIntent.BackPressed)
        advanceUntilIdle()

        assertTrue(notes.all().isEmpty(), "an empty note must leave no trace")
    }

    @Test
    fun `a note typed then cleared is discarded rather than left blank`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIntent(NoteEditorIntent.ContentChanged("oops"))
        advanceUntilIdle()
        assertEquals(1, notes.all().size)

        vm.onIntent(NoteEditorIntent.ContentChanged(""))
        vm.onIntent(NoteEditorIntent.BackPressed)
        advanceUntilIdle()

        assertTrue(notes.all().isEmpty())
    }

    /**
     * An existing note emptied by the user is NOT discarded. It may already
     * exist on other devices, and hard-deleting it here would hide it locally
     * while leaving it alive everywhere else.
     */
    @Test
    fun `clearing an existing note does not silently destroy it`() = runTest(dispatcher) {
        seedNote(content = "was here")
        val vm = viewModel("note-1")
        advanceUntilIdle()

        vm.onIntent(NoteEditorIntent.TitleChanged(""))
        vm.onIntent(NoteEditorIntent.ContentChanged(""))
        vm.onIntent(NoteEditorIntent.BackPressed)
        advanceUntilIdle()

        assertNotNull(notes.current("note-1"), "an existing note must survive being emptied")
    }

    // ---- loading -----------------------------------------------------------

    @Test
    fun `an existing note is loaded into the fields`() = runTest(dispatcher) {
        seedNote(title = "Groceries", content = "milk")
        val vm = viewModel("note-1")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Groceries", state.title)
        assertEquals("milk", state.content)
        assertFalse(state.isLoading)
    }

    /**
     * A note can vanish between the list rendering and the tap landing - deleted
     * on another device and pulled in between. The editor must bounce back
     * rather than present a blank page that silently creates a new note.
     */
    @Test
    fun `opening a note that no longer exists navigates back instead of showing a blank editor`() =
        runTest(dispatcher) {
            val vm = viewModel("does-not-exist")

            vm.effect.test {
                // Effects are Channel-backed, so those emitted during init are
                // buffered and delivered to this collector in order.
                assertTrue(awaitItem() is NoteEditorEffect.ShowMessage)
                assertTrue(awaitItem() is NoteEditorEffect.NavigateBack)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- actions -----------------------------------------------------------

    @Test
    fun `pinning an unsaved note creates it first so the pin has something to attach to`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onIntent(NoteEditorIntent.TitleChanged("Pin me"))
            vm.onIntent(NoteEditorIntent.TogglePin)
            advanceUntilIdle()

            val created = notes.all().singleOrNull()
            assertNotNull(created)
            assertTrue(created.isPinned)
        }

    @Test
    fun `deleting cancels the pending autosave rather than resurrecting the note`() =
        runTest(dispatcher) {
            seedNote()
            val vm = viewModel("note-1")
            advanceUntilIdle()

            vm.onIntent(NoteEditorIntent.ContentChanged("edited just before deleting"))
            advanceTimeBy(100)
            vm.onIntent(NoteEditorIntent.Delete)
            advanceUntilIdle()

            assertTrue(assertNotNull(notes.current("note-1")).isDeleted)
        }

    @Test
    fun `a failed save surfaces an error instead of pretending it worked`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        notes.failNextWrite = AppError.Storage("disk full")
        vm.onIntent(NoteEditorIntent.ContentChanged("will not save"))
        advanceUntilIdle()

        assertNotNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isSaving)
    }
}
