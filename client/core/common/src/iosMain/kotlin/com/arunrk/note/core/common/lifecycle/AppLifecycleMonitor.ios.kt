package com.arunrk.note.core.common.lifecycle

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * NOTE: this file has never been compiled or run. iOS targets cannot be built on
 * a Windows host, so everything in iosMain across this project is written to the
 * documented API but unverified. See docs/ARCHITECTURE.md L11.
 */
actual fun createAppLifecycleMonitor(context: PlatformContext): AppLifecycleMonitor =
    IosAppLifecycleMonitor()

/**
 * Backed by the two UIKit notifications that bracket a session in front of the
 * user.
 *
 * `DidEnterBackground` rather than `WillResignActive` on purpose: resigning
 * active covers momentary interruptions - a notification banner, the app
 * switcher, a permission dialog - and treating those as "gone" would stop
 * polling for a screen the user is still looking at, then immediately restart
 * it. Entering the background is the real thing.
 *
 * Starts optimistic, matching every other platform here: an app that is being
 * constructed is, in practice, an app that is starting up in front of someone.
 */
private class IosAppLifecycleMonitor : AppLifecycleMonitor {

    private val _isActive = MutableStateFlow(true)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    init {
        val center = NSNotificationCenter.defaultCenter

        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ -> _isActive.value = true }

        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> _isActive.value = false }
    }
}
