package com.childhelper.core.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * P2P communication using Android's built-in WiFi Direct + TCP sockets.
 *
 * ZERO external dependencies — no Google Play Services, no server, no Firebase.
 * Works on all Android devices since API 16.
 *
 * How it works:
 * 1. Both devices enable WiFi Direct discovery
 * 2. Devices find each other via WiFi Direct peer discovery
 * 3. The "group owner" starts a TCP server socket
 * 4. The "client" connects to the group owner's IP
 * 5. All messages (alerts, signaling, status) flow over this TCP socket
 */
class LocalP2pManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    private val _peerFlow = MutableStateFlow<LocalPeerState>(LocalPeerState.Idle)
    val peerFlow: StateFlow<LocalPeerState> = _peerFlow.asStateFlow()

    private val _messageFlow = MutableSharedFlow<P2pMessage>(extraBufferCapacity = 64)
    val messageFlow: SharedFlow<P2pMessage> = _messageFlow.asSharedFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var thisDeviceName = "ChildHelper"
    private var groupOwnerAddress: String? = null

    // --- WiFi Direct initialization ---

    fun initialize(deviceName: String) {
        thisDeviceName = deviceName
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "WiFi Direct initialization failed")
            _peerFlow.value = LocalPeerState.Error("WiFi Direct not available on this device")
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(wifiDirectReceiver, intentFilter)
    }

    // --- Discovery ---

    fun startDiscovery() {
        val ch = channel ?: return
        wasDiscovering = true
        wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "WiFi Direct discovery started")
                _peerFlow.value = LocalPeerState.Discovering
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Discovery failed: $reason")
                wasDiscovering = false
                _peerFlow.value = LocalPeerState.Error("Discovery failed (code $reason)")
            }
        })
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            wps.setup = WpsInfo.PBC
        }
        wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "Connecting to ${peer.name}") }
            override fun onFailure(reason: Int) {
                _peerFlow.value = LocalPeerState.Error("Connection failed (code $reason)")
            }
        })
    }

    // --- Messaging ---

    suspend fun sendMessage(message: P2pMessage): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val socket = clientSocket
            if (socket != null && socket.isConnected) {
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(json.encodeToString(message))
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Not connected"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        clientSocket?.close()
        serverSocket?.close()
        clientSocket = null
        serverSocket = null
        groupOwnerAddress = null
        wasDiscovering = false
        channel?.let { wifiP2pManager?.cancelConnect(it, null) }
        _peerFlow.value = LocalPeerState.Idle
    }

    fun destroy() {
        disconnect()
        try { context.unregisterReceiver(wifiDirectReceiver) } catch (_: Exception) {}
        scope.cancel()
        wifiP2pManager?.removeGroup(channel, null)
    }

    // --- Internal ---

    private var wasDiscovering = false

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    when (state) {
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                            Log.i(TAG, "WiFi Direct enabled")
                            // Auto-retry discovery if we were in the middle of pairing
                            if (wasDiscovering) {
                                startDiscovery()
                            }
                        }
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                            Log.w(TAG, "WiFi Direct disabled")
                            wasDiscovering = _peerFlow.value is LocalPeerState.Discovering
                            _peerFlow.value = LocalPeerState.Error("WiFi Direct was turned off. Please enable WiFi.")
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                    )
                    if (device != null) {
                        thisDeviceName = device.deviceName.take(20)
                        Log.i(TAG, "This device changed: $thisDeviceName (${device.status})")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    channel?.let { ch ->
                        wifiP2pManager?.requestPeers(ch) { peers: WifiP2pDeviceList? ->
                            val list = peers?.deviceList?.map { dev ->
                                DiscoveredPeer(
                                    name = dev.deviceName,
                                    address = dev.deviceAddress,
                                    status = dev.status
                                )
                            } ?: emptyList()
                            _discoveredPeers.value = list
                            Log.i(TAG, "Discovered ${list.size} peers: ${list.map { it.name }}")
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager?.requestConnectionInfo(channel) { info ->
                            handleConnectionInfo(info)
                        }
                    } else {
                        _peerFlow.value = LocalPeerState.Idle
                    }
                }
            }
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (info.groupFormed) {
            groupOwnerAddress = if (info.isGroupOwner) null else info.groupOwnerAddress.hostAddress
            Log.i(TAG, "Group formed. Is owner=${info.isGroupOwner}, GO address=$groupOwnerAddress")

            if (info.isGroupOwner) {
                // Start TCP server to accept client
                startServer()
            } else {
                // Connect to group owner's server
                groupOwnerAddress?.let { connectToServer(it) }
            }
        }
    }

    private fun startServer() {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(P2P_PORT)
                _peerFlow.value = LocalPeerState.Connected("server")
                Log.i(TAG, "Server listening on port $P2P_PORT")

                while (isActive) {
                    val socket = serverSocket!!.accept()
                    clientSocket = socket
                    Log.i(TAG, "Client connected: ${socket.inetAddress}")
                    readMessages(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server error", e)
                _peerFlow.value = LocalPeerState.Error("Server: ${e.message}")
            }
        }
    }

    private fun connectToServer(host: String) {
        scope.launch(Dispatchers.IO) {
            var retries = 10
            while (retries > 0 && isActive) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, P2P_PORT), 5000)
                    clientSocket = socket
                    _peerFlow.value = LocalPeerState.Connected("client")
                    Log.i(TAG, "Connected to server at $host:$P2P_PORT")
                    readMessages(socket)
                    return@launch
                } catch (e: Exception) {
                    retries--
                    if (retries > 0) delay(1000)
                    else _peerFlow.value = LocalPeerState.Error("Failed to connect: ${e.message}")
                }
            }
        }
    }

    private suspend fun readMessages(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                var line: String?
                while (isActive && socket.isConnected) {
                    line = reader.readLine() ?: break
                    try {
                        val message = json.decodeFromString<P2pMessage>(line)
                        _messageFlow.emit(message)
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid message: $line", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Read error", e)
            } finally {
                _peerFlow.value = LocalPeerState.Idle
            }
        }
    }

    companion object {
        private const val TAG = "LocalP2pManager"
        private const val P2P_PORT = 9876
        private const val SERVICE_TYPE = "_childhelper._tcp"
    }
}

data class DiscoveredPeer(
    val name: String,
    val address: String,
    val status: Int
)

sealed class LocalPeerState {
    data object Idle : LocalPeerState()
    data object Discovering : LocalPeerState()
    data class Connected(val role: String) : LocalPeerState()
    data class Error(val message: String) : LocalPeerState()
}
