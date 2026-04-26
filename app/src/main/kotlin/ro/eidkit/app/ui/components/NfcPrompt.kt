package ro.eidkit.app.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.eidkit.app.R
import ro.eidkit.app.ui.theme.ElectricBlue
import ro.eidkit.app.ui.theme.ElectricBlueLight
import ro.eidkit.app.ui.theme.WarningAmber

/**
 * Animated NFC tap prompt. Shows a pulsing ring around an NFC card icon with
 * instructional text. Used in scanning state for all three flow screens.
 */
@Composable
fun NfcPrompt(modifier: Modifier = Modifier, scanning: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "nfc_pulse")

    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring1_scale",
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring1_alpha",
    )

    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, delayMillis = 500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring2_scale",
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, delayMillis = 500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring2_alpha",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer pulsing ring 2
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(ring2Scale)
                    .graphicsLayer { alpha = ring2Alpha }
                    .border(2.dp, ElectricBlue.copy(alpha = ring2Alpha), CircleShape)
            )
            // Inner pulsing ring 1
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(ring1Scale)
                    .graphicsLayer { alpha = ring1Alpha }
                    .border(2.dp, ElectricBlueLight.copy(alpha = ring1Alpha), CircleShape)
            )
            // NFC icon in the centre
            Icon(
                painter = painterResource(R.drawable.ic_nfc_card),
                contentDescription = null,
                tint = ElectricBlueLight,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.nfc_scanning_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.nfc_prompt_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(if (scanning) R.string.nfc_scanning_subtitle else R.string.nfc_prompt_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
