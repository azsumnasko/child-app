# Architecture Code Review: Privacy-First Child Helper

## Executive Summary

| Metric | Assessment |
|--------|-----------|
| **Overall Grade** | **C+** |
| Lines of Kotlin Reviewed | ~8,500+ across 80 files |
| Module Structure | Good separation (core/common, core/security, core/network, app/child, app/parent) |
| DI Framework | Hilt (correctly applied) |
| UI Framework | Jetpack Compose with Material 3 |
| Architecture Pattern | MVVM-ish (no Domain/Use Case layer) |
| State Management | Multiple sources of truth; scattered state |

**Bottom Line:** The codebase demonstrates solid security-first thinking and privacy-conscious design but lacks a proper Clean Architecture Domain layer, contains a critical god class (`CallManager`), has scattered monitoring state across multiple components, and uses navigation patterns that lack type safety. The project is well-structured at the module level but needs architectural hardening before production scale.

---

## 1. SOLID Principles Assessment

### S - Single Responsibility Principle

| Class | Lines | Responsibilities | Grade |
|-------|-------|-----------------|-------|
| `ChildHomeScreen` | ~195 | UI composition only | A |
| `ChildHomeViewModel` | ~148 | State mgmt + navigation + TTS + monitoring control | C |
| `CryDetector` | ~246 | Audio analysis + ML inference orchestration | B |
| `MotionDetector` | ~247 | Frame differencing + event emission | B |
| `EventPipeline` | ~367 | Event collection + enrichment + alert creation + notification sending + device status reading | C |
| `AudioPipeline` | ~198 | Audio capture + PCM conversion | B+ |
| `CameraPipeline` | ~591 | Camera capture + power management + obstruction detection + frame conversion + battery monitoring | D |
| `TfliteRunner` | ~162 | Model loading + inference | A |
| `ThermalMonitor` | ~454 | Temperature reading (4 strategies) + state emission + listener management | C+ |
| `MonitoringService` | ~467 | Service lifecycle + detector coordination + thermal handling + battery monitoring + wake lock + notification creation | D |
| `CallManager` | ~973 | **WebRTC signaling + peer connection + SDP handling + camera capture + audio capture + adaptive bitrate + ICE management + call state machine** | **F** |
| `AlertHistoryRepository` | ~186 | CRUD + retention policy + data deletion | B+ |
| `SosManager` | ~168 | SOS lifecycle + location gathering + vibration | B |

#### Critical Finding: `CallManager` is a God Class

`CallManager.kt` at **973 lines** is a textbook god class violating SRP. It handles:

1. WebRTC peer connection lifecycle (`createPeerConnection`, `cleanup`)
2. SDP offer/answer creation (`createOffer`, `createAnswer`)
3. Local video capture via Camera2 API (`startLocalVideo`)
4. Local audio capture (`startLocalAudio`)
5. Adaptive bitrate control (nested `AdaptiveBitrateController` class)
6. Signaling message sending (`sendOffer`, `sendAnswer`, `sendHangUp`)
7. ICE candidate exchange
8. Call state machine management
9. Media track management (local/remote video/audio)

**Recommendation: Decompose into specialized collaborators:**

```kotlin
// Proposed architecture for CallManager decomposition

// 1. PeerConnectionManager - manages WebRTC PeerConnection lifecycle
@Singleton
class PeerConnectionManager @Inject constructor(
    private val context: Context,
    private val signalingClient: WebRtcSignalingClient
) {
    fun createPeerConnection(config: RtcConfig): PeerConnection
    fun closeConnection()
}

// 2. LocalMediaManager - handles camera and microphone
@Singleton
class LocalMediaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eglBase: EglBase
) {
    fun startVideoCapture(): VideoTrack
    fun startAudioCapture(): AudioTrack
    fun switchCamera()
    fun toggleVideo(enabled: Boolean)
    fun toggleMute(muted: Boolean)
    fun cleanup()
}

// 3. SdpNegotiator - handles offer/answer
class SdpNegotiator(private val peerConnection: PeerConnection) {
    suspend fun createOffer(): Result<SessionDescription>
    suspend fun createAnswer(): Result<SessionDescription>
    suspend fun setRemoteDescription(sdp: SessionDescription): Result<Unit>
}

// 4. AdaptiveBitrateController - already exists but should be top-level
@Singleton
class AdaptiveBitrateController @Inject constructor(...) { ... }

// 5. CallSessionManager - thin orchestrator
@Singleton
class CallManager @Inject constructor(
    private val peerConnectionManager: PeerConnectionManager,
    private val localMediaManager: LocalMediaManager,
    private val sdpNegotiatorFactory: SdpNegotiator.Factory,
    private val bitrateController: AdaptiveBitrateController,
    private val eventPipeline: EventPipeline
) {
    // ~150 lines of pure orchestration logic
}
```

### O - Open/Closed Principle

| Concern | Assessment | Grade |
|---------|-----------|-------|
| New detection types | Require modifying `EventPipeline` with new `submitXxxEvent()` method + new `AlertType` enum value + new data class | **F** |
| New alert channels | `EventPipeline.sendGuardianNotification()` is hardcoded to FCM via `NotificationSender` | **D** |
| New thermal states | Only requires adding to enum + `when` branch in `MonitoringService` and `ThermalMonitor` | **C** |
| New camera power modes | Requires modifying `CameraPipeline.bindCameraAnalysis()` and `rebindWithPowerMode()` | **D** |

**Recommendation: Introduce a Strategy pattern for detection types:**

```kotlin
// Detection strategy interface
interface DetectionStrategy<T : DetectionEvent> {
    val eventType: AlertType
    fun start(config: DetectionConfig, scope: CoroutineScope)
    fun stop()
    val events: Flow<T>
    fun convertToAlert(event: T): Alert
}

// Each detector implements the strategy
class CryDetectionStrategy @Inject constructor(
    private val cryDetector: CryDetector
) : DetectionStrategy<CryDetectionEvent> { ... }

// EventPipeline becomes generic
class EventPipeline @Inject constructor(
    private val strategies: Set<@JvmSuppressWildcards DetectionStrategy<*>>,
    private val notificationSender: NotificationSender
) {
    fun startAll(config: DetectionConfig) {
        strategies.forEach { it.start(config, scope) }
    }
}
```

### L - Liskov Substitution Principle

| Check | Result | Grade |
|-------|--------|-------|
| `SecurePreferencesImpl` vs interface | No `SecurePreferencesImpl` exists - only `SecurePreferences` interface used directly | N/A |
| Interface implementations | Only one implementation of each interface; no substitution needed yet | **B** |

**Observation:** The project correctly defines `SecurePreferences` as an interface in `:core:security`, but there is no actual implementation class - it appears to be a placeholder. This means LSP cannot be properly evaluated. When an implementation is added, ensure both paired and unpaired variants are truly substitutable.

### I - Interface Segregation Principle

| Interface | Methods | Assessment | Grade |
|-----------|---------|-----------|-------|
| `SecurePreferences` | 5 (getString, putString, getBytes, putBytes, clear) | Cohesive; all CRUD operations | **A** |
| `NotificationSender` | 1 (sendAlert) | Perfectly focused | **A** |
| `ThermalStateListener` | 4 (with default `{}` bodies) | Default implementations mean implementers choose what to handle | **A** |
| `AlertDao` | 11 | DAO interface - standard Room pattern | **B** |

**Verdict:** No interface segregation violations. The `ThermalStateListener` pattern with default empty implementations is excellent - it allows selective override without forcing implementers to provide stubs.

### D - Dependency Inversion Principle

| Check | Result | Grade |
|-------|--------|-------|
| Hilt DI graph completeness | All major components injected | **A** |
| `new` keyword in business logic | `EventPipeline` creates `Alert` objects directly (data class, acceptable) | **B** |
| ViewModels depend on abstractions | `ParentDashboardViewModel` depends on `AlertHistoryRepository` (concrete, not interface) | **C** |
| Services depend on abstractions | `MonitoringService` injects concrete detectors directly | **C** |

**Finding:** There is no repository interface for `AlertHistoryRepository`. ViewModels depend directly on the concrete class:

```kotlin
// Current (DIP violation):
class ParentDashboardViewModel @Inject constructor(
    private val alertRepository: AlertHistoryRepository  // Concrete class!
) : ViewModel()

// Should be:
interface AlertRepository {
    fun getRecentAlerts(limit: Int): Flow<List<Alert>>
    suspend fun insertAlert(alert: Alert)
    // ...
}

class ParentDashboardViewModel @Inject constructor(
    private val alertRepository: AlertRepository  // Interface!
) : ViewModel()
```

---

## 2. Clean Architecture Layering

### Current Architecture (Flattened)

```
Presentation Layer (Composable Screens + ViewModels)
    |
    v
Business Logic (in ViewModels + Managers + Services)
    |
    v
Data Layer (Repository + DAO + APIs + Preferences)
```

### What's Missing: The Domain Layer

**No Use Cases exist.** ViewModels directly command detectors and managers:

| ViewModel | Directly Controls | Should Use |
|-----------|------------------|------------|
| `ChildHomeViewModel` | `CryDetector`, `MotionDetector` | `StartMonitoringUseCase`, `StopMonitoringUseCase` |
| `BedtimeViewModel` | `CryDetector`, `MotionDetector`, `CallManager` | `EnterBedtimeModeUseCase`, `ExitBedtimeModeUseCase` |
| `CallViewModel` | `CallManager` | `InitiateCallUseCase`, `EndCallUseCase` |
| `SosViewModel` | `SosManager` | `ActivateSosUseCase` |

**Layer Leakage Examples:**

1. `ChildHomeViewModel.startMonitoring()` directly calls `cryDetector.startDetection()` and `motionDetector.startDetection()` - this is business logic that belongs in a Use Case.

2. `BedtimeViewModel` mixes screen brightness management, auto-answer logic, monitoring control, and voice prompts - all in one ViewModel.

3. `MonitoringService` contains business rules for thermal response (WARM -> reduce resolution, HOT -> disable video, CRITICAL -> stop service). These should be in a `ThermalResponseUseCase`.

**Recommended Clean Architecture:**

```kotlin
// Domain layer - pure Kotlin, no Android dependencies
interface StartMonitoringUseCase {
    suspend operator fun invoke(config: DetectionConfig, lifecycleOwner: LifecycleOwner)
}

interface StopMonitoringUseCase {
    suspend operator fun invoke()
}

interface HandleThermalStateUseCase {
    suspend operator fun invoke(state: ThermalState, temperature: Float)
}

// Implementation in data/app layer
class StartMonitoringUseCaseImpl @Inject constructor(
    private val cryDetector: CryDetector,
    private val motionDetector: MotionDetector,
    private val eventPipeline: EventPipeline,
    private val thermalMonitor: ThermalMonitor
) : StartMonitoringUseCase {
    override suspend fun invoke(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        // Orchestration logic here
    }
}

// ViewModel becomes thin
class ChildHomeViewModel @Inject constructor(
    private val startMonitoring: StartMonitoringUseCase,
    private val stopMonitoring: StopMonitoringUseCase,
    private val getContacts: GetContactsUseCase
) : ViewModel() {
    fun onStartMonitoring(config: DetectionConfig) {
        viewModelScope.launch {
            startMonitoring(config, lifecycleOwner)
        }
    }
}
```

**Grade: D+** - No Domain layer, significant layer leakage, ViewModels doing business logic.

---

## 3. MVVM Pattern Correctness

### ViewModel Assessment Matrix

| ViewModel | UiState Data Class | StateFlow | One-time Events | Android Refs | Business Logic |
|-----------|-------------------|-----------|-----------------|--------------|----------------|
| `ChildHomeViewModel` | Yes | Yes | StateFlow (BAD) | `AndroidViewModel` | Embedded (monitoring control) |
| `CallViewModel` | Yes | Yes | StateFlow (BAD) | `AndroidViewModel` | Delegated to CallManager (OK) |
| `SosViewModel` | Yes | Yes | StateFlow (BAD) | `AndroidViewModel` | Embedded (countdown) |
| `BedtimeViewModel` | Yes | Yes | None | `AndroidViewModel` | Embedded (monitoring, brightness) |
| `DetectionViewModel` | Yes | Yes | None | `ViewModel` only | Embedded (detector control) |
| `ParentDashboardViewModel` | Yes | Yes | StateFlow (BAD) | `ViewModel` | Minimal (GOOD) |
| `AlertHistoryViewModel` | Yes | Yes | None | `ViewModel` | Embedded (filtering) |
| `SettingsViewModel` | Yes | Yes | None | `ViewModel` | Delegated to repository (GOOD) |
| `LiveViewViewModel` | Yes | Yes | None | `ViewModel` | Connection state handling |

### Critical Issue: One-Time Events via StateFlow

All navigation "events" are implemented as `MutableStateFlow<T?>`, which is an **anti-pattern**:

```kotlin
// BAD - navigationEvent can be re-emitted on configuration change
private val _navigationEvent = MutableStateFlow<HomeNavigationEvent?>(null)
val navigationEvent: StateFlow<HomeNavigationEvent?> = _navigationEvent.asStateFlow()

// Consumed in Compose:
val navEvent by viewModel.navigationEvent.collectAsState()
LaunchedEffect(navEvent) {
    when (navEvent) {
        is NavigateToCall -> { navController.navigate(...); viewModel.consumeNavigationEvent() }
    }
}
```

**Problems:**
1. Survives configuration change - stale events can re-fire
2. Requires manual `consumeNavigationEvent()` boilerplate
3. Not truly "one-time" - state-based semantics are wrong for events

**Recommended Fix - Use Channel/SharedFlow:**

```kotlin
// CORRECT - one-time events via Channel
class ChildHomeViewModel @Inject constructor(...) : ViewModel() {
    
    private val _navigationEvents = Channel<HomeNavigationEvent>(Channel.BUFFERED)
    val navigationEvents = _navigationEvents.receiveAsFlow() // One-time, hot stream
    
    fun onContactClick(contact: Contact) {
        viewModelScope.launch {
            _navigationEvents.send(HomeNavigationEvent.NavigateToCall(contact.id))
        }
    }
}

// In Compose:
LaunchedEffect(Unit) {
    viewModel.navigationEvents.collect { event ->
        when (event) {
            is NavigateToCall -> navController.navigate("call/${event.contactId}")
        }
        // No manual consumption needed!
    }
}
```

### AndroidViewModel Misuse

**4 out of 9 ViewModels** extend `AndroidViewModel`, getting `Application` injected:

- `ChildHomeViewModel` - Uses `Application` for `TextToSpeech`. Should inject a `VoicePromptManager` interface.
- `CallViewModel` - Uses `Application` indirectly. Not needed.
- `SosViewModel` - Same pattern.
- `BedtimeViewModel` - Uses `Application` for TTS.

**Rule:** `AndroidViewModel` should only be used when the ViewModel genuinely needs `Application`-scoped resources that cannot be injected. All these cases can be refactored to inject the specific dependencies instead.

**Grade: C+** - UiState pattern correct, StateFlow used correctly for state, but one-time events are wrong, AndroidViewModel overused, some business logic embedded.

---

## 4. State Management Architecture

### Sources of Truth for Monitoring State

The monitoring state is scattered across **at least 7 different components**:

```
Monitoring State Sources:
1. CryDetector.isRunning          - cry detection on/off
2. MotionDetector.isRunning       - motion detection on/off  
3. CameraPipeline.isRunning       - camera active/inactive
4. AudioPipeline.isRecording      - audio recording on/off
5. MonitoringService (binder)     - overall monitoring flag
6. ChildHomeViewModel.uiState.isMonitoring  - UI reflection
7. BedtimeViewModel.uiState.isMonitoring    - bedtime UI reflection
8. ThermalMonitor state           - thermal throttling state
```

**Race Condition Risk:**

`MonitoringService` and `ChildHomeViewModel` can both start/stop detectors independently. The service has its own `stopMonitoring()` that stops all detectors, but the ViewModel also calls `cryDetector.stopDetection()` directly in `stopMonitoring()` and `onCleared()`. This creates potential race conditions where:

1. Service starts cry detection
2. User stops monitoring via UI (ViewModel stops detection)
3. Service's wake lock re-acquire loop fires and tries to restart
4. Unclear which component "owns" the monitoring state

**No Single MonitoringCoordinator exists.**

**Recommendation: Create a single source of truth:**

```kotlin
@Singleton
class MonitoringCoordinator @Inject constructor(
    private val cryDetector: CryDetector,
    private val motionDetector: MotionDetector,
    private val cameraPipeline: CameraPipeline,
    private val thermalMonitor: ThermalMonitor,
    private val eventPipeline: EventPipeline
) {
    private val _monitoringState = MutableStateFlow<MonitoringState>(MonitoringState.Idle)
    val monitoringState: StateFlow<MonitoringState> = _monitoringState.asStateFlow()
    
    sealed class MonitoringState {
        data object Idle : MonitoringState()
        data class Active(val config: DetectionConfig) : MonitoringState()
        data class ThermalThrottled(val state: ThermalState) : MonitoringState()
        data class Error(val reason: String) : MonitoringState()
    }
    
    suspend fun startMonitoring(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        // Single, atomic state transition
        _monitoringState.value = MonitoringState.Active(config)
        // Start all detectors atomically
    }
    
    suspend fun stopMonitoring() {
        _monitoringState.value = MonitoringState.Idle
        // Stop all detectors
    }
}
```

**Grade: D** - Multiple sources of truth, clear race condition potential, no coordination authority.

---

## 5. Repository Pattern

### AlertHistoryRepository Assessment

**Strengths:**
- Proper encapsulation of Room DAO
- Retention policy enforcement is well-designed
- Flow-based reactive API
- DataStore integration for settings

**Weaknesses:**
1. **Returns database entities directly** (`AlertEntity`) instead of domain models
2. **No interface abstraction** - ViewModels depend on concrete class
3. **Owns its own CoroutineScope** (`repositoryScope`) for `scheduleRetentionEnforcement()` - this scope is never cancelled, risking leaks
4. **Mixed concerns** - repository handles both data access AND retention scheduling

```kotlin
// Current - returns entities, no interface
class AlertHistoryRepository @Inject constructor(
    private val alertDao: AlertDao,
    private val dataStore: DataStore<Preferences>
)

// Should be:
interface AlertRepository {
    fun getRecentAlerts(limit: Int): Flow<List<Alert>>  // Domain model, not entity
    suspend fun insertAlert(alert: Alert)
    suspend fun deleteAll()
}

class AlertRepositoryImpl @Inject constructor(
    private val alertDao: AlertDao,
    private val retentionScheduler: RetentionScheduler  // Extracted concern
) : AlertRepository
```

### Missing Repositories

| Domain | Current Approach | Should Have |
|--------|-----------------|-------------|
| Contacts | Hardcoded in `ChildHomeViewModel` | `ContactRepository` |
| Settings | Direct DataStore access in ViewModels | `SettingsRepository` |
| Device Status | MutableStateFlow in ViewModel | `DeviceStatusRepository` |
| Pairing | Direct SecurePreferences access | `PairingRepository` |

**Grade: C+** - One repository exists and is functional but lacks abstraction and proper domain model translation.

---

## 6. Navigation Pattern

### Current Navigation Assessment

| Check | Status | Grade |
|-------|--------|-------|
| Compose Navigation used | Yes | A |
| Type-safe routes | **No - raw strings** | **F** |
| Deep links configured | No | F |
| Back stack handling | Basic (`popBackStack()`) | C |
| Navigation in ViewModels | Events emitted, handled in screens | B |
| Cross-module navigation | N/A (separate apps) | - |

**String-based routing is a maintainability risk:**

```kotlin
// Current - error-prone string routes
NavHost(navController, startDestination = "home") {
    composable("home") { ... }
    composable("call/{contactId}?video={video}") { ... }
    composable("bedtime") { ... }
}

// Recommended - Kotlin Serialization type-safe navigation (Compose Navigation 2.8+)
@Serializable object Home
@Serializable data class Call(val contactId: String, val video: Boolean = true)
@Serializable object Bedtime

NavHost(navController, startDestination = Home) {
    composable<Home> { ... }
    composable<Call> { backStackEntry ->
        val call = backStackEntry.toRoute<Call>()
        CallScreen(contactId = call.contactId, hasVideo = call.video)
    }
}
```

**Grade: C** - Navigation works but uses unsafe string routes and lacks deep links.

---

## 7. Testability Assessment

| Component | Unit Testable? | Mockable? | Issues |
|-----------|---------------|-----------|--------|
| `ChildHomeViewModel` | Partially | No | Extends AndroidViewModel, creates default contacts inline |
| `ParentDashboardViewModel` | Yes | Repository not interfaced | Uses combine() - needs test dispatcher |
| `AlertHistoryViewModel` | Yes | Repository not interfaced | Filtering logic embedded |
| `SettingsViewModel` | Yes | DataStore injectable | Complex combine - needs test dispatcher |
| `CryDetector` | Partially | No | Direct AudioPipeline dependency (concrete) |
| `MotionDetector` | Partially | No | Direct CameraPipeline dependency, reads from SecurePreferences |
| `CallManager` | **No** | **No** | Direct WebRTC APIs, Camera2 API, Context dependency |
| `TfliteRunner` | Partially | No | Loads model from assets in init block |
| `EventPipeline` | Partially | NotificationSender is interface | Direct Context for battery/network readings |
| `AlertHistoryRepository` | Yes | DAO not interfaced | Owns uncancelled scope |

**Key Testability Barriers:**

1. **No interfaces for hardware components** (`AudioRecord`, `CameraX`, `PeerConnectionFactory`)
2. **`CallManager` creates `CompletableSdpObserver` inline** - not injectable
3. **Asset loading in `TfliteRunner.init`** blocks constructor testing
4. **Context-dependent permission checks** scattered in pipeline classes
5. **`AudioPipeline` and `CameraPipeline` are concrete classes** with no interfaces

**Recommended Testable Architecture:**

```kotlin
// Define interfaces for testability
interface AudioSource {
    fun startRecording(): Flow<ByteArray>
    fun stopRecording()
    fun hasPermission(): Boolean
}

interface VideoSource {
    fun startAnalysis(lifecycleOwner: LifecycleOwner): Flow<Frame>
    fun stopAnalysis()
}

interface WebRtcProvider {
    fun createPeerConnection(config: RtcConfiguration): PeerConnection
    fun createVideoTrack(source: VideoSource): VideoTrack
}

// Inject interfaces, not concretions
class CryDetector @Inject constructor(
    private val audioSource: AudioSource,  // Interface!
    private val modelRunner: ModelRunner,   // Interface!
    private val dispatcher: CoroutineDispatcher
)
```

**Grade: C-** - Hilt enables injection, but lack of interfaces for hardware components and the CallManager god class make unit testing difficult.

---

## 8. Module Boundaries

### Module Assessment

| Module | Responsibility | App-Specific Code? | Publishable? | Grade |
|--------|---------------|-------------------|--------------|-------|
| `:core:common` | Data models, events, ResultExt, NotificationSender interface | Models are app-specific (CryDetectionEvent, AlertType) | Partially - ResultExt and CryptoUtil yes | B |
| `:core:security` | Keystore, Encryption, PairingCrypto, SecurePreferences | SecurePreferences is app-agnostic; PairingCrypto is app-specific | Partially - KeystoreManager and EncryptionManager yes | B |
| `:core:network` | APIs, WebRTC signaling, FCM, network models | All network models are app-specific (InitiatePairingRequest, TurnCredentials) | No - app-specific models | C |

### Cross-Module Dependency Concerns

1. **`:core:common` contains domain models specific to the child monitoring domain** (`CryDetectionEvent`, `MotionDetectionEvent`, `SosEvent`). These are not "common" utilities - they are domain models. This is acceptable since both apps need them, but the module name is misleading.

2. **`:core:network` has `FcmNotificationSender` implementing `NotificationSender`** from `:core:common`. This is a correct dependency direction.

3. **`:app:child` directly references `:core:network` WebRTC classes** - acceptable since WebRTC is inherently app-specific.

4. **No accidental cross-module dependencies detected.** The module graph is clean:
   - `app:child` -> `core:common`, `core:security`, `core:network`
   - `app:parent` -> `core:common`, `core:security`
   - `core:security` -> `core:common`
   - `core:network` -> `core:common`

**Grade: B+** - Clean module boundaries with correct dependency direction. Module names could be more descriptive.

---

## 9. Specific Code Quality Issues

### Issue 1: `CryDetector.getDeviceId()` Hardcoded Stub

```kotlin
// CryDetector.kt:241-244
private fun getDeviceId(): String {
    // In production, retrieve from secure preferences
    return "child_device"
}
```
**Severity: HIGH** - This stub means every cry detection event has the same hardcoded device ID. Should be injected via constructor.

### Issue 2: `EventPipeline.getMonitorMode()` Returns Hardcoded Value

```kotlin
// EventPipeline.kt:359-362
private fun getMonitorMode(): MonitorMode {
    // In production, this would check the current mode from preferences or state
    return MonitorMode.IDLE
}
```
**Severity: HIGH** - Every alert reports `IDLE` mode regardless of actual state. Should read from a `MonitoringCoordinator`.

### Issue 3: `MonitoringService` Wake Lock Re-acquire Loop Never Cancels

```kotlin
// MonitoringService.kt:231-242
serviceScope.launch {
    while (true) {  // INFINITE LOOP - never cancelled on stopMonitoring()
        delay(5 * 60 * 1000)
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }
}
```
**Severity: MEDIUM** - The wake lock re-acquire job is launched in `startMonitoring()` but only the scope is cancelled in `onDestroy()`. The `stopMonitoring()` method does not cancel this job. Since `stopMonitoring()` calls `stopSelf()`, the service process may be killed, but it's still a leak pattern.

### Issue 4: `CameraPipeline` Massive Code Duplication

`bindCameraAnalysis()` (50 lines) and `rebindWithPowerMode()` (60 lines) are nearly identical - only resolution differs. Extract the common logic.

### Issue 5: `AlertHistoryRepository` Scope Leak

```kotlin
// AlertHistoryRepository.kt:33
private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```
This scope is never cancelled. While the repository is `@Singleton` and lives for the app lifecycle, explicit cleanup should be provided.

---

## 10. Refactoring Priority List

### P0 - Critical (Do Before Production)

| # | Task | Effort | Impact |
|---|------|--------|--------|
| 1 | **Decompose `CallManager`** into PeerConnectionManager, LocalMediaManager, SdpNegotiator | 2-3 days | Eliminates 973-line god class, enables testing |
| 2 | **Create `MonitoringCoordinator`** as single source of truth | 1 day | Fixes race conditions, consolidates state |
| 3 | **Fix hardcoded `getDeviceId()` and `getMonitorMode()`** | 2 hours | Fixes broken production behavior |
| 4 | **Replace StateFlow navigation events with Channel** | 4 hours | Fixes event replay bugs on config change |

### P1 - High Priority

| # | Task | Effort | Impact |
|---|------|--------|--------|
| 5 | **Introduce Domain Use Case layer** | 2-3 days | Proper Clean Architecture, testable business logic |
| 6 | **Extract repository interfaces** (`AlertRepository`, `SettingsRepository`, etc.) | 1 day | Enables mocking for tests, proper DIP |
| 7 | **Extract hardware abstraction interfaces** (`AudioSource`, `VideoSource`, `WebRtcProvider`) | 1-2 days | Enables unit testing without hardware |
| 8 | **Migrate to type-safe navigation** (Kotlin Serialization) | 1 day | Eliminates string route bugs |

### P2 - Medium Priority

| # | Task | Effort | Impact |
|---|------|--------|--------|
| 9 | **Convert AndroidViewModels to plain ViewModels** | 4 hours | Better testability |
| 10 | **Add deep link support** for call and SOS screens | 4 hours | Better UX for notifications |
| 11 | **Extract retention scheduling from repository** | 4 hours | Better SRP |
| 12 | **Consolidate duplicate camera binding logic** in `CameraPipeline` | 2 hours | Reduced maintenance |

### P3 - Low Priority (Polish)

| # | Task | Effort | Impact |
|---|------|--------|--------|
| 13 | **Add `DetectionStrategy` interface** for OCP compliance | 1 day | Enables new detection types without modification |
| 14 | **Rename `:core:common` to `:core:domain`** | 30 min | More accurate module name |
| 15 | **Add unit tests** for Use Cases and ViewModels | 2-3 days | Quality assurance |

---

## 11. Summary Scorecard

| Category | Grade | Key Issue |
|----------|-------|-----------|
| **Overall Architecture** | **C+** | Missing Domain layer, god class |
| **Single Responsibility** | **C** | CallManager is 973 lines, 9+ responsibilities |
| **Open/Closed** | **D** | New detections require modifying EventPipeline |
| **Liskov Substitution** | **B** | No violation, but only 1 impl per interface |
| **Interface Segregation** | **A** | Well-designed interfaces |
| **Dependency Inversion** | **C+** | Hilt used, but no repository interfaces |
| **Clean Architecture** | **D+** | No Domain/Use Case layer |
| **MVVM Correctness** | **C+** | StateFlow anti-pattern for events |
| **State Management** | **D** | 8+ sources of truth for monitoring |
| **Repository Pattern** | **C+** | One repository, returns entities, no interface |
| **Navigation** | **C** | String routes, no deep links |
| **Testability** | **C-** | God class, no hardware abstractions |
| **Module Boundaries** | **B+** | Clean dependency graph |
| **Code Quality** | **B** | Good documentation, privacy comments |

### Final Verdict

The codebase shows **strong domain expertise** in privacy-first design, child psychology-informed UX, and security architecture (E2E encryption, SQLCipher, metadata-only alerts). However, it needs architectural hardening before production deployment:

1. **Decompose CallManager immediately** - this is the single biggest risk
2. **Add a Domain layer with Use Cases** - separate business logic from presentation
3. **Consolidate monitoring state** into a single coordinator
4. **Extract interfaces** for testability

With the recommended refactoring (estimated 2-3 weeks), this can become an **A-grade architecture** that is maintainable, testable, and evolvable.
