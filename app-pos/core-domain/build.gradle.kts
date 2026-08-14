plugins {
    // Pure Kotlin/JVM module, no Android. No version here on purpose: AGP 9 puts
    // the Kotlin Gradle Plugin on the build classpath (with an unmanaged version),
    // so re-declaring a version would clash. We just apply the one already present.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21) // match :app; Java 21 = the Android Studio JBR (jlink-capable, already installed)
}

dependencies {
    // Coroutines core only, for the Flow type on the Repository contract. Still pure
    // JVM (no Android): the domain stays free of Room/Retrofit/Android imports — the
    // compiler enforces that purity.
    implementation(libs.kotlinx.coroutines.core)

    // Pure-JVM tests for the pure-JVM rules that live here (phone canonicalisation).
    // No Android test runner is involved.
    testImplementation(libs.junit)
}
