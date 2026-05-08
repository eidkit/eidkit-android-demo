package ro.eidkit.app

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import ro.eidkit.app.screens.AuthScreen
import ro.eidkit.app.screens.AuthViewModel
import ro.eidkit.app.screens.RemoteAuthScreen
import ro.eidkit.app.screens.RemoteAuthViewModel
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
 * NFC routing: [onNewIntent] dispatches the card tap to whichever tab is currently selected.
 * Deep link routing: eidkit://auth intents switch to the city hall tab and init the session.
 */
class MainActivity : AppCompatActivity() {

    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    private var kycVm: KycViewModel? = null
    private var authVm: AuthViewModel? = null
    private var signingVm: SigningViewModel? = null
    private var cityHallVm: RemoteAuthViewModel? = null
    private var pendingDeepLinkIntent: Intent? = null

    private var selectedTab: Int = TAB_KYC
    private val _cityHallActive = mutableStateOf(false)
    private var cityHallActive: Boolean
        get() = _cityHallActive.value
        set(v) { _cityHallActive.value = v }

    private fun isEidkitDeepLink(uri: android.net.Uri?) =
        (uri?.scheme == "eidkit" && uri.host == "auth") ||
        (uri?.scheme == "https" && uri.host == "idp.eidkit.ro" && uri.path == "/auth") ||
        (BuildConfig.DEBUG && uri?.path == "/auth")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
if (savedInstanceState == null && isEidkitDeepLink(intent?.data)) {
            this.cityHallActive = true
            this.selectedTab = TAB_KYC
        }
        setContent {
            EidKitTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(TAB_KYC) }
                val cityHallActive by _cityHallActive

                val kycVmInstance: KycViewModel = viewModel()
                val authVmInstance: AuthViewModel = viewModel()
                val signingVmInstance: SigningViewModel = viewModel()
                val cityHallVmInstance: RemoteAuthViewModel = viewModel()

                kycVm      = kycVmInstance
                authVm     = authVmInstance
                signingVm  = signingVmInstance
                cityHallVm = cityHallVmInstance
                this.selectedTab = selectedTab

                // Init city hall VM from deep link exactly once after first composition
                LaunchedEffect(Unit) {
                    if (cityHallActive) {
                        val pending = pendingDeepLinkIntent ?: intent
                        pending?.let { parseDeepLink(it, cityHallVmInstance) }
                        pendingDeepLinkIntent = null
                    }
                }

                if (cityHallActive) {
                    // Full-screen city hall auth — no bottom bar
                    Box {
                        RemoteAuthScreen(vm = cityHallVmInstance, onClose = {
                            this@MainActivity.cityHallActive = false
                            selectedTab = TAB_KYC
                        })
                        if (EidKit.isDemoMode()) DemoRibbon()
                    }
                } else {
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
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        adapter.enableReaderMode(
            this,
            { tag: Tag ->
                val isoDep = IsoDep.get(tag) ?: return@enableReaderMode
                runOnUiThread {
                    if (cityHallActive) {
                        cityHallVm?.onCardDetected(isoDep)
                    } else {
                        when (selectedTab) {
                            TAB_KYC     -> kycVm?.onCardDetected(isoDep)
                            TAB_AUTH    -> authVm?.onCardDetected(isoDep)
                            TAB_SIGNING -> signingVm?.onCardDetected(isoDep)
                        }
                    }
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
if (isEidkitDeepLink(intent.data)) {
            val vm = cityHallVm
            if (vm != null) {
                parseDeepLink(intent, vm)
            } else {
                pendingDeepLinkIntent = intent
            }
            this.selectedTab = TAB_KYC
            this.cityHallActive = true
        }
        // NFC is handled via enableReaderMode callback, not via onNewIntent
    }

    private fun parseDeepLink(intent: Intent, vm: RemoteAuthViewModel) {
        val uri          = intent.data ?: return
        val sessionToken = uri.getQueryParameter("session")     ?: return
        val callbackUrl  = uri.getQueryParameter("callback")    ?: return
        val serviceName  = uri.getQueryParameter("service")     ?: ""
        val nonce        = uri.getQueryParameter("nonce")       ?: ""
        val traceparent  = uri.getQueryParameter("traceparent")
        vm.initFromDeepLink(sessionToken, callbackUrl, serviceName, nonce, traceparent)
    }
}
