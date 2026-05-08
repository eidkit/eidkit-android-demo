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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.StoreOp
import ro.eidkit.app.EidKitApp
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ActiveAuthStatus
import ro.eidkit.sdk.model.ReadEvent
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed class RemoteAuthState {
    object Idle : RemoteAuthState()

    data class Input(
        val can: String = "",
        val pin: String = "",
        val sessionToken: String = "",
        val callbackUrl: String = "",
        val serviceName: String = "",
        val nonce: String = "",
        val traceparent: String? = null,
    ) : RemoteAuthState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
    }

    data class Scanning(
        val completedSteps: List<ReadEvent>,
        val activeStep: ReadEvent?,
    ) : RemoteAuthState()

    object Posting : RemoteAuthState()

    data class Success(
        val firstName: String,
        val lastName: String,
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

    fun initFromDeepLink(sessionToken: String, callbackUrl: String, serviceName: String = "", nonce: String = "", traceparent: String? = null) {
        _serviceName.value = serviceName
        val input = RemoteAuthState.Input(
            sessionToken = sessionToken,
            callbackUrl  = callbackUrl,
            serviceName  = serviceName,
            nonce        = nonce,
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
            try {
                var firstName      = ""
                var familyName     = ""
                var cnp            = ""
                var dateOfBirth    = ""
                var address        = ""
                var documentNumber = ""
                var documentSeries = ""
                var documentExpiry = ""
                var documentIssuer = ""
                var dscCertBase64  = ""
                var rawDg1Base64   = ""
                var rawSodBase64   = ""
                var aaSignatureBase64 = ""
                var aaCertBase64   = ""
                var cardSerialNumber = ""
                var passedOnDevicePassiveAuth = false
                var passedOnDeviceActiveAuth  = false
                var caTerminalPublicKeyBase64 = ""
                var caEphemeralPrivateKeyBase64 = ""
                var caSharedSecretXBase64 = ""
                var rawDg14Base64 = ""

                val nonceBytes = if (input.nonce.isNotEmpty())
                    input.nonce.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                else null

                val reader = EidKit.reader(can = scannedCan)
                    .withPersonalData(pin = scannedPin)
                    .withChipAuth()
                if (nonceBytes != null) reader.withActiveAuth(nonce = nonceBytes)
                else reader.withActiveAuth(true)

                reader.readFlow(isoDep).collect { event ->
                    when (event) {
                        is ReadEvent.Done -> {
                            val result = event.result
                            val identity     = result.identity
                            val personalData = result.personalData

                            firstName   = identity?.firstName ?: ""
                            familyName  = identity?.lastName  ?: ""
                            cnp         = identity?.cnp       ?: ""
                            dateOfBirth = identity?.dateOfBirth?.toIso8601() ?: ""

                            address        = personalData?.address          ?: ""
                            documentIssuer = personalData?.issuingAuthority ?: ""
                            documentExpiry = personalData?.expiryDate?.toIso8601() ?: ""

                            val rawDocNumber = personalData?.documentNumber ?: ""
                            val splitAt = rawDocNumber.indexOfFirst { it.isDigit() }
                            if (splitAt > 0) {
                                documentSeries = rawDocNumber.substring(0, splitAt)
                                documentNumber = rawDocNumber.substring(splitAt)
                            } else {
                                documentNumber = rawDocNumber
                            }

                            passedOnDevicePassiveAuth = result.passiveAuth is ro.eidkit.sdk.model.PassiveAuthStatus.Valid
                            passedOnDeviceActiveAuth  = result.activeAuth is ActiveAuthStatus.Verified

                            val claim = result.claim
                            val proof = claim?.passiveAuthProof
                            if (proof != null) {
                                dscCertBase64 = proof.docSigningCert.toBase64()
                                rawSodBase64  = proof.sodBytes.toBase64()
                            }
                            rawDg1Base64 = claim?.rawDg1?.toBase64() ?: ""

                            val aaProof = claim?.activeAuthProof
                            aaSignatureBase64 = aaProof?.signature?.toBase64() ?: ""
                            aaCertBase64      = aaProof?.certificate?.toBase64() ?: ""
                            cardSerialNumber  = claim?.cardSerialNumber ?: ""

                            val caProof = claim?.chipAuthProof
                            caTerminalPublicKeyBase64   = caProof?.terminalPublicKey?.toBase64() ?: ""
                            caEphemeralPrivateKeyBase64 = caProof?.ephemeralPrivateKey?.toBase64() ?: ""
                            caSharedSecretXBase64       = caProof?.sharedSecretX?.toBase64() ?: ""
                            rawDg14Base64               = caProof?.rawDg14?.toBase64() ?: ""
                        }
                        else -> {
                            val current = _state.value as? RemoteAuthState.Scanning ?: return@collect
                            val completed = if (current.activeStep != null)
                                current.completedSteps + current.activeStep
                            else
                                current.completedSteps
                            _state.value = RemoteAuthState.Scanning(completed, event)
                        }
                    }
                }

                _state.value = RemoteAuthState.Posting

                postSessionComplete(
                    callbackUrl              = input.callbackUrl,
                    sessionToken             = input.sessionToken,
                    cnp                      = cnp,
                    name                     = "$firstName $familyName".trim(),
                    givenName                = firstName,
                    familyName               = familyName,
                    birthdate                = dateOfBirth,
                    address                  = address,
                    certificate              = dscCertBase64,
                    documentNumber           = documentNumber,
                    documentSeries           = documentSeries,
                    documentExpiry           = documentExpiry,
                    documentIssuer           = documentIssuer,
                    rawDg1                   = rawDg1Base64,
                    sodBytes                 = rawSodBase64,
                    dscCert                  = dscCertBase64,
                    aaChallenge              = input.nonce,
                    aaSignature              = aaSignatureBase64,
                    aaCertificate            = aaCertBase64,
                    cardSerialNumber         = cardSerialNumber,
                    passedOnDevicePassiveAuth = passedOnDevicePassiveAuth,
                    passedOnDeviceActiveAuth  = passedOnDeviceActiveAuth,
                    caTerminalPublicKey      = caTerminalPublicKeyBase64,
                    caEphemeralPrivateKey    = caEphemeralPrivateKeyBase64,
                    caSharedSecretX          = caSharedSecretXBase64,
                    rawDg14                  = rawDg14Base64,
                )

                _state.value = RemoteAuthState.Success(
                    firstName  = firstName,
                    lastName   = familyName,
                    saveDialog = pendingSaveDialog,
                )
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

    private suspend fun postSessionComplete(
        callbackUrl: String,
        sessionToken: String,
        cnp: String,
        name: String,
        givenName: String,
        familyName: String,
        birthdate: String,
        address: String,
        certificate: String,
        documentNumber: String,
        documentSeries: String,
        documentExpiry: String,
        documentIssuer: String,
        rawDg1: String,
        sodBytes: String,
        dscCert: String,
        aaChallenge: String,
        aaSignature: String,
        aaCertificate: String,
        cardSerialNumber: String,
        passedOnDevicePassiveAuth: Boolean,
        passedOnDeviceActiveAuth: Boolean,
        caTerminalPublicKey: String,
        caEphemeralPrivateKey: String,
        caSharedSecretX: String,
        rawDg14: String,
    ) = withContext(Dispatchers.IO) {
        val conn = URL(callbackUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        // Propagate trace context so POST /session/complete appears in the same Sentry trace
        val spanCtx = io.opentelemetry.api.trace.Span.current().spanContext
        if (spanCtx.isValid) {
            val traceId  = spanCtx.traceId
            val spanId   = spanCtx.spanId
            val flags    = spanCtx.traceFlags.asHex()
            val sampled  = if (spanCtx.traceFlags.isSampled) "1" else "0"
            conn.setRequestProperty("traceparent", "00-$traceId-$spanId-$flags")
            conn.setRequestProperty("sentry-trace", "$traceId-$spanId-$sampled")
        }

        val body = JSONObject().apply {
            put("sessionToken",              sessionToken)
            put("cnp",                       cnp)
            put("name",                      name)
            put("givenName",                 givenName)
            put("familyName",                familyName)
            put("birthdate",                 birthdate)
            put("address",                   address)
            put("certificate",               certificate)
            put("documentNumber",            documentNumber)
            put("documentSeries",            documentSeries)
            put("documentExpiry",            documentExpiry)
            put("documentIssuer",            documentIssuer)
            put("rawDg1",                    rawDg1)
            put("sodBytes",                  sodBytes)
            put("dscCert",                   dscCert)
            put("aaChallenge",               aaChallenge)
            put("aaSignature",               aaSignature)
            put("aaCertificate",             aaCertificate)
            put("cardSerialNumber",          cardSerialNumber)
            put("passedOnDevicePassiveAuth", passedOnDevicePassiveAuth)
            put("passedOnDeviceActiveAuth",  passedOnDeviceActiveAuth)
            put("caTerminalPublicKey",       caTerminalPublicKey)
            put("caEphemeralPrivateKey",     caEphemeralPrivateKey)
            put("caSharedSecretX",           caSharedSecretX)
            put("rawDg14",                   rawDg14)
        }.toString()

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        if (code != 200) throw Exception("Session complete failed: HTTP $code")
    }

    private fun String.toIso8601(): String {
        if (length != 8) return this
        return "${substring(4, 8)}-${substring(2, 4)}-${substring(0, 2)}"
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
}
