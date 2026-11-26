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
import com.andychen.magpie_android.phone.PhoneConnectionState
import com.andychen.magpie_android.phone.PhoneConnectionViewModel
import com.andychen.magpie_android.phone.PhoneRelaySession
import com.andychen.magpie_android.phone.PhoneStatus
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UsageConnectionMode { Paired, Manual }

internal data class LanUsageState(
    val usage: UsageState = UsageState(),
    val working: Boolean = true,
    val storageError: Boolean = false,
    val hasCustomCertificate: Boolean = false,
    val manualOrigin: UsageOrigin? = null,
    val mode: UsageConnectionMode = UsageConnectionMode.Paired,
    val phone: PhoneConnectionState = PhoneConnectionState(),
    val pairedOnline: Boolean = false,
    val bindingRevision: Long = 0
)

internal class LanUsageViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = UsageConnectionStore(application)
    private val controller = UsageController(AndroidUsageTransport(application, store), store)
    private val mutable = MutableStateFlow(LanUsageState(
        mode = UsageConnectionMode.entries.firstOrNull { it.name == saved.get<String>("usageSource") }
            ?: UsageConnectionMode.Paired,
        working = false
    ))
    val state = mutable.asStateFlow()
    private var visible = false
    private lateinit var phone: PhoneConnectionViewModel
    private var activeSession: PhoneRelaySession? = null
    private var manualLoaded = false

    private val period: UsagePeriod
        get() = UsagePeriod.entries.firstOrNull { it.name == saved.get<String>("usagePeriod") } ?: UsagePeriod.TODAY
    private val tab: UsageTab
        get() = UsageTab.entries.firstOrNull { it.name == saved.get<String>("usageTab") } ?: UsageTab.OVERVIEW

    private val observation = controller.observe { usage ->
        if (state.value.mode == UsageConnectionMode.Manual || activeSession?.let(::isCurrent) == true) {
            mutable.update { it.copy(usage = usage) }
        }
    }

    fun bindPhone(connection: PhoneConnectionViewModel) {
        if (::phone.isInitialized) return
        phone = connection
        viewModelScope.launch {
            combine(phone.state, phone.session) { _, _ -> Unit }.collect { syncPaired() }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!visible) {
            activeSession = null
            resetQuery()
        } else if (state.value.mode == UsageConnectionMode.Paired) {
            syncPaired()
        } else {
            openManualConnection()
        }
    }

    fun selectMode(mode: UsageConnectionMode) {
        if (state.value.working || state.value.mode == mode) return
        activeSession = null
        resetQuery()
        saved["usageSource"] = mode.name
        mutable.update { it.copy(mode = mode, storageError = false, pairedOnline = false) }
        if (mode == UsageConnectionMode.Paired) syncPaired() else openManualConnection()
    }

    private fun syncPaired() {
        if (!visible || state.value.mode != UsageConnectionMode.Paired) return
        val phoneState = phone.state.value
        val session = phone.session.value?.takeIf { it.isOpen && phoneState.status == PhoneStatus.Connected }
        if (session !== activeSession) {
            activeSession = session
            resetQuery()
            if (session != null) {
                controller.restoreSource(
                    PairedUsageSource(
                        exchange = { type, fields -> session.exchange(type, fields, timeoutMs = 8_000) },
                        isCurrent = { isCurrent(session) },
                        onAuthorizationLost = {
                            session.authorizationLost()
                            syncPaired()
                        }
                    ),
                    period,
                    tab
                )
            }
        }
        mutable.update { it.copy(phone = phoneState, pairedOnline = session != null) }
    }

    private fun isCurrent(session: PhoneRelaySession): Boolean =
        activeSession === session && phone.session.value === session && session.isOpen

    private fun openManualConnection() {
        if (state.value.working) return
        if (manualLoaded) {
            restoreManualQuery()
            return
        }
        changeConnection {
            val connection = store.savedConnection()
            manualLoaded = true
            mutable.update {
                it.copy(manualOrigin = connection?.origin, hasCustomCertificate = connection?.certificate != null)
            }
        }
    }

    private fun restoreManualQuery() {
        if (visible) state.value.manualOrigin?.let { controller.restore(it, period, tab) }
    }

    fun connect(origin: UsageOrigin, key: String, certificate: X509Certificate?, keepSavedCertificate: Boolean) {
        if (state.value.working || state.value.mode != UsageConnectionMode.Manual) return
        resetQuery()
        changeConnection {
            val hasCertificate = store.save(origin, key, certificate, keepSavedCertificate)
            manualLoaded = true
            mutable.update { it.copy(manualOrigin = origin, hasCustomCertificate = hasCertificate) }
        }
    }

    fun disconnect() {
        if (state.value.working || state.value.mode != UsageConnectionMode.Manual) return
        resetQuery()
        changeConnection {
            store.clear()
            manualLoaded = true
            mutable.update { it.copy(manualOrigin = null, hasCustomCertificate = false) }
        }
    }

    fun reconnect() = phone.readOrRetry()

    fun selectPeriod(period: UsagePeriod) {
        saved["usagePeriod"] = period.name
        controller.selectPeriod(period)
    }

    fun openTab(tab: UsageTab) {
        saved["usageTab"] = tab.name
        controller.openTab(tab)
    }

    fun retry() = controller.retry()
    fun refresh() = controller.refresh()
    fun loadMore() = controller.loadMore()

    private fun resetQuery() {
        controller.disconnect()
        mutable.update { it.copy(usage = UsageState(), bindingRevision = it.bindingRevision + 1) }
    }

    private fun changeConnection(operation: suspend () -> Unit) {
        mutable.update { it.copy(working = true, storageError = false) }
        viewModelScope.launch {
            try {
                operation()
                restoreManualQuery()
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
