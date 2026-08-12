package com.arunrk.note.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SecureStorage"
private const val FILE_NAME = "notes_secure_session"

actual fun createSecureStorage(context: PlatformContext): SecureStorage =
    AndroidSecureStorage(context.applicationContext)

/**
 * AES-256-GCM values with the master key held in the AndroidKeystore, which
 * keeps it out of the app's own address space and off a rooted-device dump of
 * the data directory.
 */
private class AndroidSecureStorage(private val context: Context) : SecureStorage {

    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            create(masterKey)
        } catch (e: Exception) {
            // A keystore entry can be invalidated by a device restore, an
            // Android upgrade, or the user changing their screen lock. The
            // stored session is then permanently undecryptable, and the only
            // recovery is to discard it and make the user sign in again -
            // far better than crashing on every launch.
            Log.w(TAG, "Encrypted preferences unreadable; discarding stored session", e)
            context.deleteSharedPreferences(FILE_NAME)
            create(masterKey)
        }
    }

    private fun create(masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).commit()
        Unit
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key, null)
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).commit()
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        Unit
    }
}
