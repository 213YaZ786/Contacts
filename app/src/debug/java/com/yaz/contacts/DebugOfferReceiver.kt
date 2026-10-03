package com.yaz.contacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import com.yaz.contacts.data.contacts.Offers

/**
 * Debug builds only: hands over a card as the messaging app would.
 * adb shell am broadcast -n com.yaz.contacts.debug/com.yaz.contacts.DebugOfferReceiver
 *   --es number 0698765432 --es card "BEGIN:VCARD..."   (--es verified true|false)
 */
class DebugOfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // --es tap sent : the card went to the other phone; --es tap "<vCard>" : theirs came in.
        intent.getStringExtra("tap")?.let { tap ->
            com.yaz.contacts.feature.nfc.TapDemo.play(if (tap == "sent") null else tap.replace("\\n", "\r\n"))
            return
        }
        // --es invite ask : asks SMS for this phone's invite and logs only whether it came and its shape.
        if (intent.getStringExtra("invite") == "ask") {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val link = com.yaz.contacts.core.handoff.ChatLink.invite(context)
                android.util.Log.i("ContactsDebug", "invite: " + (link?.let { "yes, ${it.length} chars, " + it.take(22) + "…" } ?: "none"))
                pending.finish()
            }
            return
        }
        val number = intent.getStringExtra("number") ?: return
        intent.getStringExtra("card")?.let { Offers.get(context).offer(number, it.replace("\\n", "\r\n"), null) }
        intent.getStringExtra("verified")?.let { Offers.get(context).setVerified(number, it.toBoolean()) }
    }
}
