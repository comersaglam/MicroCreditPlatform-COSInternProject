plugins {
    alias(libs.plugins.android.library)
    // KSP runs Room's annotation processor and Hilt's.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    // Same package as app-pos's data layer: the two apps are separate APKs with
    // different applicationIds, and the sources are a deliberate copy (like :core-domain).
    namespace = "com.example.app_pos.data"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        // Room schemas are exported so migrations can be diffed later (phase 4+).
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    compileOptions {
        // Java 21 to match :app and :core-domain (the JBR, jlink-capable, already installed).
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Domain models (Customer, Transaction, User, SellerDebt…) live in the pure module.
    implementation(project(":core-domain"))
    // `api`, not `implementation`: :app injects TokenStore and reads NetworkConfig, so those
    // types must stay on its compile classpath. Hilt is stricter still — every type named in
    // an @Inject constructor has to be resolvable by the module that generates the component.
    api(project(":core-network"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)          // Flow-returning DAO queries + suspend
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)    // Flow used across the Repository API
    // Moshi appears in RemoteDataSource's constructor, so Hilt must resolve it here.
    implementation(libs.moshi)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.converter.moshi)
    testImplementation(libs.okhttp.mockwebserver)
}
