package com.yaz.contacts.core.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * A file only a passphrase opens, for a backup that leaves the phone: the
 * key comes from the passphrase through Argon2id (RFC 9106: 64 MiB, three
 * passes), the content is sealed with AES-256-GCM, the header (format,
 * cost, salt) bound to it so none of it can be changed. Readable on any
 * phone with the passphrase, by this app.
 */
object Sealed {

    private val MAGIC = "YAZCB1".toByteArray()
    private const val SALT = 16
    private const val NONCE = 12
    private const val MEMORY_KB = 64 * 1024
    private const val PASSES = 3
    private const val LANES = 1

    fun seal(plain: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 12 + SALT + NONCE)
            .put(MAGIC).putInt(MEMORY_KB).putInt(PASSES).putInt(LANES).put(salt).put(nonce).array()
        val key = derive(passphrase, salt, MEMORY_KB, PASSES, LANES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    /** The content, or null for a wrong passphrase, a changed file or anything that is not such a file. */
    fun open(sealed: ByteArray, passphrase: CharArray): ByteArray? = runCatching {
        val headerSize = MAGIC.size + 12 + SALT + NONCE
        require(sealed.size > headerSize + 16)
        val buffer = ByteBuffer.wrap(sealed, 0, headerSize)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        require(magic.contentEquals(MAGIC))
        val memory = buffer.getInt()
        val passes = buffer.getInt()
        val lanes = buffer.getInt()
        // A file asking for an absurd cost would freeze the phone: refused.
        require(memory in 8 * 1024..256 * 1024 && passes in 1..10 && lanes in 1..4)
        val salt = ByteArray(SALT).also { buffer.get(it) }
        val nonce = ByteArray(NONCE).also { buffer.get(it) }
        val key = derive(passphrase, salt, memory, passes, lanes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(sealed, 0, headerSize)
            cipher.doFinal(sealed, headerSize, sealed.size - headerSize)
        } finally {
            key.fill(0)
        }
    }.getOrNull()

    private fun derive(passphrase: CharArray, salt: ByteArray, memoryKb: Int, passes: Int, lanes: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(passes)
            .withParallelism(lanes)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        val key = ByteArray(32)
        generator.generateBytes(bytes, key)
        bytes.fill(0)
        return key
    }
}
