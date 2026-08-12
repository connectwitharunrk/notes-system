package com.arunrk.note.core.common.platform

import android.content.Context

actual typealias PlatformContext = Context

actual val platformName: String
    get() = "Android ${android.os.Build.VERSION.SDK_INT}"

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
