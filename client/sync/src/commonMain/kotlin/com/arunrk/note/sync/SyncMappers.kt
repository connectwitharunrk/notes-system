package com.arunrk.note.sync

import com.arunrk.note.core.database.sql.NoteEntity
import com.arunrk.note.core.network.dto.NoteChangeDto
import com.arunrk.note.core.network.dto.NoteDto
import com.arunrk.note.core.network.epochMillisToIso
import com.arunrk.note.core.network.parseIsoToEpochMillis

/**
 * Builds the push payload for a locally-changed note.
 *
 * The two preconditions the server needs:
 *
 *  - `baseVersion` - the server version this edit descends from. 0 means the
 *    server has never seen this note.
 *  - `baseContentHash` - the content at our last successful sync. Without it
 *    the server cannot tell "I only toggled a flag, keep your text" from "we
 *    both rewrote this", and has to assume the latter, producing a conflict copy
 *    where a clean merge was possible.
 */
internal fun NoteEntity.toChangeDto(): NoteChangeDto = NoteChangeDto(
    id = id,
    baseVersion = baseVersion,
    title = title,
    content = content,
    contentType = contentType,
    color = color,
    isPinned = isPinned,
    isArchived = isArchived,
    isDeleted = isDeleted,
    clientCreatedAt = epochMillisToIso(createdAt),
    clientUpdatedAt = epochMillisToIso(updatedAt),
    baseContentHash = baseContentHash,
)

internal fun NoteDto.toServerNote(userId: String): ServerNote = ServerNote(
    id = id,
    userId = userId,
    title = title,
    content = content,
    contentType = contentType,
    color = color,
    isPinned = isPinned,
    isArchived = isArchived,
    isDeleted = isDeleted,
    // The client's own wall-clock times are what the UI shows, so the
    // server-authoritative created/updated columns are not what we store here.
    createdAt = parseIsoToEpochMillis(clientCreatedAt),
    updatedAt = parseIsoToEpochMillis(clientUpdatedAt),
    deletedAt = deletedAt?.let { parseIsoToEpochMillis(it) },
    version = version,
    changeSeq = changeSeq,
    contentHash = contentHash,
    conflictOfNoteId = conflictOf,
)

/**
 * A note captured at the moment it was handed to the network.
 *
 * The revision is the whole point: the response is only allowed to mark this
 * note synced if nothing has changed since, and comparing against a snapshot is
 * the only way to know.
 */
internal data class PushedSnapshot(
    val noteId: String,
    val localRevision: Long,
    val contentHash: String,
)
