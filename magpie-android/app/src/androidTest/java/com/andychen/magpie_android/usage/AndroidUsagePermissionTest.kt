package com.andychen.magpie_android.usage

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.CINNAMON_BUN)
class AndroidUsagePermissionTest {
    @Test
    fun target36UsesCompatibilityAccess() {
        val context = contextWith(Build.VERSION_CODES.BAKLAVA, PackageManager.PERMISSION_DENIED)
        assertTrue(hasLocalNetworkPermission(context))
    }

    @Test
    fun target37UsesTheRuntimeGrant() {
        assertFalse(hasLocalNetworkPermission(contextWith(Build.VERSION_CODES.CINNAMON_BUN, PackageManager.PERMISSION_DENIED)))
        assertTrue(hasLocalNetworkPermission(contextWith(Build.VERSION_CODES.CINNAMON_BUN, PackageManager.PERMISSION_GRANTED)))
    }

    private fun contextWith(targetSdk: Int, permissionResult: Int): Context =
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationInfo(): ApplicationInfo =
                ApplicationInfo(super.getApplicationInfo()).apply { targetSdkVersion = targetSdk }

            override fun checkSelfPermission(permission: String): Int = permissionResult
        }
}
