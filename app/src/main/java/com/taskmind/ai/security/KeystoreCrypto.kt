package com.taskmind.ai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM using a non-exportable key held in the Android Keystore (hardware-backed on
 * most phones). Used for API credentials and for every recording stored by the app.
 * Format: [12-byte IV][ciphertext + 16-byte tag]
 */
@Singleton
class KeystoreCrypto @Inject constructor() {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    @Synchronized
    private fun secretKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    fun encryptToBase64(text: String): String =
        Base64.encodeToString(encrypt(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun decryptFromBase64(b64: String): String? = runCatching {
        String(decrypt(Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun encryptFile(source: File, target: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { out ->
                out.write(cipher.iv)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    cipher.update(buf, 0, n)?.let { out.write(it) }
                }
                out.write(cipher.doFinal())
            }
        }
    }

    fun decryptFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            val iv = ByteArray(IV_SIZE)
            var read = 0
            while (read < IV_SIZE) {
                val n = input.read(iv, read, IV_SIZE - read)
                if (n < 0) error("Corrupted recording")
                read += n
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    cipher.update(buf, 0, n)?.let { out.write(it) }
                }
                out.write(cipher.doFinal())
            }
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "taskmind_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
