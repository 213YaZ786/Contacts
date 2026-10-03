package com.yaz.contacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yaz.contacts.data.contacts.Offers

/**
 * Debug builds only: hands over a card as the messaging app would.
 * adb shell am broadcast -n com.yaz.contacts.debug/com.yaz.contacts.DebugOfferReceiver
 *   --es number 0698765432 --es card "BEGIN:VCARD..."   (--es verified true|false)
 */
class DebugOfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        intent.getStringExtra("card")?.let { Offers.get(context).offer(number, it.replace("\\n", "\r\n"), null) }
        intent.getStringExtra("verified")?.let { Offers.get(context).setVerified(number, it.toBoolean()) }
    }
}
