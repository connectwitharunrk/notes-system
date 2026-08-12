package com.arunrk.notes.common.hash

import java.security.MessageDigest

object Hashing {

    /**
     * Hash used to store opaque tokens at rest.
     *
     * Plain SHA-256 rather than BCrypt is correct here and only here: the input
     * is 256 bits of cryptographic randomness, so there is no low-entropy
     * search space for an attacker to brute-force and no reason to pay a work
     * factor on every request. Passwords are the opposite case and use BCrypt.
     */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

    /**
     * Canonical note content hash, shared by client and server.
     *
     * Title and content are joined with a 0x00 separator so that
     * ("ab", "c") and ("a", "bc") hash differently - without the separator they
     * would collide and the conflict resolver would call a real divergence a
     * no-op.
     */
    fun noteContentHash(title: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(title.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(content.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
