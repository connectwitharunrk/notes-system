package com.arunrk.notes.domain.usecase.sync

import com.arunrk.notes.domain.model.ConflictOutcome
import com.arunrk.notes.domain.model.MergedFields
import com.arunrk.notes.domain.model.Note
import com.arunrk.notes.domain.model.NoteChange
import com.arunrk.notes.domain.model.SyncResolution

/**
 * Decides what happens when a push arrives for a note that someone else has
 * already changed.
 *
 * Governing rule: **never silently discard a user's writing.** Losing a pin
 * state is a shrug; losing a paragraph is a bug report. Where the outcome is
 * genuinely ambiguous the resolver keeps both versions rather than picking a
 * winner.
 *
 * Pure by design - it reads two values and returns a decision, performing no
 * writes. That is what makes every rung of the ladder directly testable.
 *
 * ### The ladder, in evaluation order
 *
 * | Rung | Condition | Outcome |
 * |------|-----------|---------|
 * | T0 | both sides deleted | converge, nothing to write |
 * | T1 | content hashes match | merge flags only |
 * | T2 | one side deleted, the other edited | the edit wins, note is undeleted |
 * | T3 | client content == its base | server's text wins, client's flags merge in |
 * | T4 | server content == client's base | client's text wins cleanly |
 * | T5 | both changed the content | conflict copy - both survive |
 *
 * Note what is deliberately absent: last-writer-wins on a timestamp. It is the
 * usual shortcut and it is quietly destructive - a device whose clock runs four
 * minutes fast wins every race, and the loser's text is gone with no trace.
 */
class ConflictResolver {

    fun resolve(change: NoteChange, server: Note): ConflictOutcome {
        val clientHash = Note.hashOf(change.title, change.content)
        val serverHash = server.contentHash
        val baseHash = change.baseContentHash

        // T0 - both agreed on deletion. Nothing to reconcile.
        if (change.isDeleted && server.isDeleted) {
            return ConflictOutcome.FastForward(SyncResolution.BOTH_DELETED)
        }

        // T1 - the text is already identical, so at most the flags disagree.
        if (clientHash == serverHash) {
            val merged = mergeMetadata(change, server)
            return if (merged.changesAnythingOn(server)) {
                ConflictOutcome.Merge(merged, SyncResolution.METADATA_MERGED)
            } else {
                ConflictOutcome.FastForward(SyncResolution.IDENTICAL)
            }
        }

        // From here the text genuinely differs between the two copies.
        //
        // A null base hash means the client could not tell us what it started
        // from, so we assume it edited. That biases towards a conflict copy,
        // which is recoverable, instead of an overwrite, which is not.
        val clientEditedContent = baseHash == null || clientHash != baseHash
        val serverEditedContent = baseHash == null || serverHash != baseHash

        // T2 - deletion racing an edit. The edit wins and the note comes back.
        // Re-deleting costs the user one tap; recovering deleted text they
        // never saw again costs everything.
        if (change.isDeleted != server.isDeleted) {
            val deletedByClient = change.isDeleted

            if (!deletedByClient && clientEditedContent) {
                return ConflictOutcome.Merge(
                    clientFields(change).copy(isDeleted = false),
                    SyncResolution.EDIT_WINS_OVER_DELETE,
                )
            }
            if (deletedByClient && serverEditedContent) {
                return ConflictOutcome.Merge(
                    serverFields(server).copy(isDeleted = false),
                    SyncResolution.EDIT_WINS_OVER_DELETE,
                )
            }

            // The side that deleted is also the side that changed the text, or
            // neither did. Either way the deletion is the newer intent.
            return if (deletedByClient) {
                ConflictOutcome.Merge(clientFields(change), SyncResolution.DELETE_APPLIED)
            } else {
                ConflictOutcome.FastForward(SyncResolution.DELETE_APPLIED)
            }
        }

        // T3 - the client never touched the text; it only pushed because a flag
        // changed. Keep the server's text, take the client's flags.
        if (!clientEditedContent) {
            return ConflictOutcome.Merge(
                serverFields(server).copy(
                    isPinned = change.isPinned || server.isPinned,
                    isArchived = change.isArchived,
                    clientUpdatedAt = laterOf(change.clientUpdatedAt, server.clientUpdatedAt),
                ),
                SyncResolution.SERVER_CONTENT_WINS,
            )
        }

        // T4 - the server has not moved since the client's base, so this is an
        // ordinary edit that merely raced a metadata write. The client wins.
        if (!serverEditedContent) {
            return ConflictOutcome.Merge(clientFields(change), SyncResolution.CLIENT_WINS)
        }

        // T5 - both rewrote the text from a common ancestor. There is no correct
        // automatic answer, so keep both and let the person who wrote them decide.
        return ConflictOutcome.ConflictCopy(clientFields(change))
    }

    // -----------------------------------------------------------------------

    private fun clientFields(change: NoteChange) = MergedFields(
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

    private fun serverFields(server: Note) = MergedFields(
        title = server.title,
        content = server.content,
        contentType = server.contentType,
        color = server.color,
        isPinned = server.isPinned,
        isArchived = server.isArchived,
        isDeleted = server.isDeleted,
        clientCreatedAt = server.clientCreatedAt,
        clientUpdatedAt = server.clientUpdatedAt,
    )

    /**
     * Flag-level merge, used when the text is identical on both sides.
     *
     * Pinning is unioned: it is a cheap, obvious, reversible signal, and losing
     * a pin is more annoying than gaining one. Archive and delete are decided by
     * whichever device reported the later edit - the only signal available, and
     * an acknowledged weak one, since these are untrusted client clocks. It is
     * tolerable here precisely because no text is at stake.
     */
    private fun mergeMetadata(change: NoteChange, server: Note): MergedFields {
        val clientIsNewer = change.clientUpdatedAt.isAfter(server.clientUpdatedAt)
        return MergedFields(
            title = server.title,
            content = server.content,
            contentType = if (clientIsNewer) change.contentType else server.contentType,
            color = if (clientIsNewer) change.color else server.color,
            isPinned = change.isPinned || server.isPinned,
            isArchived = if (clientIsNewer) change.isArchived else server.isArchived,
            isDeleted = if (clientIsNewer) change.isDeleted else server.isDeleted,
            clientCreatedAt = earlierOf(change.clientCreatedAt, server.clientCreatedAt),
            clientUpdatedAt = laterOf(change.clientUpdatedAt, server.clientUpdatedAt),
        )
    }

    private fun laterOf(a: java.time.Instant, b: java.time.Instant) = if (a.isAfter(b)) a else b

    private fun earlierOf(a: java.time.Instant, b: java.time.Instant) = if (a.isBefore(b)) a else b
}

/**
 * Whether writing these fields would actually change the stored note. Used to
 * avoid burning a version and a sequence number on a no-op, which would make
 * every other device re-download a note that did not change.
 */
private fun MergedFields.changesAnythingOn(server: Note): Boolean =
    title != server.title ||
        content != server.content ||
        contentType != server.contentType ||
        color != server.color ||
        isPinned != server.isPinned ||
        isArchived != server.isArchived ||
        isDeleted != server.isDeleted
