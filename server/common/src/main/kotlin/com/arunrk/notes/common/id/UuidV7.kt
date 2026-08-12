package com.arunrk.notes.common.id

import java.security.SecureRandom
import java.util.UUID

/**
 * RFC 9562 UUID version 7: a 48-bit big-endian millisecond timestamp followed
 * by random bits.
 *
 * Used instead of UUIDv4 because these values are primary keys. Random v4 keys
 * scatter inserts across the whole B-tree and fragment it; v7 keys are
 * time-ordered, so inserts land at the right edge of the index the way a
 * sequence would, while still being safe for clients to generate offline.
 *
 * Layout:
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          unix_ts_ms                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          unix_ts_ms           |  ver  |        rand_a         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                        rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 */
object UuidV7 {

    private val random = SecureRandom()

    private const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
    private const val VERSION_7 = 0x7L
    private const val RAND_A_BITS = 12
    private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT_RFC4122 = 0x2L

    fun generate(epochMillis: Long = System.currentTimeMillis()): UUID {
        val timestamp = epochMillis and TIMESTAMP_MASK
        val randA = random.nextInt(1 shl RAND_A_BITS).toLong()

        val mostSignificantBits =
            (timestamp shl 16) or (VERSION_7 shl RAND_A_BITS) or randA
        val leastSignificantBits =
            (random.nextLong() and RAND_B_MASK) or (VARIANT_RFC4122 shl 62)

        return UUID(mostSignificantBits, leastSignificantBits)
    }

    /**
     * Extracts the embedded creation time. Returns null for any UUID that is
     * not version 7 - never guess a timestamp out of a v4.
     */
    fun timestampOf(uuid: UUID): Long? {
        if (uuid.version() != 7) return null
        return uuid.mostSignificantBits ushr 16
    }
}

/**
 * Cryptographically strong opaque token material, base64url-encoded without
 * padding. Used for refresh tokens and password-reset tokens, which are stored
 * only as hashes and must be unguessable.
 */
object SecureTokens {

    private val random = SecureRandom()
    private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()

    const val DEFAULT_BYTES = 32   // 256 bits

    fun generate(byteLength: Int = DEFAULT_BYTES): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}
