package com.andychen.magpie_android.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andychen.magpie.shared.usage.UsageOrigin
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            assertEquals(origin, reloaded.savedOrigin())
            assertEquals(key, reloaded.read(origin))
            assertNull(reloaded.read(other))
            val stored = File(context.noBackupFilesDir, recordName).readBytes()
            assertFalse(stored.toString(Charsets.ISO_8859_1).contains(key))
            reloaded.clear()
            assertNull(store.savedOrigin())
            assertNull(store.read(origin))
        } finally {
            store.clear()
        }
    }
}
