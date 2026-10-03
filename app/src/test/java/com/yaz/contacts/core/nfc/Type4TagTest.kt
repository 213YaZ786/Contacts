package com.yaz.contacts.core.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Type4TagTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Reads the tag as Android's NFC reader does, and gets the message back whole. */
    @Test
    fun aReaderGetsTheWholeMessage() {
        val card = ("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Amélie Poulain\r\n" + "NOTE:" + "x".repeat(700) + "\r\nEND:VCARD\r\n").toByteArray()
        val message = Ndef.mime("text/vcard", card)
        val tag = Type4Tag(message)
        assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 04 00 07 D2760000850101 00")))
        assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 00 0C 02 E103")))
        val cc = tag.process(hex("00 B0 0000 0F"))
        assertEquals(0x20, cc[2].toInt())
        assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 00 0C 02 E104")))
        val nlen = tag.process(hex("00 B0 0000 02"))
        val length = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        assertEquals(message.size, length)
        val read = java.io.ByteArrayOutputStream()
        var offset = 2
        while (offset < length + 2) {
            val chunk = minOf(0xFB, length + 2 - offset)
            val r = tag.process(byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), chunk.toByte()))
            read.write(r, 0, r.size - 2)
            offset += r.size - 2
        }
        assertArrayEquals(message, read.toByteArray())
        // A long record: TNF 2 without the short flag, a 4 byte length.
        assertEquals(0xC2, message[0].toInt() and 0xFF)
    }

    @Test
    fun writesAndStrangersAreRefused() {
        val tag = Type4Tag(Ndef.mime("text/vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray()))
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 02 E104")))
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 04 00 07 A0000000041010 00")))
        tag.process(hex("00 A4 04 00 07 D2760000850101 00"))
        tag.process(hex("00 A4 00 0C 02 E104"))
        assertArrayEquals(Type4Tag.SECURITY, tag.process(hex("00 D6 0000 02 0000")))
        assertArrayEquals(Type4Tag.WRONG_LENGTH, tag.process(byteArrayOf(0x00)))
    }

    @Test
    fun aShortCardIsAShortRecord() {
        val m = Ndef.mime("text/vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray())
        assertEquals(0xD2, m[0].toInt() and 0xFF)
        assertEquals(10, m[1].toInt())
    }
}
