package com.arunrk.notes.common

import com.arunrk.notes.common.hash.Hashing
import com.arunrk.notes.common.id.SecureTokens
import com.arunrk.notes.common.id.UuidV7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UuidV7Test {

    @Test
    fun `generates version 7 with the RFC 4122 variant`() {
        repeat(200) {
            val uuid = UuidV7.generate()
            assertEquals(7, uuid.version(), "version nibble")
            assertEquals(2, uuid.variant(), "variant bits must be 10x")
        }
    }

    @Test
    fun `embeds the supplied timestamp`() {
        val millis = 1_760_000_000_000L
        assertEquals(millis, UuidV7.timestampOf(UuidV7.generate(millis)))
    }

    /**
     * The whole reason for choosing v7 over v4: ids generated later must sort
     * later, so they append to the right edge of the primary-key index instead
     * of scattering across it.
     */
    @Test
    fun `ids from increasing timestamps sort in chronological order`() {
        val base = 1_760_000_000_000L
        val ids = (0 until 50).map { UuidV7.generate(base + it * 1_000L).toString() }

        assertEquals(ids.sorted(), ids, "lexicographic order must match creation order")
    }

    @Test
    fun `ids generated within the same millisecond are still unique`() {
        val millis = 1_760_000_000_000L
        val ids = (0 until 5_000).map { UuidV7.generate(millis) }.toSet()

        assertEquals(5_000, ids.size)
    }

    @Test
    fun `timestampOf refuses to guess for non-v7 uuids`() {
        assertNull(UuidV7.timestampOf(java.util.UUID.randomUUID()))
    }
}

class SecureTokensTest {

    @Test
    fun `tokens are url safe and unpadded`() {
        repeat(100) {
            val token = SecureTokens.generate()
            assertTrue(token.none { it == '+' || it == '/' || it == '=' }, "not url-safe: $token")
        }
    }

    @Test
    fun `tokens do not repeat`() {
        val tokens = (0 until 10_000).map { SecureTokens.generate() }.toSet()
        assertEquals(10_000, tokens.size)
    }
}

class HashingTest {

    @Test
    fun `sha256 is stable and hex encoded`() {
        // Known-answer test against the published SHA-256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc"),
        )
    }

    /**
     * Without the 0x00 separator, ("ab","c") and ("a","bc") would hash
     * identically - and the sync conflict resolver would classify a genuine
     * divergence as "no change", silently discarding one side's edit.
     */
    @Test
    fun `note content hash separates title from content`() {
        assertNotEquals(
            Hashing.noteContentHash("ab", "c"),
            Hashing.noteContentHash("a", "bc"),
        )
    }

    @Test
    fun `note content hash is deterministic`() {
        assertEquals(
            Hashing.noteContentHash("Groceries", "milk, eggs"),
            Hashing.noteContentHash("Groceries", "milk, eggs"),
        )
    }

    @Test
    fun `note content hash changes when content changes`() {
        assertNotEquals(
            Hashing.noteContentHash("Groceries", "milk, eggs"),
            Hashing.noteContentHash("Groceries", "milk, eggs, bread"),
        )
    }

    @Test
    fun `note content hash handles empty fields`() {
        assertNotEquals(Hashing.noteContentHash("", ""), Hashing.noteContentHash("", " "))
    }
}
