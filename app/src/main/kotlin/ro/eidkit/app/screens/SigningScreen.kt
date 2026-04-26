package ro.eidkit.app.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ro.eidkit.app.R
import ro.eidkit.app.ui.components.NfcPrompt
import ro.eidkit.app.ui.components.PinField
import ro.eidkit.app.ui.components.ResultCard
import ro.eidkit.app.ui.components.ResultRow
import ro.eidkit.app.ui.components.StepState
import ro.eidkit.app.ui.components.WizardStep
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard
import ro.eidkit.app.ui.theme.SurfaceDark
import ro.eidkit.sdk.model.SignEvent

@Composable
fun SigningScreen(
    vm: SigningViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Launcher for picking a PDF to sign
    val signedPrefix = stringResource(R.string.signing_filename_prefix)
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val displayName = context.contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: it.lastPathSegment ?: "document.pdf"
            vm.onDocumentSelected(it, displayName, signedPrefix)
        }
    }

    // Launcher for choosing where to save the signed PDF
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { vm.onOutputUriSelected(it) }
    }

    // When NFC finishes, immediately prompt for save location
    LaunchedEffect(state) {
        val s = state
        if (s is SigningState.AwaitingOutputUri) {
            createDoc.launch(s.padesCtx.suggestedFilename)
        }
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
                Text(stringResource(R.string.signing_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.home_feature_signing_description),
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
                is SigningState.DocumentPicker    -> SigningDocumentPickerContent(onPick = { pickPdf.launch(arrayOf("application/pdf")) })
                is SigningState.Input             -> SigningInputContent(s, vm, onChangePdf = vm::clearDocument)
                is SigningState.Scanning          -> SigningScanningContent(s)
                is SigningState.AwaitingOutputUri -> SigningAwaitingOutputContent()
                is SigningState.Success           -> SigningSuccessContent(s, onRetry = vm::retry)
                is SigningState.Error             -> SigningErrorContent(s, onRetry = vm::retry)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SigningDocumentPickerContent(onPick: () -> Unit) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter           = painterResource(R.drawable.ic_description),
                contentDescription = null,
                modifier          = Modifier.size(48.dp),
                tint              = MaterialTheme.colorScheme.primary,
            )
            Text(
                text      = stringResource(R.string.signing_pick_pdf_title),
                style     = MaterialTheme.typography.titleMedium,
                color     = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text      = stringResource(R.string.signing_pick_pdf_description),
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.signing_pick_pdf_button))
            }
        }
    }
}

@Composable
private fun SigningInputContent(state: SigningState.Input, vm: SigningViewModel, onChangePdf: () -> Unit) {
    val canFocusRequester = remember { FocusRequester() }
    val pinFocusRequester = remember { FocusRequester() }
    // Selected document card
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text  = stringResource(R.string.signing_document_selected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = state.documentName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = stringResource(R.string.signing_hash_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = state.padesCtx.signedAttrsHash.toHexString().take(48) + "…",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onChangePdf) {
                Text(stringResource(R.string.signing_change_document))
            }
        }
    }

    PinField(
        value             = state.can,
        onValueChange     = vm::onCanChange,
        label             = stringResource(R.string.label_can),
        placeholder       = stringResource(R.string.label_can_hint),
        maxLength         = 6,
        focusRequester    = canFocusRequester,
        onComplete        = { pinFocusRequester.requestFocus() },
    )

    PinField(
        value             = state.pin,
        onValueChange     = vm::onPinChange,
        label             = stringResource(R.string.label_signing_pin),
        placeholder       = stringResource(R.string.label_signing_pin_hint),
        maxLength         = 6,
        imeAction         = ImeAction.Done,
        focusRequester    = pinFocusRequester,
        dismissOnComplete = true,
    )

    if (state.canSubmit) {
        NfcPrompt(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SigningScanningContent(state: SigningState.Scanning) {
    NfcPrompt(modifier = Modifier.fillMaxWidth(), scanning = true)

    Spacer(Modifier.height(8.dp))

    val allSteps = listOf(
        SignEvent.ConnectingToCard,
        SignEvent.EstablishingPace,
        SignEvent.VerifyingPin,
        SignEvent.SigningDocument,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        allSteps.forEach { step ->
            val stepState = when {
                state.completedSteps.any { it::class == step::class } -> StepState.Done
                state.activeStep?.let { it::class == step::class } == true -> StepState.Active
                else -> StepState.Pending
            }
            WizardStep(label = labelForSignEvent(step), state = stepState)
        }
    }
}

@Composable
private fun SigningAwaitingOutputContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text  = stringResource(R.string.signing_awaiting_output),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SigningSuccessContent(state: SigningState.Success, onRetry: () -> Unit) {
    val context = LocalContext.current
    val certInfo = remember(state.signResult.certificate) {
        parseCertInfo(state.signResult.certificate)
    }
    ResultCard(
        title   = stringResource(R.string.signing_success_saved),
        isError = false,
        onRetry = onRetry,
    ) {
        ResultRow(stringResource(R.string.signing_document_selected), state.documentName)

        certInfo?.let { info ->
            ResultRow(stringResource(R.string.result_chip_cert_label), info.subject)
            ResultRow(stringResource(R.string.result_issuer_label), info.issuer)
            val usageParts = buildList {
                if (info.hasDigitalSignature) add(stringResource(R.string.key_usage_digital_signature))
                if (info.hasNonRepudiation)   add(stringResource(R.string.key_usage_non_repudiation))
            }
            ResultRow(stringResource(R.string.result_key_usage_label), usageParts.joinToString(", ").ifEmpty { "—" })
        }

        val sigHex = state.signResult.signature.toHexString()
        ResultRow(
            label = stringResource(R.string.result_signature_label),
            value = sigHex.take(48) + "…",
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, state.outputUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.signing_success_open))
        }
    }
}

private data class CertInfo(
    val subject: String,
    val issuer: String,
    val hasDigitalSignature: Boolean,
    val hasNonRepudiation: Boolean,
)

private fun parseCertInfo(derBytes: ByteArray): CertInfo? = runCatching {
    val cert = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(derBytes.inputStream()) as java.security.cert.X509Certificate

    fun principal(name: String) = name
        .split(",").firstOrNull { it.trim().startsWith("CN=") }
        ?.substringAfter("CN=")?.trim() ?: name

    val usage = cert.keyUsage
    CertInfo(
        subject             = principal(cert.subjectX500Principal.name),
        issuer              = principal(cert.issuerX500Principal.name),
        hasDigitalSignature = usage != null && usage.size > 0 && usage[0],
        hasNonRepudiation   = usage != null && usage.size > 1 && usage[1],
    )
}.getOrNull()

@Composable
private fun SigningErrorContent(state: SigningState.Error, onRetry: () -> Unit) {
    ResultCard(title = errorMessage(state.message), isError = true, onRetry = onRetry)
}

@Composable
fun labelForSignEvent(event: SignEvent): String = when (event) {
    is SignEvent.ConnectingToCard -> stringResource(R.string.step_connecting)
    is SignEvent.EstablishingPace -> stringResource(R.string.step_establishing_pace)
    is SignEvent.VerifyingPin     -> stringResource(R.string.step_verifying_pin)
    is SignEvent.SigningDocument   -> stringResource(R.string.step_signing_document)
    is SignEvent.Done             -> stringResource(R.string.step_done)
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
