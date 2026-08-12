package com.arunrk.notes.api.v1.auth

import com.arunrk.notes.api.support.RequestContexts
import com.arunrk.notes.domain.usecase.auth.ChangePasswordCommand
import com.arunrk.notes.domain.usecase.auth.ChangePasswordUseCase
import com.arunrk.notes.domain.usecase.auth.LoginCommand
import com.arunrk.notes.domain.usecase.auth.LoginUseCase
import com.arunrk.notes.domain.usecase.auth.LogoutAllUseCase
import com.arunrk.notes.domain.usecase.auth.LogoutUseCase
import com.arunrk.notes.domain.usecase.auth.RefreshCommand
import com.arunrk.notes.domain.usecase.auth.RefreshSessionUseCase
import com.arunrk.notes.domain.usecase.auth.RegisterCommand
import com.arunrk.notes.domain.usecase.auth.RegisterUserUseCase
import com.arunrk.notes.domain.usecase.auth.RequestPasswordResetUseCase
import com.arunrk.notes.domain.usecase.auth.ResetPasswordCommand
import com.arunrk.notes.domain.usecase.auth.ResetPasswordUseCase
import com.arunrk.notes.infrastructure.security.CurrentUser
import com.arunrk.notes.infrastructure.security.RateLimiter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController(
    private val registerUser: RegisterUserUseCase,
    private val login: LoginUseCase,
    private val refreshSession: RefreshSessionUseCase,
    private val logout: LogoutUseCase,
    private val logoutAll: LogoutAllUseCase,
    private val requestPasswordReset: RequestPasswordResetUseCase,
    private val resetPassword: ResetPasswordUseCase,
    private val changePassword: ChangePasswordUseCase,
    private val rateLimiter: RateLimiter,
) {

    @PostMapping("/register")
    @Operation(summary = "Create an account and start a session")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        val session = registerUser.execute(
            RegisterCommand(
                name = request.name,
                email = request.email,
                password = request.password,
                context = RequestContexts.sessionContext(httpRequest),
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(session))
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access + refresh token pair")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
    ): AuthResponse {
        // Keyed on email AND ip: email alone lets one attacker lock a victim out
        // of their own account; ip alone is trivially defeated from a botnet.
        rateLimiter.checkLogin("${request.email.lowercase()}|${httpRequest.remoteAddr}")

        val session = login.execute(
            LoginCommand(
                email = request.email,
                password = request.password,
                context = RequestContexts.sessionContext(httpRequest),
            )
        )
        return AuthResponse.from(session)
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token for a new pair")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
        httpRequest: HttpServletRequest,
    ): AuthResponse {
        val session = refreshSession.execute(
            RefreshCommand(
                refreshToken = request.refreshToken,
                context = RequestContexts.sessionContext(httpRequest),
            )
        )
        return AuthResponse.from(session)
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke this device's session")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestBody(required = false) request: LogoutRequest?) {
        logout.execute(request?.refreshToken)
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every session for the current user")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll() {
        logoutAll.execute(CurrentUser.id())
    }

    @PostMapping("/forgot-password")
    @Operation(
        summary = "Start a password reset",
        description = "Always returns 202, whether or not the address has an account. " +
            "Reporting otherwise would make this endpoint a user-enumeration oracle.",
    )
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    fun forgotPassword(
        @Valid @RequestBody request: ForgotPasswordRequest,
        httpRequest: HttpServletRequest,
    ) {
        rateLimiter.checkForgotPassword("${request.email.lowercase()}|${httpRequest.remoteAddr}")
        requestPasswordReset.execute(request.email)
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Complete a password reset; revokes all sessions")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest) {
        resetPassword.execute(
            ResetPasswordCommand(token = request.token, newPassword = request.newPassword)
        )
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password; revokes other sessions")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest) {
        changePassword.execute(
            ChangePasswordCommand(
                userId = CurrentUser.id(),
                currentPassword = request.currentPassword,
                newPassword = request.newPassword,
                keepRefreshToken = request.refreshToken,
            )
        )
    }
}
