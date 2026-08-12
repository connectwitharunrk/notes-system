package com.arunrk.note.di

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.platform.PlatformContext
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.SecureStorage
import com.arunrk.note.core.network.ApiConfig
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.core.network.api.AuthApi
import com.arunrk.note.core.network.api.NoteApi
import com.arunrk.note.core.network.api.SyncApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the Phase 3 infrastructure graph on the desktop target.
 *
 * This is a genuine integration test, not a mock-heavy unit test: it opens the
 * real SQLite database, the real DataStore file and the real encrypted session
 * store under the user's home directory - exactly what launching the app does.
 * Compiling the graph proves nothing about whether a driver can actually open a
 * file on this platform.
 */
class CoreGraphTest {

    private lateinit var koin: org.koin.core.Koin

    @BeforeTest
    fun setUp() {
        koin = initKoin(PlatformContext(), baseUrl = "http://127.0.0.1:8080").koin
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `every infrastructure dependency resolves`() {
        assertNotNull(koin.get<NoteDatabase>())
        assertNotNull(koin.get<AppPreferences>())
        assertNotNull(koin.get<SecureStorage>())
        assertNotNull(koin.get<NetworkMonitor>())
        assertNotNull(koin.get<TokenStore>())
        assertNotNull(koin.get<HttpClient>())
        assertNotNull(koin.get<AuthApi>())
        assertNotNull(koin.get<SyncApi>())
        assertNotNull(koin.get<NoteApi>())
    }

    /**
     * Two SQLite connections to the same file would break the compare-and-set
     * guarantees the sync engine depends on, and two HTTP clients would each
     * run their own token refresh - defeating the single-flight mutex and
     * tripping the server's reuse detection.
     */
    @Test
    fun `the database and http client are single instances`() {
        assertSame(koin.get<NoteDatabase>(), koin.get<NoteDatabase>())
        assertSame(koin.get<HttpClient>(), koin.get<HttpClient>())
        assertSame(koin.get<SecureStorage>(), koin.get<SecureStorage>())
    }

    @Test
    fun `the database opens and has the expected schema`() {
        val database = koin.get<NoteDatabase>()

        // Querying at all proves the driver opened the file and ran the schema.
        val rows = database.noteEntityQueries.selectAllForUser("graph-test").executeAsList()
        assertTrue(rows.isEmpty())

        database.syncMetaEntityQueries.ensureRow("graph-test")
        val meta = assertNotNull(database.syncMetaEntityQueries.selectByUser("graph-test").executeAsOneOrNull())
        assertEquals(0, meta.lastPullCursor)

        database.syncMetaEntityQueries.deleteForUser("graph-test")
    }

    @Test
    fun `preferences produce a stable device id across calls`() = runTest {
        val preferences = koin.get<AppPreferences>()

        val first = preferences.deviceId()
        val second = preferences.deviceId()

        assertTrue(first.isNotBlank())
        // A rotating device id would fill the account's session list with ghosts
        // and break conflict attribution.
        assertEquals(first, second, "device id must survive across reads")
        assertEquals(36, first.length, "expected a canonical UUID string")
    }

    @Test
    fun `secure storage survives a full write read delete cycle`() = runTest {
        val storage = koin.get<SecureStorage>()

        storage.putString("graph_test_key", "sensitive-value")
        assertEquals("sensitive-value", storage.getString("graph_test_key"))

        storage.remove("graph_test_key")
        assertNull(storage.getString("graph_test_key"))
    }

    @Test
    fun `the token store reads back what it wrote`() = runTest {
        val tokenStore = koin.get<TokenStore>()

        tokenStore.updateTokens(
            accessToken = "access-abc",
            accessTokenExpiresAt = 1_000,
            refreshToken = "refresh-xyz",
            refreshTokenExpiresAt = 2_000,
        )

        assertEquals("access-abc", tokenStore.accessToken())
        assertEquals("refresh-xyz", tokenStore.refreshToken())

        tokenStore.clearSession()
        assertNull(tokenStore.accessToken())
        assertNull(tokenStore.refreshToken())
    }

    @Test
    fun `the api root is versioned and correctly joined`() {
        val config = koin.get<ApiConfig>()
        assertEquals("http://127.0.0.1:8080/api/v1", config.apiRoot)
    }

    @Test
    fun `a trailing slash in the base url does not produce a double slash`() {
        assertEquals(
            "http://example.com/api/v1",
            ApiConfig(baseUrl = "http://example.com/", platformName = "test").apiRoot,
        )
    }
}
