package com.arunrk.note.core.common.id

import com.arunrk.note.core.common.platform.currentTimeMillis
import kotlin.random.Random

/**
 * RFC 9562 UUID version 7 - a 48-bit millisecond timestamp followed by random
 * bits, rendered in the canonical 8-4-4-4-12 hex form.
 *
 * Notes are created offline, so the client mints their primary keys. Time
 * ordering matters because those keys land in a server B-tree index: random v4
 * keys scatter inserts across the whole index, v7 keys append at the right edge.
 *
 * Must produce the same layout as the server's `UuidV7`, since ids generated on
 * either side end up in the same column.
 */
object UuidV7 {

    private const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
    private const val VERSION_7 = 0x7L
    private const val RAND_A_BITS = 12
    private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT_RFC4122 = 0x2L

    fun generate(epochMillis: Long = currentTimeMillis()): String {
        val timestamp = epochMillis and TIMESTAMP_MASK
        val randA = Random.nextInt(1 shl RAND_A_BITS).toLong()

        val mostSignificantBits = (timestamp shl 16) or (VERSION_7 shl RAND_A_BITS) or randA
        val leastSignificantBits = (Random.nextLong() and RAND_B_MASK) or (VARIANT_RFC4122 shl 62)

        return format(mostSignificantBits, leastSignificantBits)
    }

    private fun format(msb: Long, lsb: Long): String {
        val hex = StringBuilder(36)
        hex.append(msb.toHex(16), 0, 8)
        hex.append('-')
        hex.append(msb.toHex(16), 8, 12)
        hex.append('-')
        hex.append(msb.toHex(16), 12, 16)
        hex.append('-')
        hex.append(lsb.toHex(16), 0, 4)
        hex.append('-')
        hex.append(lsb.toHex(16), 4, 16)
        return hex.toString()
    }

    /** Zero-padded, fixed width. `toString(16)` alone drops leading zeroes. */
    private fun Long.toHex(width: Int): String {
        val raw = this.toULong().toString(16)
        return if (raw.length >= width) raw else "0".repeat(width - raw.length) + raw
    }
}
