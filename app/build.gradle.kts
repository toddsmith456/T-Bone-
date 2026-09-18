import java.util.Properties

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

// Release keystore resolution order (first hit wins):
//   1. Environment variables (used by GitHub Actions CI):
//        KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
//      (CI writes KEYSTORE_BASE64 secret to $RUNNER_TEMP/release.keystore
//       and exports KEYSTORE_PATH to it — see .github/workflows/build-release.yml)
//   2. local.properties (local dev machine, never committed):
//        KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("KEYSTORE_PATH")
val keystoreFile = keystorePath?.let { rootProject.file(it).takeIf { f -> f.exists() } }
    ?: keystorePath?.let { file(it).takeIf { f -> f.exists() } }
val hasReleaseKey = keystoreFile != null &&
    signingValue("KEYSTORE_PASSWORD") != null &&
    signingValue("KEY_ALIAS") != null &&
    signingValue("KEY_PASSWORD") != null

if (!hasReleaseKey) {
    logger.warn(
        "[fork] No release keystore configured (KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD). " +
        "Release builds will fall back to the debug key. " +
        "For Play-store-quality releases set up a keystore — see docs/RELEASE.md."
    )
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    // NOTE (fork): namespace stays "social.tbone" on purpose.
    // The namespace controls generated code (R, BuildConfig, Hilt) and all
    // Kotlin sources live under social.tbone.* — keeping it avoids touching
    // 200+ files. Install identity comes from applicationId below, which IS
    // different from upstream, so this fork installs side-by-side with the
    // original T-Bone (social.tbone) without replacing it.
    namespace = "social.tbone"
    compileSdk = 34

    defaultConfig {
        // FORK IDENTITY — side-by-side with upstream (upstream: social.tbone).
        // App label stays "T-bone" (see res/values/strings.xml) per owner request.
        applicationId = "social.tbone.fork"
        minSdk = 26
        targetSdk = 34
        versionCode = 44
        versionName = "0.3.36"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = keystoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            } else {
                // Fallback: sign release with the debug key so CI / local
                // builds never break when no keystore is configured.
                // The workflow marks such APKs clearly in the release notes.
                //
                // NOTE: we must go through signingConfigs.getByName("debug").
                // The bare `debug` accessor is only available inside
                // buildTypes { } (AGP registers it there), so referencing
                // `debug.signingConfig` from inside signingConfigs { } fails
                // Kotlin DSL script compilation and breaks the whole build.
                val debugSigning = signingConfigs.getByName("debug")
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                    .takeIf { it.exists() }
                    ?: debugSigning.storeFile
                storePassword = debugSigning.storePassword
                keyAlias = debugSigning.keyAlias
                keyPassword = debugSigning.keyPassword
            }
            // Sign with v1 + v2 + v3 for maximum installer compatibility
            // (GrapheneOS and other strict installers are happiest with v1
            // present). The v1 JAR signature used to be corrupted by
            // BouncyCastle's META-INF/versions/** OSGi entries, which carry
            // POSIX permission bits; those entries are excluded in the
            // packaging block below so v1 is clean.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle ships multi-release OSGi manifests under
            // META-INF/versions/ with POSIX permission attributes that corrupt
            // the v1 (JAR) APK signature. They're unused at runtime — excluding
            // them keeps the v1 signature valid so the APK installs everywhere
            // (including GrapheneOS).
            excludes += "META-INF/versions/**"
            excludes += "META-INF/services/**"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.okhttp)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Crypto
    implementation(libs.bouncycastle.prov)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Logging
    implementation(libs.timber)

    // QR code
    implementation(libs.zxing.core)

    // Media3 — video compression/transcoding (also strips embedded metadata)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.container)

}
