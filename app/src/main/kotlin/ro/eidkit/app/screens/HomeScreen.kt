package ro.eidkit.app.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.eidkit.app.BiometricStore
import ro.eidkit.app.R
import ro.eidkit.app.ui.theme.ElectricBlueLight
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard

@Composable
fun HomeScreen(
    onKyc: () -> Unit,
    onAuth: () -> Unit,
    onSigning: () -> Unit,
) {
    val context = LocalContext.current
    var hasCredentials by remember { mutableStateOf(BiometricStore.hasCredentials(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    text  = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasCredentials) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            BiometricStore.clear(context)
                            hasCredentials = false
                        },
                    ) {
                        Text(
                            text  = stringResource(R.string.bio_forget),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            FeatureCard(
                icon        = R.drawable.ic_badge,
                title       = stringResource(R.string.home_feature_kyc_title),
                description = stringResource(R.string.home_feature_kyc_description),
                onClick     = onKyc,
            )
        }

        item {
            FeatureCard(
                icon        = R.drawable.ic_draw,
                title       = stringResource(R.string.home_feature_signing_title),
                description = stringResource(R.string.home_feature_signing_description),
                onClick     = onSigning,
            )
        }

        item {
            FeatureCard(
                icon        = R.drawable.ic_shield,
                title       = stringResource(R.string.home_feature_auth_title),
                description = stringResource(R.string.home_feature_auth_description),
                onClick     = onAuth,
            )
        }
    }
}

@Composable
private fun FeatureCard(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, SurfaceBorder),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = ElectricBlueLight,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text  = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text  = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick  = onClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_try_it))
            }
        }
    }
}
