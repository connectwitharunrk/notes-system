package com.arunrk.note.core.common.lifecycle

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app is in front of the user right now.
 *
 * Two things depend on the answer. Coming back to the app is the moment its
 * contents are about to be read, and therefore the moment they are most worth
 * refreshing - a note written on another device an hour ago should not still be
 * missing while the user is looking at the list. And the opposite: polling a
 * server every thirty seconds for a screen nobody is looking at spends battery
 * for nothing.
 */
interface AppLifecycleMonitor {
    val isActive: StateFlow<Boolean>
}

/**
 * Platforms with no observable lifecycle report "always active": the fallback
 * must never be "permanently paused", because that would silently stop
 * synchronising rather than merely doing it more often than needed.
 */
expect fun createAppLifecycleMonitor(context: PlatformContext): AppLifecycleMonitor
