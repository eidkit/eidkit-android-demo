package ro.eidkit.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ro.eidkit.app.R
import ro.eidkit.app.ui.theme.OnSurfaceMuted
import ro.eidkit.app.ui.theme.SuccessGreen

enum class StepState { Pending, Active, Done, Skipped }

/**
 * A single wizard step row: icon + label.
 *
 * - **Done**: green check circle
 * - **Active**: small indeterminate spinner
 * - **Pending**: muted dashed circle
 * - **Skipped**: muted minus circle (step was not requested)
 */
@Composable
fun WizardStep(
    label: String,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            StepState.Done -> Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp),
            )
            StepState.Active -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            StepState.Pending -> Icon(
                painter = painterResource(R.drawable.ic_circle_dashed),
                contentDescription = null,
                tint = OnSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
            StepState.Skipped -> Icon(
                painter = painterResource(R.drawable.ic_minus_circle),
                contentDescription = null,
                tint = OnSurfaceMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                StepState.Done    -> MaterialTheme.colorScheme.onSurface
                StepState.Active  -> MaterialTheme.colorScheme.primary
                StepState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                StepState.Skipped -> OnSurfaceMuted.copy(alpha = 0.5f)
            },
        )
    }
}
