package com.yaz.contacts.core.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Answers a phone that touches this one, as an NFC tag holding the user's
 * card. Bound only by Android's NFC service (BIND_NFC_SERVICE), only with
 * the phone unlocked, and silent unless the user has the sharing screen
 * open: [Sharing] holds the card only then.
 */
class CardTagService : HostApduService() {

    // The card is asked of Sharing at each selection of the application, and "sent" is told when the
    // reader has read the NDEF file to its last byte.
    private val tag = Type4Tag(ndefMessageProvider = { Sharing.message() }, onNdefRead = { Sharing.read() })

    // From https://github.com/sameerasw/MeDrop/blob/main/app/src/main/java/com/sameerasw/medrop/services/MeDropHceService.kt (MIT),
    // processCommandApdu: the commands go to the tag, and the read of the NDEF file is what is noted.
    // Changed: silent (file not found) unless the sharing screen is open.
    override fun processCommandApdu(command: ByteArray, extras: Bundle?): ByteArray {
        if (Sharing.message() == null) return Type4Tag.NOT_FOUND
        return tag.process(command)
    }

    // From https://github.com/sameerasw/MeDrop/blob/main/app/src/main/java/com/sameerasw/medrop/services/MeDropHceService.kt (MIT),
    // onDeactivated: only the selection is forgotten. Changed: nothing is announced here, a link lost
    // without a read is not a card sent.
    override fun onDeactivated(reason: Int) {
        tag.onDeactivated()
    }
}

/** The card offered by touch while the sharing screen is open, and the reads it had. */
object Sharing {
    @Volatile private var message: ByteArray? = null
    @Volatile private var until = 0L
    @Volatile var reads = 0
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun start(ndef: ByteArray) {
        message = ndef
        until = System.currentTimeMillis() + LIMIT
    }

    fun stop() {
        message = null
        until = 0L
    }

    /** The card while sharing is on, and for at most [LIMIT] after it started. */
    fun message(): ByteArray? = message?.takeIf { System.currentTimeMillis() < until }

    @Synchronized
    fun onRead(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }

    @Synchronized
    internal fun read() {
        if (message() == null) return
        reads++
        listeners.toList().forEach { it() }
    }

    /** Sharing stops by itself after five minutes, even if the screen is left open. */
    private const val LIMIT = 5 * 60 * 1000L
}
