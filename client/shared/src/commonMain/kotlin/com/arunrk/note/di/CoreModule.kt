package com.arunrk.note.di

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.connectivity.createConnectivityObserver
import com.arunrk.note.core.common.coroutines.DefaultDispatcherProvider
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.lifecycle.createAppLifecycleMonitor
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.common.platform.platformName
import com.arunrk.note.core.database.createDatabase
import com.arunrk.note.core.database.createDriverFactory
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.SecureStorage
import com.arunrk.note.core.datastore.createDataStore
import com.arunrk.note.core.datastore.createSecureStorage
import com.arunrk.note.core.network.ApiConfig
import com.arunrk.note.core.network.AuthSessionInvalidator
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.core.network.api.AuthApi
import com.arunrk.note.core.network.api.NoteApi
import com.arunrk.note.core.network.api.SyncApi
import com.arunrk.note.core.network.createHttpClient
import com.arunrk.note.data.auth.SecureTokenStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Infrastructure graph.
 *
 * Everything here is a singleton with process lifetime. The database, HTTP
 * client and preferences are all expensive to create and must not be duplicated:
 * two SQLite connections to the same file would silently break the
 * compare-and-set guarantees the sync engine relies on.
 */
fun coreModule(context: PlatformContext, apiConfig: ApiConfig): Module = module {

    single { context }
    single { apiConfig }

    single<DispatcherProvider> { DefaultDispatcherProvider() }

    /**
     * Long-lived scope for work that outlives any screen: connectivity
     * observation and, later, the sync engine. SupervisorJob so one failing
     * child does not tear down the rest.
     */
    single<CoroutineScope>(AppScope) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default)
    }

    // ---- storage ----------------------------------------------------------

    single { createDriverFactory(get()) }
    single<NoteDatabase> { createDatabase(get()) }
    single { createDataStore(get()) }
    single { AppPreferences(get()) }
    single<SecureStorage> { createSecureStorage(get()) }

    // ---- connectivity -----------------------------------------------------

    single { createConnectivityObserver(get(), get(AppScope)) }
    single {
        NetworkMonitor(get()).also { monitor ->
            // The OS signal is the only thing that can notice a reconnection
            // while the app is making no requests. Without this collection the
            // monitor is corrected exclusively by requests that complete - so
            // once a failed request marked us offline, and the sync engine
            // stopped making requests because we were offline, nothing was left
            // that could ever say otherwise.
            get<CoroutineScope>(AppScope).launch { monitor.observePlatformSignal() }
        }
    }

    // ---- lifecycle --------------------------------------------------------

    single { createAppLifecycleMonitor(get()) }

    // ---- network ----------------------------------------------------------

    single<TokenStore> { SecureTokenStore(get(), get()) }
    single<HttpClient> { createHttpClient(config = get(), tokenStore = get()) }
    single { AuthSessionInvalidator(get()) }

    single { AuthApi(get(), get(), get()) }
    single { SyncApi(get(), get(), get()) }
    single { NoteApi(get(), get()) }
}

/**
 * Default API host per platform.
 *
 * The Android emulator cannot reach the host machine's localhost - 10.0.2.2 is
 * the loopback alias it provides instead. Getting this wrong is the single most
 * common reason a working backend looks unreachable from the app.
 */
expect fun defaultApiBaseUrl(): String

fun defaultApiConfig(baseUrl: String = defaultApiBaseUrl()): ApiConfig = ApiConfig(
    baseUrl = baseUrl,
    platformName = platformName,
)
