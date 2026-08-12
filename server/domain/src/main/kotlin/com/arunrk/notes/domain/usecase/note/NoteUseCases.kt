package com.arunrk.notes.domain.usecase.note

import com.arunrk.notes.common.error.AppException
import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteContentType
import com.arunrk.notes.domain.model.NoteSortOrder
import com.arunrk.notes.domain.model.PageResult
import com.arunrk.notes.domain.policy.NotePolicy
import com.arunrk.notes.domain.port.ChangeSequencer
import com.arunrk.notes.domain.port.NoteRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.usecase.device.DeviceResolver
import java.time.Instant
import java.util.UUID

/**
 * Direct note operations, for REST clients that do not implement the sync
 * protocol.
 *
 * The Kotlin Multiplatform clients in this project deliberately do NOT use
 * these - they mutate exclusively through /sync/push so that there is exactly
 * one conflict-resolution code path. These exist for API completeness and thin
 * or third-party clients.
 *
 * Every write still allocates a change sequence, so notes edited here show up
 * in other devices' incremental pulls just like a synced change would.
 */

private fun NoteRepository.requireOwned(id: UUID, userId: UUID): Note =
    findByIdForUser(id, userId)
        ?: throw AppException(ErrorCode.NOTE_NOT_FOUND, "Note not found")

// ---------------------------------------------------------------------------

data class CreateNoteCommand(
    /** Client-supplied so the same id works offline and online. */
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val title: String,
    val content: String,
    val contentType: NoteContentType = NoteContentType.PLAIN,
    val color: String? = null,
    val isPinned: Boolean = false,
    val clientCreatedAt: Instant? = null,
    val clientUpdatedAt: Instant? = null,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
)

class CreateNoteUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val deviceResolver: DeviceResolver,
    private val transactor: Transactor,
    private val time: TimeProvider,
    private val policy: NotePolicy,
) {
    fun execute(command: CreateNoteCommand): Note {
        policy.rejectionReasonFor(command.title, command.content)?.let { (code, message) ->
            throw AppException(code, message)
        }

        return transactor.inTransaction {
            // Re-POSTing the same id is idempotent rather than a 409: an
            // offline client retrying after a dropped response must not be
            // punished for a request that actually succeeded.
            notes.findByIdForUser(command.id, command.userId)?.let { return@inTransaction it }

            val now = time.now()
            val seq = sequencer.reserve(command.userId, 1).first
            // lastModifiedBy is a foreign key into devices, so an unregistered
            // device id has to be registered before the note is written.
            val deviceId = deviceResolver.resolve(command.deviceId, command.userId, command.platform)

            notes.save(
                Note(
                    id = command.id,
                    userId = command.userId,
                    title = command.title,
                    content = command.content,
                    contentType = command.contentType,
                    color = command.color,
                    isPinned = command.isPinned,
                    clientCreatedAt = command.clientCreatedAt ?: now,
                    clientUpdatedAt = command.clientUpdatedAt ?: now,
                    createdAt = now,
                    updatedAt = now,
                    version = 1,
                    changeSeq = seq,
                    contentHash = Note.hashOf(command.title, command.content),
                    lastModifiedBy = deviceId,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------

data class UpdateNoteCommand(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val title: String,
    val content: String,
    val contentType: NoteContentType = NoteContentType.PLAIN,
    val color: String? = null,
    /** From `If-Match`. Null skips the check, which races other devices. */
    val expectedVersion: Long? = null,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
)

class UpdateNoteUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val deviceResolver: DeviceResolver,
    private val transactor: Transactor,
    private val time: TimeProvider,
    private val policy: NotePolicy,
) {
    fun execute(command: UpdateNoteCommand): Note {
        policy.rejectionReasonFor(command.title, command.content)?.let { (code, message) ->
            throw AppException(code, message)
        }

        return transactor.inTransaction {
            val existing = notes.requireOwned(command.id, command.userId)

            if (command.expectedVersion != null && command.expectedVersion != existing.version) {
                throw AppException(
                    ErrorCode.VERSION_CONFLICT,
                    "This note was modified elsewhere. Expected version " +
                        "${command.expectedVersion}, current is ${existing.version}.",
                )
            }

            val now = time.now()
            val deviceId = deviceResolver.resolve(command.deviceId, command.userId, command.platform)
            notes.save(
                existing.copy(
                    title = command.title,
                    content = command.content,
                    contentType = command.contentType,
                    color = command.color,
                    clientUpdatedAt = now,
                    updatedAt = now,
                    version = existing.version + 1,
                    changeSeq = sequencer.reserve(command.userId, 1).first,
                    contentHash = Note.hashOf(command.title, command.content),
                    lastModifiedBy = deviceId,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------

/** Shared shape for the small state-toggling operations. */
private class NoteFlagMutator(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val deviceResolver: DeviceResolver,
    private val transactor: Transactor,
    private val time: TimeProvider,
) {
    fun mutate(
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        platform: DevicePlatform,
        change: (Note, Instant) -> Note,
    ): Note = transactor.inTransaction {
        val existing = notes.requireOwned(id, userId)
        val now = time.now()
        // lastModifiedBy is a foreign key into devices.
        val resolvedDevice = deviceResolver.resolve(deviceId, userId, platform)
        notes.save(
            change(existing, now).copy(
                updatedAt = now,
                clientUpdatedAt = now,
                version = existing.version + 1,
                changeSeq = sequencer.reserve(userId, 1).first,
                lastModifiedBy = resolvedDevice,
            )
        )
    }
}

class DeleteNoteUseCase(
    notes: NoteRepository,
    sequencer: ChangeSequencer,
    deviceResolver: DeviceResolver,
    transactor: Transactor,
    time: TimeProvider,
) {
    private val mutator = NoteFlagMutator(notes, sequencer, deviceResolver, transactor, time)

    /**
     * Soft delete. The row survives as a tombstone so other devices can learn
     * about the deletion; the purge job removes it after the retention window.
     */
    fun execute(
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        platform: DevicePlatform = DevicePlatform.UNKNOWN,
    ): Note = mutator.mutate(id, userId, deviceId, platform) { note, now ->
        note.copy(isDeleted = true, deletedAt = note.deletedAt ?: now)
    }
}

class RestoreNoteUseCase(
    notes: NoteRepository,
    sequencer: ChangeSequencer,
    deviceResolver: DeviceResolver,
    transactor: Transactor,
    time: TimeProvider,
) {
    private val mutator = NoteFlagMutator(notes, sequencer, deviceResolver, transactor, time)

    fun execute(
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        platform: DevicePlatform = DevicePlatform.UNKNOWN,
    ): Note = mutator.mutate(id, userId, deviceId, platform) { note, _ ->
        note.copy(isDeleted = false, deletedAt = null)
    }
}

class ArchiveNoteUseCase(
    notes: NoteRepository,
    sequencer: ChangeSequencer,
    deviceResolver: DeviceResolver,
    transactor: Transactor,
    time: TimeProvider,
) {
    private val mutator = NoteFlagMutator(notes, sequencer, deviceResolver, transactor, time)

    fun execute(
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        archived: Boolean,
        platform: DevicePlatform = DevicePlatform.UNKNOWN,
    ): Note = mutator.mutate(id, userId, deviceId, platform) { note, _ ->
        note.copy(isArchived = archived)
    }
}

class PinNoteUseCase(
    notes: NoteRepository,
    sequencer: ChangeSequencer,
    deviceResolver: DeviceResolver,
    transactor: Transactor,
    time: TimeProvider,
) {
    private val mutator = NoteFlagMutator(notes, sequencer, deviceResolver, transactor, time)

    fun execute(
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        pinned: Boolean,
        platform: DevicePlatform = DevicePlatform.UNKNOWN,
    ): Note = mutator.mutate(id, userId, deviceId, platform) { note, _ ->
        note.copy(isPinned = pinned)
    }
}

// ---------------------------------------------------------------------------

class GetNoteUseCase(private val notes: NoteRepository) {
    fun execute(id: UUID, userId: UUID): Note {
        val note = notes.requireOwned(id, userId)
        // A tombstone is not a resource; only the sync protocol sees those.
        if (note.isDeleted) throw AppException(ErrorCode.NOTE_NOT_FOUND, "Note not found")
        return note
    }
}

data class ListNotesQuery(
    val userId: UUID,
    val archived: Boolean = false,
    val pinnedOnly: Boolean = false,
    val sort: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
    val page: Int = 0,
    val size: Int = 50,
)

class ListNotesUseCase(private val notes: NoteRepository) {
    fun execute(query: ListNotesQuery): PageResult<Note> = notes.list(
        userId = query.userId,
        archived = query.archived,
        pinnedOnly = query.pinnedOnly,
        sort = query.sort,
        page = query.page.coerceAtLeast(0),
        size = query.size.coerceIn(1, MAX_PAGE_SIZE),
    )

    private companion object {
        const val MAX_PAGE_SIZE = 200
    }
}

class SearchNotesUseCase(private val notes: NoteRepository) {
    fun execute(userId: UUID, query: String, page: Int, size: Int): PageResult<Note> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            throw AppException(ErrorCode.VALIDATION_ERROR, "Search query must not be blank")
        }
        return notes.search(
            userId = userId,
            query = trimmed,
            page = page.coerceAtLeast(0),
            size = size.coerceIn(1, MAX_PAGE_SIZE),
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
    }
}
