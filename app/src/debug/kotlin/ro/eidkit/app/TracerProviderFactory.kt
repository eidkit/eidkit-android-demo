package ro.eidkit.app

import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/**
 * Debug build: logs every span to logcat AND sends to Sentry via OTLP.
 * Filter logcat by tag "OpenTelemetry" in Android Studio to see the waterfall.
 *
 * Spans appear in Sentry under environment=debug so they are clearly separated
 * from release sessions in the Performance tab.
 */
internal fun buildTracerProvider(): TracerProvider {
    val otlpExporter = OtlpHttpSpanExporter.builder()
        .setEndpoint("https://o203117.ingest.us.sentry.io/api/4511106211840001/integration/otlp/v1/traces")
        .addHeader("x-sentry-auth", "Sentry sentry_key=22e233bd3faf6fd24e2c749885443184")
        .build()
    return SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
        .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter))
        .setResource(buildSdkResource("debug"))
        .build()
}
