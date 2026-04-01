package ro.eidkit.app

import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

/**
 * Release build: exports spans to the EidKit Sentry project via OTLP/HTTP.
 *
 * No Sentry SDK dependency — pure OTel OTLP. The endpoint and auth header are
 * hardcoded to the eidkit-android-sdk Sentry project. Integrators who want their
 * own telemetry backend pass a custom [TracerProvider] to [ro.eidkit.sdk.config.EidKitConfig].
 *
 * Device/SDK attributes are stamped on every span via [buildSdkResource].
 */
internal fun buildTracerProvider(): TracerProvider {
    val exporter = OtlpHttpSpanExporter.builder()
        .setEndpoint("https://o203117.ingest.us.sentry.io/api/4511106211840001/integration/otlp/v1/traces")
        .addHeader("x-sentry-auth", "Sentry sentry_key=22e233bd3faf6fd24e2c749885443184")
        .build()
    return SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .setResource(buildSdkResource("production"))
        .build()
}
