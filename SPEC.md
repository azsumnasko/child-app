# SPEC.md — Privacy-First Child Helper App

## 1. Project Structure

```
/
├── gradle/
│   └── libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts (root)
├── core/
│   ├── common/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/childhelper/core/common/
│   │       ├── model/
│   │       │   ├── Alert.kt
│   │       │   ├── DeviceStatus.kt
│   │       │   ├── PairingSession.kt
│   │       │   ├── Contact.kt
│   │       │   ├── SosEvent.kt
│   │       │   ├── CryDetectionEvent.kt
│   │       │   ├── MotionDetectionEvent.kt
│   │       │   ├── DetectionConfig.kt
│   │       │   ├── CallSession.kt
│   │       │   └── Settings.kt
│   │       ├── events/
│   │       │   └── AppEvents.kt
│   │       └── util/
│   │           ├── ResultExt.kt
│   │           └── CryptoUtil.kt
│   ├── security/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/childhelper/core/security/
│   │       ├── KeystoreManager.kt
│   │       ├── EncryptionManager.kt
│   │       ├── PairingCrypto.kt
│   │       ├── SecurePreferences.kt
│   │       └── di/
│   │           └── SecurityModule.kt
│   └── network/
│       ├── build.gradle.kts
│       └── src/main/java/com/childhelper/core/network/
│           ├── api/
│           │   ├── PairingApi.kt
│           │   └── SignalingApi.kt
│           ├── signaling/
│           │   ├── WebRtcSignalingClient.kt
│           │   └── SignalingMessage.kt
│           ├── push/
│           │   └── FcmService.kt
│           ├── di/
│           │   └── NetworkModule.kt
│           └── util/
│               └── NetworkUtil.kt
├── app/
│   ├── child/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/childhelper/app/child/
│   │       ├── ChildApp.kt
│   │       ├── di/
│   │       │   └── ChildAppModule.kt
│   │       ├── ui/
│   │       │   ├── home/
│   │       │   │   ├── ChildHomeScreen.kt
│   │       │   │   ├── ChildHomeViewModel.kt
│   │       │   │   └── ContactButton.kt
│   │       │   ├── sos/
│   │       │   │   ├── SosButton.kt
│   │       │   │   ├── SosManager.kt
│   │       │   │   └── SosViewModel.kt
│   │       │   ├── bedtime/
│   │       │   │   ├── BedtimeModeScreen.kt
│   │       │   │   ├── BedtimeViewModel.kt
│   │       │   │   └── VoicePromptManager.kt
│   │       │   ├── call/
│   │       │   │   ├── CallScreen.kt
│   │       │   │   ├── CallManager.kt
│   │       │   │   └── CallViewModel.kt
│   │       │   ├── detection/
│   │       │   │   ├── DetectionOverlay.kt
│   │       │   │   └── DetectionViewModel.kt
│   │       │   └── theme/
│   │       │       ├── ChildTheme.kt
│   │       │       └── ChildColors.kt
│   │       ├── detection/
│   │       │   ├── CryDetector.kt
│   │       │   ├── MotionDetector.kt
│   │       │   ├── AudioPipeline.kt
│   │       │   ├── CameraPipeline.kt
│   │       │   ├── EventPipeline.kt
│   │       │   └── TfliteRunner.kt
│   │       └── service/
│   │           ├── MonitoringService.kt
│   │           └── CallService.kt
│   └── parent/
│       ├── build.gradle.kts
│       └── src/main/java/com/childhelper/app/parent/
│           ├── ParentApp.kt
│           ├── di/
│           │   └── ParentAppModule.kt
│           ├── ui/
│           │   ├── dashboard/
│           │   │   ├── ParentDashboardScreen.kt
│           │   │   ├── ParentDashboardViewModel.kt
│           │   │   ├── DeviceStatusCard.kt
│           │   │   └── AlertFeed.kt
│           │   ├── liveview/
│           │   │   ├── LiveViewScreen.kt
│           │   │   ├── LiveViewViewModel.kt
│           │   │   └── TalkBackManager.kt
│           │   ├── settings/
│           │   │   ├── SettingsScreen.kt
│           │   │   └── SettingsViewModel.kt
│           │   ├── alerts/
│           │   │   ├── AlertHistoryScreen.kt
│           │   │   └── AlertHistoryViewModel.kt
│           │   └── theme/
│           │       ├── ParentTheme.kt
│           │       └── ParentColors.kt
│           ├── repository/
│           │   └── AlertHistoryRepository.kt
│           └── db/
│               ├── AppDatabase.kt
│               └── AlertDao.kt
```

## 2. Module Dependencies

```
:app:child  --> :core:common, :core:security, :core:network
:app:parent --> :core:common, :core:security, :core:network
:core:security --> :core:common
:core:network  --> :core:common
```

## 3. Data Models

### 3.1 Core Models

```kotlin
// Alert.kt
@Serializable
data class Alert(
    val id: String = UUID.randomUUID().toString(),
    val eventType: AlertType,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float? = null,
    val deviceStatus: DeviceStatusSnapshot,
    val childDeviceId: String
)

enum class AlertType {
    CRY_DETECTED,
    MOTION_DETECTED,
    SOS_ACTIVATED,
    CAMERA_OBSTRUCTED,
    DEVICE_OFFLINE,
    LOW_BATTERY,
    CALL_STARTED,
    CALL_ENDED
}

// DeviceStatus.kt
@Serializable
data class DeviceStatus(
    val deviceId: String,
    val isOnline: Boolean = true,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String, // "wifi" | "cellular" | "none"
    val monitorMode: MonitorMode = MonitorMode.IDLE,
    val lastSeen: Long = System.currentTimeMillis()
)

enum class MonitorMode {
    IDLE,
    BEDTIME,
    CALLING,
    SOS
}

data class DeviceStatusSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String,
    val monitorMode: MonitorMode
)

// PairingSession.kt
@Serializable
data class PairingSession(
    val sessionId: String,
    val pairingCode: String, // 6-digit alphanumeric, expires in 5 min
    val childDeviceId: String,
    val parentDeviceId: String? = null,
    val childPublicKey: String? = null,
    val parentPublicKey: String? = null,
    val status: PairingStatus = PairingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 5 * 60 * 1000
)

enum class PairingStatus {
    PENDING,
    COMPLETED,
    REVOKED,
    EXPIRED
}

// Contact.kt
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: ContactRole,
    val photoUri: String? = null,
    val phoneNumber: String? = null,
    val isPrimary: Boolean = false
)

enum class ContactRole {
    MOTHER,
    FATHER,
    GUARDIAN
}

// SosEvent.kt
@Serializable
data class SosEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val location: GeoLocation? = null,
    val childDeviceId: String
)

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null
)

// CryDetectionEvent.kt
@Serializable
data class CryDetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float,
    val consecutiveWindows: Int,
    val childDeviceId: String
)

// MotionDetectionEvent.kt
@Serializable
data class MotionDetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float,
    val consecutiveFrames: Int,
    val childDeviceId: String
)

// DetectionConfig.kt
@Serializable
data class DetectionConfig(
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val cryEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val cryThreshold: Float = 0.7f,
    val motionThreshold: Float = 0.15f,
    val cryConsecutiveWindows: Int = 3,
    val motionConsecutiveFrames: Int = 2,
    val alertHistoryRetention: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS
)

enum class SensitivityLevel {
    LOW, NORMAL, HIGH
}

enum class RetentionPeriod {
    OFF, TWENTY_FOUR_HOURS, SEVEN_DAYS
}

// CallSession.kt
@Serializable
data class CallSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val callerId: String,
    val calleeId: String,
    val status: CallStatus = CallStatus.CONNECTING,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val isAutoAnswer: Boolean = false,
    val hasVideo: Boolean = true
)

enum class CallStatus {
    CONNECTING, RINGING, CONNECTED, ENDED, FAILED
}

// Settings.kt
@Serializable
data class AppSettings(
    val cryDetectionEnabled: Boolean = true,
    val motionDetectionEnabled: Boolean = true,
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val bedtimeAutoAnswer: Boolean = true,
    val alertHistoryRetention: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS,
    val sosEscalationOrder: List<String> = emptyList(),
    val locationSharingEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true
)
```

## 4. Interface Contracts

### 4.1 Security Layer

```kotlin
interface KeystoreManager {
    fun generateKeyPair(alias: String): KeyPair
    fun getPublicKey(alias: String): PublicKey?
    fun decrypt(alias: String, encryptedData: ByteArray): ByteArray
    fun encrypt(alias: String, plainData: ByteArray): ByteArray
    fun removeKey(alias: String)
}

interface EncryptionManager {
    fun encryptWithSharedSecret(plainText: String, sharedSecret: ByteArray): String
    fun decryptWithSharedSecret(cipherText: String, sharedSecret: ByteArray): String
    fun generateSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray
}

interface PairingCrypto {
    fun generatePairingCode(): String
    fun deriveSharedSecret(childKeyPair: KeyPair, parentPublicKey: PublicKey): ByteArray
    fun verifyPairingCode(code: String, session: PairingSession): Boolean
}

interface SecurePreferences {
    suspend fun putString(key: String, value: String)
    suspend fun getString(key: String, default: String? = null): String?
    suspend fun putBoolean(key: String, value: Boolean)
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean
    suspend fun remove(key: String)
    suspend fun clear()
}
```

### 4.2 Network Layer

```kotlin
interface PairingApi {
    @POST("/api/v1/pairing/initiate")
    suspend fun initiatePairing(@Body request: InitiatePairingRequest): PairingSession

    @POST("/api/v1/pairing/complete")
    suspend fun completePairing(@Body request: CompletePairingRequest): PairingSession

    @POST("/api/v1/pairing/revoke")
    suspend fun revokePairing(@Body request: RevokePairingRequest)

    @GET("/api/v1/pairing/status/{sessionId}")
    suspend fun getPairingStatus(@Path("sessionId") sessionId: String): PairingSession

    @POST("/api/v1/turn/credentials")
    suspend fun getTurnCredentials(): TurnCredentials
}

interface SignalingApi {
    @POST("/api/v1/signal/offer")
    suspend fun sendOffer(@Body offer: SdpMessage)

    @POST("/api/v1/signal/answer")
    suspend fun sendAnswer(@Body answer: SdpMessage)

    @POST("/api/v1/signal/ice")
    suspend fun sendIceCandidate(@Body candidate: IceMessage)

    @GET("/api/v1/signal/pending/{deviceId}")
    suspend fun getPendingMessages(@Path("deviceId") deviceId: String): List<SignalingMessage>
}

data class InitiatePairingRequest(val childDeviceId: String, val childPublicKey: String)
data class CompletePairingRequest(val sessionId: String, val parentDeviceId: String, val parentPublicKey: String)
data class RevokePairingRequest(val sessionId: String, val deviceId: String)
data class TurnCredentials(val username: String, val password: String, val urls: List<String>)
```

### 4.3 Detection Layer

```kotlin
interface CryDetector {
    fun startDetection(config: DetectionConfig)
    fun stopDetection()
    val cryEvents: Flow<CryDetectionEvent>
    val isRunning: Boolean
}

interface MotionDetector {
    fun startDetection(config: DetectionConfig)
    fun stopDetection()
    val motionEvents: Flow<MotionDetectionEvent>
    val isRunning: Boolean
}

interface EventPipeline {
    val alerts: Flow<Alert>
    fun submitCryEvent(event: CryDetectionEvent)
    fun submitMotionEvent(event: MotionDetectionEvent)
    fun submitSosEvent(event: SosEvent)
    fun submitObstructionEvent()
}

interface AudioPipeline {
    fun startRecording()
    fun stopRecording()
    val audioBuffer: Flow<ByteArray>
}

interface CameraPipeline {
    fun startAnalysis()
    fun stopAnalysis()
    val frames: Flow<ImageProxy>
    val obstructionEvents: Flow<Unit>
}
```

### 4.4 WebRTC Layer

```kotlin
interface WebRtcClient {
    fun initialize(eglBase: EglBase)
    fun createPeerConnection(iceServers: List<IceServer>): PeerConnection?
    fun createOffer(): SessionDescription
    fun createAnswer(): SessionDescription
    fun setRemoteDescription(sdp: SessionDescription)
    fun addIceCandidate(candidate: IceCandidate)
    fun startLocalVideo(capture: VideoCapturer)
    fun startLocalAudio()
    fun switchCamera()
    fun enableVideo(enabled: Boolean)
    fun enableAudio(enabled: Boolean)
    val remoteVideoTrack: VideoTrack?
    val connectionState: Flow<PeerConnectionState>
    fun close()
}

interface CallManager {
    fun initiateCall(toDeviceId: String, hasVideo: Boolean = true)
    fun acceptCall(sessionId: String)
    fun endCall()
    fun enableTalkBack(enabled: Boolean)
    val callState: Flow<CallState>
    val currentSession: CallSession?
}
```

## 5. Privacy Constraints (MANDATORY)

- NO MediaRecorder usage anywhere
- NO MediaStore writes for audio/video
- NO cloud upload APIs for media
- NO persistent audio/video files on disk
- All alerts are metadata-only (event type, timestamp, confidence, device status)
- Raw audio buffers discarded immediately after analysis
- Camera frames discarded immediately after analysis
- Android Keystore for all key storage
- SQLCipher for all local database encryption
- Encrypted SharedPreferences/DataStore for settings

## 6. Technical Stack

- Kotlin 2.0+
- minSdk 26, targetSdk 36
- Jetpack Compose + Material 3
- Hilt for DI
- Coroutines + Flow
- CameraX (Preview + ImageAnalysis)
- AudioRecord for audio capture
- LiteRT / TensorFlow Lite (quantized INT8)
- WebRTC (getstream/webrtc-android)
- Firebase Cloud Messaging
- Room + SQLCipher
- Android Keystore
- Retrofit + OkHttp
- kotlinx.serialization
- JUnit 5 + MockK for testing

## 7. Gradle Version Catalog

```toml
[versions]
kotlin = "2.0.21"
agp = "8.7.3"
compose-bom = "2024.12.01"
hilt = "2.54"
room = "2.6.1"
camerax = "1.4.1"
webrtc = "1.3.7"
litert = "1.0.1"
firebase-bom = "33.7.0"
retrofit = "2.11.0"
okhttp = "4.12.0"
sqlcipher = "4.6.1"
serialization = "1.7.3"
coroutines = "1.9.0"
lifecycle = "2.8.7"
navigation = "2.8.5"

[libraries]
# Compose BOM
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-activity = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
compose-viewmodel = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }

# CameraX
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-video = { group = "androidx.camera", name = "camera-video", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
camerax-mlkit = { group = "androidx.camera", name = "camera-mlkit-vision", version.ref = "camerax" }

# WebRTC
webrtc = { group = "io.getstream", name = "stream-webrtc-android", version.ref = "webrtc" }

# LiteRT
litert = { group = "com.google.ai.edge.litert", name = "litert", version.ref = "litert" }

# Firebase
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebase-bom" }
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging-ktx" }

# Retrofit + OkHttp
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }

# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# SQLCipher
sqlcipher = { group = "net.zetetic", name = "android-database-sqlcipher", version.ref = "sqlcipher" }

# Serialization
kotlinx-serialization = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }

# Coroutines
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# Lifecycle
lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-viewmodel = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }

# DataStore
datastore = { group = "androidx.datastore", name = "datastore-preferences", version = "1.1.1" }

# Testing
junit = { group = "junit", name = "junit", version = "4.13.2" }
mockk = { group = "io.mockk", name = "mockk", version = "1.13.13" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

# Android Testing
androidx-junit = { group = "androidx.test.ext", name = "junit", version = "1.2.1" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version = "3.6.1" }
compose-test = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.28" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

## 8. Build Configurations

### 8.1 Root build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

### 8.2 :core:common build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.childhelper.core.common"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.kotlinx.serialization)
    implementation(libs.coroutines.core)
}
```

### 8.3 :core:security build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.childhelper.core.security"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.datastore)
    implementation(libs.sqlcipher)
}
```

### 8.4 :core:network build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.childhelper.core.network"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.webrtc)
}
```

### 8.5 :app:child build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.childhelper.app.child"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.childhelper.app.child"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling)
    implementation(libs.compose.preview)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.navigation)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    ksp(libs.hilt.compiler)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.litert)
    implementation(libs.webrtc)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization)
    implementation(libs.datastore)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
}
```

### 8.6 :app:parent build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.childhelper.app.parent"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.childhelper.app.parent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling)
    implementation(libs.compose.preview)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.navigation)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.webrtc)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization)
    implementation(libs.datastore)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
}
```

## 9. Settings.gradle.kts
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "ChildHelper"
include(":core:common")
include(":core:security")
include(":core:network")
include(":app:child")
include(":app:parent")
```

## 10. Implementation Order
1. Initialize project structure and Gradle files
2. :core:common — all data models and events
3. :core:security — Keystore, encryption, secure preferences
4. :core:network — API interfaces, signaling, FCM service
5. :app:child — UI screens, detection pipeline, services
6. :app:parent — dashboard, live view, settings, database
7. Integration and testing
