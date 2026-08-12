package com.arunrk.note.core.common.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the screen renders. One immutable data class, never a bag of
 * separate flows - two flows can disagree, and a UI built from disagreeing
 * sources flickers in ways that are almost impossible to reproduce.
 */
interface UiState

/** Something the user did, or the screen announcing it needs data. */
interface UiIntent

/**
 * A one-shot side effect: navigate, show a snackbar, dismiss the keyboard.
 *
 * Anything that should survive a configuration change belongs in [UiState]
 * instead. If an error must still be visible after rotation, it is state; if it
 * is a transient toast, it is an effect.
 */
interface UiEffect

abstract class MviViewModel<I : UiIntent, S : UiState, E : UiEffect>(
    initialState: S,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    /**
     * Channel-backed rather than SharedFlow: effects must be delivered exactly
     * once and must not replay. A replayed "navigate to notes" fires again on
     * every recomposition after rotation, which is how apps end up with fifty
     * copies of a screen on the back stack.
     *
     * BUFFERED so emitting never suspends the caller, and nothing is dropped
     * while the collector is briefly absent during a configuration change.
     */
    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect: Flow<E> = _effect.receiveAsFlow()

    protected val currentState: S get() = _state.value

    fun onIntent(intent: I) {
        handleIntent(intent)
    }

    protected abstract fun handleIntent(intent: I)

    protected fun setState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    protected fun sendEffect(effect: E) {
        viewModelScope.launch { _effect.send(effect) }
    }
}
