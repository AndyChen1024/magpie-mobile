package com.andychen.magpie_android.phone

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andychen.magpie_android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
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
) {
    val canScan: Boolean
        get() = status == PhoneStatus.Unpaired || status == PhoneStatus.AuthorizationLost ||
            (status == PhoneStatus.Disconnected && !canRetry)
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

internal class PhoneConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PhoneProfileStore(application)
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
                profile = withContext(Dispatchers.IO) { store.load() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                show(PhoneStatus.Unpaired, PhoneProblem.Storage)
                return@launch
            }
            restorePhoneConnection(profile, { show(it) }, { runConnection(it) })
        }
    }

    fun scan(raw: String) {
        if (!state.value.canScan) return
        val pairing = try {
            PhonePairing.parse(raw)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(problem = PhoneProblem.InvalidCode)
            return
        }
        refresh.tryReceive()
        show(PhoneStatus.Connecting)
        connection?.cancel()
        connection = viewModelScope.launch {
            val old = profile?.takeIf {
                it.offer.serverId == pairing.offer.serverId &&
                    it.offer.macPublicKey.contentEquals(pairing.offer.macPublicKey)
            }
            val candidate = PhoneProfile(pairing.offer, old?.identity ?: PhoneIdentity.generate())
            lastReading = null
            runPairing(candidate, pairing.ticket)
        }
    }

    fun cameraDenied() {
        if (state.value.canScan) mutableState.value = state.value.copy(problem = PhoneProblem.CameraDenied)
    }

    fun readOrRetry() {
        val current = state.value
        if (!current.working && (current.status == PhoneStatus.Connected || current.canRetry)) {
            mutableState.value = current.copy(working = true)
            refresh.trySend(Unit)
        }
    }

    private suspend fun runPairing(candidate: PhoneProfile, ticket: String) {
        var prepared = false
        var session: PhoneRelaySession? = null
        try {
            val fresh = PhoneRelaySession(relay, candidate.offer)
            session = fresh
            fresh.handshake()
            try {
                // Keep the identity across a crash or a lost pair acknowledgement. An
                // auth-only reconnect tells us whether the Mac committed the grant.
                withContext(Dispatchers.IO) { store.save(candidate) }
                profile = candidate
                prepared = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                show(PhoneStatus.Unpaired, PhoneProblem.Storage)
                return
            }
            fresh.authorize(candidate.identity, ticket, deviceLabel())
            debugEvent("device.pair accepted")
            session = null
            // Let the pairing coroutine and its one-time ticket go out of scope.
            connection = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runConnection(candidate, firstSession = fresh)
            }
        } catch (error: PhoneServerError) {
            showServerError(error, "device.pair")
        } catch (_: TimeoutCancellationException) {
            recoverPairing(candidate, prepared)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            recoverPairing(candidate, prepared)
        } catch (_: Exception) {
            show(PhoneStatus.Unpaired, PhoneProblem.Protocol)
        } finally {
            session?.close()
        }
    }

    private fun recoverPairing(candidate: PhoneProfile, prepared: Boolean) {
        show(PhoneStatus.Disconnected, PhoneProblem.RelayUnavailable, canRetry = prepared)
        if (prepared) {
            connection = viewModelScope.launch {
                withTimeoutOrNull(1_000) { refresh.receive() }
                runConnection(candidate)
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
                showServerError(error, operation)
                return
            } catch (_: TimeoutCancellationException) {
                show(PhoneStatus.Disconnected, PhoneProblem.RelayUnavailable, canRetry = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                show(PhoneStatus.Disconnected, PhoneProblem.RelayUnavailable, canRetry = true)
            } catch (_: Exception) {
                show(PhoneStatus.Disconnected, PhoneProblem.Protocol)
                return
            }
            if (operation == "host.get") delayMs = 1_000L
            withTimeoutOrNull(delayMs) { refresh.receive() }
            delayMs = (delayMs * 2).coerceAtMost(10_000)
        }
    }

    private suspend fun readWhileConnected(session: PhoneRelaySession) {
        while (true) {
            val reading = session.readHost()
            check(lastReading?.receipt != reading.receipt)
            lastReading = reading
            debugEvent("host.get id=${reading.requestId.take(8)} receipt=${reading.receipt.take(8)}")
            mutableState.value = PhoneConnectionState(PhoneStatus.Connected, reading.displayName)
            session.awaitRead(refresh)
        }
    }

    private suspend fun showServerError(error: PhoneServerError, operation: String) {
        when (error.code) {
            "device_not_authorized" -> {
                debugEvent("$operation device_not_authorized")
                mutableState.value = recordPhoneRevocation(checkNotNull(profile)) { updated ->
                    profile = updated
                    withContext(Dispatchers.IO) { store.save(updated) }
                }
            }
            "invalid_pairing" -> show(PhoneStatus.Unpaired, PhoneProblem.ExpiredCode)
            "device_limit" -> show(PhoneStatus.Unpaired, PhoneProblem.DeviceLimit)
            else -> show(PhoneStatus.Disconnected, PhoneProblem.Protocol)
        }
    }

    private fun show(status: PhoneStatus, problem: PhoneProblem? = null, canRetry: Boolean = false) {
        mutableState.value = PhoneConnectionState(status, problem = problem, canRetry = canRetry)
    }

    private fun deviceLabel(): String = Build.MODEL.filterNot {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }.trim().take(64).ifBlank { "Android" }

    private fun debugEvent(message: String) {
        if (BuildConfig.DEBUG) Log.d("MagpiePhone", message)
    }
}
