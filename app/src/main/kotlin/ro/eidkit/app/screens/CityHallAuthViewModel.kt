package ro.eidkit.app.screens

import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ReadEvent
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed class CityHallAuthState {
    /** Deep link received but not yet parsed (brief transition state). */
    object Idle : CityHallAuthState()

    /** Waiting for CAN + PIN from user before card tap. */
    data class Input(
        val can: String = "",
        val pin: String = "",
        val sessionId: String = "",
        val challengeHex: String = "",
        val callbackBase: String = "",
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

    private var lastInput: CityHallAuthState.Input? = null

    fun initFromDeepLink(sessionId: String, challengeHex: String, callbackBase: String) {
        val input = CityHallAuthState.Input(
            can          = "143859",
            pin          = "1357",
            sessionId    = sessionId,
            challengeHex = challengeHex,
            callbackBase = callbackBase,
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
            try {
                var finalFirstName = ""
                var finalLastName  = ""
                var activeAuthSig  = ""
                var activeAuthCert = ""
                var cnp            = ""
                var address        = ""

                EidKit.reader(can = input.can)
                    .withPersonalData(pin = input.pin)
                    .withActiveAuth()
                    .readFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is ReadEvent.Done -> {
                                val result = event.result
                                finalFirstName = result.identity?.firstName ?: ""
                                finalLastName  = result.identity?.lastName  ?: ""
                                cnp            = result.identity?.cnp        ?: ""
                                address        = result.personalData?.address ?: ""

                                val proof = result.claim?.activeAuthProof
                                if (proof != null) {
                                    activeAuthSig  = proof.signature.toHex()
                                    activeAuthCert = proof.certificate.toBase64()
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

                postCallback(
                    callbackBase  = input.callbackBase,
                    sessionId     = input.sessionId,
                    firstName     = finalFirstName,
                    lastName      = finalLastName,
                    cnp           = cnp,
                    address       = address,
                    activeAuthSig = activeAuthSig,
                    activeAuthCert= activeAuthCert,
                )

                _state.value = CityHallAuthState.Success(finalFirstName, finalLastName)

            } catch (e: CeiError.WrongPin) {
                _state.value = CityHallAuthState.Error("wrong_pin:${e.attemptsRemaining}")
            } catch (e: CeiError.PinBlocked) {
                _state.value = CityHallAuthState.Error("pin_blocked")
            } catch (e: CeiError.CardLost) {
                _state.value = CityHallAuthState.Error("card_lost")
            } catch (e: CeiError.PaceFailure) {
                _state.value = CityHallAuthState.Error("pace_failed")
            } catch (e: CeiError) {
                _state.value = CityHallAuthState.Error("generic:${e.message}")
            } catch (e: Exception) {
                _state.value = CityHallAuthState.Error("network:${e.message}")
            }
        }
    }

    fun retry() {
        _state.value = lastInput ?: CityHallAuthState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun postCallback(
        callbackBase: String,
        sessionId: String,
        firstName: String,
        lastName: String,
        cnp: String,
        address: String,
        activeAuthSig: String,
        activeAuthCert: String,
    ) = withContext(Dispatchers.IO) {
        val url  = URL("$callbackBase/callback")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000

        val body = JSONObject().apply {
            put("sessionId",     sessionId)
            put("firstName",     firstName)
            put("lastName",      lastName)
            put("cnp",           cnp)
            put("address",       address)
            put("activeAuthSig", activeAuthSig)
            put("activeAuthCert",activeAuthCert)
        }.toString()

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        if (code != 200) throw Exception("Callback failed: HTTP $code")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
}
