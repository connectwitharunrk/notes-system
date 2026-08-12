package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.hash.Hashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordFlowTest {

    private val fixture = AuthTestFixture()

    // ---- forgot password --------------------------------------------------

    @Test
    fun `forgot password emails a reset token to a known address`() {
        fixture.registerUser(email = "arun@example.com")

        fixture.requestReset.execute("Arun@Example.com")

        assertEquals(1, fixture.emailSender.sent.size)
        assertEquals("arun@example.com", fixture.emailSender.sent.single().to)
    }

    /**
     * Unauthenticated endpoint: it must behave identically for registered and
     * unregistered addresses, or it becomes a free user-enumeration oracle.
     */
    @Test
    fun `forgot password silently does nothing for an unknown address`() {
        fixture.requestReset.execute("nobody@example.com")

        assertTrue(fixture.emailSender.sent.isEmpty())
        assertTrue(fixture.resetTokens.rows.isEmpty())
    }

    @Test
    fun `requesting a new reset invalidates the previous one`() {
        fixture.registerUser()

        fixture.requestReset.execute("arun@example.com")
        val firstToken = fixture.emailSender.sent[0].token

        fixture.requestReset.execute("arun@example.com")

        // The older emailed link must stop working the moment a newer one exists.
        val error = assertFailsWith<AppException> {
            fixture.resetPassword.execute(ResetPasswordCommand(firstToken, "brand-new-pass-1"))
        }
        assertEquals(ErrorCode.TOKEN_INVALID, error.code)
    }

    @Test
    fun `the reset token is stored only as a hash`() {
        fixture.registerUser()
        fixture.requestReset.execute("arun@example.com")

        val rawToken = fixture.emailSender.sent.single().token
        val stored = fixture.resetTokens.rows.values.single()

        assertEquals(Hashing.sha256Hex(rawToken), stored.tokenHash)
        assertTrue(fixture.resetTokens.rows.values.none { it.tokenHash == rawToken })
    }

    // ---- reset password ---------------------------------------------------

    @Test
    fun `reset password changes the password and lets the user log in again`() {
        fixture.registerUser()
        fixture.requestReset.execute("arun@example.com")
        val token = fixture.emailSender.sent.single().token

        fixture.resetPassword.execute(ResetPasswordCommand(token, "brand-new-pass-1"))

        val session = fixture.login.execute(
            LoginCommand("arun@example.com", "brand-new-pass-1", fixture.context())
        )
        assertEquals("arun@example.com", session.user.email)
    }

    /**
     * A reset is the response to a suspected compromise, so every existing
     * session must die - including any the attacker is holding.
     */
    @Test
    fun `reset password revokes every existing session`() {
        val session = fixture.registerUser()
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))
        fixture.requestReset.execute("arun@example.com")
        val token = fixture.emailSender.sent.single().token

        fixture.resetPassword.execute(ResetPasswordCommand(token, "brand-new-pass-1"))

        assertEquals(0, fixture.refreshTokens.activeCountFor(session.user.id))
    }

    @Test
    fun `a reset token cannot be used twice`() {
        fixture.registerUser()
        fixture.requestReset.execute("arun@example.com")
        val token = fixture.emailSender.sent.single().token

        fixture.resetPassword.execute(ResetPasswordCommand(token, "brand-new-pass-1"))

        val error = assertFailsWith<AppException> {
            fixture.resetPassword.execute(ResetPasswordCommand(token, "another-pass-2"))
        }
        assertEquals(ErrorCode.TOKEN_INVALID, error.code)
    }

    @Test
    fun `an expired reset token is rejected`() {
        fixture.registerUser()
        fixture.requestReset.execute("arun@example.com")
        val token = fixture.emailSender.sent.single().token

        fixture.time.advance(fixture.policy.passwordResetTtl.toMillis() + 1_000)

        val error = assertFailsWith<AppException> {
            fixture.resetPassword.execute(ResetPasswordCommand(token, "brand-new-pass-1"))
        }
        assertEquals(ErrorCode.TOKEN_INVALID, error.code)
    }

    @Test
    fun `reset password enforces the password policy`() {
        fixture.registerUser()
        fixture.requestReset.execute("arun@example.com")
        val token = fixture.emailSender.sent.single().token

        val error = assertFailsWith<AppException> {
            fixture.resetPassword.execute(ResetPasswordCommand(token, "weak"))
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.code)
    }

    // ---- change password --------------------------------------------------

    @Test
    fun `change password requires the current password`() {
        val session = fixture.registerUser()

        val error = assertFailsWith<AppException> {
            fixture.changePassword.execute(
                ChangePasswordCommand(session.user.id, "not-my-password-1", "brand-new-pass-1")
            )
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, error.code)
    }

    @Test
    fun `change password rejects reusing the same password`() {
        val session = fixture.registerUser()

        val error = assertFailsWith<AppException> {
            fixture.changePassword.execute(
                ChangePasswordCommand(session.user.id, "correct-horse-1", "correct-horse-1")
            )
        }
        assertEquals(ErrorCode.VALIDATION_ERROR, error.code)
    }

    @Test
    fun `change password keeps the calling session and revokes the others`() {
        val mine = fixture.registerUser()
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))
        assertEquals(3, fixture.refreshTokens.activeCountFor(mine.user.id))

        fixture.changePassword.execute(
            ChangePasswordCommand(
                userId = mine.user.id,
                currentPassword = "correct-horse-1",
                newPassword = "brand-new-pass-1",
                keepRefreshToken = mine.tokens.refreshToken,
            )
        )

        assertEquals(1, fixture.refreshTokens.activeCountFor(mine.user.id))
        // The surviving session is specifically the caller's.
        val survivor = fixture.refreshTokens.rows.values.single { it.revokedAt == null }
        assertEquals(Hashing.sha256Hex(mine.tokens.refreshToken), survivor.tokenHash)
    }

    @Test
    fun `change password without a kept token signs out everywhere`() {
        val session = fixture.registerUser()
        fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))

        fixture.changePassword.execute(
            ChangePasswordCommand(session.user.id, "correct-horse-1", "brand-new-pass-1")
        )

        assertEquals(0, fixture.refreshTokens.activeCountFor(session.user.id))
    }

    /**
     * A token belonging to a different account must not be honoured as "keep
     * this one", or user A could preserve a session while resetting user B.
     */
    @Test
    fun `change password ignores a kept token that belongs to another user`() {
        val victim = fixture.registerUser(email = "victim@example.com")
        val attacker = fixture.registerUser(email = "attacker@example.com")

        fixture.changePassword.execute(
            ChangePasswordCommand(
                userId = victim.user.id,
                currentPassword = "correct-horse-1",
                newPassword = "brand-new-pass-1",
                keepRefreshToken = attacker.tokens.refreshToken,
            )
        )

        assertEquals(0, fixture.refreshTokens.activeCountFor(victim.user.id))
        assertEquals(1, fixture.refreshTokens.activeCountFor(attacker.user.id))
    }

    @Test
    fun `change password fails for an unknown user`() {
        val error = assertFailsWith<AppException> {
            fixture.changePassword.execute(
                ChangePasswordCommand(java.util.UUID.randomUUID(), "correct-horse-1", "brand-new-pass-1")
            )
        }
        assertEquals(ErrorCode.USER_NOT_FOUND, error.code)
    }

    @Test
    fun `the old password stops working after a change`() {
        val session = fixture.registerUser()

        fixture.changePassword.execute(
            ChangePasswordCommand(
                session.user.id, "correct-horse-1", "brand-new-pass-1", session.tokens.refreshToken
            )
        )

        assertFailsWith<AppException> {
            fixture.login.execute(LoginCommand("arun@example.com", "correct-horse-1", fixture.context()))
        }
        assertNull(
            fixture.users.rows[session.user.id]?.passwordHash?.takeIf { it == "hashed:correct-horse-1" }
        )
    }
}
