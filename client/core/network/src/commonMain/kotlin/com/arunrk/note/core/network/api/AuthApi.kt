package com.arunrk.note.core.network.api

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.network.ApiHeaders
import com.arunrk.note.core.network.ApiPaths
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.core.network.dto.AuthResponseDto
import com.arunrk.note.core.network.dto.ChangePasswordRequestDto
import com.arunrk.note.core.network.dto.ForgotPasswordRequestDto
import com.arunrk.note.core.network.dto.LoginRequestDto
import com.arunrk.note.core.network.dto.LogoutRequestDto
import com.arunrk.note.core.network.dto.RegisterRequestDto
import com.arunrk.note.core.network.dto.ResetPasswordRequestDto
import com.arunrk.note.core.network.dto.UpdateProfileRequestDto
import com.arunrk.note.core.network.dto.UserDto
import com.arunrk.note.core.network.executeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class AuthApi(
    private val client: HttpClient,
    private val monitor: NetworkMonitor,
    private val tokenStore: TokenStore,
) {

    suspend fun register(name: String, email: String, password: String): Outcome<AuthResponseDto> =
        executeRequest(client, monitor) {
            post(ApiPaths.REGISTER) {
                header(ApiHeaders.DEVICE_ID, tokenStore.deviceId())
                setBody(RegisterRequestDto(name = name, email = email, password = password))
            }
        }

    suspend fun login(email: String, password: String): Outcome<AuthResponseDto> =
        executeRequest(client, monitor) {
            post(ApiPaths.LOGIN) {
                header(ApiHeaders.DEVICE_ID, tokenStore.deviceId())
                setBody(LoginRequestDto(email = email, password = password))
            }
        }

    /**
     * Best-effort: the caller has already cleared local state by the time this
     * runs, so a failure here only means the server-side token outlives its
     * client. It will expire on its own.
     */
    suspend fun logout(refreshToken: String?): Outcome<Unit> =
        executeRequest(client, monitor) {
            post(ApiPaths.LOGOUT) {
                header(ApiHeaders.DEVICE_ID, tokenStore.deviceId())
                setBody(LogoutRequestDto(refreshToken))
            }
        }

    suspend fun logoutAll(): Outcome<Unit> =
        executeRequest(client, monitor) { post(ApiPaths.LOGOUT_ALL) }

    /** Always succeeds server-side, whether or not the address has an account. */
    suspend fun forgotPassword(email: String): Outcome<Unit> =
        executeRequest(client, monitor) {
            post(ApiPaths.FORGOT_PASSWORD) { setBody(ForgotPasswordRequestDto(email)) }
        }

    suspend fun resetPassword(token: String, newPassword: String): Outcome<Unit> =
        executeRequest(client, monitor) {
            post(ApiPaths.RESET_PASSWORD) { setBody(ResetPasswordRequestDto(token, newPassword)) }
        }

    /**
     * Sends our own refresh token so this device stays signed in while every
     * other one is revoked.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Outcome<Unit> =
        executeRequest(client, monitor) {
            post(ApiPaths.CHANGE_PASSWORD) {
                setBody(
                    ChangePasswordRequestDto(
                        currentPassword = currentPassword,
                        newPassword = newPassword,
                        refreshToken = tokenStore.refreshToken(),
                    )
                )
            }
        }

    suspend fun me(): Outcome<UserDto> =
        executeRequest(client, monitor) { get(ApiPaths.ME) }

    suspend fun updateProfile(name: String): Outcome<UserDto> =
        executeRequest(client, monitor) {
            patch(ApiPaths.ME) { setBody(UpdateProfileRequestDto(name)) }
        }
}
