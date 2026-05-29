package ro.eidkit.app.screens

import android.app.Application
import android.nfc.tech.IsoDep
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.opentelemetry.api.trace.Span
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
import ro.eidkit.app.EmailStore
import ro.eidkit.app.StoreOp
import ro.eidkit.app.EidKitApp
import ro.eidkit.app.relay.AttestationProvider
import ro.eidkit.app.relay.OkHttpRelayTransport
import ro.eidkit.app.relay.performAttestation
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

    data class EmailInput(val prefill: String?, val clientId: String = "", val serviceName: String = "", val pendingEmail: String? = null) : RemoteAuthState()

    data class OtpInput(val email: String, val clientId: String = "", val serviceName: String = "") : RemoteAuthState()

    data class Success(
        val firstName: String = "",
        val lastName: String = "",
        val saveDialog: SaveDialogState? = null,
    ) : RemoteAuthState()

    data class Error(val message: String, val traceId: String? = null) : RemoteAuthState()
}

class RemoteAuthViewModel(app: Application) : AndroidViewModel(app) {

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

    private var activeTransport: OkHttpRelayTransport? = null

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
        val remoteAuthSpan: Span = EidKitApp.tracer.spanBuilder("remote_auth")
            .setParent(parentCtx)
            .startSpan()
        val spanCtx = parentCtx.with(remoteAuthSpan)
        val rawTraceId: String = remoteAuthSpan.spanContext.traceId
        val spanTraceId: String? = if (rawTraceId == "00000000000000000000000000000000") null else rawTraceId

        viewModelScope.launch(spanCtx.asContextElement()) {
            val transport = OkHttpRelayTransport()
            activeTransport = transport
            try {
                // Attestation handshake before NFC — server sends challenge, we respond
                // with a Play Integrity token proving this is a genuine EidKit app.
                // Soft enforcement: server logs verdict, never rejects. ~1-2s before card tap.
                transport.connectForAttestation(input.wsUrl)
                performAttestation(
                    provider     = AttestationProvider(getApplication()),
                    transport    = transport,
                    receiveFrame = { transport.receiveFrame() },
                )

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
                    onUnknownFrame = { type, frame ->
                        handleRelayFrame(type, frame, transport)
                    },
                )

                _state.value = RemoteAuthState.Success(saveDialog = pendingSaveDialog)
                pendingSaveDialog = null
                remoteAuthSpan.setStatus(StatusCode.OK)

            } catch (e: CeiError.WrongPin) {
                _state.value = RemoteAuthState.Error("wrong_pin:${e.attemptsRemaining}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PinBlocked) {
                _state.value = RemoteAuthState.Error("pin_blocked", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.CardLost) {
                _state.value = RemoteAuthState.Error("card_lost", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PaceFailure) {
                _state.value = RemoteAuthState.Error("pace_failed", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: CeiError) {
                _state.value = RemoteAuthState.Error("generic:${e.message}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } catch (e: Exception) {
                _state.value = RemoteAuthState.Error("network:${e.message}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
            } finally {
                activeTransport = null
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

    // ── Email / OTP ───────────────────────────────────────────────────────────

    private fun handleRelayFrame(type: String, frame: org.json.JSONObject, transport: OkHttpRelayTransport) {
        when (type) {
            "email_request" -> {
                val prefill = frame.optString("prefill").takeIf { it.isNotEmpty() }
                val clientId = frame.optString("client_id").takeIf { it.isNotEmpty() } ?: ""
                val serviceName = _serviceName.value
                // If a remembered email exists for this client, silently resubmit it
                val remembered = if (clientId.isNotEmpty()) EmailStore.getRemembered(getApplication(), clientId) else null
                if (remembered != null) {
                    transport.sendFrame(
                        org.json.JSONObject().apply {
                            put("type", "email_submit"); put("email", remembered)
                        }.toString()
                    )
                } else {
                    _state.value = RemoteAuthState.EmailInput(prefill, clientId, serviceName)
                }
            }
            "email_otp_sent" -> {
                val s = _state.value
                val email = (s as? RemoteAuthState.EmailInput)?.pendingEmail
                    ?: (s as? RemoteAuthState.OtpInput)?.email
                    ?: ""
                val clientId = (s as? RemoteAuthState.EmailInput)?.clientId
                    ?: (s as? RemoteAuthState.OtpInput)?.clientId ?: ""
                val serviceName = (s as? RemoteAuthState.EmailInput)?.serviceName
                    ?: (s as? RemoteAuthState.OtpInput)?.serviceName ?: ""
                _state.value = RemoteAuthState.OtpInput(email, clientId, serviceName)
            }
        }
    }

    fun submitEmail(email: String, remember: Boolean) {
        val s = _state.value as? RemoteAuthState.EmailInput ?: return
        if (remember && s.clientId.isNotEmpty()) {
            EmailStore.remember(getApplication(), s.clientId, s.serviceName, email)
        }
        // Stamp the typed email so email_otp_sent can pick it up if OTP is needed
        _state.value = s.copy(pendingEmail = email)
        activeTransport?.sendFrame(
            org.json.JSONObject().apply {
                put("type", "email_submit"); put("email", email)
            }.toString()
        )
    }

    fun submitOtp(code: String, remember: Boolean) {
        val s = _state.value as? RemoteAuthState.OtpInput
        if (remember && s != null && s.clientId.isNotEmpty()) {
            EmailStore.remember(getApplication(), s.clientId, s.serviceName, s.email)
        }
        activeTransport?.sendFrame(
            org.json.JSONObject().apply {
                put("type", "email_otp"); put("code", code); put("remember", remember)
            }.toString()
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
