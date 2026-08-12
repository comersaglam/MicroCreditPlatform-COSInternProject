plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.navigation.safeargs)
    // KSP runs Hilt's processor, which generates the injector for the Application,
    // the Activity, the Fragments and the ViewModels.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.app_pos"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.app_pos"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Feeds the debug-only network security config (res/xml). Must be the SAME value
            // core-network builds API_BASE_URL from, or the app would call a host it is not
            // allowed to reach in cleartext -- and the failure would look like a dead server.
            resValue(
                "string",
                "debug_api_host",
                (project.findProperty("posApiHost") as String?) ?: "10.0.2.2"
            )
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        // Java 21 = the Android Studio JBR, which ships jlink. This keeps JdkImageTransform
        // on a jlink-capable JDK (fixes the jlink-not-found error) AND avoids Gradle trying to
        // download a JDK we don't have (we have 11 and 21 installed, not 17). minSdk (24) is
        // unchanged, so device compatibility is unaffected — compile-time language level only
        // (D8 desugars to Dalvik regardless).
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        viewBinding = true
        // Off by default in AGP 9. Needed for the debug-only debug_api_host string that the
        // network security config points at; BuildConfig stays off here (:core-network owns
        // API_BASE_URL, and NetworkConfig.isDebug already carries the build type).
        resValues = true
    }
}

dependencies {
    // Domain models (Customer, Transaction, enums) live in the pure-Kotlin module.
    implementation(project(":core-domain"))
    // Room-backed persistence + the Repository implementation.
    implementation(project(":core-data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Background sync: the OS drains the outbox even while the app is closed.
    implementation(libs.androidx.work.runtime.ktx)
    // @HiltWorker support. Its processor is AndroidX's, separate from Dagger's above —
    // both have to run, or the Worker's factory is never generated.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
