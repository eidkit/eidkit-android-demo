package ro.eidkit.app.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.ui.components.SaveCredentialsDialog
import ro.eidkit.app.ui.components.findActivity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ro.eidkit.app.R
import ro.eidkit.app.ui.components.NfcPrompt
import ro.eidkit.app.ui.components.PinField
import ro.eidkit.app.ui.components.ResultCard
import ro.eidkit.app.ui.components.ResultRow
import ro.eidkit.app.ui.components.StepState
import ro.eidkit.app.ui.components.WizardStep
import ro.eidkit.app.ui.theme.SurfaceDark
import ro.eidkit.sdk.model.ActiveAuthStatus
import ro.eidkit.sdk.model.PassiveAuthStatus
import ro.eidkit.sdk.model.ReadEvent

@Composable
fun KycScreen(
    vm: KycViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    val successState = state as? KycState.Success
    val activity = LocalContext.current.findActivity()

    val context = LocalContext.current
    if (successState?.saveDialog != null && activity != null && !BiometricStore.neverAsk(context)) {
        SaveCredentialsDialog(
            activity     = activity,
            state        = successState.saveDialog,
            onToggleCan  = { vm.onSaveDialogToggle(saveCan = it) },
            onTogglePin  = { vm.onSaveDialogToggle(savePin = it) },
            onTogglePin2 = {},
            onConfirm    = { a -> vm.confirmSave(a) },
            onDismiss    = vm::dismissSaveDialog,
            onNeverAsk   = vm::neverAskSave,
        )
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.kyc_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.home_feature_kyc_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            if (successState != null) {
                KycExportBar(
                    state       = successState,
                    onExportPdf = vm::exportToPdf,
                    onRetry     = vm::retry,
                )
            }
        },
        containerColor = SurfaceDark,
    ) { innerPadding ->
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                    focusManager.clearFocus()
                },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            when (val s = state) {
                is KycState.Input    -> KycInputContent(s, vm)
                is KycState.Scanning -> KycScanningContent(s)
                is KycState.Success  -> KycSuccessContent(s)
                is KycState.Error    -> KycErrorContent(s, onRetry = vm::retry)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KycInputContent(state: KycState.Input, vm: KycViewModel) {
    val canFocusRequester = remember { FocusRequester() }
    val pinFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    var showCanInfo by remember { mutableStateOf(false) }

    val activity = context.findActivity()
    LaunchedEffect(Unit) { activity?.let { vm.tryBiometricLoad(it) } }
    var hasCredentials by remember { mutableStateOf(BiometricStore.hasCredentials(context)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showCanInfo) {
        ModalBottomSheet(
            onDismissRequest = { showCanInfo = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text  = stringResource(R.string.can_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = stringResource(R.string.can_info_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val bitmap = remember {
                    runCatching {
                        context.assets.open("can_location.jpg").use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    PinField(
        value          = state.can,
        onValueChange  = vm::onCanChange,
        label          = stringResource(R.string.label_can),
        maxLength      = 6,
        focusRequester = canFocusRequester,
        onComplete     = { pinFocusRequester.requestFocus() },
        maskable       = true,
        onClear        = { vm.onCanChange("") },
        labelTrailing  = {
            IconButton(
                onClick  = { showCanInfo = true },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    painter            = painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.can_info_title),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(16.dp),
                )
            }
        },
    )

    PinField(
        value             = state.pin,
        onValueChange     = vm::onPinChange,
        label             = stringResource(R.string.label_auth_pin),
        maxLength         = 4,
        imeAction         = ImeAction.Done,
        focusRequester    = pinFocusRequester,
        dismissOnComplete = true,
        maskable          = true,
        onClear           = { vm.onPinChange("") },
    )

    if (hasCredentials) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.TextButton(
                onClick = {
                    BiometricStore.clear(context)
                    hasCredentials = false
                    vm.onCanChange("")
                    vm.onPinChange("")
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(
                    text  = stringResource(R.string.bio_forget),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.includePhoto, onCheckedChange = vm::onPhotoToggle)
        Text(
            text  = stringResource(R.string.kyc_include_photo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.includeSignature, onCheckedChange = vm::onSignatureToggle)
        Text(
            text  = stringResource(R.string.kyc_include_signature),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (state.canSubmit) {
        NfcPrompt(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun KycScanningContent(state: KycState.Scanning) {
    NfcPrompt(modifier = Modifier.fillMaxWidth(), scanning = true)

    Spacer(Modifier.height(8.dp))

    val allSteps = listOf(
        ReadEvent.ConnectingToCard,
        ReadEvent.EstablishingPace,
        ReadEvent.ReadingPhoto,
        ReadEvent.ReadingSignatureImage,
        ReadEvent.VerifyingPassiveAuth,
        ReadEvent.VerifyingPin,
        ReadEvent.ReadingIdentity,
        ReadEvent.VerifyingActiveAuth,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        allSteps.forEach { step ->
            val stepState = when {
                state.completedSteps.any { it::class == step::class } -> StepState.Done
                state.activeStep?.let { it::class == step::class } == true -> StepState.Active
                step is ReadEvent.ReadingPhoto && !state.includePhoto -> StepState.Skipped
                step is ReadEvent.ReadingSignatureImage && !state.includeSignature -> StepState.Skipped
                else -> StepState.Pending
            }
            WizardStep(label = labelForReadEvent(step), state = stepState)
        }
    }
}

@Composable
private fun KycExportBar(state: KycState.Success, onExportPdf: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color          = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val es = state.exportState) {
                is ExportState.Idle -> Button(
                    onClick  = onExportPdf,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_export_pdf))
                }
                is ExportState.Exporting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text  = stringResource(R.string.kyc_export_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is ExportState.Done -> Button(
                    onClick  = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, es.uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.kyc_export_saved))
                }
                is ExportState.Failed -> Text(
                    text  = stringResource(R.string.kyc_export_error, es.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_try_again))
            }
        }
    }
}

@Composable
private fun KycSuccessContent(state: KycState.Success) {
    val result = state.result
    ResultCard(
        title   = result.identity?.let { "${it.firstName} ${it.lastName}" }
            ?: stringResource(R.string.result_passive_auth_valid),
        isError = false,
    ) {
        // Photo
        result.photo?.let { jpegBytes ->
            val bitmap = remember(jpegBytes) {
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(width = 120.dp, height = 150.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // Signature image
        result.signatureImage?.let { jpegBytes ->
            val bitmap = remember(jpegBytes) {
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }
            bitmap?.let {
                ResultRow(stringResource(R.string.result_signature_image_label), "")
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // Identity fields
        result.identity?.let { id ->
            ResultRow(stringResource(R.string.result_cnp_label), id.cnp)
            ResultRow(stringResource(R.string.result_dob_label), formatDob(id.dateOfBirth))
            ResultRow(stringResource(R.string.result_nationality_label), id.nationality)
        }

        // Personal data
        result.personalData?.let { pd ->
            pd.birthPlace?.let { ResultRow(stringResource(R.string.result_birthplace_label), it) }
            pd.address?.let { ResultRow(stringResource(R.string.result_address_label), it) }
            pd.documentNumber?.let { ResultRow(stringResource(R.string.result_document_label), it) }
            pd.issueDate?.let { ResultRow(stringResource(R.string.result_issue_date_label), formatDob(it)) }
            pd.expiryDate?.let { ResultRow(stringResource(R.string.result_expiry_label), formatDob(it)) }
            pd.issuingAuthority?.let { ResultRow(stringResource(R.string.result_issuing_authority_label), it) }
        }

        // Auth status
        when (val pa = result.passiveAuth) {
            is PassiveAuthStatus.Valid   -> {
                ResultRow(stringResource(R.string.result_passive_auth_valid), "✓")
                ResultRow(stringResource(R.string.result_dsc_subject_label), pa.dscSubject)
                ResultRow(stringResource(R.string.result_issuer_label), pa.issuer)
            }
            is PassiveAuthStatus.Invalid -> ResultRow(stringResource(R.string.result_passive_auth_invalid), pa.reason)
        }
        when (val aa = result.activeAuth) {
            is ActiveAuthStatus.Verified -> {
                ResultRow(stringResource(R.string.result_active_auth_verified), "✓")
                ResultRow(stringResource(R.string.result_chip_cert_label), aa.certSubject)
            }
            is ActiveAuthStatus.Failed   -> ResultRow(stringResource(R.string.result_active_auth_failed), aa.reason)
            is ActiveAuthStatus.Skipped  -> {}
        }

    }
}

@Composable
private fun KycErrorContent(state: KycState.Error, onRetry: () -> Unit) {
    val message = errorMessage(state.message)
    ResultCard(title = message, isError = true, onRetry = onRetry)
}

/**
 * Format a DDMMYYYY string from the card into a locale-appropriate date string.
 * Falls back to the raw value if it can't be parsed.
 */
fun formatDob(raw: String): String {
    if (raw.length != 8) return raw
    val dd = raw.substring(0, 2)
    val mm = raw.substring(2, 4)
    val yyyy = raw.substring(4, 8)
    return try {
        val cal = java.util.Calendar.getInstance()
        cal.set(yyyy.toInt(), mm.toInt() - 1, dd.toInt())
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(cal.time)
    } catch (_: Exception) { raw }
}

@Composable
fun labelForReadEvent(event: ReadEvent): String = when (event) {
    is ReadEvent.ConnectingToCard      -> stringResource(R.string.step_connecting)
    is ReadEvent.EstablishingPace      -> stringResource(R.string.step_establishing_pace)
    is ReadEvent.ReadingPhoto          -> stringResource(R.string.step_reading_photo)
    is ReadEvent.ReadingSignatureImage -> stringResource(R.string.step_reading_signature_image)
    is ReadEvent.VerifyingPassiveAuth  -> stringResource(R.string.step_verifying_passive_auth)
    is ReadEvent.VerifyingPin          -> stringResource(R.string.step_verifying_pin)
    is ReadEvent.ReadingIdentity       -> stringResource(R.string.step_reading_identity)
    is ReadEvent.VerifyingActiveAuth   -> stringResource(R.string.step_verifying_active_auth)
    is ReadEvent.Done                  -> stringResource(R.string.step_done)
}

@Composable
fun errorMessage(code: String): String {
    return when {
        code.startsWith("wrong_pin:") -> {
            val n = code.removePrefix("wrong_pin:").toIntOrNull() ?: 0
            stringResource(R.string.error_wrong_pin, n)
        }
        code == "pin_blocked"  -> stringResource(R.string.error_pin_blocked)
        code == "card_lost"    -> stringResource(R.string.error_card_lost)
        code == "pace_failed"  -> stringResource(R.string.error_pace_failed)
        code.startsWith("generic:") -> stringResource(
            R.string.error_generic, code.removePrefix("generic:")
        )
        else -> code
    }
}
