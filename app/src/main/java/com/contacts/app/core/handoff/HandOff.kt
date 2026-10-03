package com.contacts.app.core.handoff

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization as Org
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Intents.Insert
import com.contacts.app.core.contacts.Address
import com.contacts.app.core.contacts.Details
import com.contacts.app.core.contacts.Labelled
import com.contacts.app.core.contacts.Name
import com.contacts.app.core.contacts.Organization

/** What another app asked of the contacts app. */
sealed interface HandOff {
    /** A contact's page. */
    data class Show(val uri: Uri) : HandOff
    /** A contact's editor. */
    data class Edit(val uri: Uri, val prefill: Details) : HandOff
    /** A new contact, filled with what the app gave. */
    data class Insert(val prefill: Details, val account: String?) : HandOff
    /** Add what the app gave to a contact, new or chosen. */
    data class InsertOrEdit(val prefill: Details) : HandOff
    /** Pick a contact, a number, an address or a postal address; only that row goes back. */
    data class Pick(val kind: PickKind) : HandOff
    /** A contact card to read and save. */
    data class Import(val uri: Uri) : HandOff
    /** The contact with this number or address, or a new one with it. */
    data class ShowOrCreate(val scheme: String, val value: String, val name: String?) : HandOff
}

enum class PickKind { CONTACT, PHONE, EMAIL, POSTAL }

/**
 * Reads another app's intent. Everything in it is taken as a suggestion:
 * trimmed, bounded and only ever shown or put in the editor, so a hostile
 * intent can neither save, delete nor send anything, nor swamp the app.
 */
object HandOffs {

    fun of(intent: Intent?): HandOff? {
        intent ?: return null
        val type = intent.type
        return when (intent.action) {
            Intent.ACTION_VIEW, ContactsContract.QuickContact.ACTION_QUICK_CONTACT -> {
                val data = intent.data ?: return null
                when {
                    type?.startsWith("text/") == true || data.scheme == "file" || isCard(intent.type) -> Import(data)
                    data.scheme == "content" -> HandOff.Show(data)
                    else -> null
                }
            }
            Intent.ACTION_EDIT -> intent.data?.takeIf { it.scheme == "content" }?.let { HandOff.Edit(it, prefill(intent)) }
            Intent.ACTION_INSERT -> HandOff.Insert(prefill(intent), accountOf(intent))
            Intent.ACTION_INSERT_OR_EDIT -> HandOff.InsertOrEdit(prefill(intent))
            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> HandOff.Pick(pickKind(intent.type ?: intent.data?.toString().orEmpty()))
            SHOW_OR_CREATE -> {
                val data = intent.data ?: return null
                val scheme = data.scheme ?: return null
                if (scheme != "tel" && scheme != "mailto") return null
                val value = clip(data.schemeSpecificPart.orEmpty().substringBefore('?'), 200)
                if (value.isBlank()) return null
                HandOff.ShowOrCreate(scheme, value, intent.getStringExtra(EXTRA_CREATE_DESCRIPTION)?.let { clip(it, 200) })
            }
            else -> null
        }
    }

    @Suppress("FunctionName")
    private fun Import(uri: Uri) = HandOff.Import(uri)

    private fun isCard(type: String?) = type == "text/vcard" || type == "text/x-vcard" || type == "text/directory"

    private fun pickKind(type: String): PickKind = when {
        type.contains("phone") -> PickKind.PHONE
        type.contains("email") -> PickKind.EMAIL
        type.contains("postal") -> PickKind.POSTAL
        else -> PickKind.CONTACT
    }

    private fun accountOf(intent: Intent): String? {
        val account = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Insert.EXTRA_ACCOUNT, android.accounts.Account::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Insert.EXTRA_ACCOUNT)
        return account?.let { "${it.type}/${it.name}" }
    }

    /** What the asking app wants in the contact, in Android's documented extras. */
    fun prefill(intent: Intent): Details {
        val extras = runCatching { intent.extras }.getOrNull() ?: return Details()
        fun text(key: String, max: Int = FIELD) = runCatching { extras.getCharSequence(key)?.toString() }.getOrNull()?.let { clip(it, max) }?.takeIf { it.isNotBlank() }
        fun kind(key: String, fallback: Int): Pair<Int, String?> = when (val v = runCatching { extras.get(key) }.getOrNull()) {
            is Int -> v to null
            is CharSequence -> 0 to clip(v.toString(), 60)
            else -> fallback to null
        }
        var details = Details()
        text(Insert.NAME)?.let { details = details.copy(name = split(it)) }
        text(Insert.PHONETIC_NAME)?.let { details = details.copy(name = details.name.copy(phoneticGiven = it)) }
        val company = text(Insert.COMPANY)
        val title = text(Insert.JOB_TITLE)
        if (company != null || title != null) details = details.copy(organization = Organization(company = company.orEmpty(), title = title.orEmpty()))
        text(Insert.NOTES, 5000)?.let { details = details.copy(note = Labelled(kind = 0, value = it)) }

        val phones = mutableListOf<Labelled>()
        listOf(Triple(Insert.PHONE, Insert.PHONE_TYPE, true), Triple(Insert.SECONDARY_PHONE, Insert.SECONDARY_PHONE_TYPE, false), Triple(Insert.TERTIARY_PHONE, Insert.TERTIARY_PHONE_TYPE, false))
            .forEach { (key, typeKey, first) ->
                text(key, 80)?.let { number ->
                    val (k, custom) = kind(typeKey, Phone.TYPE_MOBILE)
                    phones += Labelled(kind = k, custom = custom, value = number, primary = first && extras.getBoolean(Insert.PHONE_ISPRIMARY))
                }
            }
        val emails = mutableListOf<Labelled>()
        listOf(Insert.EMAIL to Insert.EMAIL_TYPE, Insert.SECONDARY_EMAIL to Insert.SECONDARY_EMAIL_TYPE, Insert.TERTIARY_EMAIL to Insert.TERTIARY_EMAIL_TYPE)
            .forEach { (key, typeKey) ->
                text(key, 200)?.let { address ->
                    val (k, custom) = kind(typeKey, Email.TYPE_HOME)
                    emails += Labelled(kind = k, custom = custom, value = address)
                }
            }
        val addresses = mutableListOf<Address>()
        text(Insert.POSTAL, 500)?.let { postal ->
            val (k, custom) = kind(Insert.POSTAL_TYPE, StructuredPostal.TYPE_HOME)
            addresses += Address(kind = k, custom = custom, street = postal)
        }
        val messengers = mutableListOf<Labelled>()
        text(Insert.IM_HANDLE, 200)?.let { handle ->
            messengers += Labelled(kind = Im.PROTOCOL_CUSTOM, custom = text(Insert.IM_PROTOCOL, 60), value = handle)
        }
        val websites = mutableListOf<Labelled>()
        val events = mutableListOf<Labelled>()
        val relations = mutableListOf<Labelled>()
        val sips = mutableListOf<Labelled>()

        // Rows of any kind, as ContentValues (Android's richer way).
        val rows: List<ContentValues> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) extras.getParcelableArrayList(Insert.DATA, ContentValues::class.java)
            else @Suppress("DEPRECATION") extras.getParcelableArrayList<ContentValues>(Insert.DATA)
        }.getOrNull().orEmpty().take(ROWS)
        rows.forEach { v ->
            fun s(column: String, max: Int = FIELD) = v.getAsString(column)?.let { clip(it, max) }.orEmpty()
            fun k(fallback: Int) = runCatching { v.getAsInteger("data2") }.getOrNull() ?: fallback
            val label = v.getAsString("data3")?.let { clip(it, 60) }
            when (v.getAsString(ContactsContract.Data.MIMETYPE)) {
                StructuredName.CONTENT_ITEM_TYPE -> details = details.copy(
                    name = Name(
                        prefix = s(StructuredName.PREFIX), given = s(StructuredName.GIVEN_NAME), middle = s(StructuredName.MIDDLE_NAME),
                        family = s(StructuredName.FAMILY_NAME), suffix = s(StructuredName.SUFFIX),
                        phoneticGiven = s(StructuredName.PHONETIC_GIVEN_NAME), phoneticMiddle = s(StructuredName.PHONETIC_MIDDLE_NAME),
                        phoneticFamily = s(StructuredName.PHONETIC_FAMILY_NAME)
                    ).let { n -> if (n.isEmpty) split(s(StructuredName.DISPLAY_NAME)) else n }
                )
                Phone.CONTENT_ITEM_TYPE -> s(Phone.NUMBER, 80).takeIf { it.isNotBlank() }?.let { phones += Labelled(kind = k(Phone.TYPE_MOBILE), custom = label, value = it) }
                Email.CONTENT_ITEM_TYPE -> s(Email.ADDRESS, 200).takeIf { it.isNotBlank() }?.let { emails += Labelled(kind = k(Email.TYPE_HOME), custom = label, value = it) }
                Website.CONTENT_ITEM_TYPE -> s(Website.URL, 500).takeIf { it.isNotBlank() }?.let { websites += Labelled(kind = k(Website.TYPE_HOMEPAGE), custom = label, value = it) }
                Event.CONTENT_ITEM_TYPE -> s(Event.START_DATE, 20).takeIf { it.isNotBlank() }?.let { events += Labelled(kind = k(Event.TYPE_BIRTHDAY), custom = label, value = it) }
                Relation.CONTENT_ITEM_TYPE -> s(Relation.NAME, 200).takeIf { it.isNotBlank() }?.let { relations += Labelled(kind = k(Relation.TYPE_CUSTOM), custom = label, value = it) }
                SipAddress.CONTENT_ITEM_TYPE -> s(SipAddress.SIP_ADDRESS, 200).takeIf { it.isNotBlank() }?.let { sips += Labelled(kind = k(SipAddress.TYPE_HOME), custom = label, value = it) }
                Im.CONTENT_ITEM_TYPE -> s(Im.DATA, 200).takeIf { it.isNotBlank() }?.let { messengers += Labelled(kind = Im.PROTOCOL_CUSTOM, custom = v.getAsString(Im.CUSTOM_PROTOCOL)?.let { p -> clip(p, 60) }, value = it) }
                Nickname.CONTENT_ITEM_TYPE -> s(Nickname.NAME).takeIf { it.isNotBlank() }?.let { details = details.copy(nickname = Labelled(kind = Nickname.TYPE_DEFAULT, value = it)) }
                Note.CONTENT_ITEM_TYPE -> s(Note.NOTE, 5000).takeIf { it.isNotBlank() }?.let { details = details.copy(note = Labelled(kind = 0, value = it)) }
                Org.CONTENT_ITEM_TYPE -> details = details.copy(organization = Organization(company = s(Org.COMPANY), title = s(Org.TITLE), department = s(Org.DEPARTMENT)))
                StructuredPostal.CONTENT_ITEM_TYPE -> Address(
                    kind = k(StructuredPostal.TYPE_HOME), custom = label,
                    street = s(StructuredPostal.STREET).ifBlank { s(StructuredPostal.FORMATTED_ADDRESS, 500) },
                    pobox = s(StructuredPostal.POBOX), neighborhood = s(StructuredPostal.NEIGHBORHOOD),
                    city = s(StructuredPostal.CITY), region = s(StructuredPostal.REGION),
                    postcode = s(StructuredPostal.POSTCODE), country = s(StructuredPostal.COUNTRY)
                ).takeIf { !it.isEmpty }?.let { addresses += it }
            }
        }
        return details.copy(
            phones = phones.take(ROWS), emails = emails.take(ROWS), addresses = addresses.take(ROWS),
            websites = websites.take(ROWS), events = events.take(ROWS), relations = relations.take(ROWS),
            messengers = messengers.take(ROWS), sips = sips.take(ROWS)
        )
    }

    /** "Joëlle Martin" in parts: the last word the family name, the rest given names. */
    fun split(full: String): Name {
        val words = full.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (words.size) {
            0 -> Name()
            1 -> Name(given = words[0])
            else -> Name(given = words.dropLast(1).joinToString(" "), family = words.last())
        }
    }

    /** At most [max] characters, without control characters a field has no use for. */
    fun clip(text: String, max: Int): String =
        text.filter { it == '\n' || !it.isISOControl() }.trim().take(max)

    const val SHOW_OR_CREATE = "com.android.contacts.action.SHOW_OR_CREATE_CONTACT"
    const val EXTRA_CREATE_DESCRIPTION = "com.android.contacts.action.CREATE_DESCRIPTION"
    private const val FIELD = 300
    private const val ROWS = 30
}
