package com.arunrk.note.feature.notes.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.model.Note

/**
 * One note in the list or grid.
 *
 * The card is the whole tap target rather than a "open" affordance inside it -
 * a small hit area on a phone is the most common source of mis-taps. The pin
 * button is the one nested target, and it is sized to the 48dp minimum.
 */
@Composable
fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    maxPreviewLines: Int = 6,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = note.displayTitle.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (note.isPinned) {
                            NoteIcons.PushPin
                        } else {
                            NoteIcons.PushPinOutlined
                        },
                        // Describes the action, not the current state: a screen
                        // reader user needs to know what the button will do.
                        contentDescription = if (note.isPinned) "Unpin note" else "Pin note",
                        modifier = Modifier.size(18.dp),
                        tint = if (note.isPinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (note.preview.isNotBlank()) {
                Text(
                    text = note.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = maxPreviewLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = relativeTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncStatusBadge(status = note.syncStatus)
            }
        }
    }
}
