package com.arunrk.note.di

import com.arunrk.note.data.auth.AuthRepositoryImpl
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.usecase.auth.ChangePasswordUseCase
import com.arunrk.note.domain.usecase.auth.LoginUseCase
import com.arunrk.note.domain.usecase.auth.LogoutUseCase
import com.arunrk.note.domain.usecase.auth.ObserveAuthStateUseCase
import com.arunrk.note.domain.usecase.auth.RegisterUseCase
import com.arunrk.note.domain.usecase.auth.RequestPasswordResetUseCase
import com.arunrk.note.domain.usecase.auth.RestoreSessionUseCase
import com.arunrk.note.domain.usecase.auth.UpdateProfileUseCase
import com.arunrk.note.session.SessionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Repositories are singletons because they hold observable state - AuthRepository
 * owns the StateFlow the navigation graph reacts to, and a second instance would
 * mean a sign-out that the UI never notices.
 *
 * Use cases are factories: they are stateless and cheap.
 */
val dataModule: Module = module {
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get(), get()) }
}

val domainModule: Module = module {
    factory { ObserveAuthStateUseCase(get()) }
    factory { RestoreSessionUseCase(get()) }
    factory { LoginUseCase(get()) }
    factory { RegisterUseCase(get()) }
    factory { LogoutUseCase(get()) }
    factory { RequestPasswordResetUseCase(get()) }
    factory { ChangePasswordUseCase(get()) }
    factory { UpdateProfileUseCase(get()) }

    viewModelOf(::SessionViewModel)
}
