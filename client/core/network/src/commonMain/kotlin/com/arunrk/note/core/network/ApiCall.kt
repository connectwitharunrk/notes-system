package com.arunrk.note.core.network

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.network.dto.ErrorEnvelopeDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Runs a request and turns every outcome into an [Outcome].
 *
 * Three kinds of failure are deliberately kept apart, because the app reacts to
 * each differently: a transport failure means offline (retry later, show the
 * banner), a 4xx means the request was wrong (do not retry), and a 5xx means the
 * server broke (retry with backoff).
 */
suspend inline fun <reified T> executeRequest(
    client: HttpClient,
    monitor: NetworkMonitor,
    crossinline request: suspend HttpClient.() -> HttpResponse,
): Outcome<T> = try {
    val response = client.request()
    monitor.reportReachable()

    if (response.status.isSuccess()) {
        // 204 and friends have no body; asking Ktor to deserialise one throws.
        if (T::class == Unit::class) {
            @Suppress("UNCHECKED_CAST")
            Outcome.Success(Unit as T)
        } else {
            Outcome.Success(response.body<T>())
        }
    } else {
        Outcome.Failure(response.toAppError())
    }
} catch (e: CancellationException) {
    // Never swallow cancellation - doing so turns a cancelled screen into a
    // spurious error toast and breaks structured concurrency.
    throw e
} catch (e: HttpRequestTimeoutException) {
    monitor.reportUnreachable()
    Outcome.Failure(AppError.Timeout)
} catch (e: ConnectTimeoutException) {
    monitor.reportUnreachable()
    Outcome.Failure(AppError.Timeout)
} catch (e: SocketTimeoutException) {
    monitor.reportUnreachable()
    Outcome.Failure(AppError.Timeout)
} catch (e: IOException) {
    // Genuine transport failure: DNS, refused connection, dropped socket, TLS.
    // From the user's point of view that is "offline".
    Log.w("Api", "Transport failure: ${e::class.simpleName}", e)
    monitor.reportUnreachable()
    Outcome.Failure(AppError.Offline)
} catch (e: Exception) {
    // NOT offline. Reporting a local storage failure, a serialization mismatch
    // or an outright bug as "you're offline" tells the user to check their wifi
    // for a problem their network cannot fix, and hides the real fault from us.
    // The network state is deliberately left untouched here.
    Log.e("Api", "Unexpected failure: ${e::class.simpleName}", e)
    Outcome.Failure(AppError.Unknown(e.message ?: "Unexpected error", e))
}

/**
 * Maps an error response onto [AppError], preferring the server's stable error
 * code over its human-readable message.
 */
suspend fun HttpResponse.toAppError(): AppError {
    val envelope = runCatching { body<ErrorEnvelopeDto>() }.getOrNull()
    val code = envelope?.error?.code.orEmpty()
    val message = envelope?.error?.message ?: "Request failed (${status.value})"

    return when (status.value) {
        400 -> AppError.Validation(
            message = message,
            fieldErrors = envelope?.error?.details.orEmpty().associate { it.field to it.message },
        )

        401 -> when (code) {
            "INVALID_CREDENTIALS" -> AppError.InvalidCredentials(message)
            // TOKEN_EXPIRED, TOKEN_INVALID and TOKEN_REUSE_DETECTED all mean the
            // session is unrecoverable by the time they reach here: the refresh
            // flow already tried and failed.
            else -> AppError.Unauthenticated
        }

        403 -> AppError.Server(code.ifEmpty { "FORBIDDEN" }, message)
        404 -> AppError.NotFound(message)
        409 -> AppError.Conflict(code.ifEmpty { "CONFLICT" }, message)
        413 -> AppError.Validation(message)
        429 -> AppError.RateLimited(message)
        in 500..599 -> AppError.Server(code.ifEmpty { "INTERNAL_ERROR" }, message)
        else -> AppError.Unknown(message)
    }
}
