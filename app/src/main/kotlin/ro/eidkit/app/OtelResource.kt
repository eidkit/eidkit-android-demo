package ro.eidkit.app

import android.os.Build
import io.opentelemetry.sdk.resources.Resource
import ro.eidkit.sdk.BuildConfig as SdkBuildConfig

/**
 * OTel Resource carrying device + SDK attributes stamped on every span.
 *
 * Shared between the debug and release [TracerProviderFactory] so both build types
 * emit the same context. Allows filtering by SDK, OS version, and device in Sentry.
 *
 * ## Attributes
 * - `sdk.name` / `sdk.version` — identifies this as eidkit-android and its version
 * - `device.manufacturer` / `device.model` — useful for NFC hardware quirk correlation
 * - `device.os_version` — correlates failures with specific Android releases
 * - `nfc.tech` — always IsoDep for Romanian CEI sessions
 *
 * No PII is included — Build fields are hardware metadata only.
 */
internal fun buildSdkResource(environment: String): Resource = Resource.builder()
    .put("sdk.name",               "eidkit-android")
    .put("sdk.version",            SdkBuildConfig.SDK_VERSION)
    .put("device.manufacturer",    Build.MANUFACTURER)
    .put("device.model",           Build.MODEL)
    .put("device.os_version",      Build.VERSION.RELEASE)
    .put("nfc.tech",               "IsoDep")
    .put("deployment.environment", environment)
    .build()
