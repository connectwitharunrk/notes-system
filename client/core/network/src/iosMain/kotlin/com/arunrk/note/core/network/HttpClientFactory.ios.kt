package com.arunrk.note.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

/**
 * NOTE: never compiled - iOS cannot be built on a Windows host.
 * See docs/ARCHITECTURE.md L11.
 */
internal actual fun createPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) {
    config()
}
