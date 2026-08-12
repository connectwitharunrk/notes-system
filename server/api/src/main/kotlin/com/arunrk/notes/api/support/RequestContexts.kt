package com.arunrk.notes.api.support

import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.usecase.auth.SessionContext
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/**
 * Builds a [SessionContext] from transport headers.
 *
 * Everything here is descriptive metadata, never used for authorisation, so a
 * malformed value degrades to null instead of failing the request - a client
 * with a corrupted device id should still be able to sign in.
 */
object RequestContexts {

    const val HEADER_DEVICE_ID = "X-Device-Id"
    const val HEADER_DEVICE_PLATFORM = "X-Device-Platform"
    const val HEADER_FORWARDED_FOR = "X-Forwarded-For"

    fun sessionContext(request: HttpServletRequest): SessionContext = SessionContext(
        deviceId = deviceId(request),
        platform = DevicePlatform.parse(request.getHeader(HEADER_DEVICE_PLATFORM)),
        userAgent = request.getHeader("User-Agent")?.take(512),
        ipAddress = clientIp(request),
    )

    fun platform(request: HttpServletRequest): DevicePlatform =
        DevicePlatform.parse(request.getHeader(HEADER_DEVICE_PLATFORM))

    fun deviceId(request: HttpServletRequest): UUID? =
        request.getHeader(HEADER_DEVICE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader(HEADER_FORWARDED_FOR)
            // X-Forwarded-For is a client-supplied chain; only the first entry
            // is meaningful and it is still spoofable. Audit data only.
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
}
