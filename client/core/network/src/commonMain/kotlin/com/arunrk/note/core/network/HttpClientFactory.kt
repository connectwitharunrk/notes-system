package com.arunrk.note.core.network

import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.network.dto.AuthResponseDto
import com.arunrk.note.core.network.dto.RefreshRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val TAG = "Http"

/**
 * Builds the shared HTTP client.
 *
 * The interesting part is token refresh. When several requests are in flight and
 * the access token expires, every one of them gets a 401 at roughly the same
 * moment. Refreshing per request would fire N concurrent refreshes against a
 * *rotating* refresh token - the first succeeds, rotates the token, and the rest
 * present a token that has just been consumed. The server correctly reads that
 * as replay, revokes the entire family, and logs the user out.
 *
 * So refresh is single-flight: the first caller performs it under a mutex and
 * the rest wait and reuse the result.
 */
fun createHttpClient(
    config: ApiConfig,
    tokenStore: TokenStore,
    engineConfig: HttpClientConfig<*>.() -> Unit = {},
): HttpClient {
    val json = Json {
        ignoreUnknownKeys = true      // the server may add fields; that must not break old clients
        explicitNulls = false
        isLenient = true
        encodeDefaults = true
    }

    val refreshMutex = Mutex()

    return createPlatformHttpClient {
        expectSuccess = false          // errors are mapped explicitly, not thrown raw

        install(ContentNegotiation) { json(json) }

        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.requestTimeoutMillis
        }

        if (config.enableLogging) {
            install(Logging) {
                // HEADERS, never BODY: request bodies contain passwords and note
                // text, and response bodies contain everything the user wrote.
                level = LogLevel.HEADERS
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) = Log.d(TAG, message)
                }
            }
        }

        install(Auth) {
            bearer {
                loadTokens {
                    val access = tokenStore.accessToken() ?: return@loadTokens null
                    BearerTokens(access, tokenStore.refreshToken().orEmpty())
                }

                refreshTokens {
                    refreshMutex.withLock {
                        // Another caller may have refreshed while we queued. If
                        // the token changed under us, reuse theirs rather than
                        // burning ours - rotation makes a second refresh fatal.
                        val current = tokenStore.accessToken()
                        if (current != null && current != oldTokens?.accessToken) {
                            return@withLock BearerTokens(current, tokenStore.refreshToken().orEmpty())
                        }

                        val refresh = tokenStore.refreshToken()
                        if (refresh.isNullOrBlank()) {
                            tokenStore.clearSession()
                            return@withLock null
                        }

                        val response: HttpResponse = client.post("${config.apiRoot}/${ApiPaths.REFRESH}") {
                            markAsRefreshTokenRequest()
                            contentType(ContentType.Application.Json)
                            setBody(RefreshRequestDto(refresh))
                        }

                        if (response.status != HttpStatusCode.OK) {
                            // 401 here means expired, invalid, or - worst case -
                            // reuse detected. All three are terminal: the session
                            // is gone and the user must sign in again.
                            Log.w(TAG, "Token refresh failed with ${response.status}")
                            tokenStore.clearSession()
                            return@withLock null
                        }

                        val body = response.body<AuthResponseDto>()
                        tokenStore.updateTokens(
                            accessToken = body.tokens.accessToken,
                            accessTokenExpiresAt = parseIsoToEpochMillis(body.tokens.accessTokenExpiresAt),
                            refreshToken = body.tokens.refreshToken,
                            refreshTokenExpiresAt = parseIsoToEpochMillis(body.tokens.refreshTokenExpiresAt),
                        )
                        BearerTokens(body.tokens.accessToken, body.tokens.refreshToken)
                    }
                }

                sendWithoutRequest { request ->
                    // Attach the token up front for everything except the public
                    // auth endpoints. Sending it to /auth/login is harmless but
                    // pointless; sending it to /auth/refresh confuses the plugin.
                    val url = request.url.buildString()
                    ApiPaths.PUBLIC.none { url.endsWith("/$it") }
                }
            }
        }

        defaultRequest {
            url("${config.apiRoot}/")
            contentType(ContentType.Application.Json)
            header(ApiHeaders.DEVICE_PLATFORM, config.platformName)
        }

        engineConfig()
    }
}

/** Each target supplies its own engine: OkHttp, Darwin or CIO. */
internal expect fun createPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient
