package io.github.jambolo.transmogrifier.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plaintext: String): String

    /** @throws java.security.GeneralSecurityException if the ciphertext can't be decrypted (for example, the key was reset). */
    fun decrypt(ciphertext: String): String
}

/** AES-GCM with a non-exportable key held in the Android Keystore. Output is base64 of IV followed by ciphertext. */
class KeystoreCipher(private val alias: String = "api-keys") : SecretCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(
            cipher.iv + cipher.doFinal(plaintext.toByteArray())
        )
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
