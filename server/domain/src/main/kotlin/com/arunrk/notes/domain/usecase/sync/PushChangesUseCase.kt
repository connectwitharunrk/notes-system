package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.common.error.ErrorCode
import com.arunrk.notes.common.id.UuidV7
import com.arunrk.notes.common.time.TimeProvider
import com.arunrk.notes.domain.model.ConflictOutcome
import com.arunrk.notes.domain.model.DevicePlatform
import com.arunrk.notes.domain.model.MergedFields
import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteChange
import com.arunrk.notes.domain.model.PushOutcome
import com.arunrk.notes.domain.model.PushResult
import com.arunrk.notes.domain.model.SyncResolution
import com.arunrk.notes.domain.policy.NotePolicy
import com.arunrk.notes.domain.port.ChangeSequencer
import com.arunrk.notes.domain.port.NoteRepository
import com.arunrk.notes.domain.port.Transactor
import com.arunrk.notes.domain.usecase.device.DeviceResolver
import java.time.Instant
import java.util.UUID

data class PushCommand(
    val userId: UUID,
    val deviceId: UUID?,
    val changes: List<NoteChange>,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
)

/**
 * Applies a batch of client changes.
 *
 * The whole batch runs in one transaction, and every write takes its sequence
 * number from a single reservation held under the user row lock. That is what
 * makes the pull cursor hole-free: no other transaction can interleave sequence
 * numbers with this one and commit first.
 */
class PushChangesUseCase(
    private val notes: NoteRepository,
    private val sequencer: ChangeSequencer,
    private val conflictResolver: ConflictResolver,
    private val deviceResolver: DeviceResolver,
    private val transactor: Transactor,
    private val time: TimeProvider,
    private val policy: NotePolicy,
) {

    fun execute(command: PushCommand): PushOutcome {
        policy.requireBatchWithinLimit(command.changes.size)

        if (command.changes.isEmpty()) {
            val cursor = sequencer.current(command.userId)
            return PushOutcome(emptyList(), cursor, time.now())
        }

        return transactor.inTransaction { apply(command) }
    }

    private fun apply(command: PushCommand): PushOutcome {
        val now = time.now()
        val userId = command.userId

        // Notes carry last_modified_by as a foreign key into devices, so the
        // device row must exist before any note is written. Resolved once per
        // push rather than per note.
        val deviceId = deviceResolver.resolve(command.deviceId, userId, command.platform)

        val seenIds = mutableSetOf<UUID>()
        val existing = notes.findAllByIdsForUser(command.changes.map { it.id }, userId)
            .associateBy { it.id }

        // Pass 1: decide everything up front, without writing. Decisions depend
        // only on the server state loaded above, so they cannot influence each
        // other - which is exactly why duplicate ids in one batch are rejected
        // rather than quietly folded together.
        val plans = command.changes.map { change ->
            when {
                !seenIds.add(change.id) -> Plan.Reject(
                    change.id,
                    ErrorCode.VALIDATION_ERROR,
                    "Duplicate note id in the same push batch",
                )

                else -> planFor(change, existing[change.id])
            }
        }

        // Pass 2: reserve exactly the sequence numbers the writes need.
        val required = plans.sumOf { it.sequencesNeeded }
        val sequences = if (required > 0) {
            sequencer.reserve(userId, required).iterator()
        } else {
            LongRange.EMPTY.iterator()
        }

        val toSave = mutableListOf<Note>()
        val results = plans.map { plan -> execute(plan, userId, deviceId, now, sequences, toSave) }

        if (toSave.isNotEmpty()) notes.saveAll(toSave)

        return PushOutcome(
            results = results,
            serverCursor = sequencer.current(userId),
            serverTime = now,
        )
    }

    // -----------------------------------------------------------------------

    private fun planFor(change: NoteChange, server: Note?): Plan {
        policy.rejectionReasonFor(change.title, change.content)?.let { (code, message) ->
            return Plan.Reject(change.id, code, message)
        }

        if (server == null) {
            // Nothing on the server. Either a genuine offline creation, or a
            // note whose tombstone has already been purged.
            return if (change.isDeleted) {
                // Do not resurrect a purged tombstone just to delete it again.
                Plan.AcknowledgeGone(change.id)
            } else {
                Plan.Create(change)
            }
        }

        if (server.version == change.baseVersion) {
            return Plan.Write(change.id, server, clientFieldsOf(change), SyncResolution.APPLIED)
        }

        return when (val outcome = conflictResolver.resolve(change, server)) {
            is ConflictOutcome.Merge -> Plan.Write(change.id, server, outcome.fields, outcome.resolution)
            is ConflictOutcome.FastForward -> Plan.FastForward(change.id, server, outcome.resolution)
            is ConflictOutcome.ConflictCopy -> Plan.CopyOnConflict(change.id, server, outcome.fields)
        }
    }

    private fun execute(
        plan: Plan,
        userId: UUID,
        deviceId: UUID?,
        now: Instant,
        sequences: Iterator<Long>,
        toSave: MutableList<Note>,
    ): PushResult = when (plan) {

        is Plan.Reject -> PushResult.Rejected(plan.noteId, plan.code.name, plan.message)

        is Plan.AcknowledgeGone -> PushResult.Applied(
            noteId = plan.noteId,
            // Version 0 signals "the server holds nothing for this id" - the
            // client should stop tracking it rather than expect it back on pull.
            version = 0,
            changeSeq = 0,
            resolution = SyncResolution.BOTH_DELETED,
        )

        is Plan.Create -> {
            val seq = sequences.next()
            val note = newNote(plan.change, userId, deviceId, now, seq)
            toSave += note
            PushResult.Applied(note.id, note.version, note.changeSeq, SyncResolution.APPLIED)
        }

        is Plan.Write -> {
            val seq = sequences.next()
            val note = plan.server.applying(plan.fields, deviceId, now, seq)
            toSave += note
            if (plan.resolution == SyncResolution.APPLIED) {
                PushResult.Applied(note.id, note.version, note.changeSeq, plan.resolution)
            } else {
                // The client's push did not land verbatim, so it must adopt the
                // merged server state rather than keep its own copy.
                PushResult.Conflicted(note.id, plan.resolution, note, conflictCopy = null)
            }
        }

        is Plan.FastForward -> PushResult.Conflicted(
            noteId = plan.noteId,
            resolution = plan.resolution,
            server = plan.server,
            conflictCopy = null,
        )

        is Plan.CopyOnConflict -> {
            val seq = sequences.next()
            val copy = conflictCopyOf(plan.fields, plan.server, userId, deviceId, now, seq)
            toSave += copy
            PushResult.Conflicted(
                noteId = plan.noteId,
                resolution = SyncResolution.CONFLICT_COPY_CREATED,
                server = plan.server,
                conflictCopy = copy,
            )
        }
    }

    private fun newNote(
        change: NoteChange,
        userId: UUID,
        deviceId: UUID?,
        now: Instant,
        seq: Long,
    ) = Note(
        id = change.id,
        userId = userId,
        title = change.title,
        content = change.content,
        contentType = change.contentType,
        color = change.color,
        isPinned = change.isPinned,
        isArchived = change.isArchived,
        isDeleted = change.isDeleted,
        clientCreatedAt = change.clientCreatedAt,
        clientUpdatedAt = change.clientUpdatedAt,
        createdAt = now,
        updatedAt = now,
        deletedAt = if (change.isDeleted) now else null,
        version = 1,
        changeSeq = seq,
        contentHash = Note.hashOf(change.title, change.content),
        lastModifiedBy = deviceId,
    )

    /**
     * Materialises the client's losing version as a separate note so that no
     * writing is destroyed. The server copy is left completely untouched.
     */
    private fun conflictCopyOf(
        fields: MergedFields,
        server: Note,
        userId: UUID,
        deviceId: UUID?,
        now: Instant,
        seq: Long,
    ) = Note(
        id = UuidV7.generate(now.toEpochMilli()),
        userId = userId,
        title = conflictTitle(fields.title),
        content = fields.content,
        contentType = fields.contentType,
        color = fields.color,
        isPinned = fields.isPinned,
        isArchived = fields.isArchived,
        // A conflict copy is never born deleted - it exists to be reviewed.
        isDeleted = false,
        clientCreatedAt = fields.clientCreatedAt,
        clientUpdatedAt = fields.clientUpdatedAt,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
        version = 1,
        changeSeq = seq,
        contentHash = Note.hashOf(conflictTitle(fields.title), fields.content),
        lastModifiedBy = deviceId,
        conflictOf = server.id,
    )

    private fun conflictTitle(title: String): String {
        val suffixed = if (title.isBlank()) CONFLICT_SUFFIX.trim() else "$title$CONFLICT_SUFFIX"
        return suffixed.take(policy.maxTitleLength)
    }

    private fun clientFieldsOf(change: NoteChange) = MergedFields(
        title = change.title,
        content = change.content,
        contentType = change.contentType,
        color = change.color,
        isPinned = change.isPinned,
        isArchived = change.isArchived,
        isDeleted = change.isDeleted,
        clientCreatedAt = change.clientCreatedAt,
        clientUpdatedAt = change.clientUpdatedAt,
    )

    private sealed interface Plan {
        val sequencesNeeded: Int

        data class Reject(val noteId: UUID, val code: ErrorCode, val message: String) : Plan {
            override val sequencesNeeded = 0
        }

        data class AcknowledgeGone(val noteId: UUID) : Plan {
            override val sequencesNeeded = 0
        }

        data class Create(val change: NoteChange) : Plan {
            override val sequencesNeeded = 1
        }

        data class Write(
            val noteId: UUID,
            val server: Note,
            val fields: MergedFields,
            val resolution: SyncResolution,
        ) : Plan {
            override val sequencesNeeded = 1
        }

        data class FastForward(
            val noteId: UUID,
            val server: Note,
            val resolution: SyncResolution,
        ) : Plan {
            override val sequencesNeeded = 0
        }

        data class CopyOnConflict(
            val noteId: UUID,
            val server: Note,
            val fields: MergedFields,
        ) : Plan {
            override val sequencesNeeded = 1
        }
    }

    private companion object {
        const val CONFLICT_SUFFIX = " (conflict copy)"
    }
}

/** Produces the next version of a note from merged field values. */
internal fun Note.applying(
    fields: MergedFields,
    deviceId: UUID?,
    now: Instant,
    seq: Long,
): Note = copy(
    title = fields.title,
    content = fields.content,
    contentType = fields.contentType,
    color = fields.color,
    isPinned = fields.isPinned,
    isArchived = fields.isArchived,
    isDeleted = fields.isDeleted,
    clientCreatedAt = fields.clientCreatedAt,
    clientUpdatedAt = fields.clientUpdatedAt,
    updatedAt = now,
    // Keep the original deletion time if it was already a tombstone, so the
    // retention window is measured from the first delete rather than being
    // extended by every later touch.
    deletedAt = when {
        !fields.isDeleted -> null
        deletedAt != null -> deletedAt
        else -> now
    },
    version = version + 1,
    changeSeq = seq,
    contentHash = Note.hashOf(fields.title, fields.content),
    lastModifiedBy = deviceId,
)
