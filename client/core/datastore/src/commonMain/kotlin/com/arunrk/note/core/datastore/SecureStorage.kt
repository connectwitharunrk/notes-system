package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.platform.PlatformContext

/**
 * Storage for session credentials.
 *
 * The protection this actually provides differs sharply by platform, and it is
 * worth being honest about that rather than letting the interface name imply
 * uniform safety:
 *
 * | Platform | Mechanism | Real protection |
 * |----------|-----------|-----------------|
 * | Android  | EncryptedSharedPreferences, key in the hardware-backed Keystore | Strong |
 * | iOS      | Keychain, `AfterFirstUnlockThisDeviceOnly` | Strong |
 * | Desktop  | AES-GCM file with a key file beside it | **Obfuscation only** |
 *
 * There is no OS keychain available to a plain JVM process, so on desktop any
 * process running as the same user can read the key and decrypt the session.
 * That is mitigated - not solved - by issuing desktop refresh tokens with a
 * 7-day lifetime instead of 60, and by server-side revocation on logout.
 */
interface SecureStorage {

    suspend fun putString(key: String, value: String)

    suspend fun getString(key: String): String?

    suspend fun remove(key: String)

    /** Wipes everything. Called on logout and on an unrecoverable auth failure. */
    suspend fun clear()

    companion object Keys {
        const val ACCESS_TOKEN = "access_token"
        const val ACCESS_TOKEN_EXPIRES_AT = "access_token_expires_at"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_ID = "user_id"
        const val USER_EMAIL = "user_email"
        const val USER_NAME = "user_name"
    }
}

expect fun createSecureStorage(context: PlatformContext): SecureStorage
