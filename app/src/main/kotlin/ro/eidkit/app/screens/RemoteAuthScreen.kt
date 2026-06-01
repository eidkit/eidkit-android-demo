package ro.eidkit.app.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.ui.components.SaveCredentialsDialog
import ro.eidkit.app.ui.components.findActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.eidkit.app.R
import ro.eidkit.app.ui.components.NfcPrompt
import ro.eidkit.app.ui.components.PinField
import ro.eidkit.app.ui.components.ResultCard
import ro.eidkit.app.ui.components.StepState
import ro.eidkit.app.ui.components.WizardStep
import ro.eidkit.app.ui.theme.ElectricBlueLight
import ro.eidkit.app.ui.theme.ErrorRed
import ro.eidkit.app.ui.theme.SuccessGreen
import ro.eidkit.app.ui.theme.SurfaceDark
import ro.eidkit.sdk.model.ReadEvent

// Timișoara brand blue — used as an accent in this screen only.
private val TmBlue = Color(0xFF1B3A82)

@Composable
fun RemoteAuthScreen(vm: RemoteAuthViewModel, onClose: (() -> Unit)? = null) {
    val state by vm.state.collectAsState()
    val serviceNameRaw by vm.serviceName.collectAsState()

    val serviceName = serviceNameRaw.ifBlank { stringResource(R.string.cityhall_title) }
    val activity = LocalContext.current.findActivity()
    val successState = state as? RemoteAuthState.Success

    val context = LocalContext.current
    if (successState?.saveDialog != null && activity != null && !BiometricStore.neverAsk(context) && BiometricStore.canSave(context)) {
        SaveCredentialsDialog(
            activity     = activity,
            state        = successState.saveDialog,
            onToggleCan  = { vm.onSaveDialogToggle(saveCan = it) },
            onTogglePin  = { vm.onSaveDialogToggle(savePin = it) },
            onTogglePin2 = {},
            onConfirm    = { a -> vm.confirmSave(a) },
            onDismiss    = vm::dismissSaveDialog,
            onNeverAsk   = { vm.neverAskSave(context) },
        )
    }

    Scaffold(
        topBar = { RemoteAuthHeader(serviceName) },
        containerColor = SurfaceDark,
    ) { innerPadding ->
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { focusManager.clearFocus() },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            when (val s = state) {
                is RemoteAuthState.Idle       -> IdleContent()
                is RemoteAuthState.Input      -> InputContent(s, vm)
                is RemoteAuthState.Scanning   -> ScanningContent(s)
                is RemoteAuthState.EmailInput -> EmailInputContent(s, vm)
                is RemoteAuthState.OtpInput   -> OtpInputContent(s, vm)
                is RemoteAuthState.Success    -> SuccessContent()
                is RemoteAuthState.Error      -> ErrorContent(s, onRetry = { vm.retry() })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RemoteAuthHeader(serviceName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TmBlue)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_badge),
                    contentDescription = null,
                    tint = TmBlue,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text = serviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.cityhall_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun IdleContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            painter = painterResource(R.drawable.ic_badge),
            contentDescription = null,
            tint = ElectricBlueLight,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = stringResource(R.string.cityhall_idle_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputContent(state: RemoteAuthState.Input, vm: RemoteAuthViewModel) {
    val pinFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    var showCanInfo by remember { mutableStateOf(false) }
    val activity = context.findActivity()
    LaunchedEffect(Unit) { activity?.let { vm.tryBiometricLoad(it) } }
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
                    text = stringResource(R.string.can_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.can_info_body),
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

    Text(
        text = stringResource(R.string.cityhall_pin_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PinField(
        value         = state.can,
        onValueChange = vm::onCanChange,
        label         = stringResource(R.string.label_can),
        maxLength     = 6,
        onComplete    = { pinFocusRequester.requestFocus() },
        maskable      = true,
        onClear       = { vm.onCanChange("") },
        labelTrailing = {
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
        focusRequester    = pinFocusRequester,
        imeAction         = ImeAction.Done,
        dismissOnComplete = true,
        maskable          = true,
        onClear           = { vm.onPinChange("") },
    )


    if (state.canSubmit) {
        NfcPrompt(modifier = Modifier.fillMaxWidth(), scanning = true)
    }
}

@Composable
private fun ScanningContent(state: RemoteAuthState.Scanning) {
    NfcPrompt(modifier = Modifier.fillMaxWidth(), scanning = true)

    Spacer(Modifier.height(8.dp))

    val allSteps = listOf(
        ReadEvent.ConnectingToCard,
        ReadEvent.EstablishingPace,
        ReadEvent.VerifyingPassiveAuth,
        ReadEvent.VerifyingPin,
        ReadEvent.ReadingIdentity,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        allSteps.forEach { step ->
            val stepState = when {
                state.completedSteps.any { it::class == step::class } -> StepState.Done
                state.activeStep?.let { it::class == step::class } == true -> StepState.Active
                else -> StepState.Pending
            }
            WizardStep(label = labelForReadEvent(step), state = stepState)
        }
    }
}

@Composable
private fun EmailInputContent(state: RemoteAuthState.EmailInput, vm: RemoteAuthViewModel) {
    var email by remember { mutableStateOf(state.prefill ?: "") }
    var saveForNextTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.email_input_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.label_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.email_remember),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = saveForNextTime,
                onCheckedChange = { saveForNextTime = it },
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                ),
            )
        }
        androidx.compose.material3.Button(
            onClick = { if (email.isNotBlank()) vm.submitEmail(email, saveForNextTime) },
            enabled = email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun OtpInputContent(state: RemoteAuthState.OtpInput, vm: RemoteAuthViewModel) {
    var code by remember { mutableStateOf("") }
    var saveForNextTime by remember { mutableStateOf(false) }
    // Clear local code each time server signals invalid so user retypes from scratch
    LaunchedEffect(state.invalidCount) { if (state.invalidCount > 0) code = "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.otp_hint, state.email),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.invalidCount > 0) {
            Text(
                text = stringResource(R.string.otp_invalid_error),
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
            )
        }
        PinField(
            value = code,
            onValueChange = { code = it },
            label = stringResource(R.string.label_otp),
            maxLength = 6,
            imeAction = ImeAction.Done,
            dismissOnComplete = true,
            maskable = false,
            onClear = { code = "" },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.email_remember),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = saveForNextTime,
                onCheckedChange = { saveForNextTime = it },
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                ),
            )
        }
        androidx.compose.material3.Button(
            onClick = { if (code.length == 6) vm.submitOtp(code, saveForNextTime) },
            enabled = code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_verify))
        }
    }
}

@Composable
private fun SuccessContent() {
    val activity = LocalContext.current.findActivity()
    ResultCard(
        title = stringResource(R.string.cityhall_success_message),
        isError = false,
    )
    Spacer(Modifier.height(8.dp))
    androidx.compose.material3.Button(
        onClick = { activity?.finish() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.cityhall_back_to_browser))
    }
}

private val SERVER_REJECTION_CODES = setOf(
    "name_mismatch", "dg1_hash_mismatch", "dg14_hash_mismatch",
    "active_auth_failed", "cnp_mismatch", "cnp_extraction_failed", "cnp_edata_extraction_failed",
)

@Composable
private fun ErrorContent(state: RemoteAuthState.Error, onRetry: () -> Unit) {
    val message = when {
        state.message.startsWith("wrong_pin:") -> {
            val remaining = state.message.substringAfter("wrong_pin:").toIntOrNull() ?: 0
            stringResource(R.string.error_wrong_pin, remaining)
        }
        state.message == "pin_blocked"         -> stringResource(R.string.error_pin_blocked)
        state.message == "card_lost"           -> stringResource(R.string.error_card_lost)
        state.message == "pace_failed"         -> stringResource(R.string.error_pace_failed)
        state.message in SERVER_REJECTION_CODES -> stringResource(R.string.error_server_rejection)
        else -> stringResource(R.string.error_generic, state.message)
    }
    val showContact = state.message in SERVER_REJECTION_CODES || state.message.startsWith("generic:")
    ResultCard(title = message, isError = true, onRetry = onRetry) {
        if (showContact && state.traceId != null) {
            SelectionContainer {
                Text(
                    text = stringResource(R.string.error_contact_us, state.traceId),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
