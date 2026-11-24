package com.andychen.magpie_android

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageResourcesTest {
    @Test
    fun loadsEnglishAndSimplifiedChineseWithEnglishFallback() {
        val english = resourcesFor("en-US")
        val chinese = resourcesFor("zh-CN")
        val fallback = resourcesFor("fr-FR")

        assertEquals("Usage", english.getString(R.string.usage))
        assertEquals("Connect to host", english.getString(R.string.usage_connect_title))
        assertEquals("This key can only view its own requests.", english.getString(R.string.usage_restricted))
        assertEquals("用量", chinese.getString(R.string.usage))
        assertEquals("连接主机", chinese.getString(R.string.usage_connect_title))
        assertEquals("此密钥只能查看自己的请求。", chinese.getString(R.string.usage_restricted))
        assertEquals("Usage", fallback.getString(R.string.usage))

        assertEquals("Import CA", english.getString(R.string.usage_ca_import))
        assertEquals("导入 CA", chinese.getString(R.string.usage_ca_import))
        assertEquals("Import CA", fallback.getString(R.string.usage_ca_import))

        assertEquals("1 failure", english.getQuantityString(R.plurals.failed_calls, 1, 1))
        assertEquals("2 failures", english.getQuantityString(R.plurals.failed_calls, 2, 2))
        assertEquals("1 unpriced request", english.getQuantityString(R.plurals.usage_unpriced_count, 1, 1L))
        assertEquals("2 unpriced requests", english.getQuantityString(R.plurals.usage_unpriced_count, 2, 2L))
        assertEquals("1 次未定价", chinese.getQuantityString(R.plurals.usage_unpriced_count, 1, 1L))
    }

    private fun resourcesFor(languageTag: String): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(config).resources
    }
}
