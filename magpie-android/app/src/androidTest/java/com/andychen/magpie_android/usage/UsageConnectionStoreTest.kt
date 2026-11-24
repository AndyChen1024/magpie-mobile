package com.andychen.magpie_android.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andychen.magpie.shared.usage.UsageOrigin
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageConnectionStoreTest {
    @Test
    fun encryptsConnectionAndBindsTheKeyToItsOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recordName = "magpie-usage-fixture"
        val store = UsageConnectionStore(context, recordName)
        val origin = checkNotNull(UsageOrigin.parse("https://sample.local:8443/v1"))
        val other = checkNotNull(UsageOrigin.parse("https://other.local"))
        val key = "local-fixture-key"
        try {
            store.save(origin, key)
            val reloaded = UsageConnectionStore(context, recordName)
            assertEquals(origin, reloaded.savedConnection()?.origin)
            assertEquals(key, reloaded.read(origin))
            assertNull(reloaded.readConnection(origin)?.certificate)
            assertNull(reloaded.read(other))
            val stored = File(context.noBackupFilesDir, recordName).readBytes()
            assertFalse(stored.toString(Charsets.ISO_8859_1).contains(key))
            reloaded.clear()
            assertNull(store.savedConnection())
            assertNull(store.read(origin))
        } finally {
            store.clear()
        }
    }

    @Test
    fun persistsTheCaAndKeepsItOnlyWhenEditingItsOrigin() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recordName = "magpie-usage-certificate-fixture"
        val store = UsageConnectionStore(instrumentation.targetContext, recordName)
        val certificate = instrumentation.context.assets.open("usage-test-ca.pem").use(::readUsageCertificate)
        val origin = checkNotNull(UsageOrigin.parse("https://sample.local:8443"))
        val other = checkNotNull(UsageOrigin.parse("https://sample.local:9443"))
        try {
            assertTrue(store.save(origin, "local-fixture-key", certificate))
            val reloaded = UsageConnectionStore(instrumentation.targetContext, recordName)
            assertEquals(certificate, reloaded.readConnection(origin)?.certificate)
            assertNull(reloaded.readConnection(other))
            assertTrue(reloaded.save(origin, "updated-fixture-key", keepSavedCertificate = true))
            assertEquals(certificate, reloaded.savedConnection()?.certificate)
            assertEquals("updated-fixture-key", reloaded.read(origin))

            assertFalse(reloaded.save(other, "other-fixture-key", keepSavedCertificate = true))
            assertNull(reloaded.savedConnection()?.certificate)
            assertNull(reloaded.readConnection(origin))

            assertTrue(reloaded.save(other, "other-fixture-key", certificate))
            assertFalse(reloaded.save(other, "other-fixture-key"))
            assertNull(reloaded.savedConnection()?.certificate)
        } finally {
            store.clear()
        }
    }
}
