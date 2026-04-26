package ro.eidkit.app.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun CityHallAuthScreen(vm: CityHallAuthViewModel, onClose: (() -> Unit)? = null) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = { CityHallHeader() },
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
                is CityHallAuthState.Idle     -> IdleContent()
                is CityHallAuthState.Input    -> InputContent(s, vm)
                is CityHallAuthState.Scanning -> ScanningContent(s)
                is CityHallAuthState.Posting  -> PostingContent()
                is CityHallAuthState.Success  -> SuccessContent(s)
                is CityHallAuthState.Error    -> ErrorContent(s, onRetry = { vm.retry(); onClose?.invoke() })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CityHallHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TmBlue)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Shield logo placeholder
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
                    text = stringResource(R.string.cityhall_title),
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

@Composable
private fun InputContent(state: CityHallAuthState.Input, vm: CityHallAuthViewModel) {
    Text(
        text = stringResource(R.string.cityhall_pin_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PinField(
        value         = state.can,
        onValueChange = vm::onCanChange,
        label         = stringResource(R.string.label_can),
        placeholder   = stringResource(R.string.label_can_hint),
        maxLength     = 6,
        masked        = true,
    )

    PinField(
        value             = state.pin,
        onValueChange     = vm::onPinChange,
        label             = stringResource(R.string.label_auth_pin),
        placeholder       = stringResource(R.string.label_auth_pin_hint),
        maxLength         = 4,
        imeAction         = ImeAction.Done,
        dismissOnComplete = true,
        masked            = true,
    )

    if (state.canSubmit) {
        NfcPrompt(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ScanningContent(state: CityHallAuthState.Scanning) {
    NfcPrompt(modifier = Modifier.fillMaxWidth())

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
private fun SuccessContent(state: CityHallAuthState.Success) {
    ResultCard(
        title = stringResource(R.string.cityhall_success_prefix) +
                "${state.firstName} ${state.lastName}" +
                stringResource(R.string.cityhall_success_suffix),
        isError = false,
    )
}

@Composable
private fun ErrorContent(state: CityHallAuthState.Error, onRetry: () -> Unit) {
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
