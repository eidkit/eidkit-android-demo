package ro.eidkit.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.eidkit.app.R
import ro.eidkit.app.ui.theme.ErrorRed
import ro.eidkit.app.ui.theme.SuccessGreen
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard

/**
 * Summary card shown after a session completes (success or error).
 *
 * @param title     Main heading (e.g. "Identity verified" or "Card moved")
 * @param isError   If true, uses error styling
 * @param content   Composable slot for detail rows
 * @param onRetry   Called when the user taps "Try Again"
 */
@Composable
fun ResultCard(
    title: String,
    isError: Boolean = false,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(
                        if (isError) R.drawable.ic_error_circle else R.drawable.ic_check_circle
                    ),
                    contentDescription = null,
                    tint = if (isError) ErrorRed else SuccessGreen,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isError) ErrorRed else SuccessGreen,
                )
            }

            Spacer(Modifier.height(16.dp))
            content()
            if (onRetry != null) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick  = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_try_again))
                }
            }
        }
    }
}

/**
 * A single labelled row inside a [ResultCard].
 */
@Composable
fun ResultRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
    }
}
