package com.contact.app.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what the app keeps of the user's contacts in its own files (the
 * trash): AES-256-GCM with a key made inside Android's Keystore, in the
 * phone's secure chip when it has one, which never leaves it and cannot be
 * used while the phone is locked. On top of Android's own file encryption,
 * so a copy of the files alone reads as noise.
 */
object Vault {

    private const val ALIAS = "contacts-vault-1"
    private const val IV = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun make(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
        return try {
            make(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            make(strongBox = false)
        } catch (_: Exception) {
            make(strongBox = false)
        }
    }

    /** [plain] sealed, with [label] bound to it: a file moved under another name does not open. */
    fun seal(plain: ByteArray, label: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(label.toByteArray())
        val sealed = cipher.doFinal(plain)
        return cipher.iv + sealed
    }

    /** The plain bytes, or null when they were changed, moved or sealed by another key. */
    fun open(sealed: ByteArray, label: String): ByteArray? = runCatching {
        require(sealed.size > IV)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV))
        cipher.updateAAD(label.toByteArray())
        cipher.doFinal(sealed, IV, sealed.size - IV)
    }.getOrNull()
}
