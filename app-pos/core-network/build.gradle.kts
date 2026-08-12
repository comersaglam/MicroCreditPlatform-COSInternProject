plugins {
    alias(libs.plugins.android.library)
    // KSP runs Moshi's adapter generator and Hilt's processor.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.app_pos.network"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        debug {
            // Where a debug build looks for the backend (docker compose publishes it on
            // 4010). Overridable because the right answer depends on the device:
            //
            //   emulator        10.0.2.2      its alias for the host; 127.0.0.1 is itself
            //   physical device the host's LAN IP, reachable over the same Wi-Fi
            //
            // Set `posApiHost` in a local gradle.properties (untracked) rather than editing
            // this line, so a changed LAN address never shows up as a source diff.
            val apiHost = (project.findProperty("posApiHost") as String?) ?: "10.0.2.2"
            buildConfigField("String", "API_BASE_URL", "\"http://$apiHost:4010/\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.veresiye.example/\"")
        }
    }

    buildFeatures {
        // Carries API_BASE_URL; build types, not flavours, so the variant matrix stays at two.
        buildConfig = true
    }

    compileOptions {
        // Java 21 to match :app and :core-data (the JBR, jlink-capable, already installed).
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    // Domain models, so mappers can turn DTOs into them. This module knows the domain;
    // the domain does not know this module.
    implementation(project(":core-domain"))

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Token persistence (the session outlives the process).
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
