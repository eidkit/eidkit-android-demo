package ro.eidkit.app.screens

import android.nfc.tech.IsoDep
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.StoreOp
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ReadEvent
import ro.eidkit.sdk.model.ReadResult

sealed class AuthState {
    data class Input(
        val can: String = "",
        val pin: String = "",
    ) : AuthState() {
        val canSubmit get() = can.length == 6 && pin.length == 4
    }

    data class Scanning(val completedSteps: List<ReadEvent>, val activeStep: ReadEvent?) : AuthState()

    data class Success(
        val result: ReadResult,
        val saveDialog: SaveDialogState? = null,
    ) : AuthState()

    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Input())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var snapshot = Triple<String?, String?, String?>(null, null, null)

    // ── Biometric load ────────────────────────────────────────────────────────

    fun tryBiometricLoad(activity: FragmentActivity) {
        if (!BiometricStore.hasCredentials(activity)) return
        BiometricStore.load(
            activity  = activity,
            onSuccess = { can, pin, _ ->
                snapshot = Triple(can, pin, null)
                val s = _state.value as? AuthState.Input ?: return@load
                _state.value = s.copy(
                    can = can ?: s.can,
                    pin = pin ?: s.pin,
                )
            },
            onFail = {},
        )
    }

    // ── Input changes ─────────────────────────────────────────────────────────

    fun onCanChange(v: String) {
        val s = _state.value as? AuthState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? AuthState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? AuthState.Input ?: return
        if (!input.canSubmit) return

        val scannedCan = input.can
        val scannedPin = input.pin

        _state.value = AuthState.Scanning(emptyList(), null)

        viewModelScope.launch {
            try {
                EidKit.reader(can = scannedCan)
                    .withPersonalData(pin = scannedPin)
                    .withActiveAuth()
                    .readFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is ReadEvent.Done -> {
                                _state.value = AuthState.Success(
                                    result     = event.result,
                                    saveDialog = buildSaveDialog(scannedCan, scannedPin),
                                )
                            }
                            else -> {
                                val current = _state.value as? AuthState.Scanning ?: return@collect
                                val completed = if (current.activeStep != null)
                                    current.completedSteps + current.activeStep
                                else
                                    current.completedSteps
                                _state.value = AuthState.Scanning(completed, event)
                            }
                        }
                    }
            } catch (e: CeiError.WrongPin) {
                _state.value = AuthState.Error("wrong_pin:${e.attemptsRemaining}")
            } catch (e: CeiError.PinBlocked) {
                _state.value = AuthState.Error("pin_blocked")
            } catch (e: CeiError.CardLost) {
                _state.value = AuthState.Error("card_lost")
            } catch (e: CeiError.PaceFailure) {
                _state.value = AuthState.Error("pace_failed")
            } catch (e: CeiError) {
                _state.value = AuthState.Error("generic:${e.message}")
            }
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────

    fun onSaveDialogToggle(saveCan: Boolean? = null, savePin: Boolean? = null) {
        val s = _state.value as? AuthState.Success ?: return
        val d = s.saveDialog ?: return
        _state.value = s.copy(saveDialog = d.copy(
            saveCan = saveCan ?: d.saveCan,
            savePin = savePin ?: d.savePin,
        ))
    }

    fun dismissSaveDialog() {
        val s = _state.value as? AuthState.Success ?: return
        _state.value = s.copy(saveDialog = null)
    }

    fun neverAskSave(context: android.content.Context) {
        BiometricStore.setNeverAsk(context)
        dismissSaveDialog()
    }

    fun confirmSave(activity: FragmentActivity) {
        val s = _state.value as? AuthState.Success ?: return
        val d = s.saveDialog ?: return
        BiometricStore.save(
            activity = activity,
            can      = StoreOp.Write(if (d.saveCan) d.scannedCan else null),
            pin      = StoreOp.Write(if (d.savePin) d.scannedPin else null),
            pin2     = StoreOp.Skip,
            onDone   = {
                _state.value = (_state.value as? AuthState.Success)?.copy(saveDialog = null) ?: _state.value
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
        snapshot = Triple(null, null, null)
        _state.value = AuthState.Input()
    }

    // ── Private ───────────────────────────────────────────────────────────────

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
