package ro.eidkit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.eidkit.app.R
import ro.eidkit.app.screens.SaveDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveCredentialsDialog(
    activity:     FragmentActivity,
    state:        SaveDialogState,
    onToggleCan:  (Boolean) -> Unit,
    onTogglePin:  (Boolean) -> Unit,
    onTogglePin2: (Boolean) -> Unit,
    onConfirm:    (FragmentActivity) -> Unit,
    onDismiss:    () -> Unit,
    onNeverAsk:   () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text  = stringResource(R.string.bio_save_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = stringResource(R.string.bio_save_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label     = stringResource(R.string.bio_save_can),
                checked   = state.saveCan,
                onChanged = onToggleCan,
            )
            ToggleRow(
                label     = stringResource(R.string.bio_save_pin_auth),
                checked   = state.savePin,
                onChanged = onTogglePin,
            )
            if (state.showPin2Row) {
                ToggleRow(
                    label     = stringResource(R.string.bio_save_pin_sign),
                    checked   = state.savePin2,
                    onChanged = onTogglePin2,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick  = { onConfirm(activity) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bio_save_yes))
            }
            OutlinedButton(
                onClick  = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bio_save_no))
            }
            TextButton(
                onClick  = onNeverAsk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text  = stringResource(R.string.bio_save_never),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChanged)
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
