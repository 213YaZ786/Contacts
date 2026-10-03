package com.yaz.contacts.core.handoff

import java.util.concurrent.atomic.AtomicLong

/**
 * What is handed from screen to screen without going through a route: a
 * contact another app asked to fill, cards read from the SIM. Held while
 * the user moves around, then let go with the process.
 */
class Drafts {
    private val next = AtomicLong(1)
    private val held = HashMap<Long, Any>()

    @Synchronized
    fun put(value: Any): Long = next.getAndIncrement().also { held[it] = value }

    @Synchronized
    fun peek(id: Long): Any? = held[id]
}
