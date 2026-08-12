package com.arunrk.note.core.common.connectivity

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * Backed by NWPathMonitor, Apple's supported connectivity API.
 *
 * NOTE: this file has never been compiled or run. iOS targets cannot be built
 * on a Windows host, so everything in iosMain across this project is written to
 * the documented API but unverified. See docs/ARCHITECTURE.md L11.
 */
actual fun createConnectivityObserver(
    context: PlatformContext,
    scope: CoroutineScope,
): ConnectivityObserver = IosConnectivityObserver()

private class IosConnectivityObserver : ConnectivityObserver {

    private val state = MutableStateFlow(true)
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}
