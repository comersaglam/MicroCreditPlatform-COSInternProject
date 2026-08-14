package com.example.app_pos.network.di

import android.content.Context
import com.example.app_pos.network.NetworkConfig
import com.example.app_pos.network.api.ApprovalApi
import com.example.app_pos.network.api.AuthApi
import com.example.app_pos.network.api.BuyerApi
import com.example.app_pos.network.api.CustomerApi
import com.example.app_pos.network.api.LedgerApi
import com.example.app_pos.network.api.SyncApi
import com.example.app_pos.network.api.UserApi
import com.example.app_pos.network.auth.AuthInterceptor
import com.example.app_pos.network.auth.DataStoreTokenStore
import com.example.app_pos.network.auth.TokenAuthenticator
import com.example.app_pos.network.auth.TokenStore
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** The OkHttp client WITHOUT auth, used only for the sign-in and refresh endpoints. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

/** The OkHttp client that attaches the bearer token and refreshes it on a 401. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiClient

/**
 * Builds the network graph as singletons.
 *
 * There are deliberately TWO OkHttp clients. The authenticated one carries the token and
 * renews it when the server answers 401; the bare one serves AuthApi. If sign-in and
 * refresh went through the authenticated client, a 401 during refresh would trigger
 * another refresh — the loop is prevented by construction rather than by a guard.
 *
 * The two share a connection pool and dispatcher (via newBuilder), so the split costs one
 * object, not a second thread pool.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        DataStoreTokenStore(context)

    @Provides
    @Singleton
    @AuthClient
    fun provideAuthClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Reconnect attempts only; retrying a request is the outbox's job, not the
            // client's — a retry here would be invisible to the queue's bookkeeping.
            .retryOnConnectionFailure(true)
            .apply { if (NetworkConfig.isDebug) addInterceptor(loggingInterceptor()) }
            .build()

    /**
     * The authenticated client, derived from the bare one so both share a connection pool
     * and dispatcher: the split costs one object, not a second thread pool.
     *
     * The authenticator receives AuthApi lazily because that API is built on the bare
     * client, which is being assembled here — deferring the lookup breaks the cycle.
     */
    @Provides
    @Singleton
    @ApiClient
    fun provideApiClient(
        @AuthClient authClient: OkHttpClient,
        tokens: TokenStore,
        authApi: Provider<AuthApi>
    ): OkHttpClient =
        authClient.newBuilder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens) { authApi.get() })
            .build()

    /** Retrofit for the sign-in endpoints only — no token attached, no refresh on 401. */
    @Provides
    @Singleton
    @AuthClient
    fun provideAuthRetrofit(@AuthClient client: OkHttpClient, moshi: Moshi): Retrofit =
        buildRetrofit(client, moshi)

    /** Retrofit for everything else: token attached, renewed silently on a 401. */
    @Provides
    @Singleton
    @ApiClient
    fun provideApiRetrofit(@ApiClient client: OkHttpClient, moshi: Moshi): Retrofit =
        buildRetrofit(client, moshi)

    @Provides @Singleton fun provideAuthApi(@AuthClient retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides @Singleton fun provideUserApi(@ApiClient retrofit: Retrofit): UserApi =
        retrofit.create(UserApi::class.java)

    @Provides @Singleton fun provideCustomerApi(@ApiClient retrofit: Retrofit): CustomerApi =
        retrofit.create(CustomerApi::class.java)

    @Provides @Singleton fun provideLedgerApi(@ApiClient retrofit: Retrofit): LedgerApi =
        retrofit.create(LedgerApi::class.java)

    @Provides @Singleton fun provideBuyerApi(@ApiClient retrofit: Retrofit): BuyerApi =
        retrofit.create(BuyerApi::class.java)

    @Provides @Singleton fun provideApprovalApi(@ApiClient retrofit: Retrofit): ApprovalApi =
        retrofit.create(ApprovalApi::class.java)

    @Provides @Singleton fun provideSyncApi(@ApiClient retrofit: Retrofit): SyncApi =
        retrofit.create(SyncApi::class.java)

    private fun buildRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            // The trailing slash matters: without it Retrofit drops the last path segment
            // of the base URL when resolving a relative endpoint.
            .baseUrl(NetworkConfig.baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    /**
     * Full request/response logging in debug builds only — and never the token. Without
     * the redaction every debug session prints a live bearer token into logcat, which is
     * how credentials end up pasted into bug reports.
     */
    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor()
            .setLevel(HttpLoggingInterceptor.Level.BODY)
            .apply { redactHeader("Authorization") }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
}
