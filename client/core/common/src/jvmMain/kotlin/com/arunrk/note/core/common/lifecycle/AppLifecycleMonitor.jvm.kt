package com.arunrk.note.core.common.lifecycle

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager

actual fun createAppLifecycleMonitor(context: PlatformContext): AppLifecycleMonitor =
    if (GraphicsEnvironment.isHeadless()) {
        // Tests and headless tooling: there is no window to watch, and pausing
        // synchronisation there would break the integration suite for no reason.
        AlwaysActiveLifecycleMonitor
    } else {
        DesktopAppLifecycleMonitor()
    }

/**
 * Desktop has no "app is in the background" concept, so window focus stands in
 * for it: AWT reports which window of this process, if any, is the active one.
 *
 * The trade-off is deliberate and visible to the user only in one direction. A
 * notes window left open but unfocused behind another app is treated as inactive
 * and stops polling, so it can show stale content while visible - but the moment
 * it is clicked, focus returns, and that fires an immediate sync. Content is
 * fresh by the time anyone is actually reading it.
 *
 * Starts optimistic: at construction no window exists yet, and starting from
 * "inactive" would pause synchronisation until the user's first click.
 */
private class DesktopAppLifecycleMonitor : AppLifecycleMonitor {

    private val _isActive = MutableStateFlow(true)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("activeWindow") { event ->
                _isActive.value = event.newValue != null
            }
    }
}

private object AlwaysActiveLifecycleMonitor : AppLifecycleMonitor {
    override val isActive: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
