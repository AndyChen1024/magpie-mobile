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
import java.security.cert.X509Certificate
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

    suspend fun savedConnection(): SavedUsageConnection? = withContext(Dispatchers.IO) { decrypt() }

    suspend fun readConnection(origin: UsageOrigin): SavedUsageConnection? = withContext(Dispatchers.IO) {
        decrypt(origin)
    }

    override suspend fun read(origin: UsageOrigin): String? = readConnection(origin)?.key

    suspend fun save(
        origin: UsageOrigin,
        key: String,
        certificate: X509Certificate? = null,
        keepSavedCertificate: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) { writeRecord(origin, key, certificate, keepSavedCertificate) }

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
            check(size in 17..MAX_ENCRYPTED_BYTES)
            Record(origin, iv, ByteArray(size).also(input::readFully))
        }
    }

    @Synchronized
    private fun decrypt(origin: UsageOrigin? = null): SavedUsageConnection? {
        val record = readRecord()?.takeIf { origin == null || it.origin == origin } ?: return null
        val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.iv))
        cipher.updateAAD(record.origin.value.toByteArray())
        val plaintext = cipher.doFinal(record.encrypted)
        return try {
            // Legacy records contain only the printable ASCII key. New CA data follows a NUL separator.
            val separator = plaintext.indexOf(0)
            val keyEnd = if (separator < 0) plaintext.size else separator
            val certificate = if (separator < 0) null else
                parseUsageCertificate(plaintext.copyOfRange(separator + 1, plaintext.size))
            SavedUsageConnection(record.origin, plaintext.decodeToString(endIndex = keyEnd), certificate)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    private fun writeRecord(
        origin: UsageOrigin,
        key: String,
        certificate: X509Certificate?,
        keepSavedCertificate: Boolean
    ): Boolean {
        require(key.length in 1..4096 && key.all { it.code in 33..126 })
        val selectedCertificate = certificate ?: if (keepSavedCertificate) decrypt(origin)?.certificate else null
        val encoded = selectedCertificate?.encoded
        require(encoded == null || encoded.size in 1..MAX_USAGE_CERTIFICATE_BYTES)
        val keyBytes = key.toByteArray()
        val plaintext = if (encoded == null) keyBytes else ByteArray(keyBytes.size + 1 + encoded.size).also {
            keyBytes.copyInto(it)
            encoded.copyInto(it, keyBytes.size + 1)
            keyBytes.fill(0)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val encrypted = try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            check(cipher.iv.size == 12)
            cipher.updateAAD(origin.value.toByteArray())
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
        return selectedCertificate != null
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
        const val MAX_ENCRYPTED_BYTES = 4096 + 1 + MAX_USAGE_CERTIFICATE_BYTES + 16
    }
}

internal class SavedUsageConnection(val origin: UsageOrigin, val key: String, val certificate: X509Certificate?)
