package com.yaz.contacts.data.contacts

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.yaz.contacts.core.security.SafeImages
import java.io.ByteArrayOutputStream
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Snapshot
import com.yaz.contacts.core.security.Vault
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * People the user keeps private: out of Android's contacts, so no other app
 * reads them, each sealed by the Vault in this app's own files (never in a
 * backup of the phone). Dialer and SMS may ask only for the name of a
 * number, through a door for apps signed with the same key.
 */
class PrivateBook private constructor(context: Context) {

    @Serializable
    data class Person(val id: String, val details: Details, val photo: String? = null, val at: Long)

    private val dir = File(context.filesDir, "private").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people.asStateFlow()

    /** Reads them; nothing while the phone is locked, as the Vault's key waits for an unlock. */
    @Synchronized
    fun load() {
        _people.value = dir.listFiles().orEmpty().mapNotNull { f ->
            Vault.open(f.readBytes(), f.name)?.let { runCatching { json.decodeFromString(Person.serializer(), it.decodeToString()) }.getOrNull() }
        }.sortedBy { it.details.display.lowercase() }
    }

    @Synchronized
    fun put(details: Details, photo: String?): Person {
        val id = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val person = Person(id, Snapshot.fresh(details).copy(groups = emptySet(), display = details.display, photo = null, thumbnail = null, accounts = emptyList(), readOnly = false, readOnlyRaws = emptySet(), others = emptyList()), photo, System.currentTimeMillis())
        write(person)
        _people.value = (_people.value + person).sortedBy { it.details.display.lowercase() }
        return person
    }

    @Synchronized
    fun remove(id: String) {
        File(dir, id).delete()
        _people.value = _people.value.filterNot { it.id == id }
    }

    fun get(id: String): Person? = _people.value.firstOrNull { it.id == id }

    /** The private person with this number, by its last nine digits. */
    fun lookup(number: String): Person? {
        val key = number.filter(Char::isDigit).takeLast(9)
        if (key.length < 6) return null
        if (_people.value.isEmpty()) load()
        return _people.value.firstOrNull { p -> p.details.phones.any { it.value.filter(Char::isDigit).takeLast(9) == key } }
    }

    private fun write(person: Person) {
        val target = File(dir, person.id)
        val temp = File(dir, person.id + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(Vault.seal(json.encodeToString(Person.serializer(), person).encodeToByteArray(), person.id))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Takes [d] out of Android's contacts into this book: its photo made
     * small, then the contact deleted (from its account too, so a synced
     * copy goes as well). Null when it could not be done; nothing lost then.
     */
    suspend fun hide(context: Context, d: Details, writer: ContactWriter): Person? {
        val photo = d.photo?.let { SafeImages.decode(context, Uri.parse(it), 720) }?.let { bitmap ->
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val person = runCatching { put(d, photo) }.getOrNull() ?: return null
        if (writer.delete(listOf(d.id)) > 0) return person
        remove(person.id)
        return null
    }

    /** Back into Android's contacts, in the account new contacts go to; the new contact's id. */
    suspend fun show(context: Context, person: Person, writer: ContactWriter, store: ContactStore, defaultAccount: String?): Long? {
        val accounts = store.accounts()
        val account = accounts.firstOrNull { it.key == defaultAccount } ?: accounts.firstOrNull()
        val bitmap = person.photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.let { SafeImages.decode(context, it, 720) }
        val saved = writer.save(null, Snapshot.fresh(person.details), account, bitmap) ?: return null
        remove(person.id)
        return saved.contactId
    }

    companion object {
        @Volatile private var instance: PrivateBook? = null

        fun get(context: Context): PrivateBook = instance ?: synchronized(this) {
            instance ?: PrivateBook(context.applicationContext).also { b ->
                runCatching { b.load() }
                instance = b
            }
        }
    }
}
