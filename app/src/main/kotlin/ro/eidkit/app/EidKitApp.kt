package ro.eidkit.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.opentelemetry.api.trace.Tracer
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import ro.eidkit.sdk.EidKit
import ro.eidkit.sdk.config.EidKitConfig

class EidKitApp : Application() {

    companion object {
        lateinit var tracer: Tracer
            private set
    }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        initSentry()
        val tp = buildTracerProvider()
        tracer = tp.get("eidkit-app")
        EidKit.configure(this, EidKitConfig {
            licenseToken = "demo"
            tracerProvider = tp
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
