package com.yaz.contacts.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.yaz.contacts.core.vcard.VCard

/**
 * The door the messaging app uses to hand over what the user's contacts
 * share through its encrypted chat. Only apps signed with the same key
 * (the user's own SMS and Dialer) may open it: the permission on it is a
 * signature one. Nothing that comes in is written to the contacts; it
 * waits as an offer for the user's tap.
 *
 * call("offer", null, {number, card (vCard text), photo (JPEG bytes)?})
 * call("verified", null, {number, verified: Boolean})  (absent: forget)
 * Each returns {ok: Boolean}; ok false when the phone is locked (the keys
 * that seal the offers wait for an unlock) or the data is refused.
 */
class CardsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return result(false)
        // Android does not check a provider's permission on call(): checked here,
        // so only an app signed with the same key gets past this line.
        if (context.checkCallingPermission(context.packageName + ".permission.CARDS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Not allowed")
        }
        val number = extras?.getString(NUMBER)?.trim().orEmpty()
        val digits = number.count { it.isDigit() }
        if (digits !in 3..20 || number.length > 32 || number.any { !(it.isDigit() || it in "+-() ") }) return result(false)
        val offers = Offers.get(context)
        return when (method) {
            OFFER -> {
                val card = extras?.getString(CARD).orEmpty()
                if (card.length !in 1..MAX_CARD) return result(false)
                // Only a real contact card, and one only.
                if (VCard.parse(card, max = 2).size != 1) return result(false)
                val photo = extras?.getByteArray(PHOTO)?.takeIf { it.size in 1..MAX_PHOTO }
                result(runCatching { offers.offer(number, card, photo?.let { Base64.encodeToString(it, Base64.NO_WRAP) }) }.isSuccess)
            }
            VERIFIED -> {
                val verified = if (extras?.containsKey(IS_VERIFIED) == true) extras.getBoolean(IS_VERIFIED) else null
                result(runCatching { offers.setVerified(number, verified) }.isSuccess)
            }
            else -> result(false)
        }
    }

    private fun result(ok: Boolean) = Bundle().apply { putBoolean("ok", ok) }

    // Not a table: nothing can be read back through it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val OFFER = "offer"
        const val VERIFIED = "verified"
        const val NUMBER = "number"
        const val CARD = "card"
        const val PHOTO = "photo"
        const val IS_VERIFIED = "verified"
        private const val MAX_CARD = 64 * 1024
        private const val MAX_PHOTO = 400 * 1024
    }
}
