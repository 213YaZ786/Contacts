package com.yaz.contacts.data.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
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
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Address
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.core.contacts.LookCodec
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every change the app makes to Android's contacts, each in one batch so a
 * contact is never left half saved. Only rows the user changed are touched,
 * so what a sync or another app keeps in a contact stays as it is.
 */
class ContactWriter(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** A contact saved: its id and the lookup URI apps keep to find it again. */
    data class Saved(val contactId: Long, val uri: Uri)

    /**
     * Saves [edited]: a new contact in [account] when [original] is null,
     * else only what differs from [original]. [photo] replaces the photo,
     * [removePhoto] takes it away.
     */
    suspend fun save(original: Details?, edited: Details, account: Account?, photo: Bitmap? = null, removePhoto: Boolean = false, me: Boolean = false): Saved? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ops = ArrayList<ContentProviderOperation>()
                val isNew = original == null || original.mainRaw == 0L
                // A new contact's rows point at the raw contact made by the first operation.
                val target: Target = if (isNew) {
                    // The user's own card goes to Android's profile, kept on the phone.
                    ops += ContentProviderOperation.newInsert(if (me) ContactsContract.Profile.CONTENT_RAW_CONTACTS_URI else RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_TYPE, if (me) null else account?.type)
                        .withValue(RawContacts.ACCOUNT_NAME, if (me) null else account?.name)
                        .withValue(RawContacts.STARRED, if (edited.starred) 1 else 0)
                        .withValue(RawContacts.SEND_TO_VOICEMAIL, if (edited.toVoicemail) 1 else 0)
                        .withValue(RawContacts.CUSTOM_RINGTONE, edited.ringtone)
                        .build()
                    Target.Back(0)
                } else {
                    Target.Raw(original!!.mainRaw)
                }
                val before = original ?: Details()
                locked = before.readOnlyRaws

                // The name, in its parts; Android writes the display name from them.
                with(edited.name) {
                    val values = ContentValues().apply {
                        put(StructuredName.PREFIX, prefix.trim())
                        put(StructuredName.GIVEN_NAME, given.trim())
                        put(StructuredName.MIDDLE_NAME, middle.trim())
                        put(StructuredName.FAMILY_NAME, family.trim())
                        put(StructuredName.SUFFIX, suffix.trim())
                        put(StructuredName.PHONETIC_GIVEN_NAME, phoneticGiven.trim())
                        put(StructuredName.PHONETIC_MIDDLE_NAME, phoneticMiddle.trim())
                        put(StructuredName.PHONETIC_FAMILY_NAME, phoneticFamily.trim())
                        // The parts decide: an old display name would win over them.
                        putNull(StructuredName.DISPLAY_NAME)
                    }
                    single(ops, target, StructuredName.CONTENT_ITEM_TYPE, before.name.rowId, if (isEmpty && !hasPhonetic) null else values, changed = before.name != edited.name)
                }
                with(edited.organization) {
                    val values = ContentValues().apply {
                        put(Org.COMPANY, company.trim())
                        put(Org.TITLE, title.trim())
                        put(Org.DEPARTMENT, department.trim())
                        put(Org.TYPE, Org.TYPE_WORK)
                    }
                    single(ops, target, Org.CONTENT_ITEM_TYPE, before.organization.rowId, if (isEmpty) null else values, changed = before.organization != edited.organization)
                }
                single(
                    ops, target, Nickname.CONTENT_ITEM_TYPE, before.nickname?.rowId ?: 0L,
                    edited.nickname?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { ContentValues().apply { put(Nickname.NAME, it); put(Nickname.TYPE, Nickname.TYPE_DEFAULT) } },
                    changed = before.nickname?.value != edited.nickname?.value
                )
                single(
                    ops, target, Note.CONTENT_ITEM_TYPE, before.note?.rowId ?: 0L,
                    edited.note?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { ContentValues().apply { put(Note.NOTE, it) } },
                    changed = before.note?.value != edited.note?.value
                )
                list(ops, target, Phone.CONTENT_ITEM_TYPE, before.phones, edited.phones, Phone.NUMBER)
                list(ops, target, Email.CONTENT_ITEM_TYPE, before.emails, edited.emails, Email.ADDRESS)
                list(ops, target, Website.CONTENT_ITEM_TYPE, before.websites, edited.websites, Website.URL)
                list(ops, target, Event.CONTENT_ITEM_TYPE, before.events, edited.events, Event.START_DATE)
                list(ops, target, Relation.CONTENT_ITEM_TYPE, before.relations, edited.relations, Relation.NAME)
                list(ops, target, SipAddress.CONTENT_ITEM_TYPE, before.sips, edited.sips, SipAddress.SIP_ADDRESS)
                messengers(ops, target, before.messengers, edited.messengers)
                addresses(ops, target, before.addresses, edited.addresses)

                // Labels: memberships added and taken away.
                (edited.groups - before.groups).forEach { group ->
                    ops += insert(target, GroupMembership.CONTENT_ITEM_TYPE, ContentValues().apply { put(GroupMembership.GROUP_ROW_ID, group) })
                }
                (before.groups - edited.groups).forEach { group ->
                    before.groupRows[group]?.let { ops += deleteRow(it) }
                }

                // The person's look, in this app's own row.
                if (edited.look != before.look) {
                    single(
                        ops, target, LookCodec.MIMETYPE, before.lookRow,
                        if (edited.look.isDefault) null else ContentValues().apply { put(Data.DATA1, LookCodec.encode(edited.look)) },
                        changed = true
                    )
                }

                val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                val rawId = if (isNew) ContentUris.parseId(results[0].uri!!) else original!!.mainRaw

                // Options of the whole contact, and the photo, once its rows exist.
                val contactId = contactOf(rawId) ?: return@runCatching null
                if (!isNew) {
                    if (before.starred != edited.starred || before.ringtone != edited.ringtone || before.toVoicemail != edited.toVoicemail) {
                        options(contactId, edited.starred, edited.ringtone, edited.toVoicemail)
                    }
                }
                when {
                    photo != null -> writePhoto(rawId, photo)
                    removePhoto && original != null -> removePhoto(original)
                }
                val uri = if (ContactsContract.isProfileId(contactId)) ContactsContract.Profile.CONTENT_URI
                else ContactsContract.Contacts.getLookupUri(resolver, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))
                Saved(contactId, uri)
            }.getOrNull()
        }

    /** Raw contacts of the contact being saved that their account does not let change. */
    @Volatile private var locked: Set<Long> = emptySet()

    private sealed interface Target {
        data class Back(val index: Int) : Target
        data class Raw(val id: Long) : Target
    }

    private fun insert(target: Target, mimetype: String, values: ContentValues): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mimetype).withValues(values)
        return when (target) {
            is Target.Back -> builder.withValueBackReference(Data.RAW_CONTACT_ID, target.index)
            is Target.Raw -> builder.withValue(Data.RAW_CONTACT_ID, target.id)
        }.build()
    }

    private fun update(row: Long, values: ContentValues) =
        ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, row)).withValues(values).build()

    private fun deleteRow(row: Long) = ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, row)).build()

    /** A kind with at most one row: added, changed, or taken away when [values] is null. */
    private fun single(ops: MutableList<ContentProviderOperation>, target: Target, mimetype: String, row: Long, values: ContentValues?, changed: Boolean) {
        if (!changed) return
        when {
            values == null && row != 0L -> ops += deleteRow(row)
            values == null -> Unit
            row != 0L -> ops += update(row, values)
            else -> ops += insert(target, mimetype, values)
        }
    }

    /** A kind with a label and a value, many rows: each compared by its row. */
    private fun list(ops: MutableList<ContentProviderOperation>, target: Target, mimetype: String, before: List<Labelled>, after: List<Labelled>, valueColumn: String) {
        val kept = after.filter { it.value.isNotBlank() && it.rawId !in locked }
        val keptRows = kept.map { it.rowId }.toSet()
        before.filter { it.rowId != 0L && it.rowId !in keptRows && it.rawId !in locked }.forEach { ops += deleteRow(it.rowId) }
        val old = before.associateBy { it.rowId }
        kept.forEach { item ->
            val values = ContentValues().apply {
                put(valueColumn, item.value.trim())
                put(Data.DATA2, item.kind)
                if (item.custom.isNullOrBlank()) putNull(Data.DATA3) else put(Data.DATA3, item.custom.trim())
                put(Data.IS_PRIMARY, if (item.primary) 1 else 0)
                put(Data.IS_SUPER_PRIMARY, if (item.primary) 1 else 0)
            }
            when {
                item.rowId == 0L -> ops += insert(rawTarget(target, item.rawId), mimetype, values)
                old[item.rowId] != item -> ops += update(item.rowId, values)
            }
        }
    }

    /** Messengers keep their service in the protocol column, not the label. */
    private fun messengers(ops: MutableList<ContentProviderOperation>, target: Target, before: List<Labelled>, after: List<Labelled>) {
        val kept = after.filter { it.value.isNotBlank() && it.rawId !in locked }
        val keptRows = kept.map { it.rowId }.toSet()
        before.filter { it.rowId != 0L && it.rowId !in keptRows && it.rawId !in locked }.forEach { ops += deleteRow(it.rowId) }
        val old = before.associateBy { it.rowId }
        kept.forEach { item ->
            val values = ContentValues().apply {
                put(Im.DATA, item.value.trim())
                put(Im.TYPE, Im.TYPE_OTHER)
                put(Im.PROTOCOL, Im.PROTOCOL_CUSTOM)
                put(Im.CUSTOM_PROTOCOL, item.custom?.trim().orEmpty())
            }
            when {
                item.rowId == 0L -> ops += insert(rawTarget(target, item.rawId), Im.CONTENT_ITEM_TYPE, values)
                old[item.rowId] != item -> ops += update(item.rowId, values)
            }
        }
    }

    private fun addresses(ops: MutableList<ContentProviderOperation>, target: Target, before: List<Address>, after: List<Address>) {
        val kept = after.filter { !it.isEmpty && it.rawId !in locked }
        val keptRows = kept.map { it.rowId }.toSet()
        before.filter { it.rowId != 0L && it.rowId !in keptRows && it.rawId !in locked }.forEach { ops += deleteRow(it.rowId) }
        val old = before.associateBy { it.rowId }
        kept.forEach { a ->
            val values = ContentValues().apply {
                put(StructuredPostal.TYPE, a.kind)
                if (a.custom.isNullOrBlank()) putNull(StructuredPostal.LABEL) else put(StructuredPostal.LABEL, a.custom.trim())
                put(StructuredPostal.STREET, a.street.trim())
                put(StructuredPostal.POBOX, a.pobox.trim())
                put(StructuredPostal.NEIGHBORHOOD, a.neighborhood.trim())
                put(StructuredPostal.CITY, a.city.trim())
                put(StructuredPostal.REGION, a.region.trim())
                put(StructuredPostal.POSTCODE, a.postcode.trim())
                put(StructuredPostal.COUNTRY, a.country.trim())
                // Android writes the formatted address again from the parts.
                putNull(StructuredPostal.FORMATTED_ADDRESS)
            }
            when {
                a.rowId == 0L -> ops += insert(rawTarget(target, a.rawId), StructuredPostal.CONTENT_ITEM_TYPE, values)
                old[a.rowId] != a -> ops += update(a.rowId, values)
            }
        }
    }

    /** A new row of a merged contact goes with its siblings' raw contact when known. */
    private fun rawTarget(target: Target, rawId: Long): Target = if (rawId != 0L && target is Target.Raw) Target.Raw(rawId) else target

    private fun contactOf(rawId: Long): Long? =
        resolver.query(ContentUris.withAppendedId(if (ContactsContract.isProfileId(rawId)) ContactsContract.Profile.CONTENT_RAW_CONTACTS_URI else RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0).takeIf { id -> id > 0 } else null }

    /** Star, ringtone and voicemail are the whole contact's: Android spreads them to its raw contacts. */
    private fun options(contactId: Long, starred: Boolean, ringtone: String?, toVoicemail: Boolean) {
        resolver.update(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtone)
                put(ContactsContract.Contacts.SEND_TO_VOICEMAIL, if (toVoicemail) 1 else 0)
            },
            null, null
        )
    }

    suspend fun star(contactId: Long, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) }, null, null
            ) > 0
        }.getOrDefault(false)
    }

    suspend fun setRingtone(contactId: Long, ringtone: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtone) }, null, null
            ) > 0
        }.getOrDefault(false)
    }

    suspend fun setVoicemail(contactId: Long, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                ContentValues().apply { put(ContactsContract.Contacts.SEND_TO_VOICEMAIL, if (on) 1 else 0) }, null, null
            ) > 0
        }.getOrDefault(false)
    }

    /**
     * The full size photo of a raw contact, written through Android's own
     * door for it so it keeps its resolution; Android makes the small one.
     */
    private fun writePhoto(rawId: Long, bitmap: Bitmap) {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
        val base = if (ContactsContract.isProfileId(rawId)) ContactsContract.Profile.CONTENT_RAW_CONTACTS_URI else RawContacts.CONTENT_URI
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(base, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        resolver.openAssetFileDescriptor(uri, "rw")?.use { fd -> fd.createOutputStream().use { it.write(bytes) } }
    }

    private fun removePhoto(details: Details) {
        details.raws.forEach { raw ->
            resolver.delete(
                Data.CONTENT_URI,
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(raw.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            )
        }
    }

    /** Deletes contacts and all their raw contacts; a sync deletes them on its server too. */
    suspend fun delete(contactIds: Collection<Long>): Int = withContext(Dispatchers.IO) {
        runCatching {
            val ops = ArrayList(contactIds.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it)).build() })
            resolver.applyBatch(ContactsContract.AUTHORITY, ops).size
        }.getOrDefault(0)
    }

    /**
     * Shows [contactIds] as one person: Android keeps each raw contact as it
     * is (each account keeps its own) and joins them, as Google Contacts'
     * merge does. Undone with [separate].
     */
    suspend fun merge(contactIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val raws = contactIds.flatMap { rawsOf(it) }.distinct()
            if (raws.size < 2) return@runCatching false
            val ops = ArrayList<ContentProviderOperation>()
            for (i in raws.indices) for (j in i + 1 until raws.size) {
                ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                    .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws[i])
                    .withValue(AggregationExceptions.RAW_CONTACT_ID2, raws[j])
                    .build()
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }

    /** Splits a merged contact back into one contact per raw contact. */
    suspend fun separate(details: Details): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val raws = details.raws
            if (raws.size < 2) return@runCatching false
            val ops = ArrayList<ContentProviderOperation>()
            for (i in raws.indices) for (j in i + 1 until raws.size) {
                ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                    .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_SEPARATE)
                    .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws[i])
                    .withValue(AggregationExceptions.RAW_CONTACT_ID2, raws[j])
                    .build()
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }

    private fun rawsOf(contactId: Long): List<Long> =
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()), null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    /** A new label in an account. */
    suspend fun createGroup(title: String, account: Account): Long? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.insert(
                Groups.CONTENT_URI,
                ContentValues().apply {
                    put(Groups.TITLE, title.trim())
                    put(Groups.ACCOUNT_TYPE, account.type)
                    put(Groups.ACCOUNT_NAME, account.name)
                    put(Groups.GROUP_VISIBLE, 1)
                }
            )?.let { ContentUris.parseId(it) }
        }.getOrNull()
    }

    suspend fun renameGroup(groupId: Long, title: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.update(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), ContentValues().apply { put(Groups.TITLE, title.trim()) }, null, null) > 0
        }.getOrDefault(false)
    }

    /** Deletes a label; the people in it stay. */
    suspend fun deleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), null, null) > 0 }.getOrDefault(false)
    }

    /** Puts people in a label, or takes them out. */
    suspend fun setInGroup(groupId: Long, contactIds: Collection<Long>, inGroup: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val ops = ArrayList<ContentProviderOperation>()
            contactIds.forEach { contact ->
                val raws = rawsOf(contact)
                if (inGroup) {
                    // The label belongs to one account: its member is that account's raw contact, else the first.
                    raws.firstOrNull()?.let { raw ->
                        ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, raw)
                            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                            .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                            .build()
                    }
                } else {
                    ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                        .withSelection(
                            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                            arrayOf(contact.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
                        ).build()
                }
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }
}
