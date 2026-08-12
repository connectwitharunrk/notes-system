package com.arunrk.note.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

internal actual fun createPlatformHttpClient(
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO) {
    config()
}
