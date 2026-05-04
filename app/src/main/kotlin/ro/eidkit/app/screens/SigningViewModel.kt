package ro.eidkit.app.screens

import android.app.Application
import android.net.Uri
import android.nfc.tech.IsoDep
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.StoreOp
import ro.eidkit.app.pdf.PadesContext
import ro.eidkit.app.pdf.PdfSigner
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.SignEvent
import ro.eidkit.sdk.model.SignResult

sealed class SigningState {

    data object DocumentPicker : SigningState()

    data class Input(
        val documentName: String,
        val padesCtx: PadesContext,
        val can: String = "",
        val pin: String = "",
    ) : SigningState() {
        val canSubmit get() = can.length == 6 && pin.length == 6
    }

    data class Scanning(val completedSteps: List<SignEvent>, val activeStep: SignEvent?) : SigningState()

    data class AwaitingOutputUri(
        val padesCtx: PadesContext,
        val signResult: SignResult,
    ) : SigningState()

    data class Success(
        val outputUri: Uri,
        val documentName: String,
        val signResult: SignResult,
        val saveDialog: SaveDialogState? = null,
    ) : SigningState()

    data class Error(val message: String) : SigningState()
}

class SigningViewModel(app: Application) : AndroidViewModel(app) {

    private val pdfSigner = PdfSigner(app)

    private val _state = MutableStateFlow<SigningState>(SigningState.DocumentPicker)
    val state: StateFlow<SigningState> = _state.asStateFlow()

    private var snapshot = Triple<String?, String?, String?>(null, null, null)

    // ── Biometric load ────────────────────────────────────────────────────────

    fun tryBiometricLoad(activity: FragmentActivity) {
        if (!BiometricStore.hasCredentials(activity)) return
        BiometricStore.load(
            activity  = activity,
            onSuccess = { can, _, pin2 ->
                snapshot = Triple(can, null, pin2)
                val s = _state.value as? SigningState.Input ?: return@load
                _state.value = s.copy(
                    can = can  ?: s.can,
                    pin = pin2 ?: s.pin,
                )
            },
            onFail = {},
        )
    }

    // ── Document selection ────────────────────────────────────────────────────

    fun onDocumentSelected(uri: Uri, displayName: String, signedPrefix: String) {
        viewModelScope.launch {
            pdfSigner.prepare(uri, displayName, signedPrefix).fold(
                onSuccess = { ctx ->
                    val s = SigningState.Input(documentName = displayName, padesCtx = ctx)
                    _state.value = s.copy(
                        can = snapshot.first  ?: "",
                        pin = snapshot.third  ?: "",
                    )
                },
                onFailure = { e -> _state.value = SigningState.Error("generic:${e.message}") },
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

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? SigningState.Input ?: return
        if (!input.canSubmit) return

        val scannedCan = input.can
        val scannedPin = input.pin
        pendingSaveDialog = buildSaveDialog(scannedCan, scannedPin)

        _state.value = SigningState.Scanning(emptyList(), null)

        viewModelScope.launch {
            try {
                EidKit.signer(can = scannedCan)
                    .sign(input.padesCtx.signedAttrsHash, signingPin = scannedPin)
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
            pdfSigner.complete(
                ctx              = awaiting.padesCtx,
                signatureBytes   = awaiting.signResult.signature,
                certificateBytes = awaiting.signResult.certificate,
                outputUri        = uri,
            ).fold(
                onSuccess = {
                    // We need the scanned values for the dialog — store them temporarily in the
                    // AwaitingOutputUri state isn't ideal, so we carry them via a local variable
                    // captured from the last onCardDetected call.
                    _state.value = SigningState.Success(
                        outputUri    = uri,
                        documentName = awaiting.padesCtx.suggestedFilename,
                        signResult   = awaiting.signResult,
                        saveDialog   = pendingSaveDialog,
                    )
                    pendingSaveDialog = null
                },
                onFailure = { e -> _state.value = SigningState.Error("generic:${e.message}") },
            )
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────

    fun onSaveDialogToggle(saveCan: Boolean? = null, savePin: Boolean? = null, savePin2: Boolean? = null) {
        val s = _state.value as? SigningState.Success ?: return
        val d = s.saveDialog ?: return
        _state.value = s.copy(saveDialog = d.copy(
            saveCan  = saveCan  ?: d.saveCan,
            savePin  = savePin  ?: d.savePin,
            savePin2 = savePin2 ?: d.savePin2,
        ))
    }

    fun dismissSaveDialog() {
        val s = _state.value as? SigningState.Success ?: return
        _state.value = s.copy(saveDialog = null)
    }

    fun neverAskSave() {
        BiometricStore.setNeverAsk(getApplication())
        dismissSaveDialog()
    }

    fun confirmSave(activity: FragmentActivity) {
        val s = _state.value as? SigningState.Success ?: return
        val d = s.saveDialog ?: return
        BiometricStore.save(
            activity = activity,
            can      = StoreOp.Write(if (d.saveCan)  d.scannedCan  else null),
            pin      = StoreOp.Skip,
            pin2     = StoreOp.Write(if (d.savePin2) d.scannedPin2 else null),
            onDone   = {
                _state.value = (_state.value as? SigningState.Success)?.copy(saveDialog = null) ?: _state.value
                snapshot = Triple(
                    if (d.saveCan)  d.scannedCan  else null,
                    snapshot.second,
                    if (d.savePin2) d.scannedPin2 else null,
                )
            },
        )
    }

    // ── Other ─────────────────────────────────────────────────────────────────

    fun clearDocument() { _state.value = SigningState.DocumentPicker }

    fun retry() {
        pendingSaveDialog = null
        _state.value = SigningState.DocumentPicker
    }

    // ── Private ───────────────────────────────────────────────────────────────

    // Bridge between onCardDetected (where we know scanned values) and onOutputUriSelected
    private var pendingSaveDialog: SaveDialogState? = null

    private fun buildSaveDialog(scannedCan: String, scannedPin2: String): SaveDialogState? {
        val canChanged  = scannedCan  != (snapshot.first ?: "")
        val pin2Changed = scannedPin2 != (snapshot.third ?: "")
        if (!canChanged && !pin2Changed) return null
        return SaveDialogState(
            scannedCan  = scannedCan,
            scannedPin  = "",
            scannedPin2 = scannedPin2,
            saveCan     = snapshot.first != null || canChanged,
            savePin     = false,
            savePin2    = snapshot.third != null || pin2Changed,
            showPin2Row = true,
        )
    }
}
