package com.arunrk.note.core.common.connectivity

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop has no dependable OS-level connectivity signal available to a plain
 * JVM process. Rather than fake one - polling a hard-coded host would be both
 * a privacy smell and wrong behind a proxy - this reports "online" and lets
 * [NetworkMonitor] derive the truth from actual request outcomes.
 *
 * The practical effect is that desktop notices it is offline one failed request
 * later than mobile does, which is acceptable: the sync engine already treats a
 * transport failure as offline and backs off.
 */
actual fun createConnectivityObserver(
    context: PlatformContext,
    scope: CoroutineScope,
): ConnectivityObserver = DesktopConnectivityObserver()

private class DesktopConnectivityObserver : ConnectivityObserver {
    private val state = MutableStateFlow(true)
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()
}
