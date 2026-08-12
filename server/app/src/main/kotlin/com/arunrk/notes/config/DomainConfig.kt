package com.arunrk.notes.config

import com.arunrk.notes.common.time.SystemTimeProvider
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.policy.AuthPolicy
import com.arunrk.notes.domain.port.AccessTokenIssuer
import com.arunrk.notes.domain.port.DeviceRepository
import com.arunrk.notes.domain.port.EmailSender
import com.arunrk.notes.domain.port.PasswordHasher
import com.arunrk.notes.domain.port.PasswordResetTokenRepository
import com.arunrk.notes.domain.port.RefreshTokenRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.port.UserRepository
import com.arunrk.notes.domain.usecase.auth.ChangePasswordUseCase
import com.arunrk.notes.domain.usecase.auth.LoginUseCase
import com.arunrk.notes.domain.usecase.auth.LogoutAllUseCase
import com.arunrk.notes.domain.usecase.auth.LogoutUseCase
import com.arunrk.notes.domain.usecase.auth.RefreshSessionUseCase
import com.arunrk.notes.domain.usecase.auth.RegisterUserUseCase
import com.arunrk.notes.domain.usecase.auth.RequestPasswordResetUseCase
import com.arunrk.notes.domain.usecase.auth.ResetPasswordUseCase
import com.arunrk.notes.domain.usecase.auth.SessionIssuer
import com.arunrk.notes.domain.usecase.user.GetCurrentUserUseCase
import com.arunrk.notes.domain.usecase.user.UpdateProfileUseCase
import com.arunrk.notes.infrastructure.mail.MailProperties
import com.arunrk.notes.infrastructure.security.AuthProperties
import com.arunrk.notes.infrastructure.security.RateLimitProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Explicit wiring for the domain layer.
 *
 * Use cases are constructed here as plain objects rather than annotated with
 * @Service, which is what keeps :domain free of any Spring import. The cost is
 * this file; the benefit is that the business core stays independently testable
 * and portable.
 */
@Configuration
@EnableConfigurationProperties(
    AuthProperties::class,
    RateLimitProperties::class,
    MailProperties::class,
)
class DomainConfig {

    @Bean
    fun timeProvider(): TimeProvider = SystemTimeProvider()

    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager) =
        TransactionTemplate(transactionManager)

    @Bean
    fun authPolicy(properties: AuthProperties) = AuthPolicy(
        accessTokenTtl = properties.accessTokenTtl,
        refreshTokenTtl = properties.refreshTokenTtl,
        desktopRefreshTokenTtl = properties.desktopRefreshTokenTtl,
        passwordResetTtl = properties.passwordResetTtl,
    )

    @Bean
    fun sessionIssuer(
        accessTokenIssuer: AccessTokenIssuer,
        refreshTokens: RefreshTokenRepository,
        time: TimeProvider,
        policy: AuthPolicy,
    ) = SessionIssuer(accessTokenIssuer, refreshTokens, time, policy)

    // ---- auth use cases ---------------------------------------------------

    @Bean
    fun registerUserUseCase(
        users: UserRepository,
        devices: DeviceRepository,
        passwordHasher: PasswordHasher,
        sessionIssuer: SessionIssuer,
        transactor: Transactor,
        time: TimeProvider,
    ) = RegisterUserUseCase(users, devices, passwordHasher, sessionIssuer, transactor, time)

    @Bean
    fun loginUseCase(
        users: UserRepository,
        devices: DeviceRepository,
        passwordHasher: PasswordHasher,
        sessionIssuer: SessionIssuer,
        transactor: Transactor,
        time: TimeProvider,
    ) = LoginUseCase(users, devices, passwordHasher, sessionIssuer, transactor, time)

    @Bean
    fun refreshSessionUseCase(
        users: UserRepository,
        devices: DeviceRepository,
        refreshTokens: RefreshTokenRepository,
        sessionIssuer: SessionIssuer,
        transactor: Transactor,
        time: TimeProvider,
    ) = RefreshSessionUseCase(users, devices, refreshTokens, sessionIssuer, transactor, time)

    @Bean
    fun logoutUseCase(refreshTokens: RefreshTokenRepository, time: TimeProvider) =
        LogoutUseCase(refreshTokens, time)

    @Bean
    fun logoutAllUseCase(refreshTokens: RefreshTokenRepository, time: TimeProvider) =
        LogoutAllUseCase(refreshTokens, time)

    @Bean
    fun requestPasswordResetUseCase(
        users: UserRepository,
        resetTokens: PasswordResetTokenRepository,
        emailSender: EmailSender,
        transactor: Transactor,
        time: TimeProvider,
        policy: AuthPolicy,
    ) = RequestPasswordResetUseCase(users, resetTokens, emailSender, transactor, time, policy)

    @Bean
    fun resetPasswordUseCase(
        users: UserRepository,
        resetTokens: PasswordResetTokenRepository,
        refreshTokens: RefreshTokenRepository,
        passwordHasher: PasswordHasher,
        transactor: Transactor,
        time: TimeProvider,
    ) = ResetPasswordUseCase(users, resetTokens, refreshTokens, passwordHasher, transactor, time)

    @Bean
    fun changePasswordUseCase(
        users: UserRepository,
        refreshTokens: RefreshTokenRepository,
        passwordHasher: PasswordHasher,
        transactor: Transactor,
        time: TimeProvider,
    ) = ChangePasswordUseCase(users, refreshTokens, passwordHasher, transactor, time)

    // ---- user use cases ---------------------------------------------------

    @Bean
    fun getCurrentUserUseCase(users: UserRepository) = GetCurrentUserUseCase(users)

    @Bean
    fun updateProfileUseCase(users: UserRepository, time: TimeProvider) =
        UpdateProfileUseCase(users, time)
}
