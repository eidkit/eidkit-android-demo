package ro.eidkit.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.EmailStore
import ro.eidkit.app.R
import ro.eidkit.app.ui.components.findActivity
import ro.eidkit.app.ui.theme.SurfaceDark

@Composable
fun SavedDataScreen() {
    val context = LocalContext.current
    val activity = context.findActivity()

    var hasCredentials by remember { mutableStateOf(BiometricStore.hasCredentials(context)) }
    var emails by remember { mutableStateOf(EmailStore.all(context)) }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.saved_data_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.saved_data_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Credentials ──────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.saved_data_section_credentials))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = stringResource(R.string.saved_data_credentials_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasCredentials) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text  = if (hasCredentials) stringResource(R.string.saved_data_field_saved)
                                else stringResource(R.string.saved_data_field_not_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasCredentials) {
                    TextButton(onClick = {
                        BiometricStore.clear(context)
                        hasCredentials = false
                    }) {
                        Text(
                            text  = stringResource(R.string.saved_data_action_clear),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Remembered emails ────────────────────────────────────────────
            SectionHeader(stringResource(R.string.saved_data_section_emails))

            if (emails.isEmpty()) {
                Text(
                    text = stringResource(R.string.saved_data_no_emails),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                emails.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    EmailRow(
                        serviceName = entry.serviceName.ifBlank { entry.clientId },
                        email       = maskEmail(entry.email),
                        onForget    = {
                            EmailStore.forget(context, entry.clientId)
                            emails = EmailStore.all(context)
                        },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmailRow(serviceName: String, email: String, onForget: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = serviceName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onForget) {
            Text(
                text  = stringResource(R.string.saved_data_action_forget),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at < 0) return email
    val local  = email.substring(0, at)
    val domain = email.substring(at)
    return "${local.take(2)}···$domain"
}
