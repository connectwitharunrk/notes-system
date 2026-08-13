package com.arunrk.note.core.common.lifecycle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createAppLifecycleMonitor(context: PlatformContext): AppLifecycleMonitor =
    AndroidAppLifecycleMonitor(context)

/**
 * Counts started activities.
 *
 * Deliberately plain framework API rather than `ProcessLifecycleOwner`, which
 * would pull in another AndroidX artifact to do the same counting. Counting also
 * survives a configuration change without help: during a rotation the
 * replacement activity starts before the old one stops, so the count never
 * reaches zero and the app is never briefly reported as backgrounded.
 */
private class AndroidAppLifecycleMonitor(context: Context) : AppLifecycleMonitor {

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Only ever touched from the main thread, where these callbacks run. */
    private var startedActivities = 0

    private val callbacks = object : Application.ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            _isActive.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            _isActive.value = startedActivities > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        val application = context.applicationContext as? Application
        if (application == null) {
            // No Application to observe - a unit test, or an unusual host. Assume
            // active rather than pausing synchronisation for a lifecycle we
            // cannot see.
            _isActive.value = true
        } else {
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }
}
