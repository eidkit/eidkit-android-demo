package ro.eidkit.app.screens

import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ro.eidkit.app.EidKitApp
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.PassiveAuthStatus
import ro.eidkit.sdk.model.ReadEvent
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed class CityHallAuthState {
    object Idle : CityHallAuthState()

    data class Input(
        val can: String = "",
        val pin: String = "",
        val sessionToken: String = "",
        val callbackUrl: String = "",
        val serviceName: String = "",
    ) : CityHallAuthState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
    }

    data class Scanning(
        val completedSteps: List<ReadEvent>,
        val activeStep: ReadEvent?,
    ) : CityHallAuthState()

    object Posting : CityHallAuthState()

    data class Success(val firstName: String, val lastName: String) : CityHallAuthState()

    data class Error(val message: String) : CityHallAuthState()
}

class CityHallAuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<CityHallAuthState>(CityHallAuthState.Idle)
    val state: StateFlow<CityHallAuthState> = _state.asStateFlow()

    private val _serviceName = MutableStateFlow("")
    val serviceName: StateFlow<String> = _serviceName.asStateFlow()

    private var lastInput: CityHallAuthState.Input? = null

    fun initFromDeepLink(sessionToken: String, callbackUrl: String, serviceName: String = "") {
        _serviceName.value = serviceName
        val input = CityHallAuthState.Input(
            sessionToken = sessionToken,
            callbackUrl  = callbackUrl,
            serviceName  = serviceName,
        )
        lastInput = input
        _state.value = input
    }

    fun onCanChange(v: String) {
        val s = _state.value as? CityHallAuthState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? CityHallAuthState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? CityHallAuthState.Input ?: return
        if (!input.canSubmit) return

        _state.value = CityHallAuthState.Scanning(emptyList(), null)

        viewModelScope.launch {
            val span = EidKitApp.tracer.spanBuilder("cityhall_auth").startSpan()
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
                var passiveAuthValid = false
                var dscCertBase64  = ""

                EidKit.reader(can = input.can)
                    .withPersonalData(pin = input.pin)
                    .readFlow(isoDep)
                    .collect { event ->
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

                                passiveAuthValid = result.passiveAuth is PassiveAuthStatus.Valid

                                val proof = result.claim?.passiveAuthProof
                                if (proof != null) {
                                    dscCertBase64 = proof.docSigningCert.toBase64()
                                }
                            }
                            else -> {
                                val current = _state.value as? CityHallAuthState.Scanning ?: return@collect
                                val completed = if (current.activeStep != null)
                                    current.completedSteps + current.activeStep
                                else
                                    current.completedSteps
                                _state.value = CityHallAuthState.Scanning(completed, event)
                            }
                        }
                    }

                _state.value = CityHallAuthState.Posting

                postSessionComplete(
                    callbackUrl     = input.callbackUrl,
                    sessionToken    = input.sessionToken,
                    cnp             = cnp,
                    name            = "$firstName $familyName".trim(),
                    givenName       = firstName,
                    familyName      = familyName,
                    birthdate       = dateOfBirth,
                    address         = address,
                    passiveAuthValid = passiveAuthValid,
                    certificate     = dscCertBase64,
                    documentNumber  = documentNumber,
                    documentSeries  = documentSeries,
                    documentExpiry  = documentExpiry,
                    documentIssuer  = documentIssuer,
                )

                _state.value = CityHallAuthState.Success(firstName, familyName)
                span.setStatus(StatusCode.OK)

            } catch (e: CeiError.WrongPin) {
                _state.value = CityHallAuthState.Error("wrong_pin:${e.attemptsRemaining}")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PinBlocked) {
                _state.value = CityHallAuthState.Error("pin_blocked")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.CardLost) {
                _state.value = CityHallAuthState.Error("card_lost")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } catch (e: CeiError.PaceFailure) {
                _state.value = CityHallAuthState.Error("pace_failed")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } catch (e: CeiError) {
                _state.value = CityHallAuthState.Error("generic:${e.message}")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } catch (e: Exception) {
                _state.value = CityHallAuthState.Error("network:${e.message}")
                span.recordException(e); span.setStatus(StatusCode.ERROR)
            } finally {
                span.end()
            }
        }
    }

    fun retry() {
        _state.value = lastInput ?: CityHallAuthState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun postSessionComplete(
        callbackUrl: String,
        sessionToken: String,
        cnp: String,
        name: String,
        givenName: String,
        familyName: String,
        birthdate: String,
        address: String,
        passiveAuthValid: Boolean,
        certificate: String,
        documentNumber: String,
        documentSeries: String,
        documentExpiry: String,
        documentIssuer: String,
    ) = withContext(Dispatchers.IO) {
        val conn = URL(callbackUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000

        val body = JSONObject().apply {
            put("sessionToken",    sessionToken)
            put("cnp",             cnp)
            put("name",            name)
            put("givenName",       givenName)
            put("familyName",      familyName)
            put("birthdate",       birthdate)
            put("address",         address)
            put("passiveAuthValid", passiveAuthValid)
            put("certificate",     certificate)
            put("documentNumber",  documentNumber)
            put("documentSeries",  documentSeries)
            put("documentExpiry",  documentExpiry)
            put("documentIssuer",  documentIssuer)
        }.toString()

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        if (code != 200) throw Exception("Session complete failed: HTTP $code")
    }

    // DDMMYYYY → YYYY-MM-DD
    private fun String.toIso8601(): String {
        if (length != 8) return this
        return "${substring(4, 8)}-${substring(2, 4)}-${substring(0, 2)}"
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
}
