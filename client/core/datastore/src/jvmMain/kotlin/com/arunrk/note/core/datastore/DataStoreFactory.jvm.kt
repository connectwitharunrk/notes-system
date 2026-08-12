package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.platform.PlatformContext
import okio.FileSystem
import java.io.File

/**
 * Alongside the database in the user's home directory, so preferences survive
 * launching the app from a different working directory.
 */
actual fun preferencesFilePath(context: PlatformContext): String {
    val directory = File(System.getProperty("user.home"), ".notes-system")
    directory.mkdirs()
    return File(directory, PREFERENCES_FILE).absolutePath
}

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM
