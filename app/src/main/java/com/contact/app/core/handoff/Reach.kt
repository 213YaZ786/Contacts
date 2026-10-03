package com.contact.app.core.handoff

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.contact.app.core.dial.NumberActions

/**
 * Where a contact's buttons lead: the phone app calls, the messaging app
 * writes, mail and maps open their apps, Dialer shows the calls with a
 * number and blocks it. This app only hands off, never does their job.
 */
object Reach {

    /** Calls at once when allowed to, else the phone app with the number ready. */
    fun call(context: Context, number: String) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (allowed && open(context, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))) return
        NumberActions.dial(context, number)
    }

    fun message(context: Context, numbers: List<String>) {
        if (numbers.isEmpty()) return
        // Several numbers open their group conversation in SMS.
        open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(",") { Uri.encode(it) })))
    }

    fun email(context: Context, addresses: List<String>) {
        if (addresses.isEmpty()) return
        open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, addresses.toTypedArray()))
    }

    fun map(context: Context, address: String) {
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address.replace('\n', ' ')))))
    }

    /** A website: only http and https, a bare address taken as https. */
    fun web(context: Context, url: String) {
        val clean = url.trim()
        val uri = Uri.parse(if (clean.contains("://")) clean else "https://$clean")
        if (uri.scheme !in setOf("http", "https")) return
        open(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    fun canMessage(context: Context): Boolean = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")).resolveActivity(context.packageManager) != null
    fun canEmail(context: Context): Boolean = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).resolveActivity(context.packageManager) != null

    /** The calls with a number: Dialer's page, where it can also be blocked. */
    fun calls(context: Context, number: String) = NumberActions.showCalls(context, number)

    fun canShowCalls(context: Context): Boolean = NumberActions.canShowCalls(context)

    /**
     * Blocking is the phone app's: Dialer's page of the number, where the
     * user blocks it; without Dialer, Android's own list.
     */
    fun block(context: Context, number: String) {
        if (canShowCalls(context)) {
            calls(context, number)
            return
        }
        runCatching { context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent() }.getOrNull()?.let { open(context, it) }
    }

    /** A contact as a card (vCard) to another app, Android writing the card. */
    fun share(context: Context, lookupKeys: List<String>, title: String) {
        if (lookupKeys.isEmpty()) return
        val uri = if (lookupKeys.size == 1) Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKeys[0])
        else Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI, Uri.encode(lookupKeys.joinToString(":")))
        val send = Intent(Intent.ACTION_SEND)
            .setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        open(context, Intent.createChooser(send, title))
    }

    /** Copies a value; a number or an address is marked private so the keyboard does not show it around. */
    fun copy(context: Context, label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    fun open(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
