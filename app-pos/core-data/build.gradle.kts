plugins {
    alias(libs.plugins.android.library)
    // KSP runs Room's annotation processor (generates the DAO/database code) and Hilt's.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
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
    // Domain models (Customer, Transaction, User, OrderBody…) live in the pure module.
    implementation(project(":core-domain"))
    // The API surface + TokenStore. This module composes local and remote; the network
    // module knows nothing about Room, so the dependency only points this way.
    // `api` rather than `implementation`: :app injects TokenStore (to prime it) and reads
    // ApiResult, so those types have to stay on its compile classpath.
    api(project(":core-network"))
    // ConsentStore keeps the KVKK acceptance on its own DataStore file (see
    // data/consent/ConsentStore.kt for why it is not in TokenStore, which lives in
    // :core-network and clears itself on every logout).
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)          // Flow-returning DAO queries + suspend
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)    // Flow used across the Repository API

    // Moshi is in RemoteDataSource's constructor (apiCall parses the error envelope with
    // it), so Hilt has to resolve the type here — an implementation dep of :core-network
    // would not be on this module's compile classpath.
    implementation(libs.moshi)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The session tests construct a RemoteDataSource that is never called, so Retrofit
    // only has to create the interfaces — no server, no MockWebServer.
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.converter.moshi)
    // The drain tests run against a real HTTP server, so retry classification, headers and
    // dropped sockets are exercised through the actual OkHttp stack rather than a stub.
    testImplementation(libs.okhttp.mockwebserver)
}
