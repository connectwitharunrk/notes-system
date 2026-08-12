package com.arunrk.notes.api.v1.sync

import com.arunrk.notes.api.support.RequestContexts
import com.arunrk.notes.domain.usecase.sync.PullChangesUseCase
import com.arunrk.notes.domain.usecase.sync.PullCommand
import com.arunrk.notes.domain.usecase.sync.PushChangesUseCase
import com.arunrk.notes.domain.usecase.sync.PushCommand
import com.arunrk.notes.domain.usecase.sync.SyncStatusUseCase
import com.arunrk.notes.infrastructure.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The offline-first synchronisation protocol.
 *
 * A sync cycle is: push local changes, then pull remote ones. Pushing first
 * matters - if a resync is required, the client must get its unsynced work to
 * the server before wiping local state.
 */
@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "Synchronisation")
class SyncController(
    private val pushChanges: PushChangesUseCase,
    private val pullChanges: PullChangesUseCase,
    private val syncStatus: SyncStatusUseCase,
) {

    @PostMapping("/push")
    @Operation(
        summary = "Upload local changes",
        description = "Applies a batch atomically. Each result reports whether the " +
            "change landed verbatim, was merged, produced a conflict copy, or was " +
            "rejected. A CONFLICT result is not an error - it carries the " +
            "authoritative server note and, where both sides rewrote the content, " +
            "a conflict copy the client must insert locally so no writing is lost.",
    )
    fun push(
        @Valid @RequestBody request: PushRequest,
        httpRequest: HttpServletRequest,
    ): PushResponse = PushResponse.from(
        pushChanges.execute(
            PushCommand(
                userId = CurrentUser.id(),
                deviceId = request.deviceId ?: RequestContexts.deviceId(httpRequest),
                changes = request.changes.map { it.toDomain() },
                platform = RequestContexts.platform(httpRequest),
            )
        )
    )

    @GetMapping("/pull")
    @Operation(
        summary = "Download changes since a cursor",
        description = "Returns notes ordered by change sequence, tombstones included. " +
            "Persist nextCursor after every page so an interrupted pull resumes " +
            "rather than restarting.",
    )
    fun pull(
        @RequestParam(defaultValue = "0") cursor: Long,
        @RequestParam(required = false) limit: Int?,
    ): PullResponse = PullResponse.from(
        pullChanges.execute(
            PullCommand(userId = CurrentUser.id(), cursor = cursor, limit = limit)
        )
    )

    @GetMapping("/status")
    @Operation(summary = "Server cursor, tombstone floor and how far behind a cursor is")
    fun status(@RequestParam(defaultValue = "0") cursor: Long): SyncStatusResponse =
        SyncStatusResponse.from(syncStatus.execute(CurrentUser.id(), cursor))
}
