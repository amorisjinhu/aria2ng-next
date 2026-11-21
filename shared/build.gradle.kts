plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.slipstream.shared"
        compileSdk =
            libs.versions.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: these types appear in the module's public
            // surface (JsonObject, StateFlow, suspend APIs) — consumers must see them.
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        // Pure-logic unit tests live in androidApp/src/test (its test classpath
        // includes shared). This module has not opted into host tests, so the
        // auto-created commonTest source set is wired to no compilation; the AGP
        // KMP plugin does support host tests via an explicit withHostTest opt-in.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}
