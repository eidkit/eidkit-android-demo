package ro.eidkit.app.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.ui.components.findActivity
import ro.eidkit.app.R
import ro.eidkit.app.ui.components.NfcPrompt
import ro.eidkit.app.ui.components.PinField
import ro.eidkit.app.ui.components.SaveCredentialsDialog
import ro.eidkit.app.ui.components.ResultCard
import ro.eidkit.app.ui.components.ResultRow
import ro.eidkit.app.ui.components.StepState
import ro.eidkit.app.ui.components.WizardStep
import ro.eidkit.app.ui.theme.ElectricBlueLight
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard
import ro.eidkit.app.ui.theme.SurfaceDark
import ro.eidkit.sdk.model.ActiveAuthStatus
import ro.eidkit.sdk.model.PassiveAuthStatus
import ro.eidkit.sdk.model.ReadEvent

@Composable
fun AuthScreen(
    vm: AuthViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    val activity = LocalContext.current.findActivity()
    val successState = state as? AuthState.Success

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
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.home_feature_auth_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                is AuthState.Input    -> AuthInputContent(s, vm)
                is AuthState.Scanning -> AuthScanningContent(s)
                is AuthState.Success  -> AuthSuccessContent(s, onRetry = vm::retry)
                is AuthState.Error    -> AuthErrorContent(s, onRetry = vm::retry)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuthInputContent(state: AuthState.Input, vm: AuthViewModel) {
    val canFocusRequester = remember { FocusRequester() }
    val pinFocusRequester = remember { FocusRequester() }
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) { activity?.let { vm.tryBiometricLoad(it) } }
    Text(
        text  = stringResource(R.string.auth_pin_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PinField(
        value             = state.can,
        onValueChange     = vm::onCanChange,
        label             = stringResource(R.string.label_can),
        maxLength         = 6,
        focusRequester    = canFocusRequester,
        onComplete        = { pinFocusRequester.requestFocus() },
        maskable          = true,
        onClear           = { vm.onCanChange("") },
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

    SsoCalloutCard()

    if (state.canSubmit) {
        NfcPrompt(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AuthScanningContent(state: AuthState.Scanning) {
    NfcPrompt(modifier = Modifier.fillMaxWidth(), scanning = true)

    Spacer(Modifier.height(8.dp))

    val allSteps = listOf(
        ReadEvent.ConnectingToCard,
        ReadEvent.EstablishingPace,
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
                else -> StepState.Pending
            }
            WizardStep(label = labelForReadEvent(step), state = stepState)
        }
    }
}

@Composable
private fun AuthSuccessContent(state: AuthState.Success, onRetry: () -> Unit) {
    val identity = state.result.identity
    val activeAuth = state.result.activeAuth
    val isVerified = activeAuth is ActiveAuthStatus.Verified

    val title = when {
        isVerified && identity != null -> "${identity.firstName} ${identity.lastName}"
        isVerified -> stringResource(R.string.result_active_auth_verified)
        else -> stringResource(R.string.result_active_auth_failed)
    }

    SsoCalloutCard()
    ResultCard(title = title, isError = !isVerified, onRetry = onRetry) {
        when (val aa = activeAuth) {
            is ActiveAuthStatus.Verified -> {
                ResultRow(stringResource(R.string.result_active_auth_verified), "✓")
                ResultRow(stringResource(R.string.result_chip_cert_label), aa.certSubject)
            }
            is ActiveAuthStatus.Failed  -> ResultRow(stringResource(R.string.result_active_auth_failed), aa.reason)
            is ActiveAuthStatus.Skipped -> {}
        }
        when (val pa = state.result.passiveAuth) {
            is PassiveAuthStatus.Valid   -> {
                ResultRow(stringResource(R.string.result_passive_auth_valid), "✓")
                ResultRow(stringResource(R.string.result_dsc_subject_label), pa.dscSubject)
                ResultRow(stringResource(R.string.result_issuer_label), pa.issuer)
            }
            is PassiveAuthStatus.Invalid -> ResultRow(stringResource(R.string.result_passive_auth_invalid), pa.reason)
        }
        identity?.let { id ->
            ResultRow(stringResource(R.string.result_cnp_label), id.cnp)
            ResultRow(stringResource(R.string.result_dob_label), formatDob(id.dateOfBirth))
        }
        if (state.result.claim != null) {
            ResultRow(stringResource(R.string.result_claim_ready), "✓")
        }
    }
}

@Composable
private fun AuthErrorContent(state: AuthState.Error, onRetry: () -> Unit) {
    ResultCard(title = errorMessage(state.message), isError = true, onRetry = onRetry)
}

@Composable
private fun SsoCalloutCard() {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.outlinedCardColors(containerColor = SurfaceCard),
        border   = BorderStroke(1.dp, SurfaceBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painter           = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint              = ElectricBlueLight,
                    modifier          = Modifier.size(18.dp),
                )
                Text(
                    text  = stringResource(R.string.auth_sso_callout_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text  = stringResource(R.string.auth_sso_callout_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick  = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://primariatm.eidkit.ro/"))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.auth_sso_callout_cta_demo))
            }
            TextButton(
                onClick  = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://eidkit.ro/sso"))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = stringResource(R.string.auth_sso_callout_cta_docs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
