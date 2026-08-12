package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * NOTE: never compiled - iOS cannot be built on a Windows host.
 * See docs/ARCHITECTURE.md L11.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun preferencesFilePath(context: PlatformContext): String {
    val documents: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documents?.path) { "Unable to resolve the documents directory" } +
        "/" + PREFERENCES_FILE
}

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM
