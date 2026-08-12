package com.arunrk.note.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.arunrk.note.core.common.platform.PlatformContext
import okio.FileSystem
import okio.Path.Companion.toPath

internal const val PREFERENCES_FILE = "notes.preferences_pb"

/** Absolute path of the preferences file for this platform. */
expect fun preferencesFilePath(context: PlatformContext): String

expect fun platformFileSystem(): FileSystem

fun createDataStore(context: PlatformContext): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = platformFileSystem(),
            serializer = PreferencesSerializer,
            producePath = { preferencesFilePath(context).toPath() },
        )
    )
