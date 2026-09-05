package com.moneyprinterturbo.android.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore-backed AES/GCM encrypted key-value store.
 * API keys never touch plaintext prefs, logs, or exported files.
 */
class SecureStore(context: Context) {

    private val alias = "mpt_master_key"
    private val file = File(context.filesDir, "secure_kv.bin")
    private val lock = Any()
    private val map: MutableMap<String, String>

    init {
        map = if (file.exists()) {
            try { decryptAll(file.readBytes()).toMutableMap() } catch (e: Exception) { mutableMapOf() }
        } else mutableMapOf()
    }

    fun put(key: String, value: String) = synchronized(lock) {
        if (value.isBlank()) map.remove(key) else map[key] = value
        persist()
    }

    fun get(key: String): String? = synchronized(lock) { map[key] }

    fun remove(key: String) = synchronized(lock) {
        map.remove(key); persist()
    }

    private fun persist() {
        val ser = map.entries.joinToString("\n") { e ->
            Base64.getEncoder().encodeToString(e.key.toByteArray(Charsets.UTF_8)) + " " +
                Base64.getEncoder().encodeToString(e.value.toByteArray(Charsets.UTF_8))
        }
        file.writeBytes(encrypt(ser.toByteArray(Charsets.UTF_8)))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decryptAll(blob: ByteArray): Map<String, String> {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ser = String(cipher.doFinal(ct), Charsets.UTF_8)
        val out = mutableMapOf<String, String>()
        ser.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val (k64, v64) = line.split(' ', limit = 2)
            out[String(Base64.getDecoder().decode(k64), Charsets.UTF_8)] =
                String(Base64.getDecoder().decode(v64), Charsets.UTF_8)
        }
        return out
    }
}
