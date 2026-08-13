package com.arunrk.note.core.network

import com.arunrk.note.core.common.connectivity.ConnectivityObserver
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.AppError
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.network.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Error mapping, without a server.
 *
 * The point is that every failure the UI can encounter is a distinct, actionable
 * type. Collapsing them into one "something went wrong" is what leads to telling
 * a user to check their wifi when the real problem is a full disk - which is
 * precisely the bug this project already hit once.
 */
class ApiErrorMappingTest {

    private val monitor = NetworkMonitor(
        object : ConnectivityObserver {
            override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
        }
    )

    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
    ): HttpClient = HttpClient(
        MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
    ) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun errorEnvelope(code: String, message: String, details: String = "[]") = """
        {"error":{"code":"$code","message":"$message","details":$details,
        "traceId":"t","timestamp":"2026-08-12T10:00:00Z"}}
    """.trimIndent()

    private suspend fun request(client: HttpClient): Outcome<UserDto> =
        executeRequest(client, monitor) { get("http://test/users/me") }

    @Test
    fun `a successful response is decoded`() = runTest {
        val client = clientReturning(
            HttpStatusCode.OK,
            """{"id":"1","email":"a@b.com","name":"A","emailVerified":false,
               "createdAt":"2026-08-12T10:00:00Z","updatedAt":"2026-08-12T10:00:00Z"}""",
        )

        val result = assertIs<Outcome.Success<UserDto>>(request(client))
        assertEquals("a@b.com", result.value.email)
    }

    @Test
    fun `a wrong password is invalid credentials, not a generic failure`() = runTest {
        val client = clientReturning(
            HttpStatusCode.Unauthorized,
            errorEnvelope("INVALID_CREDENTIALS", "Invalid email or password"),
        )

        val error = assertIs<Outcome.Failure>(request(client)).error
        val invalid = assertIs<AppError.InvalidCredentials>(error)
        assertEquals("Invalid email or password", invalid.message)
    }

    /**
     * An expired token has already been through the refresh flow by the time it
     * reaches here, so it means the session is genuinely gone - distinct from a
     * wrong password, which the user can correct by typing.
     */
    @Test
    fun `an expired token means the session is over`() = runTest {
        val client = clientReturning(
            HttpStatusCode.Unauthorized,
            errorEnvelope("TOKEN_EXPIRED", "Access token has expired"),
        )

        assertEquals(AppError.Unauthenticated, assertIs<Outcome.Failure>(request(client)).error)
    }

    @Test
    fun `validation errors keep their per-field detail`() = runTest {
        val client = clientReturning(
            HttpStatusCode.BadRequest,
            errorEnvelope(
                "VALIDATION_ERROR",
                "Request validation failed",
                """[{"field":"email","message":"must be a well-formed email address"}]""",
            ),
        )

        val error = assertIs<AppError.Validation>(assertIs<Outcome.Failure>(request(client)).error)
        assertEquals("must be a well-formed email address", error.fieldErrors["email"])
    }

    @Test
    fun `a conflict keeps the server's stable code for the caller to branch on`() = runTest {
        val client = clientReturning(
            HttpStatusCode.Conflict,
            errorEnvelope("EMAIL_ALREADY_EXISTS", "An account with this email already exists"),
        )

        val error = assertIs<AppError.Conflict>(assertIs<Outcome.Failure>(request(client)).error)
        assertEquals("EMAIL_ALREADY_EXISTS", error.code)
    }

    @Test
    fun `rate limiting is distinguishable so the UI can say to wait`() = runTest {
        val client = clientReturning(
            HttpStatusCode.TooManyRequests,
            errorEnvelope("RATE_LIMITED", "Too many login attempts"),
        )

        assertIs<AppError.RateLimited>(assertIs<Outcome.Failure>(request(client)).error)
    }

    @Test
    fun `a server fault is retryable, unlike a validation failure`() = runTest {
        val client = clientReturning(
            HttpStatusCode.InternalServerError,
            errorEnvelope("INTERNAL_ERROR", "An unexpected error occurred"),
        )

        val error = assertIs<Outcome.Failure>(request(client)).error
        assertIs<AppError.Server>(error)
        assertTrue(error.isRetryable, "the sync engine backs off on retryable errors only")
        assertTrue(AppError.Validation("bad").isRetryable.not())
    }

    /**
     * A transport failure is the only thing that should read as "offline".
     * Anything else - a serialization mismatch, a local storage fault, a bug -
     * must surface as itself so it is not hidden behind a wifi message.
     */
    @Test
    fun `a dropped connection is reported as offline`() = runTest {
        val client = HttpClient(
            MockEngine { throw kotlinx.io.IOException("connection refused") }
        ) {
            expectSuccess = false
        }

        assertEquals(AppError.Offline, assertIs<Outcome.Failure>(request(client)).error)
        assertTrue(monitor.isOnline.value.not(), "a transport failure marks us offline")
    }

    @Test
    fun `an unparseable error body still yields the right category from the status`() = runTest {
        val client = clientReturning(HttpStatusCode.NotFound, "this is not json at all")

        assertIs<AppError.NotFound>(assertIs<Outcome.Failure>(request(client)).error)
    }

    @Test
    fun `a successful response marks us reachable again`() = runTest {
        monitor.reportUnreachable()

        val client = clientReturning(
            HttpStatusCode.OK,
            """{"id":"1","email":"a@b.com","name":"A",
               "createdAt":"2026-08-12T10:00:00Z","updatedAt":"2026-08-12T10:00:00Z"}""",
        )
        request(client)

        assertTrue(monitor.isOnline.value)
    }
}
