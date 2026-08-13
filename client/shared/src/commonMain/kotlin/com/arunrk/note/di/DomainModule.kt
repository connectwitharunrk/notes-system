package com.arunrk.note.di

import com.arunrk.note.data.auth.AuthRepositoryImpl
import com.arunrk.note.data.note.NoteRepositoryImpl
import com.arunrk.note.data.preferences.PreferencesRepositoryImpl
import com.arunrk.note.domain.repository.AuthRepository
import com.arunrk.note.domain.repository.NoteRepository
import com.arunrk.note.domain.repository.PreferencesRepository
import com.arunrk.note.domain.repository.SyncManager
import com.arunrk.note.domain.usecase.auth.SignOutUseCase
import com.arunrk.note.sync.DefaultSyncManager
import com.arunrk.note.sync.SyncEngine
import com.arunrk.note.sync.SyncLocalStore
import com.arunrk.note.domain.usecase.note.CreateNoteUseCase
import com.arunrk.note.domain.usecase.note.DeleteNoteUseCase
import com.arunrk.note.domain.usecase.note.DiscardBlankNoteUseCase
import com.arunrk.note.domain.usecase.note.GetNoteUseCase
import com.arunrk.note.domain.usecase.note.ObserveNoteUseCase
import com.arunrk.note.domain.usecase.note.ObserveNotesUseCase
import com.arunrk.note.domain.usecase.note.ObserveSyncCountsUseCase
import com.arunrk.note.domain.usecase.note.RestoreNoteUseCase
import com.arunrk.note.domain.usecase.note.SetArchivedUseCase
import com.arunrk.note.domain.usecase.note.TogglePinUseCase
import com.arunrk.note.domain.usecase.note.UpdateNoteUseCase
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
    single<NoteRepository> { NoteRepositoryImpl(get(), get()) }
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }

    // ---- synchronisation --------------------------------------------------

    single { SyncLocalStore(get(), get()) }
    single { SyncEngine(get(), get()) }
    single<SyncManager> {
        DefaultSyncManager(
            // Explicit: the manager depends on the SyncCycle seam, but there is
            // one engine and it is registered under its own type.
            engine = get<SyncEngine>(),
            store = get(),
            database = get(),
            authRepository = get(),
            networkMonitor = get(),
            lifecycle = get(),
            dispatchers = get(),
            // The process-lifetime scope: sync must outlive any screen, and
            // tying it to a ViewModel would cancel a push mid-flight whenever
            // the user navigated away.
            scope = get(AppScope),
        )
    }
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

    // ---- notes ------------------------------------------------------------

    factory { ObserveNotesUseCase(get()) }
    factory { ObserveNoteUseCase(get()) }
    factory { ObserveSyncCountsUseCase(get()) }
    factory { GetNoteUseCase(get()) }
    factory { CreateNoteUseCase(get()) }
    factory { UpdateNoteUseCase(get()) }
    factory { TogglePinUseCase(get()) }
    factory { SetArchivedUseCase(get()) }
    factory { DeleteNoteUseCase(get()) }
    factory { RestoreNoteUseCase(get()) }
    factory { DiscardBlankNoteUseCase(get()) }
    factory { SignOutUseCase(get(), get(), get()) }

    viewModelOf(::SessionViewModel)
}
