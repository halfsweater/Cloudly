package com.halfblanket.cloudly.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores short-lived OAuth access tokens encrypted with an AES-256-GCM key held in the Android Keystore.
 * The key never leaves secure hardware (where available) and the file lives in no-backup storage, so
 * tokens are useless if copied off the device. Refresh tokens are never handled by the app at all: Google
 * Play services keeps the grant and mints new access tokens on request.
 */
class TokenVault(context: Context) {
    private val dir = File(context.noBackupFilesDir, "tokens").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    data class Entry(val token: String, val expiresAtMillis: Long)

    @Synchronized
    fun read(accountId: String): Entry? {
        val f = fileFor(accountId)
        if (!f.exists()) return null
        return runCatching {
            val parts = f.readText().split(':')
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            val plain = String(cipher.doFinal(data), Charsets.UTF_8)
            val expires = plain.substringBefore('|').toLong()
            Entry(plain.substringAfter('|'), expires)
        }.getOrElse {
            f.delete() // corrupted or key invalidated: drop it, a new token will be requested
            null
        }
    }

    @Synchronized
    fun write(accountId: String, entry: Entry) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val data = cipher.doFinal("${entry.expiresAtMillis}|${entry.token}".toByteArray(Charsets.UTF_8))
            val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(data, Base64.NO_WRAP)
            val tmp = File(dir, "${safeName(accountId)}.tmp")
            tmp.writeText(text)
            tmp.renameTo(fileFor(accountId))
        }
    }

    @Synchronized
    fun delete(accountId: String) {
        fileFor(accountId).delete()
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(accountId: String) = File(dir, "${safeName(accountId)}.bin")
    private fun safeName(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "x" }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cloudly_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
