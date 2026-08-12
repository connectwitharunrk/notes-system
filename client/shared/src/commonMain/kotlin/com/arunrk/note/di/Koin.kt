package com.arunrk.note.di

import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.feature.auth.di.authFeatureModule
import com.arunrk.note.feature.notes.di.notesFeatureModule
import com.arunrk.note.feature.settings.di.settingsFeatureModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * Single entry point for dependency injection, called once per process by each
 * app shell before any UI is composed.
 */
fun initKoin(
    context: PlatformContext,
    baseUrl: String = defaultApiBaseUrl(),
    appDeclaration: KoinAppDeclaration = {},
): KoinApplication = startKoin {
    appDeclaration()
    modules(
        coreModule(context, defaultApiConfig(baseUrl)),
        dataModule,
        domainModule,
        authFeatureModule,
        notesFeatureModule,
        settingsFeatureModule,
    )
}
