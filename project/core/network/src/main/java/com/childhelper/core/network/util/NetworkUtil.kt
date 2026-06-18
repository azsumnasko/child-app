package com.childhelper.core.network.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for monitoring and querying network connectivity state.
 *
 * Provides both one-shot checks (e.g., [isConnected], [isWifiConnected]) and
 * reactive [Flow]-based observation of connectivity changes. Uses the
 * Android [ConnectivityManager] APIs (modern, non-deprecated approach).
 *
 * All operations are safe to call from any thread. Flow-based observation
 * uses callbackFlow for proper backpressure handling and lifecycle safety.
 *
 * @param context Application context for accessing [ConnectivityManager].
 */
@Singleton
class NetworkUtil @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService<ConnectivityManager>()

    /**
     * Returns `true` if the device currently has an active network connection
     * (Wi-Fi, cellular, Ethernet, or VPN) that is validated and capable of
     * reaching the internet.
     */
    val isConnected: Boolean
        get() = connectivityManager?.let { manager ->
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false

    /**
     * Returns `true` if the device is currently connected via Wi-Fi.
     */
    val isWifiConnected: Boolean
        get() = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

    /**
     * Returns `true` if the device is currently connected via cellular data.
     */
    val isCellularConnected: Boolean
        get() = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    /**
     * Returns `true` if the device is currently connected via VPN.
     */
    val isVpnConnected: Boolean
        get() = hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    /**
     * Returns `true` if the device is currently connected via Ethernet.
     */
    val isEthernetConnected: Boolean
        get() = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    /**
     * A [Flow] that emits `true` when the device gains internet connectivity
     * and `false` when it loses it. Uses [distinctUntilChanged] to suppress
     * duplicate consecutive emissions.
     *
     * Collect this flow in a ViewModel or Service to react to connectivity changes:
     * ```
     * viewModelScope.launch {
     *     networkUtil.connectivityFlow.collect { isOnline ->
     *         _isOnline.value = isOnline
     *     }
     * }
     * ```
     */
    val connectivityFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isConnected)
            }

            override fun onLost(network: Network) {
                trySend(isConnected)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(isConnected)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(isConnected)

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * A [Flow] that emits the current network type as a human-readable string
     * whenever the active network changes. Possible values:
     * "wifi", "cellular", "ethernet", "vpn", "other", or "none".
     */
    val networkTypeFlow: Flow<String> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkType())
            }

            override fun onLost(network: Network) {
                trySend(getNetworkType())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getNetworkType())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(getNetworkType())

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Returns a human-readable string describing the current active network type.
     *
     * @return One of: "wifi", "cellular", "ethernet", "vpn", "other", "none".
     */
    fun getNetworkType(): String {
        val manager = connectivityManager ?: return "none"
        val network = manager.activeNetwork ?: return "none"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "none"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }

    /**
     * Checks whether the current connection has the specified transport type.
     *
     * @param transport The [NetworkCapabilities] transport constant to check.
     * @return `true` if the active network uses the specified transport.
     */
    private fun hasTransport(transport: Int): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(transport)
    }

    companion object {

        /**
         * Executes the given [block] only if the device is currently online.
         * Returns the result of [block] wrapped in [Result], or [Result.failure]
         * with a [NoConnectivityException] if offline.
         *
         * @param networkUtil The [NetworkUtil] instance to check connectivity with.
         * @param block The suspending block to execute if online.
         * @return [Result] containing the block's result or a connectivity failure.
         */
        suspend inline fun <T> ifConnected(
            networkUtil: NetworkUtil,
            block: () -> T
        ): Result<T> {
            return if (networkUtil.isConnected) {
                runCatching { block() }
            } else {
                Result.failure(NoConnectivityException())
            }
        }

        /**
         * Extension on [Result] that returns `true` if the result is a failure
         * caused by a network connectivity issue.
         */
        fun <T> Result<T>.isConnectivityError(): Boolean {
            return isFailure && (exceptionOrNull() is NoConnectivityException ||
                exceptionOrNull() is java.net.UnknownHostException ||
                exceptionOrNull() is java.net.SocketTimeoutException ||
                exceptionOrNull() is java.io.IOException)
        }
    }
}

/**
 * Exception thrown when a network operation is attempted while the device
 * has no active internet connection.
 */
class NoConnectivityException : Exception("No active internet connection available")