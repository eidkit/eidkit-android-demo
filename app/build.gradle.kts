import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load secrets.properties if present — values flow into BuildConfig.
// Copy secrets.properties.template to secrets.properties and fill in your values.
// secrets.properties is gitignored — NEVER commit it.
val secrets = Properties().also { props ->
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "ro.eidkit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.eidkit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sentry DSN — sourced from secrets.properties, injected at build time.
        // Empty string = Sentry init is skipped (local dev without a DSN).
        buildConfigField("String", "SENTRY_DSN", "\"${secrets["SENTRY_DSN"] ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore/release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "eidkit-demo"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
    }
}

dependencies {
    implementation("ro.eidkit:sdk-android:0.1.9")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.bcpkix)

    // Sentry — crash reporting for the app
    implementation(libs.sentry.android)
    // OTel SDK + OTLP exporter — sends SDK spans to eidkit-android-sdk Sentry project
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.opentelemetry.exporter.logging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
