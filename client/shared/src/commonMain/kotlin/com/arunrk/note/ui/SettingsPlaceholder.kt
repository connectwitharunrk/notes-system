package com.arunrk.note.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arunrk.note.core.designsystem.theme.Spacing

/**
 * Stand-in for the settings screen until Phase 7.
 *
 * Carries sign-out only, so the signed-in half of the app is reachable and
 * exitable while the notes UI is being built.
 */
@Composable
fun SettingsPlaceholder(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(Spacing.lg)
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text("Coming in the next phase", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Appearance, manual sync, last sync time and account settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }

            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}
