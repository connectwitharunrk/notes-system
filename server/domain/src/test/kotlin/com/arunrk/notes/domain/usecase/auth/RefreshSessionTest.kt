package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefreshSessionTest {

    private val fixture = AuthTestFixture()

    @Test
    fun `refresh rotates the token and issues a different one`() {
        val initial = fixture.registerUser()

        val rotated = fixture.refresh.execute(
            RefreshCommand(initial.tokens.refreshToken, fixture.context())
        )

        assertNotEquals(initial.tokens.refreshToken, rotated.tokens.refreshToken)
        assertTrue(rotated.tokens.accessToken.isNotBlank())
    }

    @Test
    fun `the previous refresh token stops working after rotation`() {
        val initial = fixture.registerUser()
        fixture.refresh.execute(RefreshCommand(initial.tokens.refreshToken, fixture.context()))

        val error = assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(initial.tokens.refreshToken, fixture.context()))
        }
        assertEquals(ErrorCode.TOKEN_REUSE_DETECTED, error.code)
    }

    /**
     * The core anti-theft property. Replaying a rotated-away token proves a copy
     * escaped, so the whole rotation chain dies - the attacker is locked out and
     * the legitimate device is forced to re-authenticate.
     */
    @Test
    fun `replaying an old token revokes the entire family including the current one`() {
        val initial = fixture.registerUser()
        val rotated = fixture.refresh.execute(
            RefreshCommand(initial.tokens.refreshToken, fixture.context())
        )

        assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(initial.tokens.refreshToken, fixture.context()))
        }

        // The successor that was live a moment ago must now be dead too.
        val afterBreach = assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(rotated.tokens.refreshToken, fixture.context()))
        }
        assertEquals(ErrorCode.TOKEN_REUSE_DETECTED, afterBreach.code)
        assertEquals(0, fixture.refreshTokens.activeCountFor(initial.user.id))
    }

    /**
     * Regression: the new refresh token carries a device_id foreign key, so a
     * device the server has never stored (reinstall, restored backup, a device
     * the user removed) used to fail the insert with a constraint violation
     * surfaced as a 500. Refresh must register the device instead.
     */
    @Test
    fun `refresh registers a device it has not seen before`() {
        val initial = fixture.registerUser()
        val unknownDevice = UUID.randomUUID()
        assertNull(fixture.devices.rows[unknownDevice])

        val rotated = fixture.refresh.execute(
            RefreshCommand(initial.tokens.refreshToken, fixture.context(deviceId = unknownDevice))
        )

        assertTrue(rotated.tokens.refreshToken.isNotBlank())
        assertNotNull(fixture.devices.rows[unknownDevice], "device should have been registered")
        assertEquals(initial.user.id, fixture.devices.rows[unknownDevice]?.userId)
    }

    @Test
    fun `refresh updates the last seen time of a known device`() {
        val deviceId = UUID.randomUUID()
        val initial = fixture.register.execute(
            RegisterCommand("Arun", "arun@example.com", "correct-horse-1", fixture.context(deviceId = deviceId))
        )
        val firstSeen = fixture.devices.rows[deviceId]!!.lastSeenAt

        fixture.time.advance(60_000)
        fixture.refresh.execute(
            RefreshCommand(initial.tokens.refreshToken, fixture.context(deviceId = deviceId))
        )

        assertTrue(fixture.devices.rows[deviceId]!!.lastSeenAt.isAfter(firstSeen))
    }

    @Test
    fun `refresh rejects an unknown token`() {
        val error = assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand("not-a-real-token", fixture.context()))
        }
        assertEquals(ErrorCode.TOKEN_INVALID, error.code)
    }

    @Test
    fun `refresh rejects an expired token`() {
        val initial = fixture.registerUser()

        fixture.time.advance(fixture.policy.refreshTokenTtl.toMillis() + 1_000)

        val error = assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(initial.tokens.refreshToken, fixture.context()))
        }
        assertEquals(ErrorCode.TOKEN_EXPIRED, error.code)
    }

    @Test
    fun `rotation stays within one family so a breach revokes exactly one session chain`() {
        val first = fixture.registerUser()
        val second = fixture.login.execute(
            LoginCommand("arun@example.com", "correct-horse-1", fixture.context())
        )

        // Break the first chain.
        fixture.refresh.execute(RefreshCommand(first.tokens.refreshToken, fixture.context()))
        assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(first.tokens.refreshToken, fixture.context()))
        }

        // The other device logged in separately, so its family is untouched.
        val stillWorks = fixture.refresh.execute(
            RefreshCommand(second.tokens.refreshToken, fixture.context())
        )
        assertTrue(stillWorks.tokens.refreshToken.isNotBlank())
    }

    @Test
    fun `logout revokes only the calling session`() {
        val first = fixture.registerUser()
        val second = fixture.login.execute(
            LoginCommand("arun@example.com", "correct-horse-1", fixture.context())
        )

        fixture.logout.execute(first.tokens.refreshToken)

        assertFailsWith<AppException> {
            fixture.refresh.execute(RefreshCommand(first.tokens.refreshToken, fixture.context()))
        }
        assertTrue(
            fixture.refresh.execute(RefreshCommand(second.tokens.refreshToken, fixture.context()))
                .tokens.refreshToken.isNotBlank()
        )
    }

    @Test
    fun `logout is idempotent and tolerates unknown or missing tokens`() {
        val session = fixture.registerUser()

        // A client retrying after a network failure must never be able to fail here.
        fixture.logout.execute(session.tokens.refreshToken)
        fixture.logout.execute(session.tokens.refreshToken)
        fixture.logout.execute("never-issued")
        fixture.logout.execute(null)
        fixture.logout.execute("")
    }

    @Test
    fun `logout all revokes every session for the user`() {
        val session = fixture.registerUser()
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))

        fixture.logoutAll.execute(session.user.id)

        assertEquals(0, fixture.refreshTokens.activeCountFor(session.user.id))
    }
}
