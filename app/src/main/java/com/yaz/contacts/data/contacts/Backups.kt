package com.yaz.contacts.data.contacts

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Snapshot
import com.yaz.contacts.core.security.SafeImages
import com.yaz.contacts.core.security.Sealed
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every contact in one file only the user's passphrase opens, to take to
 * a new phone without any account: fields, labels' names, look, a small
 * photo. Made and read on the phone; the file goes where the user puts it.
 */
class Backups(private val context: Context, private val store: ContactStore, private val writer: ContactWriter) {

    @Serializable
    data class Person(val details: Details, val photo: String? = null)

    @Serializable
    data class Book(val v: Int = 1, val at: Long, val people: List<Person>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Writes the sealed file to [target]; the number of people in it, or -1 on failure. */
    suspend fun make(target: Uri, passphrase: CharArray, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val all = store.contacts.value.orEmpty()
        val people = all.mapIndexedNotNull { i, c ->
            progress(i, all.size)
            val d = store.details(c.id) ?: return@mapIndexedNotNull null
            Person(d, smallPhoto(d))
        }
        val book = Book(at = System.currentTimeMillis(), people = people)
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.encodeToString(Book.serializer(), book).toByteArray()) } }.toByteArray()
        val sealed = Sealed.seal(packed, passphrase)
        val ok = runCatching { context.contentResolver.openOutputStream(target, "wt")?.use { it.write(sealed) } != null }.getOrDefault(false)
        if (ok) people.size else -1
    }

    /** The people in a backup, or null when the passphrase is wrong or the file is not one. */
    suspend fun read(source: Uri, passphrase: CharArray): Book? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE) return@runCatching null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext null
        val packed = Sealed.open(bytes, passphrase) ?: return@withContext null
        runCatching {
            val text = GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_UNPACKED)
                    out.write(buf, 0, n)
                }
                out.toByteArray().decodeToString()
            }
            json.decodeFromString(Book.serializer(), text)
        }.getOrNull()
    }

    /** Saves everyone of [book] as new contacts in [account]; the number saved. */
    suspend fun restore(book: Book, account: Account?, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        var done = 0
        book.people.forEachIndexed { i, p ->
            progress(i, book.people.size)
            val photo = p.photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.let { SafeImages.decode(context, it, 720) }
            // Labels belong to the old phone's accounts: they are left out.
            if (writer.save(null, Snapshot.fresh(p.details).copy(groups = emptySet()), account, photo) != null) done++
        }
        done
    }

    private suspend fun smallPhoto(d: Details): String? {
        val bytes = runCatching {
            ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContactsContract.Contacts.getLookupUri(d.id, d.lookup), true)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val bitmap = SafeImages.decode(context, bytes, 360) ?: return null
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_FILE = 200 * 1024 * 1024
        const val MAX_UNPACKED = 400 * 1024 * 1024
    }
}
