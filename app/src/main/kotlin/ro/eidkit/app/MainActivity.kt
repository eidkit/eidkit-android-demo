package ro.eidkit.app

import android.content.Intent
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import ro.eidkit.app.screens.AuthScreen
import ro.eidkit.app.screens.AuthViewModel
import ro.eidkit.app.screens.KycScreen
import ro.eidkit.app.screens.KycViewModel
import ro.eidkit.app.screens.SigningScreen
import ro.eidkit.app.screens.SigningViewModel
import ro.eidkit.app.ui.components.DemoRibbon
import ro.eidkit.app.ui.theme.EidKitTheme
import ro.eidkit.sdk.EidKit

private const val TAB_KYC     = 0
private const val TAB_AUTH    = 1
private const val TAB_SIGNING = 2

/**
 * Single-activity host with a bottom navigation bar.
 *
 * All three flow ViewModels are kept alive simultaneously (scoped to the activity) so that
 * switching tabs does not lose in-progress state.
 *
 * NFC routing: [onNewIntent] dispatches the card tap to whichever tab is currently selected.
 */
class MainActivity : ComponentActivity() {

    private val nfcManager = EidKit.nfcManager()

    // ViewModel refs are set once the composables enter composition and remain stable.
    private var kycVm: KycViewModel? = null
    private var authVm: AuthViewModel? = null
    private var signingVm: SigningViewModel? = null

    // Tracks which tab is visible so NFC events go to the right VM.
    private var selectedTab: Int = TAB_KYC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EidKitTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(TAB_KYC) }

                // Instantiate all VMs at the activity scope so they survive tab switches.
                val kycVmInstance: KycViewModel = viewModel()
                val authVmInstance: AuthViewModel = viewModel()
                val signingVmInstance: SigningViewModel = viewModel()

                // Keep activity-level refs in sync for NFC routing.
                kycVm          = kycVmInstance
                authVm         = authVmInstance
                signingVm      = signingVmInstance
                this.selectedTab = selectedTab

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == TAB_KYC,
                                onClick  = { selectedTab = TAB_KYC },
                                icon     = { Icon(painterResource(R.drawable.ic_badge), contentDescription = null) },
                                label    = { Text(stringResource(R.string.kyc_title)) },
                            )
                            NavigationBarItem(
                                selected = selectedTab == TAB_AUTH,
                                onClick  = { selectedTab = TAB_AUTH },
                                icon     = { Icon(painterResource(R.drawable.ic_shield), contentDescription = null) },
                                label    = { Text(stringResource(R.string.auth_title)) },
                            )
                            NavigationBarItem(
                                selected = selectedTab == TAB_SIGNING,
                                onClick  = { selectedTab = TAB_SIGNING },
                                icon     = { Icon(painterResource(R.drawable.ic_draw), contentDescription = null) },
                                label    = { Text(stringResource(R.string.signing_title)) },
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            TAB_KYC     -> KycScreen(vm = kycVmInstance)
                            TAB_AUTH    -> AuthScreen(vm = authVmInstance)
                            TAB_SIGNING -> SigningScreen(vm = signingVmInstance)
                        }
                        if (EidKit.isDemoMode()) DemoRibbon()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            nfcManager.enableForegroundDispatch(this)
        } catch (_: ro.eidkit.sdk.error.CeiError.NfcUnsupported) {}
    }

    override fun onPause() {
        super.onPause()
        nfcManager.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isoDep: IsoDep = nfcManager.handleIntent(intent) ?: return

        when (selectedTab) {
            TAB_KYC     -> kycVm?.onCardDetected(isoDep)
            TAB_AUTH    -> authVm?.onCardDetected(isoDep)
            TAB_SIGNING -> signingVm?.onCardDetected(isoDep)
        }
    }
}
