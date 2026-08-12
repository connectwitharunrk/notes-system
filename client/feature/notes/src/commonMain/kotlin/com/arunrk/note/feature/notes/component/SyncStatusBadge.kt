package com.arunrk.note.feature.notes.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.LocalSyncStatusColors
import com.arunrk.note.domain.model.SyncStatus

/**
 * Per-note sync indicator.
 *
 * Each state gets a distinct **icon** as well as a distinct colour, and a
 * content description for screen readers. Colour alone would be invisible to
 * anyone with a colour vision deficiency - and "amber dot versus green dot" is
 * exactly the distinction they lose.
 *
 * SYNCED renders nothing: the normal case needs no decoration, and a check mark
 * on every card is noise that makes the exceptions harder to spot.
 */
@Composable
fun SyncStatusBadge(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val colors = LocalSyncStatusColors.current

    val (icon, tint, label) = when (status) {
        SyncStatus.SYNCED -> return
        SyncStatus.SYNCING -> Triple(NoteIcons.Refresh, colors.syncing, "Syncing")
        SyncStatus.PENDING -> Triple(NoteIcons.CloudOff, colors.pending, "Waiting to sync")
        SyncStatus.FAILED -> Triple(NoteIcons.SyncProblem, colors.failed, "Sync failed")
        SyncStatus.CONFLICT -> Triple(NoteIcons.Warning, colors.conflict, "Edited on another device")
    }

    Row(
        modifier = modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
