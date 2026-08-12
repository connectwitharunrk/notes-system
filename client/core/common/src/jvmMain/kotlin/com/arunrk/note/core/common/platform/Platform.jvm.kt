package com.arunrk.note.core.common.platform

/**
 * Desktop needs no ambient platform handle; this exists only to satisfy the
 * expect declaration.
 */
actual class PlatformContext

actual val platformName: String
    get() = "Desktop ${System.getProperty("os.name")} ${System.getProperty("os.version")}"

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
