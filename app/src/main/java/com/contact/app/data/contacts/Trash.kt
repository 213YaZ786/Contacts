package com.contact.app.data.contacts

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.Groups
import android.util.Base64
import com.contact.app.core.contacts.Account
import com.contact.app.core.contacts.Details
import com.contact.app.core.security.Vault
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Deleted contacts, kept on the phone for a while so a delete can be taken
 * back: each one whole (every field, labels, look, photo, account), sealed
 * by the Vault, in the app's own files, which no backup takes. Gone for
 * good after the days the user chose.
 */
class Trash(private val context: Context, private val writer: ContactWriter) {

    @Serializable
    data class Entry(val id: String, val deletedAt: Long, val name: String, val details: Details, val account: Account?, val photo: String? = null)

    private val dir = File(context.filesDir, "trash").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Reads what is in the trash, and lets go of what waited long enough. */
    suspend fun load(days: Int) = withContext(Dispatchers.IO) {
        val limit = System.currentTimeMillis() - days * DAY
        val kept = dir.listFiles().orEmpty().mapNotNull { file ->
            val at = file.name.substringBefore('-').toLongOrNull()
            if (at == null || at < limit) {
                file.delete()
                return@mapNotNull null
            }
            val plain = Vault.open(file.readBytes(), file.name) ?: return@mapNotNull null
            runCatching { json.decodeFromString(Entry.serializer(), plain.decodeToString()) }.getOrNull()
        }
        _entries.value = kept.sortedByDescending { it.deletedAt }
    }

    /**
     * Keeps [details] and deletes the contact. False when it could not be
     * kept (then nothing is deleted) or not deleted.
     */
    suspend fun delete(details: Details, account: Account?): Boolean = withContext(Dispatchers.IO) {
        val photo = details.photo?.let { uri ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } }.getOrNull()
                ?.takeIf { it.size <= MAX_PHOTO }
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }
        val id = System.currentTimeMillis().toString() + "-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val entry = Entry(id, System.currentTimeMillis(), details.display, details, account, photo)
        val kept = runCatching {
            File(dir, id).writeBytesAtomically(Vault.seal(json.encodeToString(Entry.serializer(), entry).encodeToByteArray(), id))
            true
        }.getOrDefault(false)
        if (!kept) return@withContext false
        if (writer.delete(listOf(details.id)) == 0) {
            File(dir, id).delete()
            return@withContext false
        }
        _entries.value = listOf(entry) + _entries.value
        true
    }

    /** Puts a contact back as it was, in its account when it is still on the phone. */
    suspend fun restore(entry: Entry, accounts: List<Account>): ContactWriter.Saved? = withContext(Dispatchers.IO) {
        val account = entry.account?.takeIf { a -> accounts.any { it.key == a.key } }
        val alive = existingGroups()
        val fresh = entry.details.copy(
            id = 0L, mainRaw = 0L, lookRow = 0L, raws = emptyList(), groupRows = emptyMap(),
            name = entry.details.name.copy(rowId = 0L),
            organization = entry.details.organization.copy(rowId = 0L),
            nickname = entry.details.nickname?.copy(rowId = 0L, rawId = 0L),
            note = entry.details.note?.copy(rowId = 0L, rawId = 0L),
            phones = entry.details.phones.map { it.copy(rowId = 0L, rawId = 0L) },
            emails = entry.details.emails.map { it.copy(rowId = 0L, rawId = 0L) },
            addresses = entry.details.addresses.map { it.copy(rowId = 0L, rawId = 0L) },
            websites = entry.details.websites.map { it.copy(rowId = 0L, rawId = 0L) },
            events = entry.details.events.map { it.copy(rowId = 0L, rawId = 0L) },
            relations = entry.details.relations.map { it.copy(rowId = 0L, rawId = 0L) },
            messengers = entry.details.messengers.map { it.copy(rowId = 0L, rawId = 0L) },
            sips = entry.details.sips.map { it.copy(rowId = 0L, rawId = 0L) },
            // A label deleted since, or of another account, is left out.
            groups = entry.details.groups.filter { alive[it] == (account?.key ?: "") }.toSet()
        )
        val photo = entry.photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val saved = writer.save(null, fresh, account, photo) ?: return@withContext null
        forget(entry)
        saved
    }

    suspend fun forget(entry: Entry) = withContext(Dispatchers.IO) {
        File(dir, entry.id).delete()
        _entries.value = _entries.value.filterNot { it.id == entry.id }
    }

    suspend fun empty() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _entries.value = emptyList()
    }

    private fun existingGroups(): Map<Long, String> = runCatching {
        context.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME), "${Groups.DELETED} = 0", null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)?.let { "$it/${c.getString(2)}" } ?: "") }
        }
    }.getOrNull().orEmpty()

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val MAX_PHOTO = 4 * 1024 * 1024
    }
}

/** Written beside the file, synced, then moved over it: whole or not at all. */
private fun File.writeBytesAtomically(bytes: ByteArray) {
    val temp = File(parentFile, "$name.tmp")
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    Files.move(temp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
