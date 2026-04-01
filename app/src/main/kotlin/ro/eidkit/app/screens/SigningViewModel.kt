package ro.eidkit.app.screens

import android.app.Application
import android.net.Uri
import android.nfc.tech.IsoDep
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.eidkit.app.pdf.PadesContext
import ro.eidkit.app.pdf.PdfSigner
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.SignEvent
import ro.eidkit.sdk.model.SignResult

sealed class SigningState {

    /** No document selected yet. */
    data object DocumentPicker : SigningState()

    /** PDF picked; PDFBox placeholder written; hash ready to send to card. */
    data class Input(
        val documentName: String,
        val padesCtx: PadesContext,
        val can: String = "",
        val pin: String = "",
    ) : SigningState() {
        val canSubmit get() = can.length == 6 && pin.length == 6
    }

    data class Scanning(val completedSteps: List<SignEvent>, val activeStep: SignEvent?) : SigningState()

    /** NFC done; waiting for user to pick a save location via CreateDocument. */
    data class AwaitingOutputUri(
        val padesCtx: PadesContext,
        val signResult: SignResult,
    ) : SigningState()

    data class Success(val outputUri: Uri, val documentName: String, val signResult: SignResult) : SigningState()
    data class Error(val message: String) : SigningState()
}

class SigningViewModel(app: Application) : AndroidViewModel(app) {

    private val pdfSigner = PdfSigner(app)

    private val _state = MutableStateFlow<SigningState>(SigningState.DocumentPicker)
    val state: StateFlow<SigningState> = _state.asStateFlow()

    fun onDocumentSelected(uri: Uri, displayName: String, signedPrefix: String) {
        viewModelScope.launch {
            // Phase 1: prepare PAdES — writes PDF placeholder and computes byte-range hash
            pdfSigner.prepare(uri, displayName, signedPrefix).fold(
                onSuccess = { ctx ->
                    _state.value = SigningState.Input(
                        documentName = displayName,
                        padesCtx     = ctx,
                    )
                },
                onFailure = { e ->
                    _state.value = SigningState.Error("generic:${e.message}")
                },
            )
        }
    }

    fun onCanChange(v: String) {
        val s = _state.value as? SigningState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? SigningState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? SigningState.Input ?: return
        if (!input.canSubmit) return

        _state.value = SigningState.Scanning(emptyList(), null)

        viewModelScope.launch {
            try {
                EidKit.signer(can = input.can)
                    .sign(input.padesCtx.signedAttrsHash, signingPin = input.pin)
                    .executeFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is SignEvent.Done -> _state.value = SigningState.AwaitingOutputUri(
                                padesCtx   = input.padesCtx,
                                signResult = event.result,
                            )
                            else -> {
                                val current = _state.value as? SigningState.Scanning ?: return@collect
                                val completed = if (current.activeStep != null)
                                    current.completedSteps + current.activeStep
                                else
                                    current.completedSteps
                                _state.value = SigningState.Scanning(completed, event)
                            }
                        }
                    }
            } catch (e: CeiError.WrongPin) {
                _state.value = SigningState.Error("wrong_pin:${e.attemptsRemaining}")
            } catch (e: CeiError.PinBlocked) {
                _state.value = SigningState.Error("pin_blocked")
            } catch (e: CeiError.CardLost) {
                _state.value = SigningState.Error("card_lost")
            } catch (e: CeiError.PaceFailure) {
                _state.value = SigningState.Error("pace_failed")
            } catch (e: CeiError) {
                _state.value = SigningState.Error("generic:${e.message}")
            }
        }
    }

    fun onOutputUriSelected(uri: Uri) {
        val awaiting = _state.value as? SigningState.AwaitingOutputUri ?: return
        viewModelScope.launch {
            // Phase 2: embed CMS signature and write final PDF
            pdfSigner.complete(
                ctx              = awaiting.padesCtx,
                signatureBytes   = awaiting.signResult.signature,
                certificateBytes = awaiting.signResult.certificate,
                outputUri        = uri,
            ).fold(
                onSuccess = {
                    _state.value = SigningState.Success(
                        outputUri    = uri,
                        documentName = awaiting.padesCtx.suggestedFilename,
                        signResult   = awaiting.signResult,
                    )
                },
                onFailure = { e ->
                    _state.value = SigningState.Error("generic:${e.message}")
                },
            )
        }
    }

    fun clearDocument() {
        _state.value = SigningState.DocumentPicker
    }

    fun retry() {
        _state.value = SigningState.DocumentPicker
    }
}
