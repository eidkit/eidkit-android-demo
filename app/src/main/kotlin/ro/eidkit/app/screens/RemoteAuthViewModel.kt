package ro.eidkit.app.screens

import android.nfc.tech.IsoDep
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.StoreOp
import ro.eidkit.app.EidKitApp
import ro.eidkit.app.relay.OkHttpRelayTransport
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ReadEvent

sealed class RemoteAuthState {
    object Idle : RemoteAuthState()

    data class Input(
        val can: String = "",
        val pin: String = "",
        val sessionToken: String = "",
        val wsUrl: String = "",
        val serviceName: String = "",
        val traceparent: String? = null,
    ) : RemoteAuthState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
    }

    data class Scanning(
        val completedSteps: List<ReadEvent>,
        val activeStep: ReadEvent?,
    ) : RemoteAuthState()

    data class Success(
        val firstName: String = "",
        val lastName: String = "",
        val saveDialog: SaveDialogState? = null,
    ) : RemoteAuthState()

    data class Error(val message: String) : RemoteAuthState()
}

class RemoteAuthViewModel : ViewModel() {

    companion object {
        private val MapGetter = object : TextMapGetter<Map<String, String>> {
            override fun keys(carrier: Map<String, String>) = carrier.keys
            override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
        }
    }

    private val _state = MutableStateFlow<RemoteAuthState>(RemoteAuthState.Idle)
    val state: StateFlow<RemoteAuthState> = _state.asStateFlow()

    private val _serviceName = MutableStateFlow("")
    val serviceName: StateFlow<String> = _serviceName.asStateFlow()

    private var lastInput: RemoteAuthState.Input? = null
    private var snapshot = Triple<String?, String?, String?>(null, null, null)
    private var pendingSaveDialog: SaveDialogState? = null

    // ── Biometric load ────────────────────────────────────────────────────────

    fun tryBiometricLoad(activity: FragmentActivity) {
        if (!BiometricStore.hasCredentials(activity)) return
        BiometricStore.load(
            activity  = activity,
            onSuccess = { can, pin, _ ->
                snapshot = Triple(can, pin, null)
                val s = _state.value as? RemoteAuthState.Input ?: return@load
                _state.value = s.copy(
                    can = can ?: s.can,
                    pin = pin ?: s.pin,
                )
            },
            onFail = {},
        )
    }

    // ── Deep link init ────────────────────────────────────────────────────────

    fun initFromDeepLink(
        sessionToken: String,
        wsUrl: String,
        serviceName: String = "",
        traceparent: String? = null,
    ) {
        _serviceName.value = serviceName
        val input = RemoteAuthState.Input(
            sessionToken = sessionToken,
            wsUrl        = wsUrl,
            serviceName  = serviceName,
            traceparent  = traceparent,
        )
        lastInput = input
        _state.value = input
    }

    fun onCanChange(v: String) {
        val s = _state.value as? RemoteAuthState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? RemoteAuthState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? RemoteAuthState.Input ?: return
        if (!input.canSubmit) return

        val scannedCan = input.can
        val scannedPin = input.pin
        pendingSaveDialog = buildSaveDialog(scannedCan, scannedPin)

        _state.value = RemoteAuthState.Scanning(emptyList(), null)

        val parentCtx = input.traceparent
            ?.let { W3CTraceContextPropagator.getInstance().extract(Context.root(), mapOf("traceparent" to it), MapGetter) }
            ?: Context.current()
        val remoteAuthSpan = EidKitApp.tracer.spanBuilder("remote_auth")
            .setParent(parentCtx)
            .startSpan()
        val spanCtx = parentCtx.with(remoteAuthSpan)

        viewModelScope.launch(spanCtx.asContextElement()) {
            val transport = OkHttpRelayTransport()
            try {
                EidKit.relay(
                    isoDep    = isoDep,
                    can       = scannedCan,
                    pin       = scannedPin,
                    wsUrl     = input.wsUrl,
                    transport = transport,
                    onEvent   = { event ->
                        val current = _state.value as? RemoteAuthState.Scanning ?: return@relay
                        val completed = if (current.activeStep != null)
                            current.completedSteps + current.activeStep
                        else
                            current.completedSteps
                        _state.value = RemoteAuthState.Scanning(completed, event)
                    },
                )

                _state.value = RemoteAuthState.Success(saveDialog = pendingSaveDialog)
                pendingSaveDialog = null
                remoteAuthSpan.setStatus(StatusCode.OK)

            } catch (e: CeiError.WrongPin) {
                _state.value = RemoteAuthState.Error("wrong_pin:${e.attemptsRemaining}")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PinBlocked) {
                _state.value = RemoteAuthState.Error("pin_blocked")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.CardLost) {
                _state.value = RemoteAuthState.Error("card_lost")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PaceFailure) {
                _state.value = RemoteAuthState.Error("pace_failed")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError) {
                _state.value = RemoteAuthState.Error("generic:${e.message}")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: Exception) {
                _state.value = RemoteAuthState.Error("network:${e.message}")
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } finally {
                remoteAuthSpan.end()
            }
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────

    fun onSaveDialogToggle(saveCan: Boolean? = null, savePin: Boolean? = null) {
        val s = _state.value as? RemoteAuthState.Success ?: return
        val d = s.saveDialog ?: return
        _state.value = s.copy(saveDialog = d.copy(
            saveCan = saveCan ?: d.saveCan,
            savePin = savePin ?: d.savePin,
        ))
    }

    fun dismissSaveDialog() {
        val s = _state.value as? RemoteAuthState.Success ?: return
        _state.value = s.copy(saveDialog = null)
    }

    fun neverAskSave(context: android.content.Context) {
        BiometricStore.setNeverAsk(context)
        dismissSaveDialog()
    }

    fun confirmSave(activity: FragmentActivity) {
        val s = _state.value as? RemoteAuthState.Success ?: return
        val d = s.saveDialog ?: return
        BiometricStore.save(
            activity = activity,
            can      = StoreOp.Write(if (d.saveCan) d.scannedCan else null),
            pin      = StoreOp.Write(if (d.savePin) d.scannedPin else null),
            pin2     = StoreOp.Skip,
            onDone   = {
                _state.value = (_state.value as? RemoteAuthState.Success)?.copy(saveDialog = null) ?: _state.value
                snapshot = Triple(
                    if (d.saveCan) d.scannedCan else null,
                    if (d.savePin) d.scannedPin else null,
                    snapshot.third,
                )
            },
        )
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    fun retry() {
        _state.value = lastInput ?: RemoteAuthState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildSaveDialog(scannedCan: String, scannedPin: String): SaveDialogState? {
        val canChanged = scannedCan != (snapshot.first  ?: "")
        val pinChanged = scannedPin != (snapshot.second ?: "")
        if (!canChanged && !pinChanged) return null
        return SaveDialogState(
            scannedCan  = scannedCan,
            scannedPin  = scannedPin,
            saveCan     = snapshot.first  != null || canChanged,
            savePin     = snapshot.second != null || pinChanged,
            showPin2Row = false,
        )
    }
}
