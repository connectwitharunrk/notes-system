package com.arunrk.note.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected rather than referencing [Dispatchers] directly, so tests can run
 * everything on a single deterministic scheduler. Sync engine tests in
 * particular are untestable against real background threads.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = ioDispatcher()
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}

/**
 * Kotlin/Native has no `Dispatchers.IO`; Default is the correct choice there.
 */
internal expect fun ioDispatcher(): CoroutineDispatcher
