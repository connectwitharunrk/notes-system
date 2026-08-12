package com.arunrk.note.di

import org.koin.core.qualifier.named

/** Qualifier for the process-lifetime coroutine scope. */
val AppScope = named("appScope")
