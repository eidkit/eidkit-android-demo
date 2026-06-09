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
import io.sentry.Sentry
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ro.eidkit.app.R
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
        val mode: String = "secure",
    ) : RemoteAuthState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
        val isFastMode get() = mode == "fast"
    }

    data class Scanning(
        val completedSteps: List<ReadEvent>,
        val activeStep: ReadEvent?,
        val retryMessage: String? = null,
        val retryCount: Int = 0,
    ) : RemoteAuthState()

    data class EmailInput(val prefill: String?, val clientId: String = "", val serviceName: String = "", val pendingEmail: String? = null, val remember: Boolean = false) : RemoteAuthState()

    data class OtpInput(val email: String, val clientId: String = "", val serviceName: String = "", val invalidCount: Int = 0, val remember: Boolean = false) : RemoteAuthState()

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
        const val MAX_CARD_RETRIES = 2
    }

    private val _state = MutableStateFlow<RemoteAuthState>(RemoteAuthState.Idle)
    val state: StateFlow<RemoteAuthState> = _state.asStateFlow()

    private val _serviceName = MutableStateFlow("")
    val serviceName: StateFlow<String> = _serviceName.asStateFlow()

    private var lastInput: RemoteAuthState.Input? = null
    private var snapshot = Triple<String?, String?, String?>(null, null, null)
    private var pendingSaveDialog: SaveDialogState? = null

    private var activeTransport: OkHttpRelayTransport? = null
    // Persisted across card-lost retries so all attempts share one trace
    private var activeAuthSpan: Span? = null
    private var activeSpanCtx: io.opentelemetry.context.Context? = null
    private var activeSpanTraceId: String? = null
    // Fast mode: base URL and session token kept for email/OTP HTTP calls
    private var fastBaseUrl: String? = null
    private var fastSessionToken: String? = null

    // ── Biometric load ────────────────────────────────────────────────────────

    fun tryBiometricLoad(activity: FragmentActivity) {
        if (!BiometricStore.hasCredentials(activity)) return
        BiometricStore.load(
            activity  = activity,
            onSuccess = { can, pin, _ ->
                snapshot = Triple(can, pin, null)
                val s = _state.value as? RemoteAuthState.Input ?: return@load
                val updated = s.copy(can = can ?: s.can, pin = pin ?: s.pin)
                _state.value = updated
                lastInput = updated
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
        mode: String = "secure",
    ) {
        _serviceName.value = serviceName
        val input = RemoteAuthState.Input(
            sessionToken = sessionToken,
            wsUrl        = wsUrl,
            serviceName  = serviceName,
            traceparent  = traceparent,
            mode         = mode,
        )
        lastInput = input
        _state.value = input
    }

    fun onCanChange(v: String) {
        val s = _state.value as? RemoteAuthState.Input ?: return
        val updated = s.copy(can = v)
        _state.value = updated
        lastInput = updated
    }

    fun onPinChange(v: String) {
        val s = _state.value as? RemoteAuthState.Input ?: return
        val updated = s.copy(pin = v)
        _state.value = updated
        lastInput = updated
    }

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onCardDetected(isoDep: IsoDep) {
        // Allow retry from Scanning state (card was lost and user re-tapped)
        val scanning = _state.value as? RemoteAuthState.Scanning
        val input = (_state.value as? RemoteAuthState.Input)
            ?: lastInput?.takeIf { scanning != null }
            ?: return.also { android.util.Log.d("EidKit", "onCardDetected: no valid input state, state=${_state.value::class.simpleName}") }
        if (!input.canSubmit) {
            android.util.Log.w("EidKit", "onCardDetected: canSubmit=false (can=${input.can.length}, pin=${input.pin.length}), dropping tap")
            return
        }
        android.util.Log.d("EidKit", "onCardDetected: retryCount=${scanning?.retryCount ?: 0}, state=${_state.value::class.simpleName}")

        val scannedCan = input.can
        val scannedPin = input.pin
        val retryCount = scanning?.retryCount ?: 0
        pendingSaveDialog = buildSaveDialog(scannedCan, scannedPin)

        _state.value = RemoteAuthState.Scanning(emptyList(), null)

        // On first attempt, create the root span. On retries, reuse it so all
        // card-lost attempts appear under one trace rather than as siblings.
        val remoteAuthSpan: Span
        val spanCtx: io.opentelemetry.context.Context
        val spanTraceId: String?
        if (retryCount == 0 || activeAuthSpan == null) {
            val parentCtx = input.traceparent
                ?.let { W3CTraceContextPropagator.getInstance().extract(Context.root(), mapOf("traceparent" to it), MapGetter) }
                ?: Context.current()
            remoteAuthSpan = EidKitApp.tracer.spanBuilder("remote_auth")
                .setParent(parentCtx)
                .startSpan()
            spanCtx = parentCtx.with(remoteAuthSpan)
            val rawTraceId = remoteAuthSpan.spanContext.traceId
            spanTraceId = if (rawTraceId == "00000000000000000000000000000000") null else rawTraceId
            activeAuthSpan = remoteAuthSpan
            activeSpanCtx = spanCtx
            activeSpanTraceId = spanTraceId
        } else {
            remoteAuthSpan = activeAuthSpan!!
            spanCtx = activeSpanCtx!!
            spanTraceId = activeSpanTraceId
        }

        viewModelScope.launch(spanCtx.asContextElement()) {
            try {
                if (input.isFastMode) {
                    runFastSession(isoDep, input, scannedCan, scannedPin, retryCount)
                } else {
                    val transport = OkHttpRelayTransport()
                    activeTransport = transport
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
                    activeTransport = null
                }

                // Fast mode email: runFastSession sets state to EmailInput/OtpInput and returns —
                // Success is emitted later by submitFastEmail/submitFastOtp. Don't overwrite it here.
                // In secure relay mode, relay() only returns after done frame — always go to Success.
                val isFastEmailPending = input.isFastMode &&
                    (_state.value is RemoteAuthState.EmailInput || _state.value is RemoteAuthState.OtpInput)
                if (!isFastEmailPending) {
                    _state.value = RemoteAuthState.Success(saveDialog = pendingSaveDialog)
                    pendingSaveDialog = null
                    remoteAuthSpan.setStatus(StatusCode.OK)
                    remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
                }

            } catch (e: CeiError.WrongPin) {
                _state.value = RemoteAuthState.Error("wrong_pin:${e.attemptsRemaining}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
            } catch (e: CeiError.PinBlocked) {
                _state.value = RemoteAuthState.Error("pin_blocked", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
            } catch (e: CeiError.CardLost) {
                android.util.Log.d("EidKit", "CardLost caught, retryCount=$retryCount, cause=${e.cause}")
                activeTransport?.close(); activeTransport = null
                if (retryCount < MAX_CARD_RETRIES) {
                    remoteAuthSpan.addEvent("card_lost_retry_${retryCount + 1}")
                    _state.value = RemoteAuthState.Scanning(
                        completedSteps = emptyList(),
                        activeStep = null,
                        retryMessage = getApplication<android.app.Application>().getString(R.string.error_card_lost),
                        retryCount = retryCount + 1,
                    )
                } else {
                    _state.value = RemoteAuthState.Error("card_lost", spanTraceId)
                    remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                    remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
                }
            } catch (e: CeiError.PaceFailure) {
                android.util.Log.d("EidKit", "PaceFailure caught, retryCount=$retryCount, cause=${e.cause}")
                activeTransport?.close(); activeTransport = null
                // Only retry PACE failure on the first attempt — could be card moved during tap.
                // On subsequent attempts it's more likely a wrong CAN — send to error screen.
                if (retryCount == 0) {
                    remoteAuthSpan.addEvent("pace_failure_retry_1")
                    _state.value = RemoteAuthState.Scanning(
                        completedSteps = emptyList(),
                        activeStep = null,
                        retryMessage = getApplication<android.app.Application>().getString(R.string.error_card_lost),
                        retryCount = 1,
                    )
                } else {
                    _state.value = RemoteAuthState.Error("pace_failed", spanTraceId)
                    remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                    remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
                }
            } catch (e: CeiError.Unexpected) {
                android.util.Log.e("EidKit", "CeiError.Unexpected caught: ${e.message}", e)
                activeTransport?.close(); activeTransport = null
                // Stale IsoDep handle — card was pulled before or during connect. Treat as CardLost.
                val directCause = e.cause
                val rootCause = directCause?.cause ?: directCause
                if (retryCount == 0 && (rootCause is java.io.IOException || rootCause is SecurityException)) {
                    remoteAuthSpan.addEvent("connect_failure_retry_1")
                    _state.value = RemoteAuthState.Scanning(
                        completedSteps = emptyList(),
                        activeStep = null,
                        retryMessage = getApplication<android.app.Application>().getString(R.string.error_card_lost),
                        retryCount = 1,
                    )
                } else {
                    _state.value = RemoteAuthState.Error("generic:${e.message}", spanTraceId)
                    remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                    remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
                }
            } catch (e: CeiError) {
                android.util.Log.e("EidKit", "CeiError caught: ${e::class.simpleName} — ${e.message}", e)
                _state.value = RemoteAuthState.Error("generic:${e.message}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
            } catch (e: Exception) {
                android.util.Log.e("EidKit", "Exception caught: ${e::class.simpleName} — ${e.message}", e)
                _state.value = RemoteAuthState.Error("network:${e.message}", spanTraceId)
                remoteAuthSpan.recordException(e); remoteAuthSpan.setStatus(StatusCode.ERROR)
                remoteAuthSpan.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
            } finally {
                activeTransport = null
            }
        }
    }

    // ── Fast local mode ───────────────────────────────────────────────────────

    private val fastHttpClient = OkHttpClient()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private suspend fun runFastSession(
        isoDep: android.nfc.tech.IsoDep,
        input: RemoteAuthState.Input,
        can: String,
        pin: String,
        retryCount: Int = 0,
    ) = withContext(Dispatchers.IO) {
        // Derive the HTTP base URL from the wsUrl (replace wss:// → https://)
        val baseUrl = input.wsUrl
            .substringBefore("/v3/nfc-relay")
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")

        // Fetch the server-issued AA challenge so the server can verify it
        val challengeResp = fastHttpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v3/session/challenge?session=${input.sessionToken}")
                .get()
                .build()
        ).execute()
        if (!challengeResp.isSuccessful) throw Exception("challenge_fetch_failed: ${challengeResp.code}")
        val challengeJson = JSONObject(challengeResp.body!!.string())
        // server returns hex string — convert to raw bytes for the SDK
        val aaChallenge = challengeJson.getString("aa_challenge")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        var readResult: ro.eidkit.sdk.model.ReadResult? = null
        EidKit.reader(can)
            .withPersonalData(pin)
            .withActiveAuth(aaChallenge)
            .readFlow(isoDep)
            .collect { event ->
                when (event) {
                    is ReadEvent.Done -> readResult = event.result
                    else -> {
                        val current = _state.value as? RemoteAuthState.Scanning
                        val completed = if (current?.activeStep != null)
                            (current.completedSteps + current.activeStep)
                        else
                            current?.completedSteps ?: emptyList()
                        _state.value = RemoteAuthState.Scanning(completed, event, current?.retryMessage, retryCount)
                    }
                }
            }

        val claim = (readResult ?: throw Exception("claim_missing")).claim ?: throw Exception("claim_missing")
        val pa    = claim.passiveAuthProof
        val aa    = claim.activeAuthProof ?: throw Exception("active_auth_missing")
        val id    = claim.identity
        val pd    = claim.personalData

        fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("session",          input.sessionToken)
            put("raw_dg1",          claim.rawDg1?.b64() ?: "")
            put("sod",              pa.sodBytes.b64())
            put("doc_signing_cert", pa.docSigningCert.b64())
            put("csca_cert",        pa.cscaCert.b64())
            put("aa_challenge",     aa.challenge.b64())
            put("aa_signature",     aa.signature.b64())
            put("aa_cert",          aa.certificate.b64())
            put("platform",         "android")
            // Parsed identity fields — authenticated via passive+active auth above
            put("last_name",        id.lastName)
            put("first_name",       id.firstName)
            put("date_of_birth",    id.dateOfBirth)
            put("cnp",              id.cnp)
            pd?.let {
                it.documentNumber?.let { v -> put("document_number", v) }
                it.issueDate?.let      { v -> put("issue_date", v) }
                it.expiryDate?.let     { v -> put("expiry_date", v) }
                it.issuingAuthority?.let { v -> put("issuing_authority", v) }
                it.address?.let        { v -> put("address", v) }
            }
            if (retryCount > 0) put("retry_count", retryCount)
        }.toString().toRequestBody(JSON_MEDIA)

        val authResp = fastHttpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v3/local-auth")
                .post(body)
                .build()
        ).execute()
        if (!authResp.isSuccessful) throw Exception("local_auth_failed: ${authResp.code} ${authResp.body?.string()}")

        val authJson = JSONObject(authResp.body!!.string())
        when (authJson.optString("status")) {
            "email_required" -> {
                fastBaseUrl = baseUrl
                fastSessionToken = input.sessionToken
                val prefill = authJson.optString("prefill").takeIf { it.isNotEmpty() }
                val clientId = authJson.optString("client_id").takeIf { it.isNotEmpty() } ?: ""
                val remembered = if (clientId.isNotEmpty()) EmailStore.getRemembered(getApplication(), clientId) else null
                if (remembered != null) {
                    _state.value = RemoteAuthState.EmailInput(prefill = remembered, clientId = clientId, serviceName = input.serviceName, pendingEmail = remembered)
                    submitFastEmail(remembered, remember = false)
                } else {
                    _state.value = RemoteAuthState.EmailInput(prefill = prefill, clientId = clientId, serviceName = input.serviceName)
                }
            }
            else -> { /* code present — success handled by caller */ }
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
                    _state.value = RemoteAuthState.EmailInput(prefill = remembered, clientId = clientId, serviceName = serviceName, pendingEmail = remembered)
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
                val remember = (s as? RemoteAuthState.EmailInput)?.remember
                    ?: (s as? RemoteAuthState.OtpInput)?.remember ?: false
                if (email.isEmpty()) {
                    Sentry.captureMessage("email_otp_sent received but no email in state (state=${s::class.simpleName})")
                }
                _state.value = RemoteAuthState.OtpInput(email, clientId, serviceName, remember = remember)
            }
            "email_otp_invalid" -> {
                val s = _state.value as? RemoteAuthState.OtpInput ?: return
                _state.value = s.copy(invalidCount = s.invalidCount + 1)
            }
        }
    }

    fun submitEmail(email: String, remember: Boolean) {
        if (fastBaseUrl != null) { submitFastEmail(email, remember); return }
        val s = _state.value as? RemoteAuthState.EmailInput ?: return
        // If email matches the verified prefill, server will skip OTP and return code directly —
        // save to EmailStore now since submitOtp will never be reached in this path.
        if (remember && s.clientId.isNotEmpty() && !s.prefill.isNullOrEmpty() && email.trim().equals(s.prefill.trim(), ignoreCase = true)) {
            EmailStore.remember(getApplication(), s.clientId, s.serviceName, email.trim())
        }
        // Stamp the typed email and remember flag so email_otp_sent can carry them to OTP state
        _state.value = s.copy(pendingEmail = email, remember = remember)
        val transport = activeTransport
        if (transport == null) {
            Sentry.captureMessage("submitEmail: activeTransport is null — email_submit frame dropped")
            return
        }
        transport.sendFrame(
            org.json.JSONObject().apply {
                put("type", "email_submit"); put("email", email)
            }.toString()
        )
    }

    fun submitOtp(code: String, remember: Boolean) {
        if (fastBaseUrl != null) { submitFastOtp(code, remember); return }
        val s = _state.value as? RemoteAuthState.OtpInput
        if (remember && s != null && s.clientId.isNotEmpty()) {
            EmailStore.remember(getApplication(), s.clientId, s.serviceName, s.email)
        }
        val transport = activeTransport
        if (transport == null) {
            Sentry.captureMessage("submitOtp: activeTransport is null — email_otp frame dropped")
            return
        }
        transport.sendFrame(
            org.json.JSONObject().apply {
                put("type", "email_otp"); put("code", code); put("remember", remember)
            }.toString()
        )
    }

    // ── Fast mode email / OTP (HTTP, no WebSocket) ───────────────────────────

    fun submitFastEmail(email: String, remember: Boolean) {
        val s = _state.value as? RemoteAuthState.EmailInput ?: return
        _state.value = s.copy(pendingEmail = email, remember = remember)
        val baseUrl = fastBaseUrl ?: return
        val sessionToken = fastSessionToken ?: return
        viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    JSONObject().apply {
                        put("session", sessionToken)
                        put("email", email)
                    }.toString().toRequestBody(JSON_MEDIA)
                }
                val (respCode, respBody) = withContext(Dispatchers.IO) {
                    val resp = fastHttpClient.newCall(
                        Request.Builder().url("$baseUrl/v3/local-auth/email").post(body).build()
                    ).execute()
                    resp.code to resp.body!!.string()
                }
                if (respCode != 200) {
                    _state.value = RemoteAuthState.Error("email_submit_failed: $respCode")
                    return@launch
                }
                val json = JSONObject(respBody)
                when (json.optString("status")) {
                    "otp_sent" -> _state.value = RemoteAuthState.OtpInput(email, clientId = s.clientId, serviceName = s.serviceName, remember = remember)
                    else -> {
                        if (json.has("code")) {
                            // Prefill reused — OTP skipped — save email now since submitFastOtp won't be called
                            if (remember && s.clientId.isNotEmpty()) {
                                EmailStore.remember(getApplication(), s.clientId, s.serviceName, email.trim())
                            }
                            _state.value = RemoteAuthState.Success(saveDialog = pendingSaveDialog)
                            pendingSaveDialog = null
                            fastBaseUrl = null; fastSessionToken = null
                        } else {
                            _state.value = RemoteAuthState.Error("fast_email_unexpected_response")
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = RemoteAuthState.Error("fast_email_network: ${e.message}")
            }
        }
    }

    fun submitFastOtp(code: String, remember: Boolean) {
        val s = _state.value as? RemoteAuthState.OtpInput ?: return
        val baseUrl = fastBaseUrl ?: return
        val sessionToken = fastSessionToken ?: return
        if (remember && s.clientId.isNotEmpty()) {
            EmailStore.remember(getApplication(), s.clientId, s.serviceName, s.email)
        }
        viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    JSONObject().apply {
                        put("session", sessionToken)
                        put("code", code)
                    }.toString().toRequestBody(JSON_MEDIA)
                }
                val (respCode, respBody) = withContext(Dispatchers.IO) {
                    val resp = fastHttpClient.newCall(
                        Request.Builder().url("$baseUrl/v3/local-auth/otp").post(body).build()
                    ).execute()
                    resp.code to resp.body!!.string()
                }
                val json = JSONObject(respBody)
                when {
                    respCode == 200 && json.has("code") -> {
                        _state.value = RemoteAuthState.Success(saveDialog = pendingSaveDialog)
                        pendingSaveDialog = null
                        fastBaseUrl = null; fastSessionToken = null
                    }
                    json.optString("status") == "otp_invalid" -> {
                        _state.value = s.copy(invalidCount = s.invalidCount + 1)
                    }
                    else -> _state.value = RemoteAuthState.Error("otp_failed: $respCode")
                }
            } catch (e: Exception) {
                _state.value = RemoteAuthState.Error("fast_otp_network: ${e.message}")
            }
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    fun retry() {
        activeAuthSpan?.end(); activeAuthSpan = null; activeSpanCtx = null; activeSpanTraceId = null
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
