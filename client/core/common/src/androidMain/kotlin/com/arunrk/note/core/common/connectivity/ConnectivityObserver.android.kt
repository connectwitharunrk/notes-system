package com.arunrk.note.core.common.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createConnectivityObserver(
    context: PlatformContext,
    scope: CoroutineScope,
): ConnectivityObserver = AndroidConnectivityObserver(context)

private class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {

    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyValidated())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = currentlyValidated()
        }

        override fun onLost(network: Network) {
            _isOnline.value = currentlyValidated()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isOnline.value =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    init {
        // Registered for the process lifetime on purpose: this observer is a
        // singleton and there is no earlier point at which we stop caring.
        runCatching {
            manager?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    /**
     * NET_CAPABILITY_VALIDATED rather than merely "connected": it means Android
     * actually reached the internet through this network, which excludes the
     * captive-portal case where a connection exists but nothing works.
     */
    private fun currentlyValidated(): Boolean {
        val activeManager = manager ?: return false
        val network = activeManager.activeNetwork ?: return false
        val capabilities = activeManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
