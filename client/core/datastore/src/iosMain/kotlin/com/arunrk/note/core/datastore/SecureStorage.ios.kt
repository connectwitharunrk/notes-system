package com.arunrk.note.core.datastore

import com.arunrk.note.core.common.platform.PlatformContext
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE = "com.arunrk.note.session"

/**
 * Keychain-backed session storage.
 *
 * !! UNVERIFIED !!
 * iOS targets cannot be compiled on a Windows host, so this file has never been
 * built or run. It is written against Apple's documented Keychain Services API
 * and follows the standard Kotlin/Native cinterop pattern, but expect to fix
 * small compilation details on first build from a Mac.
 * See docs/ARCHITECTURE.md L11.
 */
actual fun createSecureStorage(context: PlatformContext): SecureStorage = IosSecureStorage()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosSecureStorage : SecureStorage {

    override suspend fun putString(key: String, value: String) {
        // SecItemAdd fails with errSecDuplicateItem on an existing entry, so
        // delete first: one code path instead of add-or-update, and idempotent.
        remove(key)

        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = baseQuery(key)
        CFDictionaryAddValue(query, kSecValueData, CFBridgingRetain(data))
        // ThisDeviceOnly keeps the session out of iCloud Keychain and encrypted
        // backups, so restoring a backup onto another device does not carry a
        // live session with it.
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)

        SecItemAdd(query, null)
        CFRelease(query)
    }

    override suspend fun getString(key: String): String? = memScoped {
        val query = baseQuery(key)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)

        if (status != errSecSuccess) return@memScoped null

        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    override suspend fun remove(key: String) {
        val query = baseQuery(key)
        SecItemDelete(query)
        CFRelease(query)
    }

    override suspend fun clear() {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)!!
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain(SERVICE as NSString))
        SecItemDelete(query)
        CFRelease(query)
    }

    /** Caller owns the returned dictionary and must CFRelease it. */
    private fun baseQuery(key: String): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)!!
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain(SERVICE as NSString))
        CFDictionaryAddValue(query, kSecAttrAccount, CFBridgingRetain(key as NSString))
        return query
    }
}
