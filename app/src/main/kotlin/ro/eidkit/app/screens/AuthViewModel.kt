package ro.eidkit.app.screens

import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    data class Success(val result: ReadResult) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Input())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun onCanChange(v: String) {
        val s = _state.value as? AuthState.Input ?: return
        _state.value = s.copy(can = v)
    }

    fun onPinChange(v: String) {
        val s = _state.value as? AuthState.Input ?: return
        _state.value = s.copy(pin = v)
    }

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? AuthState.Input ?: return
        if (!input.canSubmit) return

        _state.value = AuthState.Scanning(emptyList(), null)

        viewModelScope.launch {
            try {
                EidKit.reader(can = input.can)
                    .withPersonalData(pin = input.pin)
                    .withActiveAuth()
                    .readFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is ReadEvent.Done -> _state.value = AuthState.Success(event.result)
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

    fun retry() {
        _state.value = AuthState.Input()
    }
}
