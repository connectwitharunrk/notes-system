package com.arunrk.note.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.arunrk.note.core.common.connectivity.NetworkMonitor
import com.arunrk.note.core.common.platform.platformName
import com.arunrk.note.core.database.sql.NoteDatabase
import com.arunrk.note.core.datastore.AppPreferences
import com.arunrk.note.core.datastore.SecureStorage
import com.arunrk.note.core.designsystem.layout.LocalWindowSize
import com.arunrk.note.core.designsystem.theme.Spacing
import com.arunrk.note.core.network.ApiConfig
import com.arunrk.note.core.network.api.AuthApi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Temporary diagnostic screen for the Phase 3 infrastructure.
 *
 * It exists so the core stack can be verified by running the app rather than
 * only by compiling it: every row below is a real call into a different piece of
 * infrastructure. Replaced by the real navigation graph in Phase 4.
 */
@Composable
fun CoreStatusScreen() {
    val database: NoteDatabase = koinInject()
    val preferences: AppPreferences = koinInject()
    val secureStorage: SecureStorage = koinInject()
    val networkMonitor: NetworkMonitor = koinInject()
    val apiConfig: ApiConfig = koinInject()
    val authApi: AuthApi = koinInject()

    val scope = rememberCoroutineScope()
    val windowSize = LocalWindowSize.current
    val isOnline by networkMonitor.isOnline.collectAsState()

    var deviceId by remember { mutableStateOf("resolving…") }
    var noteCount by remember { mutableStateOf("counting…") }
    var secureRoundTrip by remember { mutableStateOf("testing…") }
    var serverProbe by remember { mutableStateOf("not attempted") }

    LaunchedEffect(Unit) {
        // Preferences: proves DataStore reads and writes on this platform.
        deviceId = runCatching { preferences.deviceId() }
            .getOrElse { "FAILED: ${it.message}" }

        // Database: proves the SQLDelight driver opened the file and the schema
        // exists.
        noteCount = runCatching {
            database.noteEntityQueries.selectAllForUser("smoke-test").executeAsList().size.toString()
        }.getOrElse { "FAILED: ${it.message}" }

        // Secure storage: a full write-read-delete cycle, because on some
        // platforms the failure only appears on read.
        secureRoundTrip = runCatching {
            secureStorage.putString("smoke_test", "ok")
            val read = secureStorage.getString("smoke_test")
            secureStorage.remove("smoke_test")
            if (read == "ok") "write + read + delete OK" else "MISMATCH: got $read"
        }.getOrElse { "FAILED: ${it.message}" }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text("Notes — core infrastructure", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Phase 3 diagnostics. Each row is a live call into a different layer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    StatusRow("Platform", platformName)
                    StatusRow("Window size", "$windowSize")
                    HorizontalDivider()
                    StatusRow("Preferences (device id)", deviceId)
                    StatusRow("Database (note rows)", noteCount)
                    StatusRow("Secure storage", secureRoundTrip)
                    HorizontalDivider()
                    StatusRow("API base URL", apiConfig.apiRoot)
                    StatusRow("Network monitor", if (isOnline) "online" else "offline")
                    StatusRow("Server probe", serverProbe)
                }
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        serverProbe = "contacting…"
                        // /users/me without a session must come back
                        // Unauthenticated. That is a *success* for this probe:
                        // it proves DNS, the socket, TLS, JSON decoding and the
                        // error envelope mapping all work end to end.
                        val result = authApi.me()
                        serverProbe = result.errorOrNull()
                            ?.let { "reached server, got ${it::class.simpleName}" }
                            ?: "reached server, unexpectedly authenticated"
                    }
                },
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Text("Probe server")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1.4f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
    }
}
