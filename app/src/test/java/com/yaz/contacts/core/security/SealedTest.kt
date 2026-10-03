package com.yaz.contacts.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SealedTest {

    private val plain = "BEGIN:VCARD\nFN:Amélie\nEND:VCARD".toByteArray()

    @Test
    fun opensWithThePassphraseOnly() {
        val sealed = Sealed.seal(plain, "correct horse battery".toCharArray())
        assertArrayEquals(plain, Sealed.open(sealed, "correct horse battery".toCharArray()))
        assertNull(Sealed.open(sealed, "wrong horse battery".toCharArray()))
    }

    @Test
    fun aChangedByteOrHeaderIsRefused() {
        val sealed = Sealed.seal(plain, "pass phrase here".toCharArray())
        val body = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(Sealed.open(body, "pass phrase here".toCharArray()))
        // The cost in the header is bound to the content: raising it breaks the seal.
        val header = sealed.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertNull(Sealed.open(header, "pass phrase here".toCharArray()))
        assertNull(Sealed.open("not a backup at all, just text".toByteArray(), "x".toCharArray()))
    }
}
