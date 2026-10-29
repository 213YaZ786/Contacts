package com.yaz.contacts.core.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord

/**
 * The phone as an NFC tag another phone reads by touching it: an NFC Forum
 * Type 4 Tag (version 2.0) holding one NDEF message, answering the reader's
 * commands (ISO 7816-4 APDUs) as a real tag would. Read only: every write
 * is refused.
 *
 * [ndefMessageProvider] gives the NDEF message at each selection of the NDEF
 * application (null: an empty tag). [onNdefRead] is called once per selection
 * of the NDEF file, when a read of it reaches its last byte.
 */
// From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), the class.
// Changed: public; CLA 0x00 only, UPDATE BINARY answering 6982, the NDEF file selectable only after the
// application, reads capped at the CC's MLe (all ours), and the onNdefRead callback, the read of the
// NDEF file being what MeDrop's HCE service takes as the reader's scan:
// https://github.com/sameerasw/MeDrop/blob/main/app/src/main/java/com/sameerasw/medrop/services/MeDropHceService.kt (MIT).
class Type4Tag(
    private val ndefMessageProvider: () -> ByteArray?,
    private val onNdefRead: () -> Unit = {}
) {

    private enum class SelectedFile { NONE, CC, NDEF }

    private var selectedFile = SelectedFile.NONE
    private var appSelected = false
    private var ndefFileBytes = byteArrayOf(0x00, 0x00)
    private var ndefReported = false

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), processCommandApdu.
    // Changed: CLA must be 0x00, UPDATE BINARY (0xD6) answers 6982, a command under 4 bytes answers 6700 (ours).
    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return WRONG_LENGTH
        if (apdu[0] != 0x00.toByte()) return NOT_SUPPORTED
        return when (apdu[1]) {
            0xA4.toByte() -> handleSelect(apdu)
            0xB0.toByte() -> handleReadBinary(apdu)
            // UPDATE BINARY: this tag is read only.
            0xD6.toByte() -> SECURITY
            else -> NOT_SUPPORTED
        }
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), onDeactivated.
    // Changed: also forgets that the application was selected, and the read report.
    fun onDeactivated() {
        selectedFile = SelectedFile.NONE
        appSelected = false
        ndefReported = false
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), handleSelect.
    // Changed: a file is selectable only after the NDEF application; selecting the NDEF file re-arms onNdefRead.
    private fun handleSelect(apdu: ByteArray): ByteArray {
        if (isSelectNdefTagApplication(apdu)) {
            selectedFile = SelectedFile.NONE
            appSelected = true
            refreshNdefFileBytes()
            return OK
        }

        val fileId = parseSelectedFileId(apdu) ?: return NOT_FOUND
        if (!appSelected) return NOT_FOUND
        return when {
            fileId.contentEquals(CC_ID) -> {
                selectedFile = SelectedFile.CC
                OK
            }
            fileId.contentEquals(NDEF_ID) -> {
                selectedFile = SelectedFile.NDEF
                ndefReported = false
                OK
            }
            else -> NOT_FOUND
        }
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), handleReadBinary.
    // Changed: the length is capped at MAX_READ (the CC's MLe); onNdefRead is called once when the NDEF
    // file is read up to its last byte (never for the empty file).
    private fun handleReadBinary(apdu: ByteArray): ByteArray {
        val length = parseReadLength(apdu) ?: return UNSUPPORTED_FUNCTION
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)

        val fileData = when (selectedFile) {
            SelectedFile.CC -> buildCapabilityContainer()
            SelectedFile.NDEF -> ndefFileBytes
            SelectedFile.NONE -> return NOT_FOUND
        }

        if (offset >= fileData.size) return NOT_FOUND

        val end = minOf(offset + minOf(length, MAX_READ), fileData.size)
        val answer = fileData.copyOfRange(offset, end) + OK
        if (selectedFile == SelectedFile.NDEF && end == fileData.size && fileData.size > 2 && !ndefReported) {
            ndefReported = true
            onNdefRead()
        }
        return answer
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), refreshNdefFileBytes. Unchanged.
    private fun refreshNdefFileBytes() {
        val ndefBytes = ndefMessageProvider()
        ndefFileBytes = if (ndefBytes != null) {
            val len = ndefBytes.size
            byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()) + ndefBytes
        } else {
            byteArrayOf(0x00, 0x00)
        }
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), parseSelectedFileId. Unchanged.
    private fun parseSelectedFileId(apdu: ByteArray): ByteArray? {
        if (apdu.size < 7 || apdu[2] != 0x00.toByte() || apdu[3] != 0x0C.toByte()) return null

        val lc = apdu[4].toInt() and 0xFF
        if (lc != 0x02) return null

        val dataEnd = 5 + lc
        if (!hasValidTrailingLe(apdu, dataEnd)) return null

        return byteArrayOf(apdu[5], apdu[6])
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), parseReadLength (short and extended Le). Unchanged.
    private fun parseReadLength(apdu: ByteArray): Int? {
        if (apdu.size == 5) {
            val length = apdu[4].toInt() and 0xFF
            return if (length == 0) 256 else length
        }

        if (apdu.size == 7 && apdu[4] == 0x00.toByte()) {
            val length = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            return if (length == 0) 65536 else length
        }

        return null
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), isSelectNdefTagApplication. Unchanged.
    private fun isSelectNdefTagApplication(apdu: ByteArray): Boolean {
        if (apdu.size < 5 || apdu[1] != 0xA4.toByte() || apdu[2] != 0x04.toByte()) return false

        val lc = apdu[4].toInt() and 0xFF
        if (lc != AID.size) return false

        val dataEnd = 5 + lc
        if (apdu.size < dataEnd || !hasValidTrailingLe(apdu, dataEnd)) return false

        return apdu.copyOfRange(5, dataEnd).contentEquals(AID)
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), hasValidTrailingLe. Unchanged.
    private fun hasValidTrailingLe(apdu: ByteArray, dataEnd: Int): Boolean {
        val trailingBytes = apdu.size - dataEnd
        return when (trailingBytes) {
            0, 1 -> true
            3 -> apdu[dataEnd] == 0x00.toByte()
            else -> false
        }
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), buildCapabilityContainer
    // (the file size from the message, at least 512). Changed: MLe is MAX_READ and MLc 0x34 (ours), the size stays under the spec's largest value, 0xFFFE.
    private fun buildCapabilityContainer(): ByteArray {
        val ndefLen = ndefFileBytes.size
        val maxNdefSize = minOf(maxOf(ndefLen, 512), 0xFFFE)

        return byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, MAX_READ.toByte(),
            0x00, 0x34,
            0x04,
            0x06,
            0xE1.toByte(), 0x04,
            (maxNdefSize shr 8).toByte(),
            (maxNdefSize and 0xFF).toByte(),
            0x00,
            0xFF.toByte()
        )
    }

    // From https://github.com/aeonBTC/IbisWallet/blob/main/app/src/main/java/github/aeonbtc/ibiswallet/nfc/Type4NdefApduEmulator.kt (MIT), its companion object (identifiers, status words).
    // Changed: public status words, MAX_READ, and ours: 6700, 6D00, 6982.
    companion object {
        val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)
        const val MAX_READ = 0xFB
        val OK = byteArrayOf(0x90.toByte(), 0x00)
        val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val UNSUPPORTED_FUNCTION = byteArrayOf(0x6A, 0x81.toByte())
        val NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SECURITY = byteArrayOf(0x69, 0x82.toByte())
    }
}

/** An NDEF message of one MIME record (a contact card). */
object Ndef {

    // From https://github.com/sameerasw/MeDrop/blob/main/app/src/main/java/com/sameerasw/medrop/services/MeDropHceService.kt (MIT), ndefWrap:
    // NdefMessage(NdefRecord.createMime(...)).toByteArray(). Changed: one MIME record only, and no length
    // in front (Type4Tag adds it).
    fun mime(type: String, payload: ByteArray): ByteArray =
        NdefMessage(NdefRecord.createMime(type, payload)).toByteArray()
}
