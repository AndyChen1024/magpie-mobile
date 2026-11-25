package com.andychen.magpie_android.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class PhoneProfileStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone-profile"))

    fun load(): PhoneProfile? {
        val data = try {
            file.readFully()
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return null
        }
        val bytes = ByteBuffer.wrap(data)
        require(bytes.get() == FORMAT_VERSION)
        val offerLength = bytes.int
        require(offerLength in 1..4096 && bytes.remaining() == offerLength + IV_BYTES + SEED_BYTES + TAG_BYTES)
        val offerBytes = ByteArray(offerLength).also { bytes.get(it) }
        val iv = ByteArray(IV_BYTES).also { bytes.get(it) }
        val encrypted = ByteArray(SEED_BYTES + TAG_BYTES).also { bytes.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, requireNotNull(wrappingKey()), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(offerBytes)
        val seed = cipher.doFinal(encrypted)
        return try {
            PhoneProfile.fromStoredMetadata(parseObject(offerBytes.decodeToString()), PhoneIdentity.fromSeed(seed))
        } finally {
            seed.fill(0)
        }
    }

    fun save(profile: PhoneProfile) {
        val offer = profile.storedMetadata().toString().encodeToByteArray()
        require(offer.size in 1..4096)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey() ?: createWrappingKey())
        cipher.updateAAD(offer)
        val seed = profile.identity.encodedSeed()
        val encrypted = try {
            cipher.doFinal(seed)
        } finally {
            seed.fill(0)
        }

        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val record = ByteBuffer.allocate(1 + Int.SIZE_BYTES + offer.size + IV_BYTES + encrypted.size)
            .put(FORMAT_VERSION).putInt(offer.size).put(offer).put(iv).put(encrypted).array()
        val stream = file.startWrite()
        try {
            stream.write(record)
            stream.fd.sync()
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        file.finishWrite(stream)
        // AtomicFile reports a failed rename to logcat. Verify the record before enrolling.
        check(file.readFully().contentEquals(record))
    }

    private fun wrappingKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(KEY_ALIAS, null) as SecretKey?
    }

    private fun createWrappingKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val SEED_BYTES = 32
        const val TAG_BYTES = 16
        const val KEY_ALIAS = "magpie.phone.profile.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
