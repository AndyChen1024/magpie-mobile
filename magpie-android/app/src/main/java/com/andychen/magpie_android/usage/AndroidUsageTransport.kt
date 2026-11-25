package com.andychen.magpie_android.usage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.andychen.magpie.shared.usage.UsageFailure
import com.andychen.magpie.shared.usage.UsageHttpRequest
import com.andychen.magpie.shared.usage.UsageHttpResponse
import com.andychen.magpie.shared.usage.UsageOrigin
import com.andychen.magpie.shared.usage.UsageTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.GeneralSecurityException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun hasLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        context.applicationInfo.targetSdkVersion < Build.VERSION_CODES.CINNAMON_BUN ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

internal class AndroidUsageTransport(
    private val context: Context,
    private val store: UsageConnectionStore
) : UsageTransport {
    override suspend fun get(request: UsageHttpRequest): UsageHttpResponse = withContext(Dispatchers.IO) {
        if (!hasLocalNetworkPermission(context)) {
            return@withContext UsageHttpResponse(0, "", UsageFailure.LOCAL_PERMISSION)
        }

        val url = URI(request.url)
        val origin = UsageOrigin.parse("${url.scheme}://${url.rawAuthority}") ?: return@withContext credentialFailure()
        val saved = try {
            store.readConnection(origin)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext credentialFailure()
        }
        if (saved == null || request.authorization != "Bearer ${saved.key}") {
            return@withContext credentialFailure()
        }
        val connection = url.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            saved.certificate?.let { connection.sslSocketFactory = usageSocketFactory(it) }
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", request.authorization)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            when (status) {
                in 200..299 -> {
                    val body = connection.inputStream.use { it.readJson(maxBytes = 16 * 1024 * 1024) }
                    if (body == null) UsageHttpResponse(status, "", UsageFailure.INVALID_DATA)
                    else UsageHttpResponse(status, body)
                }
                403 -> UsageHttpResponse(status, connection.errorStream?.use { it.readJson(16 * 1024) }.orEmpty())
                else -> UsageHttpResponse(status, "")
            }
        } catch (_: SSLException) {
            UsageHttpResponse(0, "", UsageFailure.TLS)
        } catch (_: GeneralSecurityException) {
            UsageHttpResponse(0, "", UsageFailure.TLS)
        } catch (_: SecurityException) {
            networkFailure()
        } catch (_: IOException) {
            networkFailure()
        } finally {
            connection.disconnect()
        }
    }

    private fun credentialFailure() = UsageHttpResponse(0, "", UsageFailure.CREDENTIAL)

    private fun networkFailure() = UsageHttpResponse(
        0, "", if (hasLocalNetworkPermission(context)) UsageFailure.NETWORK else UsageFailure.LOCAL_PERMISSION
    )

    private fun InputStream.readJson(maxBytes: Int): String? {
        val buffer = ByteArray(8192)
        val body = ByteArrayOutputStream()
        while (true) {
            val count = read(buffer)
            if (count == -1) return body.toString(Charsets.UTF_8.name())
            if (body.size() + count > maxBytes) return null
            body.write(buffer, 0, count)
        }
    }
}
