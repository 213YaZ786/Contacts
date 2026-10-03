package com.yaz.contacts.data.contacts

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
import androidx.core.content.ContextCompat
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Address
import com.yaz.contacts.core.contacts.Contact
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Group
import com.yaz.contacts.core.contacts.Labelled
import com.yaz.contacts.core.contacts.Look
import com.yaz.contacts.core.contacts.LookCodec
import com.yaz.contacts.core.contacts.Name
import com.yaz.contacts.core.contacts.Organization
import com.yaz.contacts.core.contacts.Other
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's contacts as the app shows them, read from Android's own
 * store and read again whenever it changes, whoever changed it (this app,
 * a sync, Dialer's star). Nothing is copied anywhere.
 */
class ContactStore(private val context: Context, private val scope: CoroutineScope) {

    private val resolver: ContentResolver get() = context.contentResolver

    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    /** Everyone, null until first read. */
    val contacts: StateFlow<List<Contact>?> = _contacts.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    /** Bumped on every change in the store, for a page to read its contact again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var watching = false
    private var pending: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // A sync writes many rows in a burst: read once it settles.
            pending?.cancel()
            pending = scope.launch {
                delay(250)
                load()
            }
        }
    }

    fun canRead(): Boolean = granted(Manifest.permission.READ_CONTACTS)
    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CONTACTS)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Loads once, and from then on follows the contacts as they change, so
     * asking again costs nothing. Does nothing without the permission.
     */
    fun refresh() {
        if (!canRead() || watching) return
        resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        watching = true
        scope.launch { load() }
    }

    private suspend fun load() {
        val (people, groups) = withContext(Dispatchers.IO) { readAll() to readGroups() }
        _contacts.value = people
        _groups.value = groups
        _changes.value++
        // The home screen's favourites follow.
        com.yaz.contacts.widget.FavoritesWidget.refresh(context)
    }

    private fun readAll(): List<Contact> = runCatching {
        val base = LinkedHashMap<Long, Contact>()
        val columns = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ContactsContract.Contacts.SORT_KEY_PRIMARY,
            ContactsContract.Contacts.SORT_KEY_ALTERNATIVE
        )
        // Android's index of each name in the phone's language: columns its
        // contacts store has always had but the SDK keeps unnamed; read when there.
        val cursor = runCatching { resolver.query(ContactsContract.Contacts.CONTENT_URI, columns + arrayOf("phonebook_label", "phonebook_label_alt"), null, null, null) }.getOrNull()
            ?: resolver.query(ContactsContract.Contacts.CONTENT_URI, columns, null, null, null)
        cursor?.use { c ->
            val labelled = c.columnCount > columns.size
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(2).orEmpty()
                base[id] = Contact(
                    id = id,
                    lookup = c.getString(1).orEmpty(),
                    name = name,
                    alternative = c.getString(3) ?: name,
                    photo = c.getString(4),
                    starred = c.getInt(5) != 0,
                    updated = c.getLong(6),
                    sortKey = c.getString(7).orEmpty(),
                    sortKeyAlt = c.getString(8).orEmpty(),
                    bucket = if (labelled) c.getString(9).orEmpty() else "",
                    bucketAlt = if (labelled) c.getString(10).orEmpty() else ""
                )
            }
        }
        // What the search and the lines need, in one pass over the data.
        val phones = HashMap<Long, MutableList<String>>()
        val emails = HashMap<Long, MutableList<String>>()
        val company = HashMap<Long, String>()
        val nickname = HashMap<Long, String>()
        val groups = HashMap<Long, MutableSet<Long>>()
        val birthday = HashMap<Long, String>()
        val looks = HashMap<Long, Look>()
        val more = HashMap<Long, StringBuilder>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2),
            "${Data.MIMETYPE} IN (?,?,?,?,?,?,?,?,?)",
            arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Org.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, GroupMembership.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, LookCodec.MIMETYPE, Note.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val value = c.getString(2) ?: continue
                when (c.getString(1)) {
                    Phone.CONTENT_ITEM_TYPE -> phones.getOrPut(id) { mutableListOf() }.let { if (value !in it) it += value }
                    Email.CONTENT_ITEM_TYPE -> emails.getOrPut(id) { mutableListOf() }.let { if (value !in it) it += value }
                    Org.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) company.putIfAbsent(id, value)
                    Nickname.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) nickname.putIfAbsent(id, value)
                    GroupMembership.CONTENT_ITEM_TYPE -> value.toLongOrNull()?.let { groups.getOrPut(id) { mutableSetOf() } += it }
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(3) == Event.TYPE_BIRTHDAY) birthday.putIfAbsent(id, value)
                    LookCodec.MIMETYPE -> looks.putIfAbsent(id, LookCodec.decode(value))
                    // Notes and addresses, only to be searched; capped so a long note stays light.
                    Note.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE -> more.getOrPut(id) { StringBuilder() }.let { if (it.length < 600) it.append(value.take(300)).append(' ') }
                }
            }
        }
        val accounts = HashMap<Long, MutableSet<String>>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED} = 0", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1)
                accounts.getOrPut(c.getLong(0)) { mutableSetOf() } += if (type == null) "" else "$type/${c.getString(2)}"
            }
        }
        base.values.map { contact ->
            val id = contact.id
            contact.copy(
                // A contact with no name at all is known by what it has.
                name = contact.name.ifBlank { phones[id]?.firstOrNull() ?: emails[id]?.firstOrNull() ?: "" },
                alternative = contact.alternative.ifBlank { contact.name.ifBlank { phones[id]?.firstOrNull() ?: emails[id]?.firstOrNull() ?: "" } },
                phones = phones[id].orEmpty(),
                emails = emails[id].orEmpty(),
                company = company[id],
                nickname = nickname[id],
                groups = groups[id].orEmpty(),
                accounts = accounts[id].orEmpty(),
                birthday = birthday[id],
                look = looks[id],
                more = more[id]?.toString().orEmpty()
            )
        }
    }.getOrNull().orEmpty()

    private fun readGroups(): List<Group> = runCatching {
        resolver.query(
            Groups.CONTENT_SUMMARY_URI,
            arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, Groups.SUMMARY_COUNT, Groups.SYSTEM_ID, Groups.AUTO_ADD),
            "${Groups.DELETED} = 0", null, null
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    // Google's built in "My contacts" and "Starred" are not labels people made.
                    if (c.getString(5) != null || c.getInt(6) != 0) continue
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val type = c.getString(2)
                    add(Group(c.getLong(0), title, if (type == null) "" else "$type/${c.getString(3)}", c.getInt(4)))
                }
            }.sortedBy { it.title.lowercase() }
        }
    }.getOrNull().orEmpty()

    /**
     * The accounts new contacts can go to: those whose app syncs contacts
     * both ways, as Android lists them to an app that reads contacts, and
     * the phone itself.
     */
    fun accounts(): List<Account> = runCatching {
        val writable = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
        val manager = AccountManager.get(context)
        val seen = writable.flatMap { type -> manager.getAccountsByType(type).toList() }
            .map { Account(it.type, it.name, it.name) }
        // Accounts Android does not list to us but whose contacts are here.
        val inStore = buildList {
            resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED} = 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: continue
                    if (type in writable) add(Account(type, c.getString(1), c.getString(1) ?: type))
                }
            }
        }
        listOf(phoneAccount()) + (seen + inStore).distinctBy { it.key }
    }.getOrDefault(listOf(phoneAccount()))

    fun phoneAccount() = Account(null, null, "This phone")

    /** Account types whose app syncs contacts both ways, so their contacts can be changed here. */
    private fun writableTypes(): Set<String> = runCatching {
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet()
    }.getOrDefault(emptySet())

    /** Everything a contact holds, by its id; null when it is gone. */
    suspend fun details(contactId: Long): Details? = withContext(Dispatchers.IO) { runCatching { readDetails(contactId) }.getOrNull() }

    /** The contact a lookup URI or an older contact URI points to, if it still exists. */
    suspend fun resolve(uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val path = uri.pathSegments
            when {
                // content://com.android.contacts/raw_contacts/N
                path.firstOrNull() == "raw_contacts" -> resolver.query(
                    RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(path.getOrNull(1).orEmpty()), null
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
                // content://contacts/people/N, the oldest form: the same id.
                uri.authority == "contacts" -> path.lastOrNull()?.toLongOrNull()
                else -> ContactsContract.Contacts.lookupContact(resolver, uri)?.lastPathSegment?.toLongOrNull()
                    ?: resolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }
        }.getOrNull()
    }

    private fun readDetails(contactId: Long): Details? {
        // The user's own card lives in Android's profile, its own corner of the store.
        val me = ContactsContract.isProfileId(contactId)
        val contactsUri = if (me) ContactsContract.Profile.CONTENT_URI else ContactsContract.Contacts.CONTENT_URI
        val rawUri = if (me) ContactsContract.Profile.CONTENT_RAW_CONTACTS_URI else RawContacts.CONTENT_URI
        val dataUri = if (me) Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, "data") else Data.CONTENT_URI
        var details = Details(id = contactId)
        resolver.query(
            contactsUri,
            arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.CUSTOM_RINGTONE,
                ContactsContract.Contacts.SEND_TO_VOICEMAIL,
                ContactsContract.Contacts.NAME_RAW_CONTACT_ID
            ),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            details = details.copy(
                lookup = c.getString(0).orEmpty(),
                display = c.getString(1).orEmpty(),
                photo = c.getString(2),
                thumbnail = c.getString(3),
                starred = c.getInt(4) != 0,
                ringtone = c.getString(5),
                toVoicemail = c.getInt(6) != 0,
                mainRaw = c.getLong(7)
            )
        } ?: return null

        // The raw contacts merged into this one, with their accounts. One
        // can be written when its account's app syncs changes back (or it is
        // on the phone itself), as Android's own contacts app decides.
        val writable = writableTypes()
        val raws = mutableListOf<Long>()
        val accounts = mutableListOf<Account>()
        val readOnlyRaws = mutableSetOf<Long>()
        var firstWritable = 0L
        resolver.query(
            rawUri,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getLong(0)
                raws += raw
                val type = c.getString(1)
                accounts += if (type == null) phoneAccount() else Account(type, c.getString(2), c.getString(2) ?: type)
                if (type == null || type in writable) {
                    if (firstWritable == 0L) firstWritable = raw
                } else readOnlyRaws += raw
            }
        }
        val readOnly = firstWritable == 0L
        // New rows go to the raw contact that gives the name, unless it cannot be written.
        val main = details.mainRaw.takeIf { it != 0L && it !in readOnlyRaws } ?: firstWritable
        details = details.copy(raws = raws, accounts = accounts.distinctBy { it.key }, readOnly = readOnly, readOnlyRaws = readOnlyRaws, mainRaw = main)

        val phones = mutableListOf<Labelled>()
        val emails = mutableListOf<Labelled>()
        val addresses = mutableListOf<Address>()
        val websites = mutableListOf<Labelled>()
        val events = mutableListOf<Labelled>()
        val relations = mutableListOf<Labelled>()
        val messengers = mutableListOf<Labelled>()
        val sips = mutableListOf<Labelled>()
        val groups = mutableMapOf<Long, Long>()
        val others = mutableListOf<Other>()
        resolver.query(
            dataUri,
            arrayOf(
                Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.IS_SUPER_PRIMARY,
                Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10
            ),
            "${Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val row = c.getLong(0)
                val raw = c.getLong(1)
                if (raw !in raws) continue
                fun s(i: Int) = c.getString(i).orEmpty()
                fun labelled(value: String) = Labelled(row, raw, c.getInt(5), c.getString(6), value, c.getInt(3) != 0)
                when (c.getString(2)) {
                    // Merged contacts each have a name: the one Android shows wins.
                    StructuredName.CONTENT_ITEM_TYPE -> if (details.name.rowId == 0L || raw == details.mainRaw) details = details.copy(
                        name = Name(row, s(7), s(5), s(8), s(6), s(9), s(10), s(11), s(12))
                    )
                    Phone.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) phones += labelled(s(4))
                    Email.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) emails += labelled(s(4))
                    StructuredPostal.CONTENT_ITEM_TYPE -> addresses += Address(row, raw, c.getInt(5), c.getString(6), s(7), s(8), s(9), s(10), s(11), s(12), s(13))
                    Website.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) websites += labelled(s(4))
                    Event.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) events += labelled(s(4))
                    Relation.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) relations += labelled(s(4))
                    // Messengers: the protocol in DATA5 is the label here; the old kinds are gone since Android 12.
                    Im.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) messengers += Labelled(row, raw, c.getInt(8).takeIf { !c.isNull(8) } ?: Im.PROTOCOL_CUSTOM, c.getString(9), s(4))
                    SipAddress.CONTENT_ITEM_TYPE -> if (s(4).isNotBlank()) sips += labelled(s(4))
                    Nickname.CONTENT_ITEM_TYPE -> if (details.nickname == null && s(4).isNotBlank()) details = details.copy(nickname = labelled(s(4)))
                    Note.CONTENT_ITEM_TYPE -> if (details.note == null && s(4).isNotBlank()) details = details.copy(note = Labelled(row, raw, 0, null, s(4)))
                    Org.CONTENT_ITEM_TYPE -> if (details.organization.rowId == 0L) details = details.copy(organization = Organization(row, s(4), s(7), s(8)))
                    GroupMembership.CONTENT_ITEM_TYPE -> s(4).toLongOrNull()?.let { groups[it] = row }
                    LookCodec.MIMETYPE -> if (details.lookRow == 0L) details = details.copy(look = LookCodec.decode(s(4)), lookRow = row)
                    in ANDROID_KINDS -> Unit
                    // Another app's row: the line it shows is usually in DATA3 ("Message +33…"), its name in DATA2.
                    else -> {
                        val label = s(6).ifBlank { s(5) }.take(80)
                        if (label.isNotBlank()) others += Other(row, c.getString(2), label, s(5).take(40))
                    }
                }
            }
        }
        return details.copy(
            phones = phones.distinctBy { it.value.filter(Char::isLetterOrDigit) + "/" + it.rawId },
            emails = emails.distinctBy { it.value.lowercase() + "/" + it.rawId },
            addresses = addresses,
            websites = websites,
            events = events,
            relations = relations,
            messengers = messengers,
            sips = sips,
            groups = groups.keys,
            groupRows = groups,
            others = others.distinctBy { it.mimetype + it.label }
        )
    }

    /** The user's own card (Android's profile), if they made one. */
    suspend fun me(): Contact? = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext null
        runCatching {
            resolver.query(
                ContactsContract.Profile.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) null else Contact(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(2).orEmpty(), c.getString(3), false)
            }
        }.getOrNull()
    }

    /** The contact with this number or address, if any, for "show or create". */
    suspend fun findBy(scheme: String, value: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = if (scheme == "tel") Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(value))
            else Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(value))
            val column = if (scheme == "tel") ContactsContract.PhoneLookup._ID else Email.CONTACT_ID
            resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
    }

    companion object {
        /** Android's own kinds of rows; any other comes from another app. */
        private val ANDROID_KINDS = setOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
            Website.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE, Im.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE,
            Nickname.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Org.CONTENT_ITEM_TYPE, GroupMembership.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
            LookCodec.MIMETYPE
        )

        fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    }
}
