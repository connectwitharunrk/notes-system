package com.arunrk.notes.domain.usecase.auth

import com.arunrk.notes.common.time.MutableTimeProvider
import com.arunrk.notes.domain.fake.FakeAccessTokenIssuer
import com.arunrk.notes.domain.fake.FakePasswordHasher
import com.arunrk.notes.domain.fake.InMemoryDeviceRepository
import com.arunrk.notes.domain.fake.InMemoryPasswordResetTokenRepository
import com.arunrk.notes.domain.fake.InMemoryRefreshTokenRepository
import com.arunrk.notes.domain.fake.InMemoryUserRepository
import com.arunrk.notes.domain.fake.RecordingEmailSender
import com.arunrk.notes.domain.fake.RollingBackTransactor
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.policy.AuthPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Assembles the auth use cases over in-memory ports with a frozen clock.
 */
class AuthTestFixture {

    val time = MutableTimeProvider(Instant.parse("2026-08-12T10:00:00Z"))
    val users = InMemoryUserRepository()
    val devices = InMemoryDeviceRepository()
    val refreshTokens = InMemoryRefreshTokenRepository()
    val resetTokens = InMemoryPasswordResetTokenRepository()
    val passwordHasher = FakePasswordHasher()
    val emailSender = RecordingEmailSender()
    // Rolls back on exception, exactly as the real TransactionTemplate does.
    // A permissive transactor previously let a rolled-back family revocation
    // look like a passing test.
    private val transactor = RollingBackTransactor(
        listOf(users.rows, devices.rows, refreshTokens.rows, resetTokens.rows)
    )

    val policy = AuthPolicy(
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(60),
        desktopRefreshTokenTtl = Duration.ofDays(7),
        passwordResetTtl = Duration.ofMinutes(30),
    )

    private val sessionIssuer = SessionIssuer(
        accessTokenIssuer = FakeAccessTokenIssuer(),
        refreshTokens = refreshTokens,
        time = time,
        policy = policy,
    )

    val register = RegisterUserUseCase(users, devices, passwordHasher, sessionIssuer, transactor, time)
    val login = LoginUseCase(users, devices, passwordHasher, sessionIssuer, transactor, time)
    val refresh = RefreshSessionUseCase(users, devices, refreshTokens, sessionIssuer, transactor, time)
    val logout = LogoutUseCase(refreshTokens, time)
    val logoutAll = LogoutAllUseCase(refreshTokens, time)
    val requestReset = RequestPasswordResetUseCase(users, resetTokens, emailSender, transactor, time, policy)
    val resetPassword = ResetPasswordUseCase(users, resetTokens, refreshTokens, passwordHasher, transactor, time)
    val changePassword = ChangePasswordUseCase(users, refreshTokens, passwordHasher, transactor, time)

    fun context(
        deviceId: UUID? = UUID.randomUUID(),
        platform: DevicePlatform = DevicePlatform.ANDROID,
    ) = SessionContext(deviceId = deviceId, platform = platform, userAgent = "test", ipAddress = "127.0.0.1")

    fun registerUser(
        name: String = "Arun",
        email: String = "arun@example.com",
        password: String = "correct-horse-1",
    ) = register.execute(RegisterCommand(name, email, password, context()))
}
