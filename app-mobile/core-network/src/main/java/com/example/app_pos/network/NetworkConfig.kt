package com.example.app_pos.network

/**
 * Where this build talks to. Set per build type in the module's Gradle file: the Prism
 * contract mock in debug, the real backend in release.
 *
 * Reading BuildConfig through this object keeps generated-code access in one place, so
 * nothing else in the module has to import it.
 */
object NetworkConfig {
    val baseUrl: String get() = BuildConfig.API_BASE_URL
    val isDebug: Boolean get() = BuildConfig.DEBUG
}
