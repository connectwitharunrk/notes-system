package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.platform.PlatformContext
import okio.FileSystem
import java.io.File

actual fun preferencesFilePath(context: PlatformContext): String =
    File(context.applicationContext.filesDir, PREFERENCES_FILE).absolutePath

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM
