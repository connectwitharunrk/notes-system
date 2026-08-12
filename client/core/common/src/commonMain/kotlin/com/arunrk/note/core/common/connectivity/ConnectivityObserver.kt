package com.arunrk.note.core.common.connectivity

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The operating system's view of whether a network is available.
 */
interface ConnectivityObserver {
    val isOnline: StateFlow<Boolean>
}

expect fun createConnectivityObserver(
    context: PlatformContext,
    scope: CoroutineScope,
): ConnectivityObserver

/**
 * The app's working answer to "are we online", combining the OS signal with
 * what actually happened on the wire.
 *
 * The OS signal alone is not trustworthy: a device attached to a captive portal
 * or a router with no upstream reports a perfectly good network while every
 * request fails. Conversely a platform with no signal wired up (desktop) would
 * otherwise be permanently "online".
 *
 * So a real request failure marks us offline immediately, a real success marks
 * us online immediately, and the OS signal is used to notice reconnection
 * without having to poll. Whichever evidence is freshest wins.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityObserver,
) {
    private val _isOnline = MutableStateFlow(connectivity.isOnline.value)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** Call when any request completes without a transport error. */
    fun reportReachable() {
        _isOnline.value = true
    }

    /** Call on a transport-level failure only - not on a 4xx or 5xx. */
    fun reportUnreachable() {
        _isOnline.value = false
    }

    /**
     * Mirrors the OS signal into our state. Losing the network is believed
     * immediately; regaining it is believed optimistically, and the next request
     * corrects us if the connection is useless.
     */
    suspend fun observePlatformSignal() {
        connectivity.isOnline.collect { osOnline -> _isOnline.value = osOnline }
    }
}
