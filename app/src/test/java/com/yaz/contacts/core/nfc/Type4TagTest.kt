package com.yaz.contacts.core.nfc

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cases of IbisWallet's Type4NdefApduEmulatorTest
 * (https://github.com/aeonBTC/IbisWallet/blob/main/app/src/test/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulatorTest.kt, MIT)
 * adapted to Type4Tag, and ours. Under Robolectric (https://robolectric.org/) for Android's NdefMessage.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class Type4TagTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun payload(response: ByteArray) = response.dropLast(2)
    private fun status(response: ByteArray) = response.takeLast(2).toByteArray()

    private fun card(size: Int) =
        ("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Amélie Poulain\r\nNOTE:" + "x".repeat(size) + "\r\nEND:VCARD\r\n").toByteArray()

    private fun selectApp(tag: Type4Tag) = assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 04 00 07 D2760000850101 00")))
    private fun selectFile(tag: Type4Tag, id: String) = assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 00 0C 02 $id")))

    private fun read(tag: Type4Tag, offset: Int, length: Int) =
        tag.process(byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), length.toByte()))

    /** Reads the tag as Android's NFC reader does, and gets the message back whole. */
    @Test
    fun aReaderGetsTheWholeMessage() {
        val message = Ndef.mime("text/vcard", card(700))
        val tag = Type4Tag({ message })
        selectApp(tag)
        selectFile(tag, "E103")
        val cc = tag.process(hex("00 B0 0000 0F"))
        assertEquals(0x20, cc[2].toInt())
        selectFile(tag, "E104")
        val nlen = tag.process(hex("00 B0 0000 02"))
        val length = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        assertEquals(message.size, length)
        val read = java.io.ByteArrayOutputStream()
        var offset = 2
        while (offset < length + 2) {
            val chunk = minOf(0xFB, length + 2 - offset)
            val r = read(tag, offset, chunk)
            read.write(r, 0, r.size - 2)
            offset += r.size - 2
        }
        assertArrayEquals(message, read.toByteArray())
        // A long record: TNF 2 without the short flag, a 4 byte length.
        assertEquals(0xC2, message[0].toInt() and 0xFF)
    }

    @Test
    fun writesAndStrangersAreRefused() {
        val tag = Type4Tag({ Ndef.mime("text/vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray()) })
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 02 E104")))
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 04 00 07 A0000000041010 00")))
        tag.process(hex("00 A4 04 00 07 D2760000850101 00"))
        tag.process(hex("00 A4 00 0C 02 E104"))
        assertArrayEquals(Type4Tag.SECURITY, tag.process(hex("00 D6 0000 02 0000")))
        assertArrayEquals(Type4Tag.WRONG_LENGTH, tag.process(byteArrayOf(0x00)))
    }

    @Test
    fun updateBinaryIsRefusedWithReadOnlyStatus() {
        val tag = Type4Tag({ byteArrayOf(0x01, 0x02, 0x03) })
        // Before any selection, in the CC, and in the NDEF file: always 6982.
        assertArrayEquals(byteArrayOf(0x69, 0x82.toByte()), tag.process(hex("00 D6 0000 02 0000")))
        selectApp(tag)
        selectFile(tag, "E103")
        assertArrayEquals(byteArrayOf(0x69, 0x82.toByte()), tag.process(hex("00 D6 0000 02 0000")))
        selectFile(tag, "E104")
        assertArrayEquals(byteArrayOf(0x69, 0x82.toByte()), tag.process(hex("00 D6 0000 02 0000")))
        // The file is unchanged.
        assertEquals(listOf<Byte>(0x00, 0x03), payload(read(tag, 0, 2)))
    }

    @Test
    fun aClassOtherThanZeroIsNotSupported() {
        val tag = Type4Tag({ byteArrayOf(0x01) })
        assertArrayEquals(Type4Tag.NOT_SUPPORTED, tag.process(hex("80 A4 04 00 07 D2760000850101")))
    }

    @Test
    fun aShortCardIsAShortRecord() {
        val m = Ndef.mime("text/vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray())
        assertEquals(0xD2, m[0].toInt() and 0xFF)
        assertEquals(10, m[1].toInt())
    }

    // The next four are IbisWallet's.

    @Test
    fun selectAidAcceptsShortLeVariantsFromPeerReaders() {
        val tag = Type4Tag({ byteArrayOf(0x11, 0x22, 0x33) })
        assertArrayEquals(Type4Tag.OK, status(tag.process(hex("00 A4 04 00 07 D2760000850101 10"))))
    }

    @Test
    fun readBinaryWithLeZeroReturnsTheFullCcFile() {
        val tag = Type4Tag({ byteArrayOf(0x01, 0x02, 0x03, 0x04) })
        selectApp(tag)
        selectFile(tag, "E103")
        val response = tag.process(hex("00 B0 0000 00"))
        assertArrayEquals(Type4Tag.OK, status(response))
        assertEquals(listOf<Byte>(0x00, 0x0F), payload(response).take(2))
        assertEquals(15, payload(response).size)
    }

    @Test
    fun extendedReadBinaryWorksForNdefFileReads() {
        val tag = Type4Tag({ byteArrayOf(0x01, 0x02, 0x03) })
        selectApp(tag)
        assertArrayEquals(Type4Tag.OK, tag.process(hex("00 A4 00 0C 02 E104 00")))
        val response = tag.process(hex("00 B0 0000 00 0002"))
        assertArrayEquals(Type4Tag.OK, status(response))
        assertEquals(listOf<Byte>(0x00, 0x03), payload(response))
    }

    @Test
    fun anUnknownAidIsRejected() {
        val tag = Type4Tag({ byteArrayOf(0x01) })
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 04 00 07 D2760000850102")))
    }

    // Ours, on the same parsing.

    @Test
    fun theCcSizeFollowsTheMessageButIsAtLeast512() {
        val small = Type4Tag({ byteArrayOf(0x01) })
        selectApp(small)
        selectFile(small, "E103")
        val cc = small.process(hex("00 B0 0000 0F"))
        assertEquals(0x0200, ((cc[11].toInt() and 0xFF) shl 8) or (cc[12].toInt() and 0xFF))

        val big = Type4Tag({ ByteArray(3000) })
        selectApp(big)
        selectFile(big, "E103")
        val ccBig = big.process(hex("00 B0 0000 0F"))
        assertEquals(3002, ((ccBig[11].toInt() and 0xFF) shl 8) or (ccBig[12].toInt() and 0xFF))
    }

    @Test
    fun aWrongLcOrTrailingBytesAreNotFound() {
        val tag = Type4Tag({ byteArrayOf(0x01) })
        // Lc of 3 for a file id; two stray bytes after the AID.
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 04 00 07 D2760000850101 0000")))
        selectApp(tag)
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 03 E10400")))
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 02 E104 0000")))
    }

    @Test
    fun aReadPastTheEndIsNotFoundAndReadsAreCappedAtMle() {
        val message = Ndef.mime("text/vcard", card(700))
        val tag = Type4Tag({ message })
        selectApp(tag)
        selectFile(tag, "E104")
        assertArrayEquals(Type4Tag.NOT_FOUND, read(tag, message.size + 2, 1))
        // Le 0 means 256, extended Le 65535 more: never more than the MLe.
        assertEquals(Type4Tag.MAX_READ, payload(read(tag, 0, 0)).size)
        assertEquals(Type4Tag.MAX_READ, payload(tag.process(hex("00 B0 0000 00 FFFF"))).size)
    }

    @Test
    fun aFileIsSelectedOnlyAfterTheApplication() {
        val tag = Type4Tag({ byteArrayOf(0x01) })
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 02 E103")))
        assertArrayEquals(Type4Tag.NOT_FOUND, read(tag, 0, 2))
        selectApp(tag)
        selectFile(tag, "E104")
        tag.onDeactivated()
        assertArrayEquals(Type4Tag.NOT_FOUND, tag.process(hex("00 A4 00 0C 02 E104")))
        assertArrayEquals(Type4Tag.NOT_FOUND, read(tag, 0, 2))
    }

    @Test
    fun theMessageIsAskedAtEachSelectionOfTheApplication() {
        var current: ByteArray? = byteArrayOf(0x0A)
        val tag = Type4Tag({ current })
        selectApp(tag)
        selectFile(tag, "E104")
        assertEquals(listOf<Byte>(0x00, 0x01), payload(read(tag, 0, 2)))
        current = byteArrayOf(0x0A, 0x0B)
        selectApp(tag)
        selectFile(tag, "E104")
        assertEquals(listOf<Byte>(0x00, 0x02), payload(read(tag, 0, 2)))
        // Nothing to give: an empty file.
        current = null
        selectApp(tag)
        selectFile(tag, "E104")
        assertEquals(listOf<Byte>(0x00, 0x00), payload(read(tag, 0, 2)))
    }

    @Test
    fun ndefReadFiresOnceAndOnlyWhenTheWholeFileHasBeenRead() {
        val message = Ndef.mime("text/vcard", card(700))
        var reads = 0
        val tag = Type4Tag({ message }, { reads++ })
        selectApp(tag)
        // The CC read, the NLEN, and the start of the file are not the card read.
        selectFile(tag, "E103")
        read(tag, 0, 0x0F)
        assertEquals(0, reads)
        selectFile(tag, "E104")
        read(tag, 0, 2)
        assertEquals(0, reads)
        var offset = 2
        val total = message.size + 2
        while (offset + Type4Tag.MAX_READ < total) {
            read(tag, offset, Type4Tag.MAX_READ)
            offset += Type4Tag.MAX_READ
            assertEquals(0, reads)
        }
        assertTrue(offset < total)
        read(tag, offset, total - offset)
        assertEquals(1, reads)
        // Read again in the same selection: still once.
        read(tag, offset, total - offset)
        assertEquals(1, reads)
        // A new selection of the file is a new read.
        selectFile(tag, "E104")
        read(tag, 0, 0)
        assertEquals(1, reads)
        read(tag, total - 1, 1)
        assertEquals(2, reads)
    }

    @Test
    fun ndefReadFiresOnAWholeFileInOneExtendedRead() {
        val message = Ndef.mime("text/vcard", "BEGIN:VCARD\r\nEND:VCARD".toByteArray())
        var reads = 0
        val tag = Type4Tag({ message }, { reads++ })
        selectApp(tag)
        selectFile(tag, "E104")
        tag.process(hex("00 B0 0000 00 0100"))
        assertEquals(1, reads)
    }

    @Test
    fun ndefReadNeverFiresForAnEmptyTagOrWithoutTheFile() {
        var reads = 0
        val tag = Type4Tag({ null }, { reads++ })
        selectApp(tag)
        selectFile(tag, "E104")
        read(tag, 0, 2)
        assertEquals(0, reads)
        // A reader that left without reading anything: nothing was sent.
        val full = Type4Tag({ byteArrayOf(0x01, 0x02) }, { reads++ })
        selectApp(full)
        selectFile(full, "E104")
        full.onDeactivated()
        assertEquals(0, reads)
    }
}
