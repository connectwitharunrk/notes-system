package com.arunrk.note.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arunrk.note.core.common.connectivity.ConnectivityObserver
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.coroutines.DispatcherProvider
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.lifecycle.AppLifecycleMonitor
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.domain.model.AuthState
import com.arunrk.note.domain.model.SyncReason
import com.arunrk.note.domain.model.SyncResult
import com.arunrk.note.domain.model.User
import com.arunrk.note.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the scheduler decides, with no server involved.
 *
 * The engine's failure mode is losing data, and only a real backend can prove it
 * does not - that is `SyncFlowIntegrationTest`. The scheduler's failure mode is
 * quieter and, in its way, just as bad: a change that is saved perfectly, marked
 * PENDING correctly, and then simply never sent. No error, nothing lost locally,
 * just a note that never reaches the user's other devices. Every test here is a
 * case where that has either happened or nearly did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSyncManagerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: NoteDatabase

    private val userId = "user-1"

    private val engine = RecordingSyncCycle()
    private val connectivity = FakeConnectivityObserver()
    private lateinit var monitor: NetworkMonitor
    private val lifecycle = FakeAppLifecycleMonitor()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NoteDatabase.Schema.create(driver)
        database = NoteDatabase(driver)
        monitor = NetworkMonitor(connectivity)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    /**
     * `start()` asks for a sync immediately after launching the consumer that
     * serves those requests, so the request necessarily arrives before anything
     * is listening. A SharedFlow discards what it is given in that moment, which
     * meant the sync at app start silently did not happen - the app looked idle
     * and up to date while holding changes it had never sent.
     *
     * Exactly one cycle, too. Connectivity and lifecycle are StateFlows, and a
     * StateFlow replays its current value to every new collector: an app that
     * launches online and in the foreground would otherwise announce itself as
     * having just reconnected *and* just come to the foreground, and run three
     * full cycles where one was wanted.
     */
    @Test
    fun `start-up runs exactly one cycle and does not lose it`() = runTest {
        val manager = manager()

        manager.start()
        settle()

        assertEquals(listOf(SyncReason.APP_START), engine.cycles)
    }

    // -----------------------------------------------------------------------
    // Local changes
    // -----------------------------------------------------------------------

    /**
     * THE regression test for the trigger.
     *
     * The trigger used to watch the PENDING *count*, which cannot see an edit to
     * a note that is already waiting - the number does not move. That is exactly
     * the shape of the most important edit in the system: the one made while the
     * note's upload was in flight, which the compare-and-set guard deliberately
     * leaves PENDING for the next cycle to carry. Nothing asked for that next
     * cycle, so the user's newest text sat on the device until something else
     * happened to trigger a sync.
     */
    @Test
    fun `editing a note that is already waiting triggers another cycle`() = runTest {
        insertPendingNote("note-1")
        val manager = manager()

        manager.start()
        settle()
        val beforeEdit = engine.cycles.size

        // The same note again. PENDING count: 1 before, 1 after.
        database.noteEntityQueries.updateContent(
            title = "Second thoughts",
            content = "more text",
            contentHash = "hash-2",
            updatedAt = 2_000,
            id = "note-1",
        )
        settle()

        assertTrue(
            engine.cycles.size > beforeEdit,
            "the second edit should have asked for a cycle; cycles: ${engine.cycles}",
        )
        assertEquals(SyncReason.LOCAL_CHANGE, engine.cycles.last())
    }

    /** A quiet database must not generate traffic. */
    @Test
    fun `an unchanged database asks for nothing after start-up`() = runTest {
        val manager = manager()

        manager.start()
        settle()
        val afterStart = engine.cycles.size

        settle()
        settle()

        assertEquals(afterStart, engine.cycles.size, "cycles: ${engine.cycles}")
    }

    // -----------------------------------------------------------------------
    // Failure and retry
    // -----------------------------------------------------------------------

    /**
     * Before this, a failed cycle simply sat there. Backoff was applied only when
     * some *new* request happened to arrive, so a push that failed while the user
     * put their phone down waited for the fifteen-minute tick - with the note
     * marked PENDING and nothing at all trying to send it.
     */
    @Test
    fun `a failed cycle schedules its own retry`() = runTest {
        engine.result = SyncResult(error = "server error: INTERNAL_ERROR")
        val manager = manager()

        manager.start()
        // Deliberately shorter than the two-second first backoff, so this
        // observes the failed cycle before its retry can run.
        settleBriefly()
        assertEquals(listOf(SyncReason.APP_START), engine.cycles)

        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(
            listOf(SyncReason.APP_START, SyncReason.RETRY),
            engine.cycles,
            "the failure should have retried itself, exactly once",
        )
    }

    /** A cycle that works clears the backoff rather than leaving it armed. */
    @Test
    fun `a successful cycle cancels a pending retry`() = runTest {
        engine.result = SyncResult(error = "server error: INTERNAL_ERROR")
        val manager = manager()

        manager.start()
        settleBriefly()

        // The server recovers; the scheduled retry is the cycle that finds out.
        engine.result = SyncResult()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf(SyncReason.APP_START, SyncReason.RETRY), engine.cycles)

        // Long enough for several further backoff windows, had any survived.
        advanceTimeBy(20.seconds)
        runCurrent()

        assertEquals(
            listOf(SyncReason.APP_START, SyncReason.RETRY),
            engine.cycles,
            "a success must disarm the retry rather than leave it firing",
        )
    }

    // -----------------------------------------------------------------------
    // Offline
    // -----------------------------------------------------------------------

    /**
     * The wedge this whole phase started from.
     *
     * `syncNow` refuses to make a request when it believes it is offline, and
     * that belief was only ever corrected *by* a request completing. One
     * transport failure and the app stopped syncing until it was restarted -
     * including the manual button in Settings.
     */
    @Test
    fun `Sync Now still reaches the network when the app believes it is offline`() = runTest {
        val manager = manager()
        monitor.reportUnreachable()

        manager.syncNow(SyncReason.MANUAL)

        assertEquals(
            listOf(SyncReason.MANUAL),
            engine.cycles,
            "the user asking must always produce a real attempt",
        )
    }

    /** Automatic cycles probe too, but sparingly - once per interval, not per edit. */
    @Test
    fun `an offline device probes once rather than on every request`() = runTest {
        val manager = manager()
        monitor.reportUnreachable()

        manager.syncNow(SyncReason.LOCAL_CHANGE)
        manager.syncNow(SyncReason.LOCAL_CHANGE)
        manager.syncNow(SyncReason.PERIODIC)

        assertEquals(
            1,
            engine.cycles.size,
            "only the first attempt should have probed; cycles: ${engine.cycles}",
        )
    }

    @Test
    fun `reconnecting triggers a cycle`() = runTest {
        val manager = manager()
        manager.start()
        settle()
        val beforeReconnect = engine.cycles.size

        monitor.reportUnreachable()
        settle()
        monitor.reportReachable()
        settle()

        assertTrue(
            engine.cycles.size > beforeReconnect,
            "regaining the network should sync; cycles: ${engine.cycles}",
        )
        assertEquals(SyncReason.CONNECTIVITY_REGAINED, engine.cycles.last())
    }

    // -----------------------------------------------------------------------
    // Multi-device delivery
    // -----------------------------------------------------------------------

    /**
     * The receiving half of multi-device sync. Pushing has always been prompt;
     * without this poll the only thing that ever pulled was the fifteen-minute
     * tick, so a note written on the phone could sit unseen on the desktop for a
     * quarter of an hour.
     */
    @Test
    fun `the delta poll runs a cycle only when the server is ahead`() = runTest {
        val manager = manager()
        manager.start()
        settle()
        val afterStart = engine.cycles.size

        // Nothing new on the server.
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(engine.remoteChecks > 0, "the poll should have asked the server")
        assertEquals(
            afterStart,
            engine.cycles.size,
            "an up-to-date server must not cost a full cycle; cycles: ${engine.cycles}",
        )

        // Now another device has written something.
        engine.remoteChanges = true
        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals(SyncReason.REMOTE_CHANGE, engine.cycles.last())
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Test
    fun `the poll stops while the app is in the background`() = runTest {
        val manager = manager()
        manager.start()
        settle()

        lifecycle.set(false)
        val checksWhenBackgrounded = engine.remoteChecks

        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(
            checksWhenBackgrounded,
            engine.remoteChecks,
            "a backgrounded app should not be polling",
        )
    }

    @Test
    fun `returning to the foreground syncs`() = runTest {
        val manager = manager()
        manager.start()
        settle()

        lifecycle.set(false)
        settle()
        val whileBackgrounded = engine.cycles.size

        lifecycle.set(true)
        settle()

        assertTrue(
            engine.cycles.size > whileBackgrounded,
            "coming back should refresh; cycles: ${engine.cycles}",
        )
        assertEquals(SyncReason.FOREGROUND, engine.cycles.last())
    }

    // -----------------------------------------------------------------------
    // Sign-out
    // -----------------------------------------------------------------------

    @Test
    fun `stopping halts every trigger`() = runTest {
        insertPendingNote("note-1")
        val manager = manager()
        manager.start()
        settle()

        manager.stop()
        val afterStop = engine.cycles.size

        database.noteEntityQueries.updateContent(
            title = "After sign-out",
            content = "text",
            contentHash = "hash-3",
            updatedAt = 3_000,
            id = "note-1",
        )
        advanceTimeBy(120.seconds)
        runCurrent()

        assertEquals(afterStop, engine.cycles.size, "cycles: ${engine.cycles}")
        assertFalse(manager.summary.value.state.isRunning)
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /**
     * Long enough for the local-change debounce (3s) to elapse, and short enough
     * that the delta poll (30s) does not fire and confuse the count.
     */
    private fun TestScope.settle() {
        advanceTimeBy(4.seconds)
        runCurrent()
    }

    /** Immediate work only: shorter than the first retry backoff (2s). */
    private fun TestScope.settleBriefly() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private fun TestScope.manager(): DefaultSyncManager {
        val dispatchers = SingleDispatcherProvider(StandardTestDispatcher(testScheduler))
        return DefaultSyncManager(
            engine = engine,
            store = SyncLocalStore(database, dispatchers),
            database = database,
            authRepository = FakeAuthRepository(userId),
            networkMonitor = monitor,
            lifecycle = lifecycle,
            dispatchers = dispatchers,
            // Cancelled when the test ends, so the trigger loops cannot outlive it.
            scope = backgroundScope,
        )
    }

    private fun insertPendingNote(id: String) {
        database.noteEntityQueries.insertNote(
            id = id,
            userId = userId,
            title = "Note",
            content = "text",
            contentType = "PLAIN",
            color = null,
            isPinned = false,
            isArchived = false,
            isDeleted = false,
            sortIndex = null,
            createdAt = 1_000,
            updatedAt = 1_000,
            deletedAt = null,
            syncStatus = "PENDING",
            localRevision = 1,
            baseVersion = 0,
            baseContentHash = null,
            contentHash = "hash-1",
            syncError = null,
            syncAttempts = 0,
            conflictOfNoteId = null,
        )
    }
}

private class RecordingSyncCycle : SyncCycle {

    val cycles = mutableListOf<SyncReason>()
    var result = SyncResult()
    var remoteChanges = false
    var remoteChecks = 0

    override suspend fun sync(userId: String, reason: SyncReason): SyncResult {
        cycles += reason
        return result
    }

    override suspend fun hasRemoteChanges(userId: String): Boolean {
        remoteChecks++
        return remoteChanges
    }
}

private class FakeConnectivityObserver : ConnectivityObserver {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}

private class FakeAppLifecycleMonitor : AppLifecycleMonitor {
    private val state = MutableStateFlow(true)
    override val isActive: StateFlow<Boolean> = state
    fun set(active: Boolean) {
        state.value = active
    }
}

private class SingleDispatcherProvider(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}

/** Signed in, and nothing else: the scheduler only ever reads the user id. */
private class FakeAuthRepository(userId: String) : AuthRepository {

    private val user = User(id = userId, email = "user@example.com", name = "Test User")

    override val authState: StateFlow<AuthState> =
        MutableStateFlow(AuthState.Authenticated(user))

    override suspend fun restoreSession(): AuthState = authState.value
    override suspend fun register(name: String, email: String, password: String): Outcome<User> =
        Outcome.Success(user)

    override suspend fun login(email: String, password: String): Outcome<User> =
        Outcome.Success(user)

    override suspend fun logout(): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun requestPasswordReset(email: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun refreshProfile(): Outcome<User> = Outcome.Success(user)
    override suspend fun updateProfile(name: String): Outcome<User> = Outcome.Success(user)
}
