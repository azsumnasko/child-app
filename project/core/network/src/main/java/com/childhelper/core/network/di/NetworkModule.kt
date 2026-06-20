package com.childhelper.core.network.di

import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.network.BuildConfig
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.api.SignalingApi
import com.childhelper.core.network.notification.FcmNotificationSender
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.inject.Singleton

typealias DeviceIdProvider = () -> String

/**
 * Hilt module providing network-layer dependencies as singletons.
 *
 * This module wires up:
 * - [OkHttpClient] with configurable timeouts and a logging interceptor for debug builds.
 * - [Retrofit] with kotlinx.serialization converter.
 * - [PairingApi] and [SignalingApi] Retrofit service interfaces.
 * - [NetworkUtil] for connectivity checking.
 * - [Json] serializer with lenient configuration.
 *
 * The base URL is read from [BuildConfig.API_BASE_URL] which can be overridden
 * via the `API_BASE_URL` Gradle project property at build time.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provides the configured [Json] serializer instance used across
     * the network layer for request/response serialization.
     *
     * Configuration:
     * - `ignoreUnknownKeys = true`: Tolerates new fields added by the server.
     * - `isLenient = true`: Allows more flexible JSON parsing.
     * - `encodeDefaults = true`: Ensures all fields are sent in requests.
     * - `explicitNulls = false`: Omits null values to reduce payload size.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = BuildConfig.DEBUG
    }

    /**
     * Provides the [OkHttpClient] used by Retrofit for all HTTP operations.
     *
     * Features:
     * - Connect timeout: 15 seconds.
     * - Read/write timeout: 30 seconds.
     * - HTTP logging interceptor in DEBUG builds (HEADERS level — safer than BODY).
     * - Certificate pinning for the pairing/signaling API domain (release only).
     * - No interceptors that could leak sensitive data in production.
     *
     * ## Certificate Pinning
     *
     * Certificate pinning validates that the server presents a certificate chain
     * containing a known public-key hash. This is defense-in-depth against
     * compromised certificate authorities or rogue trust-store updates.
     *
     * ### Debug builds
     * Pinning is **disabled** to allow development with self-signed certificates,
     * local proxies, and emulator networking.
     *
     * ### Release builds
     * The placeholder pin hash below MUST be replaced with the real hash before
     * shipping to production. If the placeholder is still present, a warning is
     * logged but the app continues to function (fail-open to avoid bricking
     * devices during development; operational pinning decisions belong to CI).
     *
     * ### Obtaining the real pin hash
     *
     * Run the following command against the production endpoint:
     *
     * ```bash
     * openssl s_client -servername api.childhelper.com -connect api.childhelper.com:443 </dev/null \
     *   | openssl x509 -pubkey -noout \
     *   | openssl pkey -pubin -outform der \
     *   | openssl dgst -sha256 -binary \
     *   | openssl enc -base64
     * ```
     *
     * The output is a Base64 string. Prefix it with `sha256/` when adding to the
     * pinner:
     *
     * ```kotlin
     * .add("api.childhelper.com", "sha256/REAL_HASH_HERE=")
     * ```
     *
     * Always pin **at least two independent hashes** (e.g. leaf certificate +
     * intermediate CA) to prevent lock-out when the leaf certificate rotates.
     */
    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(): DynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor()

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .addInterceptor(dynamicBaseUrlInterceptor)

        val apiHost = try {
            java.net.URI(BuildConfig.API_BASE_URL).host ?: PINNED_DOMAIN
        } catch (_: Exception) {
            PINNED_DOMAIN
        }

        // Certificate pinning is SKIPPED in debug builds so developers can use
        // self-signed certificates, local proxies (e.g. Charles Proxy, mitmproxy)
        // and emulator networking without connection failures.
        //
        // In release builds we enforce pinning. If the placeholder hash is still
        // present we log a prominent warning but do NOT crash — this avoids
        // bricking devices if a build accidentally goes to production without a
        // real pin, while making the misconfiguration highly visible in logs.
        if (!BuildConfig.DEBUG) {
            if (PIN_PLACEHOLDER.contains("AAAAA")) {
                logger.warning("CERTIFICATE PINNING NOT CONFIGURED. See NetworkModule for instructions.")
            } else {
                val certificatePinner = CertificatePinner.Builder()
                    .add(apiHost, PIN_PLACEHOLDER)
                    .build()
                builder.certificatePinner(certificatePinner)
            }
        }

        // Add logging interceptor only in debug builds. Use HEADERS level (not BODY)
        // to avoid leaking sensitive metadata (pairing session IDs, device IDs,
        // alert payloads, TURN server credentials) into logcat / bugreport.
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    /**
     * Provides the [Retrofit] instance configured with the base URL,
     * OkHttp client, and kotlinx.serialization converter factory.
     *
     * @param okHttpClient The OkHttp client for HTTP transport.
     * @param json The JSON serializer configuration.
     * @return Configured Retrofit instance.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    /**
     * Provides the [PairingApi] Retrofit service interface.
     *
     * @param retrofit The Retrofit instance to create the service from.
     */
    @Provides
    @Singleton
    fun providePairingApi(retrofit: Retrofit): PairingApi =
        retrofit.create(PairingApi::class.java)

    /**
     * Provides the [SignalingApi] Retrofit service interface.
     *
     * @param retrofit The Retrofit instance to create the service from.
     */
    @Provides
    @Singleton
    fun provideSignalingApi(retrofit: Retrofit): SignalingApi =
        retrofit.create(SignalingApi::class.java)

    /**
     * Provides a lambda that supplies the current child device ID.
     *
     * Injected into [WebRtcSignalingClient] so it can address messages
     * correctly without depending on secure preferences directly.
     *
     * @param securePreferences Secure storage for device ID retrieval.
     */
    @Provides
    @Singleton
    fun provideDeviceIdProvider(
        securePreferences: com.childhelper.core.security.SecurePreferences
    ): DeviceIdProvider {
        return {
            if (cachedDeviceId != null) cachedDeviceId!!
            else kotlinx.coroutines.runBlocking {
                val id = securePreferences.getString("device_id", "") ?: ""
                cachedDeviceId = id
                id
            }
        }
    }

    @Volatile
    private var cachedDeviceId: String? = null

    // Timeouts
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    // Certificate pinning domain (fallback if API_BASE_URL cannot be parsed)
    private const val PINNED_DOMAIN = "api.childhelper.com"

    /**
     * Placeholder SHA-256 certificate pin.
     *
     * **IMPORTANT:** Replace this with the real pin hash before production
     * deployment. The placeholder is intentionally invalid — no real certificate
     * will ever match it. To obtain the correct hash see the KDoc on
     * [provideOkHttpClient].
     */
    private const val PIN_PLACEHOLDER = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private val logger = Logger.getLogger(NetworkModule::class.java.name)
}
