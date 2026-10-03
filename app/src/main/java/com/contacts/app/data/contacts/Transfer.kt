package com.contacts.app.data.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.SimPhonebookContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contacts in and out of the phone: a file of cards written by Android's
 * own vCard writer, and the contacts kept on the SIM card.
 */
object Transfer {

    /**
     * Writes every contact of [lookups] into [target] (a file the user
     * chose), one card each, photos included. The count written.
     */
    suspend fun export(context: Context, lookups: List<String>, target: Uri, progress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        var count = 0
        runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                lookups.forEach { key ->
                    val card = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key)
                    val ok = runCatching {
                        context.contentResolver.openAssetFileDescriptor(card, "r")?.use { fd -> fd.createInputStream().use { it.copyTo(out) } } != null
                    }.getOrDefault(false)
                    if (ok) {
                        count++
                        progress(count)
                    }
                }
            }
        }
        count
    }

    /**
     * The contacts on each SIM card as cards to import: names and numbers,
     * what a SIM holds.
     */
    suspend fun simCards(context: Context): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val subs = runCatching {
            resolver.query(
                SimPhonebookContract.ElementaryFiles.CONTENT_URI,
                arrayOf(SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID, SimPhonebookContract.ElementaryFiles.EF_TYPE),
                null, null, null
            )?.use { c ->
                buildList { while (c.moveToNext()) if (c.getInt(1) == SimPhonebookContract.ElementaryFiles.EF_ADN) add(c.getInt(0)) }
            }
        }.getOrNull().orEmpty()
        buildString {
            subs.forEach { sub ->
                runCatching {
                    resolver.query(
                        SimPhonebookContract.SimRecords.getContentUri(sub, SimPhonebookContract.ElementaryFiles.EF_ADN),
                        arrayOf(SimPhonebookContract.SimRecords.NAME, SimPhonebookContract.SimRecords.PHONE_NUMBER),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(0).orEmpty().replace("\n", " ").replace(";", " ")
                            val number = c.getString(1).orEmpty().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                            if (name.isBlank() && number.isBlank()) continue
                            append("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nN:;$name;;;\r\n")
                            if (number.isNotBlank()) append("TEL;TYPE=CELL:$number\r\n")
                            append("END:VCARD\r\n")
                        }
                    }
                }
            }
        }
    }
}
