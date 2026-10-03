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

    private var tag: Type4Tag? = null

    override fun processCommandApdu(command: ByteArray, extras: Bundle?): ByteArray {
        val message = Sharing.message() ?: return Type4Tag.NOT_FOUND
        // A new reader starts from the application's selection.
        if (tag == null || command.size > 1 && command[1] == 0xA4.toByte() && command.getOrNull(2) == 0x04.toByte()) tag = Type4Tag(message)
        val answer = tag!!.process(command)
        return answer
    }

    override fun onDeactivated(reason: Int) {
        tag = null
        Sharing.read()
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
