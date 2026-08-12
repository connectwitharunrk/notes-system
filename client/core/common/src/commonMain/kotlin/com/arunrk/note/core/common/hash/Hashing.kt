package com.arunrk.note.core.common.hash

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

object Hashing {

    fun sha256Hex(value: String): String = value.encodeUtf8().sha256().hex()

    /**
     * Canonical note content hash. MUST match the server byte for byte -
     * `Hashing.noteContentHash` in the backend's :common module.
     *
     * Title and content are separated by a 0x00 byte so that ("ab", "c") and
     * ("a", "bc") hash differently. Without the separator they collide, and the
     * sync conflict resolver would classify a genuine divergence as "identical"
     * and silently discard one side's edit.
     */
    fun noteContentHash(title: String, content: String): String {
        val buffer = Buffer()
        buffer.writeUtf8(title)
        buffer.writeByte(0)
        buffer.writeUtf8(content)
        return buffer.readByteString().sha256().hex()
    }
}
