package com.andychen.magpie_android.usage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.andychen.magpie.shared.usage.UsageCredentials
import com.andychen.magpie.shared.usage.UsageOrigin
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class UsageConnectionStore(
    context: Context,
    recordName: String = "magpie-usage-connection"
) : UsageCredentials {
    private val file = AtomicFile(File(context.noBackupFilesDir, recordName))
    private val alias = "$recordName-aes"

    suspend fun savedOrigin(): UsageOrigin? = withContext(Dispatchers.IO) { readRecord()?.origin }

    override suspend fun read(origin: UsageOrigin): String? = withContext(Dispatchers.IO) {
        decrypt(origin)
    }

    suspend fun save(origin: UsageOrigin, key: String) = withContext(Dispatchers.IO) {
        writeRecord(origin, key)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { removeRecord() }

    @Synchronized
    private fun readRecord(): Record? {
        val stream = try {
            file.openRead()
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return null
        }
        return DataInputStream(stream).use { input ->
            val origin = UsageOrigin.parse(input.readUTF()) ?: error("Invalid stored origin")
            val iv = ByteArray(12).also(input::readFully)
            val size = input.readInt()
            check(size in 17..4112)
            Record(origin, iv, ByteArray(size).also(input::readFully))
        }
    }

    @Synchronized
    private fun decrypt(origin: UsageOrigin): String? {
        val record = readRecord()?.takeIf { it.origin == origin } ?: return null
        val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.iv))
        cipher.updateAAD(origin.value.toByteArray())
        val plaintext = cipher.doFinal(record.encrypted)
        return try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    private fun writeRecord(origin: UsageOrigin, key: String) {
        require(key.length in 1..4096 && key.all { it.code in 33..126 })
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        check(cipher.iv.size == 12)
        cipher.updateAAD(origin.value.toByteArray())
        val plaintext = key.toByteArray()
        val encrypted = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeUTF(origin.value)
            output.write(cipher.iv)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    private fun removeRecord() {
        keyStore().deleteEntry(alias)
        file.delete()
        check(!file.baseFile.exists())
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }

    private data class Record(val origin: UsageOrigin, val iv: ByteArray, val encrypted: ByteArray)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
