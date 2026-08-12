package com.arunrk.note.core.common.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

actual class PlatformContext

actual val platformName: String
    get() = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
