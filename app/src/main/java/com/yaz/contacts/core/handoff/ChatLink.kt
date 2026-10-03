package com.yaz.contacts.core.handoff

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The encrypted chat of SMS, through its door for apps signed with the
 * same key: its invite, to go in the card given by touching phones, and
 * the invite of the other phone, handed back to SMS when the user saves
 * their card. The chat itself stays SMS's; this app never talks to it.
 */
object ChatLink {

    private val authorities = listOf("com.yaz.sms.invite", "com.yaz.sms.debug.invite")

    /** This phone's invite, or null when SMS or its chat is not there. */
    suspend fun invite(context: Context): String? = withContext(Dispatchers.IO) {
        call(context, "invite", null)?.getString("link")?.takeIf { valid(it) }
    }

    /** Hands their invite to SMS, which starts the encrypted chat with [number]. */
    suspend fun join(context: Context, link: String, number: String): Boolean = withContext(Dispatchers.IO) {
        if (!valid(link)) return@withContext false
        call(context, "join", Bundle().apply { putString("link", link); putString("number", number) })?.getBoolean("ok") == true
    }

    private fun call(context: Context, method: String, extras: Bundle?): Bundle? {
        for (authority in authorities) {
            val result = runCatching { context.contentResolver.call(Uri.parse("content://$authority"), method, null, extras) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    /** An invite as chatmail writes it (its OPENPGP4FPR form or its https://i.delta.chat link), nothing else. */
    fun valid(link: String): Boolean =
        link.length in 20..2000 && link.none { it.isWhitespace() || it.isISOControl() } &&
            (link.startsWith("OPENPGP4FPR:", ignoreCase = true) || link.startsWith("https://i.delta.chat/#"))
}
