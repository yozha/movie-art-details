package io.github.yozha.movieartdetails.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.github.yozha.movieartdetails.BuildConfig
import io.github.yozha.movieartdetails.model.CredentialRepository
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TMDB key: AES-256-GCM with an Android Keystore key ([ALIAS]); ciphertext and IV are stored in
 * noBackupFilesDir/[FILE_NAME] as `[version][IV length][IV][ciphertext+tag]`. The file is excluded from Auto Backup,
 * and the Keystore key never leaves the device.
 *
 * The decrypted key is kept in process memory (get() is called for every TMDB request).
 * If the file cannot be decrypted (Keystore key lost, file corrupted), it is deleted and there is no key;
 * on a transient Keystore failure the file is kept, and get() returns null and retries next time.
 * Without a stored key, debug builds fall back to BuildConfig.DEV_TMDB_KEY (empty in release).
 * The key is never logged. Methods block; do not call them on the main thread.
 */
class CredentialStore(context: Context) : CredentialRepository {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    override fun get(): String? = stored() ?: devKey()

    /** @throws IllegalArgumentException if the format is not ^[0-9a-fA-F]{32}$ (check with [isValidFormat] first). */
    override fun set(key: String) {
        val normalized = requireNotNull(KeyFormat.normalize(key)) { "invalid key format" }
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey(create = true)) }
            val iv = cipher.iv
            val sealed = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
            SafeFiles.write(file, byteArrayOf(FORMAT, iv.size.toByte()) + iv + sealed)
            cache = Cached(normalized)
        }
    }

    override fun clear() {
        synchronized(lock) {
            file.delete()
            runCatching { keyStore().deleteEntry(ALIAS) }
            cache = Cached(null)
        }
    }

    override fun masked(): String? = get()?.let(KeyFormat::mask)

    /** true if the user entered a key; false if there is no key or only the build's dev key. */
    fun hasUserKey(): Boolean = stored() != null

    private fun devKey(): String? = if (BuildConfig.DEBUG) KeyFormat.normalize(BuildConfig.DEV_TMDB_KEY) else null

    private fun stored(): String? {
        cache?.let { return it.key }
        synchronized(lock) {
            cache?.let { return it.key }
            val bytes = SafeFiles.read(file, MAX_FILE_BYTES)
            val key = try {
                bytes?.let(::decrypt)
            } catch (_: Exception) {
                // Transient Keystore failure: leave the file alone and do not cache; retry on the next call.
                return null
            }
            if (key == null) file.delete()
            cache = Cached(key)
            return key
        }
    }

    /** null: permanently undecryptable (Keystore key lost, unknown format, bad tag); an exception means a transient failure. */
    private fun decrypt(bytes: ByteArray): String? {
        val ivLen = bytes.getOrNull(1)?.toInt() ?: return null
        if (bytes[0] != FORMAT || ivLen !in 12..16 || bytes.size <= 2 + ivLen) return null
        return try {
            val key = secretKey(create = false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 2, ivLen))
            KeyFormat.normalize(String(cipher.doFinal(bytes, 2 + ivLen, bytes.size - 2 - ivLen), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            null
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        } catch (_: UnrecoverableKeyException) {
            null
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Wrapper that tells "not read yet" (null) apart from "no key" (Cached(null)). */
    private class Cached(val key: String?)

    companion object {
        const val ALIAS = "movie_art_details_tmdb_key"
        const val FILE_NAME = "tmdb_key.bin"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT: Byte = 1
        private const val MAX_FILE_BYTES = 1024L

        private val lock = Any()
        /** Process-wide: settings screens and the service see the same value even with two instances. */
        @Volatile private var cache: Cached? = null

        fun isValidFormat(raw: String): Boolean = KeyFormat.isValidFormat(raw)
    }
}
