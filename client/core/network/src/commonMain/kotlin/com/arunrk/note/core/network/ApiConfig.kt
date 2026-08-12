package com.arunrk.note.core.network

/**
 * @param baseUrl root of the API, without a trailing slash, e.g.
 *   `http://10.0.2.2:8080` from the Android emulator or `http://localhost:8080`
 *   on desktop. The emulator cannot reach the host's localhost, which is the
 *   single most common cause of "the app can't see my server".
 */
data class ApiConfig(
    val baseUrl: String,
    val platformName: String,
    val appVersion: String = "1.0.0",
    val enableLogging: Boolean = true,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 15_000,
) {
    val apiRoot: String get() = "${baseUrl.trimEnd('/')}/api/v1"
}

object ApiPaths {
    const val REGISTER = "auth/register"
    const val LOGIN = "auth/login"
    const val REFRESH = "auth/refresh"
    const val LOGOUT = "auth/logout"
    const val LOGOUT_ALL = "auth/logout-all"
    const val FORGOT_PASSWORD = "auth/forgot-password"
    const val RESET_PASSWORD = "auth/reset-password"
    const val CHANGE_PASSWORD = "auth/change-password"

    const val ME = "users/me"

    const val NOTES = "notes"
    const val NOTES_SEARCH = "notes/search"

    const val SYNC_PUSH = "sync/push"
    const val SYNC_PULL = "sync/pull"
    const val SYNC_STATUS = "sync/status"

    /**
     * Endpoints that must never carry an Authorization header, and that the
     * refresh machinery must not try to retry on a 401 - otherwise a failed
     * login would trigger a token refresh loop.
     */
    val PUBLIC = setOf(REGISTER, LOGIN, REFRESH, FORGOT_PASSWORD, RESET_PASSWORD, LOGOUT)
}

object ApiHeaders {
    const val DEVICE_ID = "X-Device-Id"
    const val DEVICE_PLATFORM = "X-Device-Platform"
    const val IF_MATCH = "If-Match"
}
