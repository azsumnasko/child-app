package com.childhelper.core.network.di

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that allows dynamically overriding the base URL
 * at runtime. Used when the parent app scans a QR code from the child
 * and needs to call the server URL embedded in the QR.
 *
 * Thread-safe: the [baseUrl] property is @Volatile.
 */
class DynamicBaseUrlInterceptor : Interceptor {

    @Volatile
    var baseUrl: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val overrideUrl = baseUrl
        if (overrideUrl != null) {
            val newHttpUrl = overrideUrl.trimEnd('/').toHttpUrlOrNull()
            val originalHttpUrl = originalRequest.url
            if (newHttpUrl != null && newHttpUrl.host != originalHttpUrl.host) {
                val newUrl = originalHttpUrl.newBuilder()
                    .scheme(newHttpUrl.scheme)
                    .host(newHttpUrl.host)
                    .port(newHttpUrl.port)
                    .build()
                return chain.proceed(originalRequest.newBuilder().url(newUrl).build())
            }
        }
        return chain.proceed(originalRequest)
    }
}
