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
import ro.eidkit.app.pdf.KycPdfGenerator
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ReadEvent
import ro.eidkit.sdk.model.ReadResult
import java.time.LocalDateTime

sealed class ExportState {
    data object Idle      : ExportState()
    data object Exporting : ExportState()
    data class  Done(val uri: Uri)            : ExportState()
    data class  Failed(val message: String)   : ExportState()
}

sealed class KycState {
    data class Input(
        val can: String = "",
        val pin: String = "",
        val includePhoto: Boolean = false,
        val includeSignature: Boolean = false,
    ) : KycState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
    }

    data class Scanning(
        val completedSteps: List<ReadEvent>,
        val activeStep: ReadEvent?,
        val includePhoto: Boolean,
        val includeSignature: Boolean,
    ) : KycState()

    data class Success(
        val result: ReadResult,
        val exportState: ExportState = ExportState.Idle,
    ) : KycState()

    data class Error(val message: String) : KycState()
}

class KycViewModel(app: Application) : AndroidViewModel(app) {

    private val pdfGenerator = KycPdfGenerator(app)

    private val _state = MutableStateFlow<KycState>(KycState.Input())
    val state: StateFlow<KycState> = _state.asStateFlow()

    fun onCanChange(v: String) {
        val s = _state.value as? KycState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? KycState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    fun onPhotoToggle(v: Boolean) {
        val s = _state.value as? KycState.Input ?: return
        _state.value = s.copy(includePhoto = v)
    }

    fun onSignatureToggle(v: Boolean) {
        val s = _state.value as? KycState.Input ?: return
        _state.value = s.copy(includeSignature = v)
    }

    /** Called from MainActivity when a card is tapped while this screen is active. */
    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? KycState.Input ?: return
        if (!input.canSubmit) return

        _state.value = KycState.Scanning(
            completedSteps   = emptyList(),
            activeStep       = null,
            includePhoto     = input.includePhoto,
            includeSignature = input.includeSignature,
        )

        viewModelScope.launch {
            try {
                EidKit.reader(can = input.can)
                    .withPersonalData(pin = input.pin)
                    .withActiveAuth()
                    .withPhoto(input.includePhoto)
                    .withSignatureImage(input.includeSignature)
                    .readFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is ReadEvent.Done -> _state.value = KycState.Success(event.result)
                            else -> {
                                val current = _state.value as? KycState.Scanning ?: return@collect
                                val completed = if (current.activeStep != null)
                                    current.completedSteps + current.activeStep
                                else
                                    current.completedSteps
                                _state.value = current.copy(completedSteps = completed, activeStep = event)
                            }
                        }
                    }
            } catch (e: CeiError.WrongPin) {
                _state.value = KycState.Error("wrong_pin:${e.attemptsRemaining}")
            } catch (e: CeiError.PinBlocked) {
                _state.value = KycState.Error("pin_blocked")
            } catch (e: CeiError.CardLost) {
                _state.value = KycState.Error("card_lost")
            } catch (e: CeiError.PaceFailure) {
                _state.value = KycState.Error("pace_failed")
            } catch (e: CeiError) {
                _state.value = KycState.Error("generic:${e.message}")
            }
        }
    }

    fun exportToPdf() {
        val success = _state.value as? KycState.Success ?: return
        if (success.exportState is ExportState.Exporting) return  // already running

        _state.value = success.copy(exportState = ExportState.Exporting)

        viewModelScope.launch {
            val result = pdfGenerator.generate(success.result, LocalDateTime.now())
            val current = _state.value as? KycState.Success ?: return@launch
            _state.value = current.copy(
                exportState = result.fold(
                    onSuccess = { uri -> ExportState.Done(uri) },
                    onFailure = { e   -> ExportState.Failed(e.message ?: "unknown error") },
                )
            )
        }
    }

    fun retry() {
        _state.value = KycState.Input()
    }
}
