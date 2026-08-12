package com.arunrk.note.core.network.api

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.network.ApiHeaders
import com.arunrk.note.core.network.ApiPaths
import com.arunrk.note.core.network.TokenStore
import com.arunrk.note.core.network.dto.NoteChangeDto
import com.arunrk.note.core.network.dto.PullResponseDto
import com.arunrk.note.core.network.dto.PushRequestDto
import com.arunrk.note.core.network.dto.PushResponseDto
import com.arunrk.note.core.network.dto.SyncStatusResponseDto
import com.arunrk.note.core.network.executeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * The synchronisation protocol. This is the only API the app uses to mutate
 * notes - the REST note endpoints exist for third parties, and routing our own
 * writes through them would mean two conflict-resolution implementations.
 */
class SyncApi(
    private val client: HttpClient,
    private val monitor: NetworkMonitor,
    private val tokenStore: TokenStore,
) {

    suspend fun push(changes: List<NoteChangeDto>): Outcome<PushResponseDto> =
        executeRequest(client, monitor) {
            val deviceId = tokenStore.deviceId()
            post(ApiPaths.SYNC_PUSH) {
                header(ApiHeaders.DEVICE_ID, deviceId)
                setBody(PushRequestDto(deviceId = deviceId, changes = changes))
            }
        }

    suspend fun pull(cursor: Long, limit: Int? = null): Outcome<PullResponseDto> =
        executeRequest(client, monitor) {
            get(ApiPaths.SYNC_PULL) {
                parameter("cursor", cursor)
                limit?.let { parameter("limit", it) }
            }
        }

    suspend fun status(cursor: Long): Outcome<SyncStatusResponseDto> =
        executeRequest(client, monitor) {
            get(ApiPaths.SYNC_STATUS) { parameter("cursor", cursor) }
        }
}
