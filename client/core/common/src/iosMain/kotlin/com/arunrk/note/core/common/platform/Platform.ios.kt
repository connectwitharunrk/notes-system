package com.arunrk.note.core.common.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

/**
 * iOS needs no ambient platform handle; use [PlatformContext.INSTANCE]. The
 * class is abstract because the Android actual is `android.content.Context`.
 */
actual abstract class PlatformContext private constructor() {
    companion object {
        val INSTANCE: PlatformContext = object : PlatformContext() {}
    }
}

actual val platformName: String
    get() = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
