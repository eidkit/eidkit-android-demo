package ro.eidkit.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.config.EidKitConfig

class EidKitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        initSentry()
        EidKit.configure(this, EidKitConfig {
            licenseToken = "demo"
            tracerProvider = buildTracerProvider()
            // Forward every handled CeiError to Sentry Issues (errors that don't crash).
            // These are thrown-but-caught errors that never reach the default crash handler.
            onError = { e -> Sentry.captureException(e) }
        })
    }

    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return  // no DSN configured — skip (local dev without secrets)
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
            options.isSendDefaultPii = false
        }
    }

}
