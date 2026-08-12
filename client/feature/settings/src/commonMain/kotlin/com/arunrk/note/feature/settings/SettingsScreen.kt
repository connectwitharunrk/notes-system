package com.arunrk.note.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arunrk.note.core.designsystem.format.relativeTime
import com.arunrk.note.core.designsystem.icon.NoteIcons
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.domain.model.ThemePreference
import com.arunrk.note.domain.model.User
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                SettingsEffect.NavigateToProfile -> onNavigateToProfile()
                SettingsEffect.NavigateToChangePassword -> onNavigateToChangePassword()
                is SettingsEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    SettingsContent(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeContentPadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            // Capped so the content does not stretch across a full desktop
            // window, where 1400px-wide rows are unreadable.
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AccountCard(
                    user = state.user,
                    isSigningOut = state.isSigningOut,
                    onProfile = { onIntent(SettingsIntent.ProfileClicked) },
                    onChangePassword = { onIntent(SettingsIntent.ChangePasswordClicked) },
                    onSignOut = { onIntent(SettingsIntent.SignOutClicked) },
                )

                AppearanceCard(
                    selected = state.theme,
                    onSelect = { onIntent(SettingsIntent.ThemeSelected(it)) },
                )

                SyncCard(
                    state = state,
                    onSyncNow = { onIntent(SettingsIntent.SyncNowClicked) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun AccountCard(
    user: User?,
    isSigningOut: Boolean,
    onProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
) {
    SettingsCard(title = "Account") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user?.initials.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = Spacing.md)) {
                Text(user?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = user?.email.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))

        SettingsRow(icon = NoteIcons.Person, label = "Profile", onClick = onProfile)
        SettingsRow(icon = NoteIcons.Check, label = "Change password", onClick = onChangePassword)

        OutlinedButton(
            onClick = onSignOut,
            enabled = !isSigningOut,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        ) {
            if (isSigningOut) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(NoteIcons.Logout, contentDescription = null)
                Text("Sign out", modifier = Modifier.padding(start = Spacing.sm))
            }
        }

        // Said plainly, because signing out deletes local notes and people
        // reasonably fear losing work.
        Text(
            text = "Signing out sends any unsynced notes first, then removes them " +
                "from this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearanceCard(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    SettingsCard(title = "Appearance") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ThemePreference.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option.label()) },
                )
            }
        }
    }
}

@Composable
private fun SyncCard(
    state: SettingsState,
    onSyncNow: () -> Unit,
) {
    SettingsCard(title = "Synchronisation") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.isSyncing -> "Syncing…"
                        state.isOffline -> "Offline"
                        state.syncFailure != null -> "Last sync failed"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = lastSyncLabel(state.sync.lastSuccessfulSyncAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = onSyncNow, enabled = state.canSyncNow) {
                Text("Sync now")
            }
        }

        if (state.sync.pendingCount > 0) {
            SyncCountRow(
                "${state.sync.pendingCount} waiting to sync",
                "Saved on this device.",
            )
        }
        if (state.sync.failedCount > 0) {
            SyncCountRow(
                "${state.sync.failedCount} couldn't be sent",
                "Still safe here. They'll be retried.",
            )
        }
        if (state.sync.conflictCount > 0) {
            SyncCountRow(
                "${state.sync.conflictCount} kept as copies",
                "The same note was edited in two places, so both were kept.",
            )
        }
    }
}

@Composable
private fun SyncCountRow(title: String, detail: String) {
    Column(modifier = Modifier.padding(top = Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.padding(start = Spacing.md).weight(1f))
    }
}

private fun ThemePreference.label(): String = when (this) {
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
    ThemePreference.SYSTEM -> "System"
}

/**
 * "Never" is a real answer here and worth stating plainly - a blank line where a
 * timestamp should be reads as a bug.
 */
private fun lastSyncLabel(lastSyncAt: Long?): String {
    if (lastSyncAt == null) return "Not synced yet"
    return "Last synced ${relativeTime(lastSyncAt)}"
}
