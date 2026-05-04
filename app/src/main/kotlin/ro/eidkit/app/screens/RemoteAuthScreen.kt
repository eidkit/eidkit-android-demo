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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    if (successState?.saveDialog != null && activity != null && !BiometricStore.neverAsk(context)) {
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
                is RemoteAuthState.Idle     -> IdleContent()
                is RemoteAuthState.Input    -> InputContent(s, vm)
                is RemoteAuthState.Scanning -> ScanningContent(s)
                is RemoteAuthState.Posting  -> PostingContent()
                is RemoteAuthState.Success  -> SuccessContent(s)
                is RemoteAuthState.Error    -> ErrorContent(s, onRetry = { vm.retry() })
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
private fun PostingContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(color = ElectricBlueLight)
        Text(
            text = stringResource(R.string.cityhall_posting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessContent(state: RemoteAuthState.Success) {
    ResultCard(
        title = stringResource(R.string.cityhall_success_prefix) +
                "${state.firstName} ${state.lastName}" +
                stringResource(R.string.cityhall_success_suffix),
        isError = false,
    )
}

@Composable
private fun ErrorContent(state: RemoteAuthState.Error, onRetry: () -> Unit) {
    val message = when {
        state.message.startsWith("wrong_pin:") -> {
            val remaining = state.message.substringAfter("wrong_pin:").toIntOrNull() ?: 0
            stringResource(R.string.error_wrong_pin, remaining)
        }
        state.message == "pin_blocked"  -> stringResource(R.string.error_pin_blocked)
        state.message == "card_lost"    -> stringResource(R.string.error_card_lost)
        state.message == "pace_failed"  -> stringResource(R.string.error_pace_failed)
        else -> stringResource(R.string.error_generic, state.message)
    }
    ResultCard(title = message, isError = true, onRetry = onRetry)
}
