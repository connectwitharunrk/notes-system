package com.arunrk.note.data.note

import com.arunrk.note.core.database.sql.NoteEntity
import com.arunrk.note.domain.model.Note
import com.arunrk.note.domain.model.NoteContentType
import com.arunrk.note.domain.model.SyncStatus

/**
 * Database row to domain model.
 *
 * The local table carries sync bookkeeping the domain has no business knowing
 * about - localRevision, baseVersion, baseContentHash - so those stop here. Only
 * [SyncStatus], which the UI genuinely shows, crosses over.
 */
internal fun NoteEntity.toDomain(): Note = Note(
    id = id,
    userId = userId,
    title = title,
    content = content,
    contentType = NoteContentType.parse(contentType),
    color = color,
    isPinned = isPinned,
    isArchived = isArchived,
    isDeleted = isDeleted,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncStatus = SyncStatus.parse(syncStatus),
    conflictOfNoteId = conflictOfNoteId,
)
