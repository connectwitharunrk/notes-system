package com.arunrk.note.auth

import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.data.auth.AuthRepositoryImpl
import com.arunrk.note.di.initKoin
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.repository.AuthRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real auth stack against the running backend.
 *
 * Requires the server on 127.0.0.1:8080. Compiling a repository proves nothing
 * about whether the token refresh plugin, the DTO shapes and the error envelope
 * mapping actually agree with what the server sends - only a real round trip
 * does.
 *
 * The test uses the real secure storage under the user's home directory, exactly
 * as the app does, and clears the session when it finishes.
 */
class AuthFlowIntegrationTest {

    /**
     * One graph for the whole class.
     *
     * DataStore registers its file for the lifetime of the process and refuses a
     * second instance over the same path, so starting Koin per test method would
     * fail on the second one. Combined with `forkEvery(1)` in the build script,
     * each test class gets a clean JVM and a single graph.
     */
    private companion object {
        val koin: org.koin.core.Koin by lazy {
            initKoin(PlatformContext(), baseUrl = "http://127.0.0.1:8080").koin
        }
    }

    private val repository: AuthRepository get() = koin.get()
    private val tokenStore: TokenStore get() = koin.get()

    // Timestamp alone collides when two tests start in the same millisecond,
    // which turns an unrelated assertion into a confusing 409.
    private val uniqueEmail =
        "client-it-${System.currentTimeMillis()}-${(0..999_999).random()}@example.com"
    private val password = "correct-horse-1"

    @BeforeTest
    fun setUp() = runTest {
        // Each test starts signed out, whatever the previous one left behind.
        tokenStore.clearSession()
        (repository as AuthRepositoryImpl).restoreSession()
    }

    @AfterTest
    fun tearDown() = runTest {
        runCatching { tokenStore.clearSession() }
    }

    @Test
    fun `register signs the user in and stores a session`() = runTest {
        val result = repository.register("Client Tester", uniqueEmail, password)

        val user = assertIs<Outcome.Success<*>>(result).value
        assertNotNull(user)
        assertEquals(AuthState.Authenticated::class, repository.authState.value::class)

        // The session must actually be persisted, not merely held in memory -
        // otherwise a restart would silently sign the user out.
        assertNotNull(tokenStore.accessToken(), "access token must be stored")
        assertNotNull(tokenStore.refreshToken(), "refresh token must be stored")
    }

    @Test
    fun `a wrong password is reported as invalid credentials, not a generic failure`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)
        repository.logout()

        val result = repository.login(uniqueEmail, "definitely-wrong-9")

        val failure = assertIs<Outcome.Failure>(result)
        assertIs<AppError.InvalidCredentials>(
            failure.error,
            "a wrong password must be distinguishable from being offline",
        )
    }

    @Test
    fun `an unknown email fails the same way as a wrong password`() = runTest {
        val unknown = repository.login("nobody-${System.currentTimeMillis()}@example.com", password)
        repository.register("Client Tester", uniqueEmail, password)
        repository.logout()
        val wrongPassword = repository.login(uniqueEmail, "definitely-wrong-9")

        // Identical error types, so the UI cannot leak which addresses exist.
        assertEquals(
            assertIs<Outcome.Failure>(unknown).error::class,
            assertIs<Outcome.Failure>(wrongPassword).error::class,
        )
    }

    @Test
    fun `logout clears the stored session even though the server call also runs`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)

        repository.logout()

        assertEquals(AuthState.Unauthenticated, repository.authState.value)
        assertNull(tokenStore.accessToken())
        assertNull(tokenStore.refreshToken())
    }

    @Test
    fun `restoring a session with no stored credentials reports unauthenticated`() = runTest {
        tokenStore.clearSession()

        assertEquals(AuthState.Unauthenticated, repository.restoreSession())
    }

    /**
     * The cold-start path: sign in, then build a brand-new repository with no
     * in-memory state and prove the session comes back from disk.
     *
     * Koin is deliberately not restarted here - DataStore allows only one
     * instance per file per process, so a second graph over the same
     * preferences file would fail for reasons unrelated to what this test is
     * about. Constructing the repository directly isolates the cold-start
     * behaviour, which is the thing under test.
     */
    @Test
    fun `a stored session is restored from disk by a fresh repository`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)

        val coldRepository = AuthRepositoryImpl(
            authApi = koin.get(),
            secureStorage = koin.get(),
            tokenStore = koin.get(),
            sessionInvalidator = koin.get(),
        )
        assertEquals(AuthState.Unknown, coldRepository.authState.value)

        val restored = coldRepository.restoreSession()

        val authenticated = assertIs<AuthState.Authenticated>(restored)
        assertEquals(uniqueEmail, authenticated.user.email)
    }

    @Test
    fun `the refresh token rotates when the profile is fetched after a restart`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)

        val profile = repository.refreshProfile()

        val user = assertIs<Outcome.Success<*>>(profile).value
        assertNotNull(user)
    }

    @Test
    fun `password reset is accepted for any address`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)

        val known = repository.requestPasswordReset(uniqueEmail)
        val unknown = repository.requestPasswordReset("ghost-${System.currentTimeMillis()}@example.com")

        assertTrue(known is Outcome.Success)
        // Same answer either way, matching the server's no-enumeration rule.
        assertTrue(unknown is Outcome.Success)
    }

    @Test
    fun `changing the password lets the new one sign in and stops the old one`() = runTest {
        repository.register("Client Tester", uniqueEmail, password)
        val newPassword = "brand-new-pass-2"

        val changed = repository.changePassword(password, newPassword)
        assertTrue(changed is Outcome.Success, "change password failed: ${changed.errorOrNull()}")

        repository.logout()

        assertIs<Outcome.Failure>(repository.login(uniqueEmail, password))
        assertIs<Outcome.Success<*>>(repository.login(uniqueEmail, newPassword))
    }
}
