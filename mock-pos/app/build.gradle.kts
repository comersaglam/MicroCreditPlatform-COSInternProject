plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mock_pos"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The REAL payment gateway's id, claimed on purpose. This app stands in for it, so
        // app-pos must be able to address it exactly as it will address the real one on a
        // Token terminal:
        //
        //   am start -n com.tokeninc.sardis.paymentgateway/.MainActivity --es orderBody '…'
        //
        // Keeping com.example.mock_pos here would mean app-pos carried a development-only
        // package name in its own source, and the one thing that must not be a mock at
        // integration time is the address of the thing being integrated with.
        //
        // `namespace` stays com.example.mock_pos: that is the Kotlin package (where the
        // source lives), separate from the applicationId (how Android addresses the app).
        // The activity alias in the manifest bridges the two.
        applicationId = "com.tokeninc.sardis.paymentgateway"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
        // (D8 desugars to Dalvik regardless). Kept in sync with app-pos.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}