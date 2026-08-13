package com.arunrk.note.core.common.platform

/**
 * Desktop needs no ambient platform handle; this exists only to satisfy the
 * expect declaration. Use [PlatformContext.INSTANCE] — the class is abstract
 * because the Android actual is `android.content.Context`.
 */
actual abstract class PlatformContext private constructor() {
    companion object {
        val INSTANCE: PlatformContext = object : PlatformContext() {}
    }
}

actual val platformName: String
    get() = "Desktop ${System.getProperty("os.name")} ${System.getProperty("os.version")}"

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
