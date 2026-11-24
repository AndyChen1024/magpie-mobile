package com.andychen.magpie_android.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.andychen.magpie.shared.usage.UsageController
import com.andychen.magpie.shared.usage.UsageOrigin
import com.andychen.magpie.shared.usage.UsagePeriod
import com.andychen.magpie.shared.usage.UsageState
import com.andychen.magpie.shared.usage.UsageTab
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class LanUsageState(
    val usage: UsageState = UsageState(),
    val working: Boolean = true,
    val storageError: Boolean = false,
    val hasCustomCertificate: Boolean = false
)

internal class LanUsageViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = UsageConnectionStore(application)
    private val controller = UsageController(AndroidUsageTransport(application, store), store)
    private val mutable = MutableStateFlow(LanUsageState())
    val state = mutable.asStateFlow()
    private var started = false

    private val observation = controller.observe { usage ->
        mutable.update { it.copy(usage = usage) }
        if (usage.origin != null) {
            saved["usagePeriod"] = usage.period.name
            saved["usageTab"] = usage.tab.name
        }
    }

    fun restoreConnection() {
        if (started) return
        started = true
        changeConnection {
            val connection = store.savedConnection() ?: return@changeConnection
            mutable.update { it.copy(hasCustomCertificate = connection.certificate != null) }
            val period = UsagePeriod.entries.firstOrNull { it.name == saved.get<String>("usagePeriod") }
            val tab = UsageTab.entries.firstOrNull { it.name == saved.get<String>("usageTab") }
            controller.restore(connection.origin, period ?: UsagePeriod.TODAY, tab ?: UsageTab.OVERVIEW)
        }
    }

    fun connect(origin: UsageOrigin, key: String, certificate: X509Certificate?, keepSavedCertificate: Boolean) {
        if (state.value.working) return
        changeConnection {
            val hasCertificate = store.save(origin, key, certificate, keepSavedCertificate)
            mutable.update { it.copy(hasCustomCertificate = hasCertificate) }
            controller.connect(origin)
        }
    }

    fun disconnect() {
        if (state.value.working) return
        changeConnection {
            store.clear()
            mutable.update { it.copy(hasCustomCertificate = false) }
            controller.disconnect()
            saved.remove<String>("usagePeriod")
            saved.remove<String>("usageTab")
        }
    }

    fun selectPeriod(period: UsagePeriod) = controller.selectPeriod(period)
    fun openTab(tab: UsageTab) = controller.openTab(tab)
    fun retry() = controller.retry()
    fun refresh() = controller.refresh()
    fun loadMore() = controller.loadMore()

    private fun changeConnection(operation: suspend () -> Unit) {
        mutable.update { it.copy(working = true, storageError = false) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(storageError = true) }
            } finally {
                mutable.update { it.copy(working = false) }
            }
        }
    }

    override fun onCleared() {
        observation.cancel()
        controller.close()
    }
}
