package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.log.Log
import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "SecureStorage"
private const val KEY_LENGTH_BITS = 256
private const val GCM_TAG_BITS = 128
private const val NONCE_BYTES = 12

actual fun createSecureStorage(context: PlatformContext): SecureStorage {
    val directory = File(System.getProperty("user.home"), ".notes-system")
    return DesktopSecureStorage(
        keyFile = File(directory, "session.key"),
        dataFile = File(directory, "session.enc"),
    )
}

/**
 * AES-GCM encrypted blob with the key in a sibling file.
 *
 * ### This is obfuscation, not security
 *
 * A plain JVM process has no access to an OS keychain, so the key has to live
 * somewhere the process can read without user interaction - which means anything
 * else running as the same user can read it too. Encrypting at all is still
 * worth doing: it keeps tokens out of plain-text backups, out of casual
 * file-manager browsing, and out of accidental screen shares. It does not defend
 * against a local attacker, and pretending otherwise would be worse than the
 * limitation itself.
 *
 * The real mitigation lives on the server: desktop refresh tokens expire in
 * 7 days rather than 60, and logout revokes them immediately.
 */
private class DesktopSecureStorage(
    private val keyFile: File,
    private val dataFile: File,
) : SecureStorage {

    private val mutex = Mutex()
    private val random = SecureRandom()

    override suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readAll().toMutableMap()
            entries[key] = value
            writeAll(entries)
        }
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { readAll()[key] }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = readAll().toMutableMap()
            entries.remove(key)
            writeAll(entries)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dataFile.delete()
            Unit
        }
    }

    // -----------------------------------------------------------------------

    private fun readAll(): Map<String, String> {
        if (!dataFile.exists()) return emptyMap()
        return try {
            val raw = dataFile.readBytes()
            if (raw.size <= NONCE_BYTES) return emptyMap()

            val nonce = raw.copyOfRange(0, NONCE_BYTES)
            val payload = raw.copyOfRange(NONCE_BYTES, raw.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            decode(String(cipher.doFinal(payload), Charsets.UTF_8))
        } catch (e: Exception) {
            // Corrupt or written with a key that no longer exists. Nothing here
            // is recoverable and nothing here is precious - the user just signs
            // in again. Failing loudly would strand them on a broken launch.
            Log.w(TAG, "Stored session unreadable; discarding it", e)
            dataFile.delete()
            emptyMap()
        }
    }

    private fun writeAll(entries: Map<String, String>) {
        dataFile.parentFile?.mkdirs()

        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))

        dataFile.writeBytes(nonce + cipher.doFinal(encode(entries).toByteArray(Charsets.UTF_8)))
        restrictToOwner(dataFile)
    }

    private fun loadOrCreateKey(): SecretKeySpec {
        if (keyFile.exists()) {
            return SecretKeySpec(keyFile.readBytes(), "AES")
        }
        val key = KeyGenerator.getInstance("AES").apply { init(KEY_LENGTH_BITS) }.generateKey()
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(key.encoded)
        restrictToOwner(keyFile)
        return SecretKeySpec(key.encoded, "AES")
    }

    /**
     * Best effort. POSIX gets 0600; Windows gets the closest equivalent the
     * java.io API can express, which is weaker but better than nothing.
     */
    private fun restrictToOwner(file: File) {
        runCatching {
            val path = file.toPath()
            val supportsPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
            if (supportsPosix) {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } else {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }
    }

    // A tiny escaped key=value format. Deliberately not JSON: this module has no
    // serialization dependency and the payload is a handful of opaque tokens.
    private fun encode(entries: Map<String, String>): String =
        entries.entries.joinToString("\n") { "${it.key.escape()}=${it.value.escape()}" }

    private fun decode(raw: String): Map<String, String> = raw
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            line.substring(0, separator).unescape() to line.substring(separator + 1).unescape()
        }
        .toMap()

    private fun String.escape() = replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e")

    private fun String.unescape() = replace("\\e", "=").replace("\\n", "\n").replace("\\\\", "\\")
}
