package com.yaz.contacts.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.yaz.contacts.core.contacts.LookCodec

/**
 * Dialer and SMS ask here for the name of a number kept private, to show
 * it on a call or a conversation without it ever being in Android's
 * contacts. Only apps signed with the same key get past the check in
 * call(); one number at a time, nothing can be listed or read in bulk.
 * Nothing while the phone is locked: the private people wait for an unlock.
 *
 * call("lookup", null, {number}) → {name, look (JSON v1), photo (JPEG ≤ 200 KB)} or empty.
 */
class PrivateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle()
        if (context.checkCallingPermission(context.packageName + ".permission.PRIVATE") != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Not allowed")
        }
        if (method != "lookup") return Bundle()
        val number = extras?.getString("number")?.takeIf { it.length <= 32 && it.count(Char::isDigit) in 6..20 } ?: return Bundle()
        val person = runCatching { PrivateBook.get(context).lookup(number) }.getOrNull() ?: return Bundle()
        return Bundle().apply {
            putString("name", person.details.display.ifBlank { person.details.name.display() })
            if (!person.details.look.isDefault) putString("look", LookCodec.encode(person.details.look.copy(every = 0)))
            person.photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.takeIf { it.size <= 200 * 1024 }?.let { putByteArray("photo", it) }
        }
    }

    // Not a table: nothing can be read or listed through it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
