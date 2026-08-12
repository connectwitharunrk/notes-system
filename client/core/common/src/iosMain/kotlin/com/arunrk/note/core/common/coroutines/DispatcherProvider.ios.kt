package com.arunrk.note.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native does not provide Dispatchers.IO. Default is backed by a worker
 * pool and is the right choice for the blocking-ish work we do here (SQLite via
 * the native driver, which is fast and not truly blocking).
 */
internal actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Default
