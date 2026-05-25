package ro.eidkit.app.screens

import android.app.Application
import android.net.Uri
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.StoreOp
import ro.eidkit.app.pdf.KycPdfGenerator
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.error.CeiError
import ro.eidkit.sdk.model.ReadEvent
import ro.eidkit.sdk.model.ReadResult
import java.time.LocalDateTime

sealed class ExportState {
    data object Idle      : ExportState()
    data object Exporting : ExportState()
    data class  Done(val uri: Uri)          : ExportState()
    data class  Failed(val message: String) : ExportState()
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
        val saveDialog: SaveDialogState? = null,
    ) : KycState()

    data class Error(val message: String) : KycState()
}

class KycViewModel(app: Application) : AndroidViewModel(app) {

    private val pdfGenerator = KycPdfGenerator(app)

    private val _state = MutableStateFlow<KycState>(KycState.Input())
    val state: StateFlow<KycState> = _state.asStateFlow()

    // What was in the store when this screen opened (null = not stored)
    private var snapshot = Triple<String?, String?, String?>(null, null, null)

    // ── Biometric load ────────────────────────────────────────────────────────

    fun tryBiometricLoad(activity: FragmentActivity) {
        if (!BiometricStore.hasCredentials(activity)) return
        BiometricStore.load(
            activity  = activity,
            onSuccess = { can, pin, _ ->
                snapshot = Triple(can, pin, null)
                val s = _state.value as? KycState.Input ?: return@load
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

    // ── NFC ───────────────────────────────────────────────────────────────────

    fun onCardDetected(isoDep: IsoDep) {
        val input = _state.value as? KycState.Input ?: return
        if (input.can.length == 6 && input.pin.isEmpty()) { probeDg15(isoDep); return }
        if (!input.canSubmit) return

        val scannedCan = input.can
        val scannedPin = input.pin

        _state.value = KycState.Scanning(
            completedSteps   = emptyList(),
            activeStep       = null,
            includePhoto     = input.includePhoto,
            includeSignature = input.includeSignature,
        )

        viewModelScope.launch {
            try {
                EidKit.reader(can = scannedCan)
                    .withPersonalData(pin = scannedPin)
                    .withActiveAuth()
                    .withPhoto(input.includePhoto)
                    .withSignatureImage(input.includeSignature)
                    .readFlow(isoDep)
                    .collect { event ->
                        when (event) {
                            is ReadEvent.Done -> {
                                _state.value = KycState.Success(
                                    result     = event.result,
                                    saveDialog = buildSaveDialog(scannedCan, scannedPin),
                                )
                            }
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

    // ── Save dialog ───────────────────────────────────────────────────────────

    fun onSaveDialogToggle(saveCan: Boolean? = null, savePin: Boolean? = null) {
        val s = _state.value as? KycState.Success ?: return
        val d = s.saveDialog ?: return
        _state.value = s.copy(saveDialog = d.copy(
            saveCan = saveCan ?: d.saveCan,
            savePin = savePin ?: d.savePin,
        ))
    }

    fun dismissSaveDialog() {
        val s = _state.value as? KycState.Success ?: return
        _state.value = s.copy(saveDialog = null)
    }

    fun neverAskSave() {
        BiometricStore.setNeverAsk(getApplication())
        dismissSaveDialog()
    }

    fun confirmSave(activity: FragmentActivity) {
        val s = _state.value as? KycState.Success ?: return
        val d = s.saveDialog ?: return
        BiometricStore.save(
            activity = activity,
            can      = StoreOp.Write(if (d.saveCan) d.scannedCan else null),
            pin      = StoreOp.Write(if (d.savePin) d.scannedPin else null),
            pin2     = StoreOp.Skip,
            onDone   = {
                _state.value = (_state.value as? KycState.Success)?.copy(saveDialog = null) ?: _state.value
                snapshot = Triple(
                    if (d.saveCan) d.scannedCan else null,
                    if (d.savePin) d.scannedPin else null,
                    snapshot.third,
                )
            },
        )
    }

    // ── PDF export ────────────────────────────────────────────────────────────

    fun exportToPdf() {
        val success = _state.value as? KycState.Success ?: return
        if (success.exportState is ExportState.Exporting) return

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
        snapshot = Triple(null, null, null)
        _state.value = KycState.Input()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    // ── D003 + CE81/CE8E serial probe (debug only) ───────────────────────────
    // Enter CAN only (leave PIN empty), tap card → logs to Logcat tag "D003Probe"
    // Tests: AID1 PACE → read D003 (card serial) → read CE8E cert serial → compare

    fun probeDg15(isoDep: IsoDep) {
        val input = _state.value as? KycState.Input ?: return
        if (input.can.length != 6 || input.pin.isNotEmpty()) return
        viewModelScope.launch {
            // Use SDK readFlow (no PIN) to get ICAO data (DG1, DG14) via the proven SM path
            EidKit.reader(can = input.can)
                .withActiveAuth()
                .readFlow(isoDep)
                .collect { event ->
                    if (event is ReadEvent.Done) {
                        val claim = event.result.claim
                        Log.d("CardProbe", "=== ICAO data ===")
                        // Parse MRZ from raw DG1 bytes
                        claim?.rawDg1?.let { dg1bytes ->
                            runCatching {
                                val mrz = DG1File(dg1bytes.inputStream()).mrzInfo
                                Log.d("CardProbe", "doc#: ${mrz.documentNumber}")
                                Log.d("CardProbe", "CNP: ${mrz.personalNumber}")
                                Log.d("CardProbe", "name: ${mrz.secondaryIdentifier} ${mrz.primaryIdentifier}")
                                Log.d("CardProbe", "DOB: ${mrz.dateOfBirth}")
                                Log.d("CardProbe", "gender: ${mrz.gender}")
                            }.onFailure { Log.d("CardProbe", "DG1 parse error: ${it.message}") }
                        } ?: Log.d("CardProbe", "rawDg1: null")
                        claim?.chipAuthProof?.rawDg14?.let { dg14bytes ->
                            runCatching {
                                val caKeys = DG14File(dg14bytes.inputStream()).securityInfos
                                    .filterIsInstance<ChipAuthenticationPublicKeyInfo>()
                                caKeys.forEachIndexed { i, k ->
                                    Log.d("CardProbe", "DG14 CA pubkey[$i]: ${k.subjectPublicKey.encoded.joinToString("") { b -> "%02X".format(b) }}")
                                }
                            }.onFailure { Log.d("CardProbe", "DG14 parse error: ${it.message}") }
                        } ?: Log.d("CardProbe", "rawDg14: null (no chipAuthProof)")
                    }
                }

            // GenPKI PACE → CE8E serial (separate NFC session)
            withContext(Dispatchers.IO) {
                runCatching {
                    isoDep.timeout = 15000
                    isoDep.connect()
                    val aid1 = "00A4040C10A000000077030C60000000FE0000050000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    isoDep.transceive(aid1)
                    val cs = CardService.getInstance(isoDep)
                    cs.open()
                    val ps = PassportService(cs, 256, 0xDF, true, false)
                    ps.open()
                    val ca = CardAccessFile(ps.getInputStream(284.toShort()))
                    val pi = ca.securityInfos.filterIsInstance<PACEInfo>().first()
                    ps.doPACE(PACEKeySpec.createCANKey(input.can), pi.objectIdentifier, PACEInfo.toParameterSpec(pi.parameterId), null)
                    Log.d("CardProbe", "GenPKI PACE OK")

                    // CE8E via getInputStream — works in GenPKI context
                    val ce8eStream = runCatching { ps.getInputStream(0xCE8E.toShort()) }.getOrNull()
                    if (ce8eStream != null) {
                        val bytes = ce8eStream.readBytes()
                        Log.d("CardProbe", "CE8E raw (${bytes.size} bytes): ${bytes.joinToString("") { "%02X".format(it) }}")
                        val idx = bytes.indices.firstOrNull { i ->
                            i + 3 <= bytes.size && bytes[i] == 0x55.toByte() && bytes[i+1] == 0x04.toByte() && bytes[i+2] == 0x05.toByte()
                        }
                        if (idx != null) {
                            val strLen = bytes[idx + 4].toInt() and 0xFF
                            Log.d("CardProbe", "CE8E serialNumber: ${String(bytes, idx + 5, strLen, Charsets.US_ASCII)}")
                        } else Log.d("CardProbe", "CE8E serialNumber OID not found")
                    } else Log.d("CardProbe", "CE8E: not found")

                }.onFailure { e ->
                    Log.d("CardProbe", "GenPKI error: ${e.message}")
                }
                runCatching { isoDep.close() }
            }
        }
    }

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
