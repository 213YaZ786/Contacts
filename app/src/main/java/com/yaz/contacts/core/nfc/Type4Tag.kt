package com.yaz.contacts.core.nfc

/**
 * The phone as an NFC tag another phone reads by touching it: an NFC Forum
 * Type 4 Tag (version 2.0) holding one NDEF message, answering the reader's
 * commands (ISO 7816-4 APDUs) as a real tag would. Read only: every write
 * is refused. Written from the NFC Forum Type 4 Tag specification.
 */
class Type4Tag(ndefMessage: ByteArray) {

    /** The NDEF file: its length on two bytes, then the message. */
    private val ndefFile = byteArrayOf((ndefMessage.size shr 8).toByte(), ndefMessage.size.toByte()) + ndefMessage

    /** The Capability Container: how the reader finds and reads the NDEF file. */
    private val ccFile = byteArrayOf(
        0x00, 0x0F,             // CC length
        0x20,                   // mapping version 2.0
        0x00, MAX_READ.toByte(),// MLe: most bytes read at once
        0x00, 0x34,             // MLc: most bytes written at once
        0x04, 0x06,             // NDEF File Control TLV
        0xE1.toByte(), 0x04,    // its file id
        (MAX_FILE shr 8).toByte(), MAX_FILE.toByte(), // its largest size
        0x00,                   // read: open
        0xFF.toByte()           // write: never
    )

    private var selected: ByteArray? = null
    private var appSelected = false

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return WRONG_LENGTH
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        if (cla != 0x00) return NOT_SUPPORTED
        return when (ins) {
            0xA4 -> select(apdu, p1, p2)
            0xB0 -> read(apdu, p1, p2)
            // UPDATE BINARY and anything else: this tag is read only.
            0xD6 -> SECURITY
            else -> NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else return WRONG_LENGTH
        if (apdu.size < 5 + lc) return WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)
        return when {
            // SELECT by name: the NDEF Tag Application.
            p1 == 0x04 && p2 == 0x00 -> if (data.contentEquals(AID)) { appSelected = true; selected = null; OK } else NOT_FOUND
            // SELECT by file id, only inside the application.
            p1 == 0x00 && p2 == 0x0C && appSelected -> when {
                data.contentEquals(CC_ID) -> { selected = ccFile; OK }
                data.contentEquals(NDEF_ID) -> { selected = ndefFile; OK }
                else -> NOT_FOUND
            }
            else -> NOT_FOUND
        }
    }

    private fun read(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val file = selected ?: return NOT_FOUND
        val offset = (p1 shl 8) or p2
        if (offset > file.size) return WRONG_PARAMS
        var le = if (apdu.size >= 5) apdu[apdu.size - 1].toInt() and 0xFF else 0
        if (le == 0) le = 256
        val end = minOf(file.size, offset + minOf(le, MAX_READ))
        return file.copyOfRange(offset, end) + OK
    }

    companion object {
        val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)
        const val MAX_READ = 0xFB
        const val MAX_FILE = 0x7FFF
        val OK = byteArrayOf(0x90.toByte(), 0x00)
        val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val WRONG_PARAMS = byteArrayOf(0x6B, 0x00)
        val SECURITY = byteArrayOf(0x69, 0x82.toByte())
    }
}

/** An NDEF message of one MIME record (a contact card), as the NFC Forum writes it. */
object Ndef {

    fun mime(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val short = payload.size < 256
        // MB | ME | (SR) | TNF = 2 (a MIME type)
        val header = (0x80 or 0x40 or (if (short) 0x10 else 0) or 0x02).toByte()
        val length = if (short) byteArrayOf(payload.size.toByte())
        else byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        return byteArrayOf(header, typeBytes.size.toByte()) + length + typeBytes + payload
    }
}
