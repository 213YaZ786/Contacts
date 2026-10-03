package com.yaz.contacts.data.contacts

import android.content.Context
import com.yaz.contacts.core.security.Vault
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the user's contacts share of themselves through the encrypted chat
 * of the messaging app: their card (name, numbers, photo) offered, never
 * written until the user applies it, and whether their chat key was
 * checked. Each kept sealed by the Vault in the app's own files.
 */
class Offers private constructor(context: Context) {

    @Serializable
    data class Offer(val number: String, val card: String, val photo: String? = null, val at: Long)

    private val dir = File(context.filesDir, "offers").apply { mkdirs() }
    private val verifiedFile = File(context.filesDir, "verified")
    private val json = Json { ignoreUnknownKeys = true }

    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()

    private val _verified = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** By the last nine digits of a number: true when its chat key was checked face to face. */
    val verified: StateFlow<Map<String, Boolean>> = _verified.asStateFlow()

    @Synchronized
    fun load() {
        _offers.value = dir.listFiles().orEmpty().mapNotNull { f ->
            Vault.open(f.readBytes(), f.name)?.let { runCatching { json.decodeFromString(Offer.serializer(), it.decodeToString()) }.getOrNull() }
        }.sortedByDescending { it.at }
        _verified.value = if (verifiedFile.exists()) Vault.open(verifiedFile.readBytes(), verifiedFile.name)
            ?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), it.decodeToString()) }.getOrNull() }.orEmpty()
        else emptyMap()
    }

    /** A card offered by [number]; a newer one from the same number replaces it. */
    @Synchronized
    fun offer(number: String, card: String, photo: String?) {
        val offer = Offer(number, card, photo, System.currentTimeMillis())
        val name = nameOf(number)
        write(File(dir, name), json.encodeToString(Offer.serializer(), offer).encodeToByteArray())
        _offers.value = listOf(offer) + _offers.value.filterNot { key(it.number) == key(number) }
    }

    @Synchronized
    fun drop(number: String) {
        File(dir, nameOf(number)).delete()
        _offers.value = _offers.value.filterNot { key(it.number) == key(number) }
    }

    @Synchronized
    fun setVerified(number: String, verified: Boolean?) {
        val next = _verified.value.toMutableMap().apply { if (verified == null) remove(key(number)) else put(key(number), verified) }
        write(verifiedFile, json.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), next).encodeToByteArray())
        _verified.value = next
    }

    fun offerFor(numbers: List<String>): Offer? {
        val keys = numbers.map(::key).toSet()
        return _offers.value.firstOrNull { key(it.number) in keys }
    }

    fun isVerified(numbers: List<String>): Boolean? = numbers.mapNotNull { _verified.value[key(it)] }.let { if (it.isEmpty()) null else it.any { v -> v } }

    private fun write(target: File, plain: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(Vault.seal(plain, target.name))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The file of a number: a hash of its last nine digits, so no number shows in a file name. */
    private fun nameOf(number: String): String =
        MessageDigest.getInstance("SHA-256").digest(key(number).toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        fun key(number: String) = number.filter(Char::isDigit).takeLast(9)

        @Volatile private var instance: Offers? = null

        /** One for the whole process: the messaging app writes through the provider, the screens read. */
        fun get(context: Context): Offers = instance ?: synchronized(this) {
            instance ?: Offers(context.applicationContext).also { o ->
                runCatching { o.load() }
                instance = o
            }
        }
    }
}
