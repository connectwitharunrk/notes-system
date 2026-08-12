package com.arunrk.note.core.network.api

import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.error.Outcome
import com.arunrk.note.core.network.ApiPaths
import com.arunrk.note.core.network.dto.NoteDto
import com.arunrk.note.core.network.dto.NotePageDto
import com.arunrk.note.core.network.executeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * Read-only access to the REST note endpoints.
 *
 * Writes are deliberately absent: everything this app changes goes through
 * [SyncApi.push] so that conflict resolution has exactly one code path. Server
 * search is exposed because the local database only holds this device's copy of
 * the notes - useful as a fallback, though the offline-first UI searches
 * locally.
 */
class NoteApi(
    private val client: HttpClient,
    private val monitor: NetworkMonitor,
) {

    suspend fun list(
        archived: Boolean = false,
        pinnedOnly: Boolean = false,
        sort: String? = null,
        page: Int = 0,
        size: Int = 50,
    ): Outcome<NotePageDto> = executeRequest(client, monitor) {
        get(ApiPaths.NOTES) {
            parameter("archived", archived)
            parameter("pinned", pinnedOnly)
            sort?.let { parameter("sort", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    suspend fun search(query: String, page: Int = 0, size: Int = 50): Outcome<NotePageDto> =
        executeRequest(client, monitor) {
            get(ApiPaths.NOTES_SEARCH) {
                parameter("q", query)
                parameter("page", page)
                parameter("size", size)
            }
        }

    suspend fun byId(id: String): Outcome<NoteDto> =
        executeRequest(client, monitor) { get("${ApiPaths.NOTES}/$id") }
}
