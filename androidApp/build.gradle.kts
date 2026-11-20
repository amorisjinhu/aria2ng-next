import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// release.jks + keystore.properties are gitignored local secrets. Missing on
// CI → release falls back to debug signing (still installable/testable).
val keystoreProps =
    Properties().apply {
        rootProject
            .file("keystore.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val releaseKeystoreReady = keystoreProps["storeFile"] != null

android {
    namespace = "com.slipstream"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystoreReady) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            } else {
                initWith(signingConfigs.getByName("debug"))
            }
        }
    }

    defaultConfig {
        applicationId = "com.aria2ngnext"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 5
        versionName = "0.1.4"
        // UTC build stamp for the About page — distinguishes same-version builds.
        // Config-time value: a config-cache hit reuses it, which is fine —
        // unchanged config means the same binary anyway.
        buildConfigField(
            "String",
            "BUILD_TIME_UTC",
            "\"${Instant.now().toString().substringBefore('.')}Z\"",
        )
    }

    buildTypes {
        debug {
            // Co-installable with release: separate package, separate
            // DataStore/download dir — and a different default RPC port so
            // both engines can even run at once without a bind conflict.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appName"] = "aria2ng-next D"
            buildConfigField("int", "DEFAULT_RPC_PORT", "6803")
            buildConfigField("int", "DEFAULT_EXT_PORT", "6903")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            applicationIdSuffix = ".release"
            manifestPlaceholders["appName"] = "aria2ng-next"
            buildConfigField("int", "DEFAULT_RPC_PORT", "6802")
            buildConfigField("int", "DEFAULT_EXT_PORT", "6902")
            signingConfig = signingConfigs["release"]
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep .so uncompressed+extractable so it lands in nativeLibraryDir and can be exec()ed
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    testImplementation(libs.junit)
    // Plain Android modules don't auto-map kotlin-test to a framework —
    // the junit variant carries the @Test mapping explicitly.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
