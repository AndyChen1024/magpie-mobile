package com.andychen.magpie_android.phone

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andychen.magpie_android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal enum class PhoneStatus { Unpaired, Connecting, Connected, Disconnected, AuthorizationLost }

internal enum class PhoneProblem { InvalidCode, ExpiredCode, CameraDenied, RelayUnavailable, Protocol, Storage, DeviceLimit }

internal data class PhoneConnectionState(
    val status: PhoneStatus = PhoneStatus.Connecting,
    val macName: String? = null,
    val problem: PhoneProblem? = null,
    val working: Boolean = false,
    val canRetry: Boolean = false,
    val scanning: Boolean = false,
) {
    val canScan: Boolean
        get() = !scanning && (status == PhoneStatus.Unpaired || status == PhoneStatus.AuthorizationLost ||
            status == PhoneStatus.Disconnected)
}

internal suspend fun restorePhoneConnection(
    profile: PhoneProfile?,
    show: (PhoneStatus) -> Unit,
    reconnect: suspend (PhoneProfile) -> Unit,
) {
    when {
        profile == null -> show(PhoneStatus.Unpaired)
        profile.requiresPairing -> show(PhoneStatus.AuthorizationLost)
        else -> reconnect(profile)
    }
}

internal suspend fun recordPhoneRevocation(
    profile: PhoneProfile,
    save: suspend (PhoneProfile) -> Unit,
): PhoneConnectionState {
    val state = PhoneConnectionState(PhoneStatus.AuthorizationLost)
    return try {
        save(profile.copy(requiresPairing = true))
        state
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        state.copy(problem = PhoneProblem.Storage)
    }
}

internal class PhoneConnectionViewModel(
    private val loadProfile: suspend () -> PhoneProfile?,
    private val saveProfile: suspend (PhoneProfile) -> Unit,
    private val deviceLabel: () -> String = { "Android" },
    private val debugEvent: (String) -> Unit = {},
) : ViewModel() {
    private constructor(store: PhoneProfileStore) : this(
        loadProfile = { withContext(Dispatchers.IO) { store.load() } },
        saveProfile = { profile -> withContext(Dispatchers.IO) { store.save(profile) } },
        deviceLabel = {
            Build.MODEL.filterNot {
                it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
            }.trim().take(64).ifBlank { "Android" }
        },
        debugEvent = { message -> if (BuildConfig.DEBUG) Log.d("MagpiePhone", message) },
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application: Application = checkNotNull(this[APPLICATION_KEY])
                PhoneConnectionViewModel(PhoneProfileStore(application))
            }
        }
    }

    private val relay = PhoneRelaySession.client()
    private val mutableState = MutableStateFlow(PhoneConnectionState())
    val state = mutableState.asStateFlow()
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private var connection: Job? = null
    private var profile: PhoneProfile? = null
    private var lastReading: HostReading? = null

    init {
        connection = viewModelScope.launch {
            try {
                profile = loadProfile()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                show(PhoneStatus.Unpaired, PhoneProblem.Storage)
                return@launch
            }
            restorePhoneConnection(profile, { show(it) }, { runConnection(it) })
        }
    }

    fun beginScan(): Boolean {
        val current = state.value
        if (!current.canScan) return false
        // Reserve the scan before opening the camera, even during an automatic retry.
        mutableState.value = current.copy(scanning = true, working = false)
        connection?.cancel()
        refresh.tryReceive()
        return true
    }

    fun finishScan(raw: String?, cameraDenied: Boolean = false) {
        val current = state.value
        if (!current.scanning) return
        val pairing = raw?.let { runCatching { PhonePairing.parse(it) }.getOrNull() }
        val problem = when {
            cameraDenied -> PhoneProblem.CameraDenied
            raw != null && pairing == null -> PhoneProblem.InvalidCode
            else -> current.problem
        }
        val previous = connection
        // Keep both actions locked until the previous connection has fully stopped.
        show(PhoneStatus.Connecting)
        connection = viewModelScope.launch {
            previous?.join()
            if (pairing == null) {
                resumeSavedProfile(problem, current.status)
            } else {
                val saved = profile
                val sameMac = saved?.takeIf {
                    it.offer.serverId == pairing.offer.serverId &&
                        it.offer.macPublicKey.contentEquals(pairing.offer.macPublicKey)
                }
                val candidate = PhoneProfile(pairing.offer, sameMac?.identity ?: PhoneIdentity.generate())
                runPairing(candidate, pairing.ticket, replacing = saved != null, previousStatus = current.status)
            }
        }
    }

    fun readOrRetry() {
        val current = state.value
        if (current.scanning) return
        if (current.canRetry) {
            val previous = connection
            previous?.cancel()
            mutableState.value = current.copy(working = true)
            connection = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // A scan or another retry may cancel this job while it is waiting for cleanup.
                withContext(NonCancellable) { previous?.join() }
                ensureActive()
                restorePhoneConnection(profile, { show(it) }, { runConnection(it) })
            }
        } else if (current.status == PhoneStatus.Connected && !current.working) {
            mutableState.value = current.copy(working = true)
            refresh.trySend(Unit)
        }
    }

    private suspend fun runPairing(
        candidate: PhoneProfile,
        ticket: String,
        replacing: Boolean,
        previousStatus: PhoneStatus,
    ) {
        var session: PhoneRelaySession? = null
        val problem = try {
            val fresh = PhoneRelaySession(relay, candidate.offer)
            session = fresh
            fresh.handshake()
            // First enrollment can still check for a lost acknowledgement with the saved identity.
            if (!replacing) persistPairing(candidate)
            fresh.authorize(candidate.identity, ticket, deviceLabel())
            debugEvent("device.pair accepted")
            if (replacing) persistPairing(candidate)
            lastReading = null
            session = null
            // Let the pairing coroutine and its one-time ticket go out of scope.
            connection = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runConnection(candidate, firstSession = fresh)
            }
            return
        } catch (error: PhoneServerError) {
            when (error.code) {
                "invalid_pairing" -> PhoneProblem.ExpiredCode
                "device_limit" -> PhoneProblem.DeviceLimit
                else -> PhoneProblem.Protocol
            }
        } catch (_: PairingStorageError) {
            PhoneProblem.Storage
        } catch (_: TimeoutCancellationException) {
            PhoneProblem.RelayUnavailable
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            PhoneProblem.RelayUnavailable
        } catch (_: Exception) {
            PhoneProblem.Protocol
        } finally {
            session?.close()
        }
        if (replacing) {
            resumeSavedProfile(problem, previousStatus)
        } else if (problem == PhoneProblem.RelayUnavailable) {
            resumeSavedProfile(problem, PhoneStatus.Disconnected)
        } else {
            show(PhoneStatus.Unpaired, problem)
        }
    }

    private suspend fun persistPairing(candidate: PhoneProfile) {
        try {
            saveProfile(candidate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw PairingStorageError(error)
        }
        profile = candidate
    }

    private class PairingStorageError(cause: Exception) : Exception(cause)

    private suspend fun resumeSavedProfile(problem: PhoneProblem?, fallback: PhoneStatus) {
        val saved = profile
        when {
            saved == null || fallback == PhoneStatus.Unpaired -> show(fallback, problem)
            saved.requiresPairing -> show(PhoneStatus.AuthorizationLost, problem)
            else -> {
                show(PhoneStatus.Disconnected, problem, canRetry = true)
                delay(1_000)
                runConnection(saved)
            }
        }
    }

    private suspend fun runConnection(saved: PhoneProfile, firstSession: PhoneRelaySession? = null) {
        var pairedSession = firstSession
        var delayMs = 1_000L
        while (true) {
            val current = state.value
            if (current.status == PhoneStatus.Disconnected) {
                mutableState.value = current.copy(working = true)
            } else {
                show(PhoneStatus.Connecting)
            }
            var operation = "device.auth"
            try {
                val alreadyAuthorized = pairedSession != null
                (pairedSession ?: PhoneRelaySession(relay, saved.offer)).use { session ->
                    pairedSession = null
                    if (!alreadyAuthorized) {
                        session.handshake()
                        session.authorize(saved.identity, ticket = null, label = "")
                        debugEvent("device.auth accepted")
                    }
                    operation = "host.get"
                    readWhileConnected(session)
                }
            } catch (error: PhoneServerError) {
                currentCoroutineContext().ensureActive()
                showServerError(error, operation)
                return
            } catch (_: TimeoutCancellationException) {
                // The relay did not answer. Retry below.
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // The relay disconnected. Retry below.
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                show(PhoneStatus.Disconnected, PhoneProblem.Protocol, canRetry = true)
                return
            }
            currentCoroutineContext().ensureActive()
            show(PhoneStatus.Disconnected, PhoneProblem.RelayUnavailable, canRetry = true)
            if (operation == "host.get") delayMs = 1_000L
            withTimeoutOrNull(delayMs) { refresh.receive() }
            delayMs = (delayMs * 2).coerceAtMost(10_000)
        }
    }

    private suspend fun readWhileConnected(session: PhoneRelaySession) {
        while (true) {
            val reading = session.readHost()
            currentCoroutineContext().ensureActive()
            check(lastReading?.receipt != reading.receipt)
            lastReading = reading
            debugEvent("host.get id=${reading.requestId.take(8)} receipt=${reading.receipt.take(8)}")
            mutableState.value = PhoneConnectionState(PhoneStatus.Connected, reading.displayName)
            session.awaitRead(refresh)
        }
    }

    private suspend fun showServerError(error: PhoneServerError, operation: String) {
        if (error.code != "device_not_authorized") {
            show(PhoneStatus.Disconnected, PhoneProblem.Protocol, canRetry = true)
            return
        }
        debugEvent("$operation device_not_authorized")
        val revoked = withContext(NonCancellable) {
            recordPhoneRevocation(checkNotNull(profile)) { updated ->
                profile = updated
                saveProfile(updated)
            }
        }
        currentCoroutineContext().ensureActive()
        mutableState.value = revoked
    }

    private fun show(status: PhoneStatus, problem: PhoneProblem? = null, canRetry: Boolean = false) {
        mutableState.value = PhoneConnectionState(status, problem = problem, canRetry = canRetry)
    }
}
