package com.yaz.contacts.data.contacts

import android.content.Context
import com.yaz.contacts.core.contacts.Account
import com.yaz.contacts.core.contacts.Details
import com.yaz.contacts.core.contacts.Snapshot
import com.yaz.contacts.core.security.Vault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The contacts as they were, kept to undo changes made since, whoever made
 * them (this app, a sync, another app): one picture of every contact taken
 * when the app is opened, at most once a day, never in the background;
 * one a day for a week, then one a week up to 30 days. Each one compressed
 * and sealed by the Vault in the app's own files, never in a backup.
 */
class Snapshots(private val context: Context, private val store: ContactStore, private val writer: ContactWriter) {

    @Serializable
    data class Kept(val at: Long, val people: List<Details>, val accounts: Map<String, Account?> = emptyMap())

    private val dir = File(context.filesDir, "snapshots").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** When each kept picture was taken, newest first. */
    fun times(): List<Long> = dir.listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.sortedDescending()

    /** Takes a picture when the last one is older than a day, and lets old ones go. */
    suspend fun takeIfDue() = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (times().firstOrNull()?.let { now - it < DAY - HOUR } == true) return@withContext
            take(now)
            prune(now)
        }
    }

    private suspend fun take(now: Long) {
        val all = store.contacts.value ?: return
        val people = all.mapNotNull { c -> store.details(c.id) }
        if (people.isEmpty()) return
        val kept = Kept(now, people, people.associate { it.lookup to it.accounts.firstOrNull() })
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.encodeToString(Kept.serializer(), kept).encodeToByteArray()) }
        }.toByteArray()
        val name = now.toString()
        val file = File(dir, name)
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { out ->
            out.write(Vault.seal(bytes, name))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** One a day for the last week, one a week before, nothing past 30 days. */
    private fun prune(now: Long) {
        val keep = Snapshot.keep(times(), now)
        dir.listFiles().orEmpty().forEach { f -> if (f.name.toLongOrNull() !in keep) f.delete() }
    }

    suspend fun open(at: Long): Kept? = withContext(Dispatchers.IO) {
        val f = File(dir, at.toString())
        if (!f.exists()) return@withContext null
        val plain = Vault.open(f.readBytes(), f.name) ?: return@withContext null
        runCatching {
            val text = GZIPInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }.decodeToString()
            json.decodeFromString(Kept.serializer(), text)
        }.getOrNull()
    }

    /** What going back to [kept] would do: who comes back, who is changed back, who goes. */
    suspend fun compare(kept: Kept): Snapshot.Plan = withContext(Dispatchers.IO) {
        val now = store.contacts.value.orEmpty().mapNotNull { store.details(it.id) }
        Snapshot.plan(kept.people, now)
    }

    /**
     * Goes back to [kept]: the people deleted since are made again, the ones
     * changed are changed back, the ones added since go to the trash. A
     * picture of now is taken first, so this too can be undone.
     */
    suspend fun restore(kept: Kept, plan: Snapshot.Plan, trash: Trash, removeAdded: Boolean): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            take(System.currentTimeMillis())
            var done = 0
            val accounts = store.accounts()
            val liveGroups = store.groups.value.map { it.id }.toSet()
            plan.back.forEach { d ->
                val account = kept.accounts[d.lookup]?.takeIf { a -> accounts.any { it.key == a.key } }
                val fresh = Snapshot.fresh(d).let { f -> f.copy(groups = f.groups.filter { it in liveGroups }.toSet()) }
                if (writer.save(null, fresh, account) != null) done++
            }
            plan.changed.forEach { (then, now) ->
                if (writer.save(now, Snapshot.onto(then, now, liveGroups), null) != null) done++
            }
            if (removeAdded) plan.added.forEach { d -> if (trash.delete(d, d.accounts.firstOrNull())) done++ }
            done
        }
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}

