package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.domain.model.DevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RegisterAndLoginTest {

    private val fixture = AuthTestFixture()

    @Test
    fun `register normalises the email and never stores the raw password`() {
        val session = fixture.register.execute(
            RegisterCommand(
                name = "  Arun  ",
                email = "  Arun@Example.COM ",
                password = "correct-horse-1",
                context = fixture.context(),
            )
        )

        assertEquals("arun@example.com", session.user.email)
        assertEquals("Arun", session.user.name)
        assertNotEquals("correct-horse-1", session.user.passwordHash)
        assertTrue(session.tokens.accessToken.isNotBlank())
        assertTrue(session.tokens.refreshToken.isNotBlank())
    }

    @Test
    fun `register rejects a duplicate email regardless of casing`() {
        fixture.registerUser(email = "arun@example.com")

        val error = assertFailsWith<AppException> {
            fixture.registerUser(email = "ARUN@EXAMPLE.COM")
        }
        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, error.code)
    }

    @Test
    fun `register enforces the password policy`() {
        val error = assertFailsWith<AppException> {
            fixture.registerUser(password = "short")
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.code)
        assertTrue(error.details.isNotEmpty(), "expected field-level violations")
    }

    @Test
    fun `register stores the device so conflict copies can be attributed`() {
        val context = fixture.context(platform = DevicePlatform.IOS)
        fixture.register.execute(
            RegisterCommand("Arun", "arun@example.com", "correct-horse-1", context)
        )

        val device = fixture.devices.rows[context.deviceId!!]
        assertEquals(DevicePlatform.IOS, device?.platform)
    }

    @Test
    fun `login succeeds with correct credentials`() {
        fixture.registerUser()

        val session = fixture.login.execute(
            LoginCommand("arun@example.com", "correct-horse-1", fixture.context())
        )

        assertEquals("arun@example.com", session.user.email)
        assertTrue(session.tokens.refreshToken.isNotBlank())
    }

    @Test
    fun `login rejects a wrong password`() {
        fixture.registerUser()

        val error = assertFailsWith<AppException> {
            fixture.login.execute(LoginCommand("arun@example.com", "wrong-password-1", fixture.context()))
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, error.code)
    }

    /**
     * The unknown-email branch must burn the same CPU as a real verification.
     * Returning early there would make response latency a user-enumeration
     * oracle on an unauthenticated endpoint.
     */
    @Test
    fun `login hashes a dummy password when the email is unknown`() {
        val error = assertFailsWith<AppException> {
            fixture.login.execute(LoginCommand("nobody@example.com", "correct-horse-1", fixture.context()))
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, error.code)
        assertEquals(1, fixture.passwordHasher.dummyCalls)
    }

    @Test
    fun `login reports the same error for unknown email and wrong password`() {
        fixture.registerUser()

        val unknownEmail = assertFailsWith<AppException> {
            fixture.login.execute(LoginCommand("nobody@example.com", "correct-horse-1", fixture.context()))
        }
        val wrongPassword = assertFailsWith<AppException> {
            fixture.login.execute(LoginCommand("arun@example.com", "wrong-password-1", fixture.context()))
        }

        assertEquals(unknownEmail.code, wrongPassword.code)
        assertEquals(unknownEmail.message, wrongPassword.message)
    }

    @Test
    fun `desktop sessions get a shorter refresh token lifetime than mobile`() {
        fixture.registerUser()

        val desktop = fixture.login.execute(
            LoginCommand("arun@example.com", "correct-horse-1", fixture.context(platform = DevicePlatform.DESKTOP))
        )
        val android = fixture.login.execute(
            LoginCommand("arun@example.com", "correct-horse-1", fixture.context(platform = DevicePlatform.ANDROID))
        )

        assertTrue(
            desktop.tokens.refreshTokenExpiresAt.isBefore(android.tokens.refreshTokenExpiresAt),
            "desktop cannot store tokens securely, so its window must be shorter",
        )
    }
}
