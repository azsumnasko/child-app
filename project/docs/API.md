# Privacy-First Child Helper — API Documentation

> **Version:** 1.0  
> **Module:** `core/network`  
> **Base Package:** `com.childhelper.core.network`  

---

## Table of Contents

1. [Backend API Overview](#1-backend-api-overview)
2. [Pairing API Endpoints](#2-pairing-api-endpoints)
3. [Signaling API Endpoints](#3-signaling-api-endpoints)
4. [Data Models](#4-data-models)
5. [WebRTC Signaling Protocol](#5-webrtc-signaling-protocol)
6. [Firebase Cloud Messaging](#6-firebase-cloud-messaging)
7. [Error Codes](#7-error-codes)
8. [Rate Limits & Security](#8-rate-limits--security)

---

## 1. Backend API Overview

### Base URL Configuration

The API base URL is configured at build time via the `API_BASE_URL` Gradle project property and injected through `BuildConfig.API_BASE_URL`.

| Environment | Example Base URL |
|---|---|
| Production | `https://api.childhelper.com/` |
| Staging | `https://staging-api.childhelper.com/` |
| Development | `http://10.0.2.2:8080/` (emulator localhost) |

**Configuration in `NetworkModule.kt`:**

```kotlin
val contentType = "application/json".toMediaType()
return Retrofit.Builder()
    .baseUrl(BuildConfig.API_BASE_URL)   // Build-configurable
    .client(okHttpClient)
    .addConverterFactory(json.asConverterFactory(contentType))
    .build()
```

### Authentication

This app uses **device-based authentication** — no user passwords, accounts, or OAuth flows are required.

- Each device generates a **unique, stable device ID** on first launch (stored in `EncryptedSharedPreferences`).
- The device ID is included in request bodies (e.g., `childDeviceId`, `fromDeviceId`) and path parameters.
- Pairing establishes a **shared ECDH secret**; subsequent signaling messages are implicitly authorized by the pairing session.
- No Authorization headers or bearer tokens are used.

### Content-Type

All request and response bodies use:

```
Content-Type: application/json
```

Serialization is handled by `kotlinx.serialization` with Retrofit converter factory.

### HTTPS & Certificate Pinning

All production traffic is **HTTPS only**. Certificate pinning is configured in `NetworkModule.kt`:

```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add(apiHost, "sha256/<BASE64_CERT_HASH>")
    .build()
```

| Property | Value |
|---|---|
| Pinned Domain | `api.childhelper.com` (fallback) |
| Pin Format | SHA-256 Base64 hash of the leaf/spki public key |
| Pinning Scope | All pairing and signaling endpoints |
| Defense Model | Defense-in-depth against compromised device trust stores |

**To obtain the certificate hash:**
```bash
openssl s_client -connect api.childhelper.com:443 </dev/null 2>/dev/null | \
  openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
  openssl dgst -sha256 -binary | openssl enc -base64
```

### Network Timeouts

| Timeout | Value |
|---|---|
| Connect Timeout | 15 seconds |
| Read Timeout | 30 seconds |
| Write Timeout | 30 seconds |
| Retry on Failure | `true` |

---

## 2. Pairing API Endpoints

**Retrofit Interface:** `com.childhelper.core.network.api.PairingApi`

Pairing establishes a trust relationship between the child device and parent device using a short-lived pairing code exchanged out-of-band. After pairing, devices derive a shared ECDH secret for encrypting subsequent communications.

---

### POST `/api/v1/pairing/initiate`

Child device initiates pairing and receives a 6-character alphanumeric pairing code.

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/pairing/initiate` |
| **Auth** | Device ID + Public Key |
| **Request Body** | `InitiatePairingRequest` |
| **Response** | `PairingSession` |

**Request Body (`InitiatePairingRequest`):**

| Field | Type | Required | Description |
|---|---|---|---|
| `childDeviceId` | `string` | Yes | Unique identifier of the child device |
| `childPublicKey` | `string` | Yes | X25519/ECDH public key, Base64-encoded |

**Response (`PairingSession`):**

| Field | Type | Description |
|---|---|---|
| `sessionId` | `string` | Unique session UUID |
| `pairingCode` | `string` | 6-character alphanumeric code (e.g., `A3B9K7`) |
| `childDeviceId` | `string` | The child device that initiated pairing |
| `parentDeviceId` | `string?` | `null` until parent completes pairing |
| `childPublicKey` | `string?` | The child's public key (set on initiate) |
| `parentPublicKey` | `string?` | `null` until parent completes pairing |
| `status` | `PairingStatus` | `PENDING` on initial response |
| `createdAt` | `long` | Unix timestamp (ms) when session created |
| `expiresAt` | `long` | Unix timestamp (ms) when code expires (createdAt + 5 min) |

**Kotlin Usage:**

```kotlin
val request = InitiatePairingRequest(
    childDeviceId = deviceId,
    childPublicKey = base64PublicKey
)
val session: PairingSession = pairingApi.initiatePairing(request)
// Display session.pairingCode on the child device screen
```

**Errors:**

| HTTP Status | ErrorCode | Meaning |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed request or missing fields |
| 409 | `PAIRING_ERROR` | Device already has an active pairing |
| 500 | `SERVER_ERROR` | Internal server error |

---

### POST `/api/v1/pairing/complete`

Parent device completes pairing by entering the pairing code displayed on the child device.

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/pairing/complete` |
| **Auth** | Pairing code (via sessionId) + Parent public key |
| **Request Body** | `CompletePairingRequest` |
| **Response** | `PairingSession` |

**Request Body (`CompletePairingRequest`):**

| Field | Type | Required | Description |
|---|---|---|---|
| `sessionId` | `string` | Yes | Session ID from the child's `initiatePairing` response |
| `parentDeviceId` | `string` | Yes | Unique identifier of the parent device |
| `parentPublicKey` | `string` | Yes | X25519/ECDH public key, Base64-encoded |

**Response (`PairingSession`):**

Returns the updated session with `status = COMPLETED`, `parentDeviceId`, and `parentPublicKey` populated.

**Kotlin Usage:**

```kotlin
val request = CompletePairingRequest(
    sessionId = pairingCode,          // The code the parent sees
    parentDeviceId = parentDeviceId,
    parentPublicKey = base64PublicKey
)
val session: PairingSession = pairingApi.completePairing(request)
// Derive shared secret: ECDH(childPublicKey, parentPrivateKey)
```

**Errors:**

| HTTP Status | ErrorCode | Meaning |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed request |
| 404 | `PAIRING_ERROR` | Session not found |
| 410 | `PAIRING_ERROR` | Pairing code expired (valid for 5 minutes) |
| 409 | `PAIRING_ERROR` | Pairing already completed or revoked |
| 500 | `SERVER_ERROR` | Internal server error |

---

### POST `/api/v1/pairing/revoke`

Revokes an active pairing session, permanently unlinking the two devices.

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/pairing/revoke` |
| **Auth** | Any paired device may request revocation |
| **Request Body** | `RevokePairingRequest` |
| **Response** | `204 No Content` |

**Request Body (`RevokePairingRequest`):**

| Field | Type | Required | Description |
|---|---|---|---|
| `sessionId` | `string` | Yes | The pairing session ID to revoke |
| `deviceId` | `string` | Yes | ID of the device requesting revocation (audit logging) |

**Kotlin Usage:**

```kotlin
val request = RevokePairingRequest(
    sessionId = session.sessionId,
    deviceId = deviceId          // Either child or parent device ID
)
pairingApi.revokePairing(request)
// Shared secret is now invalidated; devices must re-pair to communicate
```

**Errors:**

| HTTP Status | ErrorCode | Meaning |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed request |
| 404 | `PAIRING_ERROR` | Session not found |
| 403 | `PAIRING_ERROR` | Device not authorized to revoke this session |
| 500 | `SERVER_ERROR` | Internal server error |

---

### GET `/api/v1/pairing/status/{sessionId}`

Retrieves the current status of a pairing session. Used for polling to detect completion.

| Property | Value |
|---|---|
| **Method** | `GET` |
| **Path** | `/api/v1/pairing/status/{sessionId}` |
| **Path Param** | `sessionId` — the pairing session UUID |
| **Response** | `PairingSession` |

**Kotlin Usage:**

```kotlin
val session: PairingSession = pairingApi.getPairingStatus(sessionId)
when (session.status) {
    PairingStatus.PENDING  -> showWaitingUi()
    PairingStatus.COMPLETED -> proceedToDashboard()
    PairingStatus.EXPIRED  -> showExpiredPrompt()
    PairingStatus.REVOKED  -> showRevokedPrompt()
}
```

**PairingStatus Enum:**

| Value | Meaning |
|---|---|
| `PENDING` | Pairing initiated; waiting for parent to enter code |
| `COMPLETED` | Parent entered code; pairing active |
| `REVOKED` | Pairing was explicitly revoked by either party |
| `EXPIRED` | Pairing code expired before completion (5-minute lifetime) |

---

### POST `/api/v1/turn/credentials`

Obtains time-limited TURN server credentials for NAT traversal during WebRTC calls.

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/turn/credentials` |
| **Response** | `TurnCredentials` |

**Response (`TurnCredentials`):**

| Field | Type | Description |
|---|---|---|
| `username` | `string` | Time-scoped TURN username |
| `password` | `string` | TURN authentication password |
| `urls` | `string[]` | TURN server URLs (e.g., `turn:turn.childhelper.com:3478`) |

**Kotlin Usage:**

```kotlin
val turnCredentials: TurnCredentials = pairingApi.getTurnCredentials()

// Configure WebRTC PeerConnection with TURN servers
val iceServers = turnCredentials.urls.map { url ->
    PeerConnection.IceServer.builder(url)
        .setUsername(turnCredentials.username)
        .setPassword(turnCredentials.password)
        .createIceServer()
}
```

> **Privacy Note:** No media content ever passes through TURN servers — only relay metadata. All media is encrypted end-to-end via DTLS-SRTP.

---

## 3. Signaling API Endpoints

**Retrofit Interface:** `com.childhelper.core.network.api.SignalingApi`

Signaling is the process by which two peers exchange control messages (SDP offers/answers and ICE candidates) to establish a peer-to-peer WebRTC connection. **No media data flows through these endpoints** — only lightweight control metadata.

After the peer connection is established, all audio/video media travels directly between devices (or via TURN relay) without touching the server.

---

### POST `/api/v1/signal/offer`

Sends an SDP offer from the caller (parent) to the callee (child).

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/signal/offer` |
| **Request Body** | `SdpMessage` (type = `OFFER`) |
| **Response** | `204 No Content` |

**Request Body Fields:**

| Field | Type | Description |
|---|---|---|
| `messageId` | `string` | Unique message ID (e.g., `sig-<uuid>`) |
| `fromDeviceId` | `string` | Caller's device ID |
| `toDeviceId` | `string` | Callee's device ID |
| `timestamp` | `long` | Unix timestamp (ms) |
| `sessionId` | `string` | Call session identifier |
| `type` | `SdpType` | `OFFER` |
| `sdp` | `string` | Raw SDP offer string |

---

### POST `/api/v1/signal/answer`

Sends an SDP answer from the callee (child) back to the caller (parent).

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/signal/answer` |
| **Request Body** | `SdpMessage` (type = `ANSWER`) |
| **Response** | `204 No Content` |

**Request Body Fields:**

Same schema as `offer`, but `type` = `ANSWER` and `sdp` contains the answer SDP string.

---

### POST `/api/v1/signal/ice`

Sends an ICE candidate to the peer device during the NAT traversal phase.

| Property | Value |
|---|---|
| **Method** | `POST` |
| **Path** | `/api/v1/signal/ice` |
| **Request Body** | `IceMessage` |
| **Response** | `204 No Content` |

**Request Body Fields:**

| Field | Type | Description |
|---|---|---|
| `messageId` | `string` | Unique message ID |
| `fromDeviceId` | `string` | Sender's device ID |
| `toDeviceId` | `string` | Recipient's device ID |
| `timestamp` | `long` | Unix timestamp (ms) |
| `sessionId` | `string` | Call session identifier |
| `candidate` | `string` | ICE candidate SDP string |
| `sdpMLineIndex` | `int` | SDP media line index |
| `sdpMid` | `string` | SDP media identifier |

---

### GET `/api/v1/signal/pending/{deviceId}`

Polls for pending signaling messages addressed to this device.

| Property | Value |
|---|---|
| **Method** | `GET` |
| **Path** | `/api/v1/signal/pending/{deviceId}` |
| **Path Param** | `deviceId` — the device polling for messages |
| **Response** | `SignalingMessage[]` |

Returns a JSON array of pending signaling messages (offers, answers, ICE candidates, hang-ups). The server should clear delivered messages from the pending queue. Empty array `[]` if no messages.

**Kotlin Usage (via `WebRtcSignalingClient`):**

```kotlin
// Start automatic polling (every 2 seconds by default)
signalingClient.startPolling(intervalMs = 2000L)

// Or trigger an immediate poll (e.g., after push notification)
val messages = signalingClient.pollNow()

// React to incoming messages
signalingClient.incomingOffers.collect { offer ->
    handleIncomingCall(offer)
}
signalingClient.incomingAnswers.collect { answer ->
    peerConnection.setRemoteDescription(answer.sdp)
}
signalingClient.incomingIceCandidates.collect { ice ->
    peerConnection.addIceCandidate(ice.toWebRtcCandidate())
}
signalingClient.incomingHangUps.collect { hangUp ->
    endCall(hangUp.reason)
}
```

---

## 4. Data Models

### 4.1 Pairing Request/Response DTOs

#### `InitiatePairingRequest`

```kotlin
package com.childhelper.core.network.model

@Serializable
data class InitiatePairingRequest(
    val childDeviceId: String,   // Unique child device identifier
    val childPublicKey: String   // X25519/ECDH public key, Base64-encoded
)
```

**JSON Example:**
```json
{
  "childDeviceId": "device-a1b2c3d4-e5f6-7890",
  "childPublicKey": "BF5wZGF0YQ==..."
}
```

---

#### `CompletePairingRequest`

```kotlin
package com.childhelper.core.network.model

@Serializable
data class CompletePairingRequest(
    val sessionId: String,        // Pairing session ID
    val parentDeviceId: String,   // Unique parent device identifier
    val parentPublicKey: String   // X25519/ECDH public key, Base64-encoded
)
```

**JSON Example:**
```json
{
  "sessionId": "abc123",
  "parentDeviceId": "device-x9y8z7w6-v5u4-3210",
  "parentPublicKey": "BG5vdGtleQ==..."
}
```

---

#### `RevokePairingRequest`

```kotlin
package com.childhelper.core.network.model

@Serializable
data class RevokePairingRequest(
    val sessionId: String,  // Session ID to revoke
    val deviceId: String    // Device requesting revocation (audit)
)
```

**JSON Example:**
```json
{
  "sessionId": "abc123",
  "deviceId": "device-a1b2c3d4-e5f6-7890"
}
```

---

### 4.2 Pairing Session Model

#### `PairingSession`

```kotlin
package com.childhelper.core.common.model

@Serializable
data class PairingSession(
    val sessionId: String,
    val pairingCode: String,              // 6-char alphanumeric, expires in 5 min
    val childDeviceId: String,
    val parentDeviceId: String? = null,   // Set on completion
    val childPublicKey: String? = null,   // Set on initiate
    val parentPublicKey: String? = null,  // Set on completion
    val status: PairingStatus = PairingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 5 * 60 * 1000
)
```

#### `PairingStatus` (Enum)

| Value | Description |
|---|---|
| `PENDING` | Pairing initiated; waiting for parent to enter the pairing code |
| `COMPLETED` | Parent successfully entered code and exchanged keys |
| `REVOKED` | Pairing was explicitly revoked by either party |
| `EXPIRED` | Pairing code expired before completion (valid for 5 minutes) |

---

### 4.3 TURN Credentials

#### `TurnCredentials`

```kotlin
package com.childhelper.core.network.model

@Serializable
data class TurnCredentials(
    val username: String,       // Time-scoped TURN username
    val password: String,       // TURN authentication password
    val urls: List<String>      // TURN server URLs
)
```

**JSON Example:**
```json
{
  "username": "1735689600:user-session-id",
  "password": "hmac-derived-auth-token",
  "urls": [
    "turn:turn.childhelper.com:3478",
    "turns:turn.childhelper.com:5349"
  ]
}
```

---

### 4.4 Signaling Message Types

All signaling messages extend the sealed class `SignalingMessage` and carry **only SDP/ICE metadata — NO media payload**.

#### `SignalingMessage` (Sealed Base Class)

```kotlin
package com.childhelper.core.network.signaling

@Serializable
sealed class SignalingMessage {
    abstract val messageId: String       // Unique message ID for deduplication
    abstract val fromDeviceId: String    // Sender device ID
    abstract val toDeviceId: String      // Recipient device ID
    abstract val timestamp: Long         // Unix timestamp (ms)
    abstract val sessionId: String       // Call session identifier
}
```

---

#### `SdpMessage`

Represents an SDP offer or answer.

```kotlin
@Serializable
@SerialName("sdp")
data class SdpMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val type: SdpType,          // OFFER or ANSWER
    val sdp: String             // Raw SDP string
) : SignalingMessage()
```

#### `SdpType` (Enum)

| Value | Description |
|---|---|
| `OFFER` | Session Description Protocol offer (caller -> callee) |
| `ANSWER` | Session Description Protocol answer (callee -> caller) |

**JSON Example (`SdpMessage`):**
```json
{
  "type": "sdp",
  "messageId": "sig-550e8400-e29b-41d4-a716-446655440000",
  "fromDeviceId": "device-parent-001",
  "toDeviceId": "device-child-001",
  "timestamp": 1735689600000,
  "sessionId": "call-session-001",
  "sdpType": "OFFER",
  "sdp": "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n..."
}
```

---

#### `IceMessage`

Represents an ICE candidate for NAT traversal.

```kotlin
@Serializable
@SerialName("ice")
data class IceMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val candidate: String,      // ICE candidate SDP string
    val sdpMLineIndex: Int,     // SDP media line index
    val sdpMid: String          // SDP media identifier
) : SignalingMessage()
```

**JSON Example (`IceMessage`):**
```json
{
  "type": "ice",
  "messageId": "sig-6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "fromDeviceId": "device-child-001",
  "toDeviceId": "device-parent-001",
  "timestamp": 1735689600500,
  "sessionId": "call-session-001",
  "candidate": "candidate:842163049 1 udp 1677729535 192.168.1.5 54321 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abc network-id 1",
  "sdpMLineIndex": 0,
  "sdpMid": "0"
}
```

---

#### `HangUpMessage`

Sent when a call is ended by either peer.

```kotlin
@Serializable
@SerialName("hangup")
data class HangUpMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val reason: HangUpReason = HangUpReason.USER_INITIATED
) : SignalingMessage()
```

#### `HangUpReason` (Enum)

| Value | Description |
|---|---|
| `USER_INITIATED` | Call was intentionally ended by the user |
| `CONNECTION_ERROR` | Peer connection encountered an unrecoverable error |
| `TIMEOUT` | Call timed out (no answer or stale connection) |
| `PEER_UNAVAILABLE` | The peer device is offline or unreachable |
| `NETWORK_ERROR` | General network failure during the call |

---

#### `PingMessage` / `PongMessage`

Keep-alive messages for session health monitoring.

```kotlin
@Serializable
@SerialName("ping")
data class PingMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String
) : SignalingMessage()

@Serializable
@SerialName("pong")
data class PongMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val rttMs: Long? = null    // Round-trip time hint in milliseconds
) : SignalingMessage()
```

---

### 4.5 Alert Models (FCM)

#### `Alert`

```kotlin
package com.childhelper.core.common.model

@Serializable
data class Alert(
    val id: String = UUID.randomUUID().toString(),
    val eventType: AlertType,           // Classification of the event
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float? = null,      // ML confidence 0.0–1.0 (if applicable)
    val deviceStatus: DeviceStatusSnapshot,
    val childDeviceId: String
)
```

#### `AlertType` (Enum)

| Value | Description |
|---|---|
| `CRY_DETECTED` | Cry detection model triggered above threshold |
| `MOTION_DETECTED` | Motion detection model triggered above threshold |
| `SOS_ACTIVATED` | Child manually activated SOS alert |
| `CAMERA_OBSTRUCTED` | Camera lens is physically obstructed |
| `DEVICE_OFFLINE` | Child device has gone offline |
| `LOW_BATTERY` | Battery fell below warning threshold |
| `CALL_STARTED` | Voice call was started |
| `CALL_ENDED` | Voice call was ended |

#### `DeviceStatusSnapshot`

```kotlin
package com.childhelper.core.common.model

@Serializable
data class DeviceStatusSnapshot(
    val batteryPercent: Int,        // 0–100
    val isCharging: Boolean,
    val networkType: String,        // "wifi", "cellular", "none", etc.
    val monitorMode: MonitorMode    // Current operating mode
)
```

#### `MonitorMode` (Enum)

| Value | Description |
|---|---|
| `IDLE` | No active monitoring |
| `BEDTIME` | Bedtime mode — cry/motion detection + auto-answer |
| `CALLING` | Voice/video call in progress |
| `SOS` | SOS alert active; emergency escalation in progress |

---

## 5. WebRTC Signaling Protocol

### 5.1 Overview

The signaling protocol uses the polling-based REST API described in Section 3. Messages are delivered via `POST` endpoints and retrieved via `GET /api/v1/signal/pending/{deviceId}`. Firebase Cloud Messaging push notifications trigger immediate polls for low-latency delivery.

**Key Privacy Guarantees:**
- Signaling messages carry **only** SDP and ICE metadata
- **No audio, video, or media payload** ever flows through the signaling server
- All media is encrypted end-to-end via DTLS-SRTP before transmission
- TURN relays see only encrypted packet metadata, not media content

### 5.2 Connection Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Parent Device
    participant S as Signaling Server<br/>(REST API)
    participant C as Child Device

    Note over P,C: Devices are paired via PairingApi

    P->>S: POST /api/v1/signal/offer<br/>(SdpMessage: OFFER)
    S-->>P: 204 No Content

    Note right of S: Push: signal_poll → Child
    C->>S: GET /api/v1/signal/pending/{childId}
    S-->>C: [SdpMessage: OFFER]

    C->>C: createPeerConnection()<br/>setRemoteDescription(offer)
    C->>C: createAnswer()<br/>setLocalDescription(answer)

    C->>S: POST /api/v1/signal/answer<br/>(SdpMessage: ANSWER)
    S-->>C: 204 No Content

    Note right of S: Push: signal_poll → Parent
    P->>S: GET /api/v1/signal/pending/{parentId}
    S-->>P: [SdpMessage: ANSWER]

    P->>P: setRemoteDescription(answer)

    loop ICE Candidate Exchange (multiple rounds)
        P->>S: POST /api/v1/signal/ice<br/>(IceMessage)
        C->>S: GET /api/v1/signal/pending/{childId}
        S-->>C: [IceMessage]
        C->>C: addIceCandidate()

        C->>S: POST /api/v1/signal/ice<br/>(IceMessage)
        P->>S: GET /api/v1/signal/pending/{parentId}
        S-->>P: [IceMessage]
        P->>P: addIceCandidate()
    end

    Note over P,C: DTLS handshake completes<br/>SRTP keys negotiated

    P<->C: Peer-to-Peer Encrypted Media<br/>(Direct or via TURN relay)

    alt Call End
        P->>S: POST /api/v1/signal/offer (hangup JSON)
        C->>S: GET /api/v1/signal/pending/{childId}
        S-->>C: [HangUpMessage]
        C->>C: closePeerConnection()
    end
```

### 5.3 Message Format Specification

All signaling messages share a common envelope with a `type` discriminator for polymorphic deserialization:

| Field | Type | Description |
|---|---|---|
| `type` | `string` | Message discriminator: `"sdp"`, `"ice"`, `"hangup"`, `"ping"`, `"pong"` |
| `messageId` | `string` | Unique UUID for deduplication |
| `fromDeviceId` | `string` | Sender device identifier |
| `toDeviceId` | `string` | Recipient device identifier |
| `timestamp` | `long` | Creation time (Unix epoch ms) |
| `sessionId` | `string` | Call session this message belongs to |

Type-specific fields follow the discriminator:

| Discriminator (`type`) | Additional Fields |
|---|---|
| `sdp` | `sdpType: "OFFER" \| "ANSWER"`, `sdp: string` |
| `ice` | `candidate: string`, `sdpMLineIndex: int`, `sdpMid: string` |
| `hangup` | `reason: "USER_INITIATED" \| "CONNECTION_ERROR" \| "TIMEOUT" \| "PEER_UNAVAILABLE" \| "NETWORK_ERROR"` |
| `ping` | *(none)* |
| `pong` | `rttMs: long?` |

### 5.4 Polling Configuration

The `WebRtcSignalingClient` manages automatic polling:

| Property | Default | Description |
|---|---|---|
| `POLL_INTERVAL_MS` | `2000L` (2 seconds) | Default interval between polls |
| `extraBufferCapacity` | `64` | Buffer for incoming messages SharedFlow |
| `Dispatcher` | `Dispatchers.IO` | All network I/O runs on IO dispatcher |

**Methods:**

| Method | Description |
|---|---|
| `startPolling(intervalMs)` | Begins periodic polling loop |
| `stopPolling()` | Cancels the polling job |
| `pollNow()` | Immediate one-shot poll (used after FCM push) |
| `shutdown()` | Full cleanup; client must not be reused |

**Filtered Flows:**

```kotlin
val incomingOffers: Flow<SdpMessage>        // SDP offer messages only
val incomingAnswers: Flow<SdpMessage>       // SDP answer messages only
val incomingIceCandidates: Flow<IceMessage> // ICE candidate messages only
val incomingHangUps: Flow<HangUpMessage>    // Hang-up messages only
```

### 5.5 Error Handling

| Scenario | Behavior |
|---|---|
| Polling network error | Silently retries on next interval (no crash) |
| `sendOffer`/`sendAnswer`/`sendIce` failure | Returns `Result.failure()`; caller may retry |
| Invalid SDP/ICE payload | Server rejects with 400; client logs and continues |
| Deduplication | Server tracks `messageId` to prevent double delivery |
| Stale messages | Messages older than session expiry are discarded |
| Connection timeout | `HangUpReason.TIMEOUT` emitted after grace period |

---

## 6. Firebase Cloud Messaging

**Service Class:** `com.childhelper.core.network.push.FcmService`

### 6.1 Architecture Overview

FCM is used exclusively for **event notifications and signaling triggers**. No media, location, or sensor data is ever transmitted through Firebase.

```
Child Device          FCM Server          Parent Device
    |                      |                      |
    | -- cry detected -->  |                      |
    |                      | -- push: CRY -->     |
    |                      |                      | show notification
    |                      |                      |
    | -- offer posted -->  |                      |
    |                      | -- push: signal_poll |
    |                      |                      | GET /signal/pending
    |                      |                      | receive offer
```

### 6.2 Message Payload Format

FCM payloads contain **only metadata** — event type, timestamp, and device status.

#### Alert Notification Payload

```json
{
  "data": {
    "eventType": "CRY_DETECTED",
    "alertId": "alert-1735689600000-1234",
    "timestamp": "1735689600000",
    "confidence": "0.92",
    "childDeviceId": "device-child-001",
    "batteryPercent": "78",
    "isCharging": "true",
    "networkType": "wifi",
    "monitorMode": "BEDTIME"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `eventType` | `string` | Yes | One of `AlertType` enum names |
| `alertId` | `string` | No | Unique alert identifier (auto-generated if absent) |
| `timestamp` | `string` | No | Unix timestamp in milliseconds (defaults to now) |
| `confidence` | `string` | No | ML model confidence score (0.0–1.0) |
| `childDeviceId` | `string` | Yes | Device ID that generated the alert |
| `batteryPercent` | `string` | No | Battery level 0–100 (defaults to -1) |
| `isCharging` | `string` | No | `"true"` or `"false"` (defaults to false) |
| `networkType` | `string` | No | `"wifi"`, `"cellular"`, `"none"`, or `"unknown"` |
| `monitorMode` | `string` | No | One of `MonitorMode` enum names (defaults to `IDLE`) |

#### Signaling Poll Trigger Payload

```json
{
  "data": {
    "type": "signal_poll"
  }
}
```

This is a lightweight trigger telling the receiving device to immediately poll `GET /api/v1/signal/pending/{deviceId}` for new signaling messages. The actual signaling messages are never embedded in push notifications.

### 6.3 Topics and Routing

| Topic Pattern | Subscribers | Purpose |
|---|---|---|
| `device_{deviceId}` | Parent device | All alerts and events from a specific child device |
| `pairing_{sessionId}` | Both devices | Pairing status updates and completion notifications |
| `signal_{deviceId}` | Parent or child | Signaling poll triggers for WebRTC |

**FCM Token Management:**

```kotlin
override fun onNewToken(token: String) {
    // TODO: Send the new FCM registration token to the backend
    // so it can target push notifications to this device.
}
```

### 6.4 Push-Triggered Signaling Poll

The `FcmService` handles `signal_poll` messages by triggering an immediate poll:

```kotlin
override fun onMessageReceived(remoteMessage: RemoteMessage) {
    val data = remoteMessage.data
    if (data.isEmpty()) return

    if (data["type"] == "signal_poll") {
        serviceScope.launch {
            runCatching {
                signalingClient.pollNow()
            }
        }
        return
    }

    // Parse and emit regular alerts
    val alert = parseAlert(data)
    if (alert != null) {
        _alertFlow.emit(alert)
    }
}
```

This hybrid approach combines the reliability of REST polling with the low latency of push notifications:
- **Normal operation:** Poll every 2 seconds
- **Push received:** Immediate poll bypasses the interval
- **Push unavailable:** Graceful degradation to pure polling

### 6.5 Consuming Alerts

Alerts are exposed as a `SharedFlow` from `FcmService`:

```kotlin
viewModelScope.launch {
    FcmService.alertFlow.collect { alert ->
        when (alert.eventType) {
            AlertType.CRY_DETECTED    -> showCryNotification(alert)
            AlertType.SOS_ACTIVATED   -> showSosDialog(alert)
            AlertType.LOW_BATTERY     -> showBatteryWarning(alert)
            AlertType.CALL_STARTED    -> updateCallStatus(alert)
            else -> logAlert(alert)
        }
    }
}
```

---

## 7. Error Codes

**Source:** `com.childhelper.core.common.util.ErrorCode`

The `ErrorCode` enum provides machine-readable error categorization for telemetry, retry logic, and user-facing error messages.

| Code | Value | Description |
|---|---|---|
| `UNKNOWN` | `0` | Unknown or uncategorized error |
| `KEYSTORE_ERROR` | `1` | Keystore operation failed (key not found, corruption, etc.) |
| `ENCRYPTION_ERROR` | `2` | Encryption or decryption operation failed |
| `NETWORK_ERROR` | `3` | Network request failed (timeout, no connectivity, etc.) |
| `SERVER_ERROR` | `4` | Server returned a non-2xx response |
| `PAIRING_ERROR` | `5` | Pairing code invalid, expired, or session not found |
| `CALL_ERROR` | `6` | WebRTC signaling or peer connection failure |
| `DETECTION_ERROR` | `7` | ML model inference failed or model not loaded |
| `PERMISSION_DENIED` | `8` | Required permission not granted |
| `INVALID_ARGUMENT` | `9` | Invalid argument or malformed data |

### 7.1 SafeResult Wrapper

Errors are wrapped in the `SafeResult<T>` sealed class:

```kotlin
sealed class SafeResult<out T> {
    data class Success<T>(val data: T) : SafeResult<T>()
    data class Failure(val error: String, val code: ErrorCode = ErrorCode.UNKNOWN) : SafeResult<Nothing>()
}
```

**Usage Pattern:**

```kotlin
val result = safeCall(ErrorCode.NETWORK_ERROR) {
    pairingApi.initiatePairing(request)
}

result.onSuccess { session ->
    navigateToPairingScreen(session.pairingCode)
}.onFailure { failure ->
    when (failure.code) {
        ErrorCode.NETWORK_ERROR -> showOfflineMessage()
        ErrorCode.PAIRING_ERROR -> showInvalidCodeMessage()
        else -> showGenericError(failure.error)
    }
}
```

### 7.2 HTTP Status to ErrorCode Mapping

| HTTP Status | ErrorCode | Typical Cause |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed request body, missing required fields |
| 401/403 | `PAIRING_ERROR` | Device not authorized for the requested session |
| 404 | `PAIRING_ERROR` | Session or resource not found |
| 408/504 | `NETWORK_ERROR` | Request timeout, gateway timeout |
| 409 | `PAIRING_ERROR` | Conflict (already paired, already completed) |
| 410 | `PAIRING_ERROR` | Pairing code expired |
| 429 | `NETWORK_ERROR` | Rate limit exceeded |
| 500–599 | `SERVER_ERROR` | Internal server error |

---

## 8. Rate Limits & Security

### 8.1 Pairing Code Security

| Property | Value | Rationale |
|---|---|---|
| **Code Format** | 6-character alphanumeric | ~2.2 billion combinations; easy to read/enter |
| **Code Lifetime** | 5 minutes (300 seconds) | Short window limits brute-force attacks |
| **Max Attempts** | 10 per code (recommended) | Prevents systematic guessing |
| **Code Display** | Shown only on child device | Out-of-band exchange prevents interception |
| **Auto-Expiry** | Server marks `EXPIRED` after `expiresAt` | No cleanup needed on client |

### 8.2 TURN Credential Expiry

| Property | Value | Rationale |
|---|---|---|
| **Credential Lifetime** | 24 hours (recommended) | Limits exposure if credentials leak |
| **Usage Scope** | Per-call session | New credentials per call |
| **Refresh** | Before each new call | Ensures always-valid credentials |
| **Transport Security** | DTLS-SRTP end-to-end | TURN server cannot inspect media |

### 8.3 API Rate Limits

| Endpoint | Recommended Limit | Burst |
|---|---|---|
| `POST /pairing/initiate` | 5 requests / hour / device | 2 |
| `POST /pairing/complete` | 10 requests / minute / IP | 5 |
| `POST /pairing/revoke` | 10 requests / minute / device | 5 |
| `GET /pairing/status/{id}` | 30 requests / minute / device | 10 |
| `POST /turn/credentials` | 10 requests / hour / device | 3 |
| `POST /signal/*` | 120 requests / minute / device | 30 |
| `GET /signal/pending/{id}` | 120 requests / minute / device | 30 |

### 8.4 Certificate Pinning Requirements

| Requirement | Detail |
|---|---|
| **Pin Format** | SHA-256 Base64 hash of SubjectPublicKeyInfo (SPKI) |
| **Backup Pin** | Include a secondary pin for certificate rotation |
| **Validation** | Certificate chain must contain at least one pinned public key |
| **Failure Action** | Connection fails closed (no bypass allowed) |
| **Expiration Handling** | Update app with new pin before certificate renewal |

### 8.5 Security Checklist

- [ ] Replace placeholder certificate pin hash (`sha256/AAAA...`) before production
- [ ] Run all traffic over HTTPS in production (HSTS enabled)
- [ ] Pairing codes expire server-side (client cannot override)
- [ ] TURN credentials are time-scoped and single-use per call
- [ ] Signaling messages carry no media payload (SDP/ICE only)
- [ ] Device IDs are stable but not personally identifiable
- [ ] No user passwords, accounts, or OAuth tokens required
- [ ] All media is end-to-end encrypted via DTLS-SRTP
- [ ] FCM payloads contain metadata only (no sensor data)
- [ ] Rate limiting enforced server-side on all endpoints

---

*This document is auto-generated from source code analysis of the `core/network` module. For implementation details, see the Kotlin source files referenced in each section.*
