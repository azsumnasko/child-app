# Deep Analysis: WebRTC Video & Audio Fixes for Live View

**Author:** Engineering Analysis  
**Date:** 2026-06-23  
**Scope:** P1 issues from `LIVEVIEW_DEBUG_LOG.md` — Video disabled (CameraX conflict) & Audio not heard on parent  
**Library:** `io.getstream:stream-webrtc-android:1.3.7` (getstream/webrtc-android)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Architecture Overview](#2-system-architecture-overview)
3. [Video Problem: Deep Root Cause Analysis](#3-video-problem-deep-root-cause-analysis)
4. [Audio Problem: Deep Root Cause Analysis](#4-audio-problem-deep-root-cause-analysis)
5. [Video Solution Architecture](#5-video-solution-architecture)
6. [Audio Solution Architecture](#6-audio-solution-architecture)
7. [Implementation Specifications](#7-implementation-specifications)
8. [Verification & Testing Strategy](#8-verification--testing-strategy)
9. [Risk Analysis & Edge Cases](#9-risk-analysis--edge-cases)
10. [Implementation Sequence](#10-implementation-sequence)

---

## 1. Executive Summary

The WebRTC Live View signaling pipeline is fully operational — the peer connection establishes successfully in ~21 seconds. However, the two media modalities are broken for different reasons:

| Modality | Status | Root Cause Category | Fix Complexity |
|----------|--------|---------------------|----------------|
| **Video** | Disabled (commented out) | Hardware resource contention (single camera, two clients) | Medium — requires architectural change |
| **Audio** | Not heard on parent | Missing audio routing setup + missing AudioTrack handling | Small — configuration + wiring fixes |

Both issues are **independent** and can be fixed separately. Audio should be fixed first (smaller change, higher confidence), followed by video (requires a new camera-sharing adapter).

---

## 2. System Architecture Overview

### 2.1 Component Map

```
PARENT APP (Samsung S24)                          CHILD APP (Xiaomi 12)
┌─────────────────────────────┐                  ┌──────────────────────────────────┐
│ LiveViewScreen (Compose)    │                  │ MonitoringService (foreground)   │
│   └─ LiveViewViewModel      │                  │   └─ MonitoringCoordinator       │
│       └─ LiveViewConnection │                  │       ├─ CameraPipeline (CameraX)│
│           Manager           │                  │       │   └─ ImageAnalysis       │
│           ├─ PeerConnection │                  │       │       (back camera)      │
│           │   Factory       │                  │       ├─ MotionDetector          │
│           │   (NO ADM)      │                  │       └─ CryDetector (AudioRecord│
│           ├─ TalkBackManager│                  │                              )     │
│           │   (data channel)│                  │                                  │
│           └─ onAddTrack:   │                  │ CallManager                      │
│               VideoTrack ✓ │                  │   ├─ WebRtcPeerConnectionManager │
│               AudioTrack ✗ │                  │   │   └─ PeerConnectionFactory   │
│                             │                  │   │       (NO ADM)               │
│ AudioManager:               │                  │   ├─ CameraCaptureManager        │
│   MODE_IN_COMMUNICATION      │                  │   │   └─ CameraVideoCapturer    │
│   (set AFTER connect)       │                  │   │       (Camera2 — CONFLICT)   │
│   speakerphone ON           │                  │   ├─ AudioDeviceManager          │
│   NO audio focus            │                  │   │   └─ AudioSource + Track    │
│                             │                  │   └─ onAddTrack:                │
│                             │                  │       VideoTrack ✓              │
│                             │                  │       AudioTrack ✓ (local)      │
└──────────────┬──────────────┘                  └──────────────┬───────────────────┘
               │                                                │
               │            Signaling Server (Ktor)             │
               │         localhost:8080 (HTTP polling)          │
               └────────────────────────────────────────────────┘
                            WebRTC P2P (DTLS-SRTP)
```

### 2.2 WebRTC Media Flow (Current State)

```
CHILD (sender)                                    PARENT (receiver)
┌─────────────────┐                              ┌──────────────────┐
│ Microphone      │                              │                  │
│   ↓             │                              │                  │
│ AudioSource     │                              │                  │
│   ↓             │                              │                  │
│ AudioTrack      │──── RTP (Opus) ────────────→ │ onAddTrack()     │
│ ("audio0")      │                              │   ↓              │
│   ↓             │                              │ AudioTrack       │
│ addTrack(pc)    │                              │   ↓              │
│                 │                              │ ??? NOT HANDLED  │
│                 │                              │   ↓              │
│ Camera2         │                              │  [no AudioSink]  │
│ (DISABLED)      │                              │   ↓              │
│   ↓             │                              │  SPEAKER         │
│ [no VideoTrack] │                              │  (no focus,      │
│                 │                              │   mode set late) │
└─────────────────┘                              └──────────────────┘
```

**The audio RTP packets arrive at the parent** (the connection is CONNECTED per the log), but the decoded audio is never properly routed to the speaker.

---

## 3. Video Problem: Deep Root Cause Analysis

### 3.1 The Two Camera Clients

#### Client 1: CameraX (Monitoring — Motion Detection)

**File:** `CameraPipeline.kt:268-338`

```kotlin
// CameraPipeline.bindCameraAnalysis()
imageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(...)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()

imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
    // Motion detection frame processing
    _frames.emit(imageProxy)
}

val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA  // ← BACK CAMERA
provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
```

CameraX uses `ProcessCameraProvider`, which internally manages a Camera2 `CameraDevice`. The `bindToLifecycle()` call opens the back camera and holds it for the lifetime of the `ImageAnalysis` use case.

#### Client 2: WebRTC CameraVideoCapturer (Call Video)

**File:** `CameraCaptureManager.kt:66-110`

```kotlin
// CameraCaptureManager.startCapture()
val cameraEnumerator = Camera2Enumerator(context)
val frontCamera = deviceNames.find { cameraEnumerator.isFrontFacing(it) }
    ?: deviceNames.firstOrNull()

val capturer = cameraEnumerator.createCapturer(frontCamera, null)
// ↑ This creates a Camera2Capturer which calls cameraManager.openCamera()

surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
capturer.startCapture(640, 480, 24)  // ← Opens camera via Camera2 API
```

WebRTC's `Camera2Enumerator.createCapturer()` returns a `Camera2Capturer` that calls `CameraManager.openCamera()` **directly** — bypassing CameraX entirely. This is a completely separate camera client.

### 3.2 Why Both Cannot Coexist

Android's camera subsystem enforces **exclusive access** per camera ID:

- **Camera2 API:** `CameraManager.openCamera(cameraId, ...)` throws `CameraAccessException.MAX_CAMERAS_IN_USE` if the camera is already open by another client.
- **CameraX:** Wraps Camera2, so it holds the same exclusive lock.
- **Concurrent camera mode** (API 33+): Only supports opening two *different* cameras (e.g., front + back simultaneously) on specific hardware. It does **not** allow two clients on the *same* camera.

Since both `CameraPipeline` (monitoring) and `CameraCaptureManager` (WebRTC) target camera sensors on the same device, the second `openCamera()` call fails.

### 3.3 The Native Crash (SIGSEGV)

When the second `openCamera()` fails, WebRTC's `Camera2Capturer` does **not** throw a clean Java exception. Instead, the failure propagates into the native peer connection layer (`libjingle_peerconnection_so.so`) during `capturer.startCapture()`, which triggers:

```
SIGSEGV in libjingle_peerconnection_so.so on signaling thread
```

This is because `Camera2Capturer`'s internal state machine enters an inconsistent state when the camera fails to open, and the native video source's `CapturerObserver` receives a callback on a thread that has already been torn down.

### 3.4 The Current Workaround (and Why It's Insufficient)

**File:** `CallManager.kt:346-365`

```kotlin
// Step 1: Release camera from monitoring
monitoringCoordinator.suspendCameraForCall()

// Step 2: Wait for camera to be released
kotlinx.coroutines.delay(1000)  // ← FIXED 1-SECOND DELAY

// Step 3: Start WebRTC camera capture
// COMMENTED OUT because it still crashes:
// val eglBase = peerConnectionManager.getEglBase()
// cameraCaptureManager.startCapture(factory, eglBase, peerConnectionManager.getPeerConnection())
_isAudioOnly.value = true  // ← Fallback to audio-only
```

The `suspendCameraForCall()` flow:
```
MonitoringCoordinator.suspendCameraForCall()
  → motionDetector.stopDetection()
  → withContext(Dispatchers.Main) {
      cameraPipeline.stopAnalysis()
        → cameraProvider?.unbindAll()  // ← ASYNCHRONOUS
        → imageAnalysis?.clearAnalyzer()
        → cameraProvider = null
        → currentLifecycleOwner = null
    }
```

**The critical flaw:** `ProcessCameraProvider.unbindAll()` is **asynchronous**. It returns immediately, but:

1. The `CameraDevice` transitions to `STATE_CLOSED` on a **background camera thread** (not the calling thread).
2. There is **no callback** from CameraX that signals "camera hardware fully released."
3. The 1000ms `delay()` is a **heuristic guess** — it races against the OS's camera release, which varies by:
   - Device load (other camera-using apps, background services)
   - OEM camera HAL implementation (Samsung/Xiaomi HALs are slower to release)
   - Android version (camera teardown timing changed in API 30+)
   - Thermal state (camera release is slower when throttling)

On the Xiaomi 12 (child device), the camera release typically takes **300-800ms** after `unbindAll()`, but under load it can exceed 1000ms — which is exactly when the crash occurs.

### 3.5 Why `CameraManager.AvailabilityCallback` Is Not a Perfect Fix Either

One might suggest using `CameraManager.registerAvailabilityCallback()` to detect `onCameraClosed(cameraId)` before starting WebRTC capture. However:

1. **CameraX may not fully release the camera** — it keeps a `ProcessCameraProvider` instance alive and may hold the camera in a "paused" state depending on lifecycle.
2. **`onCameraClosed` fires on a background thread** — synchronization is still needed.
3. **Samsung/Xiaomi HAL quirks** — some OEMs don't fire `onCameraClosed` reliably, or fire it before the hardware is truly free.

This approach (Option B in the fix section) is **fragile** but workable as a short-term fix. The robust fix is Option A (CameraX as sole camera client).

### 3.6 Additional Video Issue: Front vs. Back Camera Mismatch

There's a subtle inconsistency in the codebase:

- `CameraCaptureManager.kt:77`: Prefers the **front-facing** camera for calls:
  ```kotlin
  val frontCamera = deviceNames.find { cameraEnumerator.isFrontFacing(it) }
  ```
- `CameraPipeline.kt:326`: Uses the **back-facing** camera for monitoring:
  ```kotlin
  val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
  ```

If both used **different** cameras (front for calls, back for monitoring), there would be no conflict on devices that support concurrent cameras. However:
1. The child device (Xiaomi 12) is monitoring the room — it needs the **back** camera for motion detection.
2. For a Live View call, the parent wants to **see the child's room** — so the **back** camera is also the right choice.
3. Using the front camera for Live View would show the child's face, not the room — defeating the purpose.

So both genuinely need the same camera, and the conflict is real.

---

## 4. Audio Problem: Deep Root Cause Analysis

### 4.1 The Audio Pipeline (End-to-End)

```
CHILD (sender)                                    PARENT (receiver)
┌─────────────────────────┐                      ┌────────────────────────────┐
│ 1. AudioManager          │                      │ 5. onAddTrack() callback   │
│    MODE_IN_COMMUNICATION │                      │    (LiveViewConnectionMgr  │
│    speakerphone ON       │                      │     :329-338)              │
│                          │                      │                            │
│ 2. AudioSource           │                      │    track is VideoTrack → ✓ │
│    (createAudioSource    │                      │    track is AudioTrack → ✗ │
│     with EC/NS/AGC)      │                      │    (IGNORED — never stored,│
│                          │                      │     never enabled)         │
│ 3. AudioTrack ("audio0") │                      │                            │
│    setEnabled(true)      │                      │ 6. Default AudioDevice     │
│    addTrack(pc)          │                      │    Module (auto-created)   │
│                          │                      │    - No audio focus        │
│ 4. Opus encoder (native) │──── RTP/UDP ───────→ │    - MODE_NORMAL at init   │
│    (via WebRTC native)   │                      │    - speakerphone set LATE │
│                          │                      │                            │
│                          │                      │ 7. AudioManager            │
│                          │                      │    MODE_IN_COMMUNICATION   │
│                          │                      │    (set AFTER connect,     │
│                          │                      │     :209-214)              │
│                          │                      │    speakerphone ON         │
│                          │                      │    NO AudioFocus request   │
│                          │                      │                            │
│                          │                      │ 8. Speaker                 │
│                          │                      │    [SILENCE]               │
└─────────────────────────┘                      └────────────────────────────┘
```

### 4.2 Root Cause #1: `onAddTrack` Ignores AudioTrack

**File:** `LiveViewConnectionManager.kt:329-338`

```kotlin
override fun onAddTrack(
    receiver: RtpReceiver?,
    streams: Array<out MediaStream>?
) {
    receiver?.track()?.let { track ->
        if (track is VideoTrack) {                    // ← ONLY VideoTrack
            handler.post { _remoteVideoTrack.value = track }
        }
        // AudioTrack falls through — never stored, never enabled
    }
}
```

**Impact:** The remote `AudioTrack` object is received from the native layer but immediately **garbage collected** because no reference is held. While WebRTC's internal audio device module *may* auto-play decoded audio without an explicit Java reference, this behavior is:
- **Not guaranteed** across WebRTC library versions
- **Not reliable** on Samsung One UI (which requires explicit audio routing)
- **Fragile** — if the track is GC'd, the native audio sink may be disconnected

**Contrast with the child side:** `CallManager.kt:611-620` has the same pattern — it also only handles `VideoTrack` in `onAddTrack`. However, the child is the **sender** of audio (not the receiver), so this doesn't affect the child's outgoing audio. The child's local audio track is created and added explicitly via `AudioDeviceManager.startAudioCapture()`.

### 4.3 Root Cause #2: No Explicit AudioDeviceModule

**Verified:** `grep -r "JavaAudioDeviceModule\|AudioDeviceModule\|setAudioDeviceModule" **/*.kt` returns **zero matches** across the entire codebase.

**File:** `LiveViewConnectionManager.kt:119-134` (parent)
```kotlin
peerConnectionFactory = PeerConnectionFactory.builder()
    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
    .setOptions(PeerConnectionFactory.Options().apply {
        disableEncryption = false
        disableNetworkMonitor = false
    })
    // ← NO .setAudioDeviceModule(...)
    .createPeerConnectionFactory()
```

**File:** `WebRtcPeerConnectionManager.kt:74-89` (child) — identical pattern, no ADM.

**What happens without an explicit ADM:**
The getstream/webrtc-android library (based on Google's WebRTC M100+) internally creates a **default `JavaAudioDeviceModule`** when `createPeerConnectionFactory()` is called without `.setAudioDeviceModule()`. This default ADM:
- ✅ Creates an `AudioRecord` for capture and an `AudioTrack` for playout
- ✅ Connects to the native audio device module
- ❌ Does **NOT** call `AudioManager.setMode(MODE_IN_COMMUNICATION)` — it expects the caller to do this
- ❌ Does **NOT** request **audio focus** — the caller must do this
- ❌ Does **NOT** configure speakerphone routing — the caller must do this
- ❌ Initializes its internal `AudioTrack` (Android's, not WebRTC's) with the **current** AudioManager mode at creation time

**The critical timing issue:** Since `MODE_IN_COMMUNICATION` is set **after** the connection is established (line 209-214), the default ADM's playout `AudioTrack` is initialized in `MODE_NORMAL`. Android's `AudioTrack` does **not** dynamically switch routing when the AudioManager mode changes after creation — it must be recreated. Since the ADM is never recreated, audio is routed to the **earpiece** at **low volume**, which is effectively inaudible on a device where the user expects speaker output.

### 4.4 Root Cause #3: No Audio Focus Request

**File:** `LiveViewConnectionManager.kt:209-214`
```kotlin
val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
am.mode = AudioManager.MODE_IN_COMMUNICATION
am.isSpeakerphoneOn = true
// ← NO am.requestAudioFocus(...)
```

**Why this matters on Samsung S24 (One UI 6):**

Samsung One UI enforces a **strict audio focus policy** for `MODE_IN_COMMUNICATION`:
1. Without audio focus, `setMode(MODE_IN_COMMUNICATION)` is **silently ignored** — the mode stays `MODE_NORMAL`.
2. `isSpeakerphoneOn = true` without focus + `MODE_IN_COMMUNICATION` is **reverted** by the system audio policy within milliseconds.
3. The WebRTC ADM's playout `AudioTrack` writes to a route that the system has not authorized → audio is dropped.

This is the **most likely single reason** audio is completely silent on the Samsung S24 parent device. On stock Android (Pixel), the behavior is more lenient — `MODE_IN_COMMUNICATION` + speakerphone may work without focus. But Samsung's modified AudioFlinger enforces focus strictly.

**API level considerations:**
- API < 26: `am.requestAudioFocus(listener, streamType, duration)` (deprecated)
- API >= 26: `AudioFocusRequest.Builder()` with `AudioAttributes`

Since `minSdk = 26`, the modern `AudioFocusRequest` API should be used.

### 4.5 Root Cause #4: AudioManager Mode Set Too Late

**Timeline of the current `connect()` flow:**

```
T+0s    connect() called
T+0s    initializeWebRtc()
        → PeerConnectionFactory created
        → Default ADM initialized (AudioManager.mode = MODE_NORMAL at this point)
        → ADM's internal AudioTrack (Android) initialized with MODE_NORMAL routing
T+0-10s createPeerConnection(), addTransceiver(RECV_ONLY audio+video)
T+10s   createOffer() → sendOffer()
T+10-21s Wait for answer (pollAnswerDirect)
T+21s   setRemoteDescription(answer)
T+21s   _connectionState = CONNECTED
T+21s   ← AudioManager.mode = MODE_IN_COMMUNICATION  (TOO LATE)
T+21s   ← isSpeakerphoneOn = true                     (TOO LATE)
```

The ADM's internal Android `AudioTrack` was initialized at T+0s with `MODE_NORMAL` routing (earpiece, media volume). Setting `MODE_IN_COMMUNICATION` at T+21s does **not** cause the ADM to recreate its `AudioTrack` — the routing is already baked in.

**The fix:** Set `MODE_IN_COMMUNICATION` + request audio focus **BEFORE** `initializeWebRtc()`, so the ADM's internal `AudioTrack` is initialized with the correct routing from the start.

### 4.6 Root Cause #5: Child Sets Speakerphone ON (Echo/Feedback Risk)

**File:** `AudioDeviceManager.kt:150-161`
```kotlin
private fun configureAudioManagerForCall() {
    val am = audioManager ?: return
    previousAudioMode = am.mode
    wasSpeakerphoneOn = am.isSpeakerphoneOn
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = true  // ← PROBLEMATIC on the SENDER
}
```

The child device is only **sending** audio (microphone → WebRTC). It should not need to route audio to the speaker. However, setting `isSpeakerphoneOn = true` on the child:

1. **May cause acoustic echo:** If the child's WebRTC ADM creates a playout `AudioTrack` (even for received audio that doesn't exist), the speaker output could be captured by the microphone, creating an echo loop. WebRTC's echo cancellation (`googEchoCancellation`) may not fully suppress this on all devices.
2. **Alters microphone gain:** On some devices, `MODE_IN_COMMUNICATION` + speakerphone changes the microphone's AGC profile, which can reduce mic sensitivity — making the parent hear the child at lower volume.
3. **Unnecessary battery drain:** Speaker amplification on the child device serves no purpose since the child is not receiving audio (in Live View, the child only sends).

**The fix:** On the child (sender), set `MODE_IN_COMMUNICATION` (required for proper mic capture in VoIP mode) but do **NOT** force speakerphone on. Let the system use the default routing (which for `MODE_IN_COMMUNICATION` without speakerphone is the earpiece — but since the child isn't playing audio, this doesn't matter).

### 4.7 Root Cause #6: AudioManager State Never Restored on Parent Disconnect

**File:** `LiveViewConnectionManager.kt:229-242`
```kotlin
fun disconnect() {
    synchronized(lock) {
        iceCollectionJob?.cancel()
        peerConnection?.close()
        peerConnection?.dispose()
        // ...
        _connectionState.value = LiveConnectionState.CLOSED
        // ← NO AudioManager.mode restoration
        // ← NO isSpeakerphoneOn = false
        // ← NO abandonAudioFocus
    }
}
```

After disconnect, the parent's AudioManager stays in `MODE_IN_COMMUNICATION` with speakerphone on. This causes:
1. **Subsequent calls fail** — the ADM for the next call may initialize with stale state.
2. **System-wide audio issues** — phone calls, media playback, and notifications are routed incorrectly until the app is killed.
3. **Battery drain** — speakerphone amplification stays active.

### 4.8 SDP Negotiation Verification (Audio Track IS Negotiated)

To rule out signaling issues, let's verify the SDP negotiation includes audio:

**Child side (`handleIncomingOffer`):**
1. `createPeerConnectionWithListener(iceServers)` — PC created
2. `setRemoteDescription(offer)` — parent's offer with `a=recvonly` audio transceiver is set
3. `audioDeviceManager.startAudioCapture(factory, pc)` — audio track added to PC
4. `acceptCall()` → `createAnswer()` — answer SDP includes `a=sendonly` audio track

**Parent side (`connect`):**
1. `pc.addTransceiver(MEDIA_TYPE_AUDIO, RECV_ONLY)` — adds `a=recvonly` audio transceiver to offer
2. `createOffer()` → offer SDP includes `m=audio ... a=recvonly`
3. `setRemoteDescription(answer)` — child's answer with `a=sendonly` audio is set

**Conclusion:** The SDP negotiation correctly establishes a one-way audio stream (child → parent). The audio RTP packets are flowing (ICE is CONNECTED). The problem is purely in the **parent's audio playout path**.

---

## 5. Video Solution Architecture

### 5.1 Option A: CameraX as Sole Camera Client (RECOMMENDED)

**Principle:** Eliminate the dual-client conflict by using CameraX as the **only** camera owner. Feed CameraX frames into WebRTC's `VideoSource` via a `SurfaceTextureHelper`, which acts as a bridge.

#### Architecture

```
┌─────────────────────────────────────────────────────────┐
│ CAMERA X (sole camera owner)                            │
│                                                         │
│  ProcessCameraProvider                                  │
│    ├── Preview use case ──→ SurfaceTexture ──→ ┐       │
│    │                                            │       │
│    └── ImageAnalysis use case ──→ MotionDetector│       │
│                                                 │       │
├─────────────────────────────────────────────────┤       │
│ BRIDGE                                          ↓       │
│                                          SurfaceTexture │
│                                          Helper         │
│                                                 │       │
│                                                 ↓       │
│ WEBRTC                          videoSource.capturerObserver
│                                         │               │
│                                         ↓               │
│                                 VideoSource             │
│                                         │               │
│                                         ↓               │
│                                 VideoTrack ("video0")   │
│                                         │               │
│                                         ↓               │
│                                 pc.addTrack(videoTrack) │
└─────────────────────────────────────────────────────────┘
```

#### Key Design Decisions

1. **Use CameraX `Preview` use case** (not `ImageAnalysis`) for the WebRTC video feed:
   - `Preview` provides higher-resolution, higher-framerate frames (30fps vs. 15fps for `ImageAnalysis`).
   - `Preview` renders to a `SurfaceTexture`, which `SurfaceTextureHelper` can consume directly.
   - `ImageAnalysis` is kept separately for motion detection at its own (lower) resolution.

2. **Both use cases bind to the same `ProcessCameraProvider` call:**
   ```kotlin
   cameraProvider.bindToLifecycle(
       lifecycleOwner,
       CameraSelector.DEFAULT_BACK_CAMERA,
       preview,        // → WebRTC video
       imageAnalysis   // → motion detection
   )
   ```
   CameraX internally shares the single camera open between both use cases.

3. **`CameraCaptureManager` gets a new code path** — instead of `Camera2Enumerator`/`CameraVideoCapturer`, it receives a `SurfaceTextureHelper` from the caller and feeds it to WebRTC's `VideoSource`.

4. **`MonitoringCoordinator.suspendCameraForCall()` becomes a no-op** — or rather, it only suspends `ImageAnalysis` (motion detection), not the camera itself. The `Preview` use case stays bound for the WebRTC video feed. After the call, `ImageAnalysis` is re-added.

#### New Component: `CameraXVideoCapturer`

A new class that:
- Creates a CameraX `Preview` use case
- Creates a `SurfaceTextureHelper`
- Connects the Preview's `SurfaceProvider` to the SurfaceTextureHelper
- Exposes a `VideoSource` that WebRTC can use to create a `VideoTrack`

```
CameraXVideoCapturer
  ├── startCapture(factory, eglBase, lifecycleOwner)
  │     1. Create SurfaceTextureHelper
  │     2. Create VideoSource via factory.createVideoSource(false)
  │     3. Connect SurfaceTextureHelper → videoSource.capturerObserver
  │     4. Create CameraX Preview use case
  │     5. Set Preview's SurfaceProvider to SurfaceTextureHelper
  │     6. Bind Preview + ImageAnalysis to ProcessCameraProvider
  │     7. Create VideoTrack via factory.createVideoTrack("video0", videoSource)
  │     8. Return VideoTrack
  │
  ├── stopCapture()
  │     1. Unbind Preview use case (keep ImageAnalysis if monitoring continues)
  │     2. Dispose SurfaceTextureHelper
  │     3. Dispose VideoSource
  │
  └── switchCamera()
        1. Rebind Preview with CameraSelector.DEFAULT_FRONT_CAMERA
        (or back, toggling)
```

#### Impact on Existing Code

| File | Change |
|------|--------|
| `CameraXVideoCapturer.kt` | **NEW** — ~120 lines |
| `CameraCaptureManager.kt` | Add alternative `startCaptureFromSurfaceTexture()` method |
| `CallManager.kt:359-365` | Uncomment camera capture, use new adapter instead of `CameraVideoCapturer` |
| `MonitoringCoordinator.kt` | `suspendCameraForCall()` → only suspend `ImageAnalysis`, not `Preview` |
| `CameraPipeline.kt` | Add method to bind `Preview` alongside `ImageAnalysis` |
| `ChildAppModule.kt` | Provide `CameraXVideoCapturer` via Hilt |

#### Advantages

- **No camera conflict** — CameraX is the sole camera client.
- **Motion detection during calls** — `ImageAnalysis` can continue running during the call (or be paused without releasing the camera).
- **No timing races** — no need to wait for camera release.
- **Camera switching** — uses CameraX's `CameraSelector` which handles the switch cleanly.
- **Future-proof** — CameraX is the recommended Android camera API; Camera2 direct access is being deprecated.

#### Disadvantages

- **Requires CameraX Preview use case** — adds a rendering surface to the camera pipeline (minor overhead).
- **Frame format conversion** — CameraX `Preview` outputs to a `SurfaceTexture` (RGB), while WebRTC prefers I420. The `SurfaceTextureHelper` + `VideoSource` handle this conversion internally via the GPU (EGL), so it's efficient.
- **~150 lines of new code** — the `CameraXVideoCapturer` adapter.

### 5.2 Option B: Deterministic Camera Handoff (SIMPLER, Fragile)

**Principle:** Keep two separate camera clients but ensure the first fully releases the camera before the second opens it, using `CameraManager.AvailabilityCallback`.

#### Implementation

```kotlin
// In CameraPipeline or MonitoringCoordinator:
suspend fun waitForCameraRelease(cameraId: String, timeoutMs: Long = 3000) {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val released = CompletableDeferred<Unit>()

    val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraClosed(id: String) {
            if (id == cameraId) {
                released.complete(Unit)
            }
        }
    }
    cameraManager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
    
    withTimeoutOrNull(timeoutMs) { released.await() }
    cameraManager.unregisterAvailabilityCallback(callback)
}
```

Then in `CallManager.handleIncomingOffer()`:
```kotlin
monitoringCoordinator.suspendCameraForCall()
// Wait for the back camera to be truly released:
cameraPipeline.waitForCameraRelease("0", timeoutMs = 3000)  // cameraId "0" = back
// NOW safe to start WebRTC capture:
cameraCaptureManager.startCapture(factory, eglBase, pc)
```

#### Advantages

- **Minimal code change** (~40 lines).
- **No new components** — uses existing `CameraCaptureManager` and `CameraPipeline`.

#### Disadvantages

- **Still two clients** — the fundamental conflict exists.
- **`onCameraClosed` is unreliable on some OEMs** — Samsung and Xiaomi camera HALs may not fire this callback consistently.
- **Motion detection paused during calls** — `ImageAnalysis` is stopped, so no motion alerts during Live View.
- **3-second timeout** — if the camera doesn't release, the call starts without video (degrades silently).
- **Race condition window** — between `onCameraClosed` and `Camera2Enumerator.createCapturer()`, another app could grab the camera.

### 5.3 Recommendation

**Option A** for production. **Option B** as a quick validation if you want to confirm the diagnosis before investing in the full adapter.

---

## 6. Audio Solution Architecture

### 6.1 Fix Overview

Six changes, all small, targeting the parent's audio playout path:

| # | Fix | File | Lines |
|---|-----|------|-------|
| 1 | Store & enable remote AudioTrack in `onAddTrack` | `LiveViewConnectionManager.kt` | ~15 |
| 2 | Create explicit `JavaAudioDeviceModule` | `LiveViewConnectionManager.kt` | ~10 |
| 3 | Request audio focus + set mode BEFORE `initializeWebRtc()` | `LiveViewConnectionManager.kt` | ~20 |
| 4 | Restore AudioManager state on `disconnect()` | `LiveViewConnectionManager.kt` | ~10 |
| 5 | Remove speakerphone on child sender | `AudioDeviceManager.kt` | ~2 |
| 6 | Dispose ADM on `fullCleanup()` | `LiveViewConnectionManager.kt` | ~3 |

### 6.2 Fix #1: Handle Remote AudioTrack

**Problem:** `onAddTrack` ignores `AudioTrack`, so the track is never stored or explicitly enabled.

**Solution:** Add a `_remoteAudioTrack` StateFlow and handle `AudioTrack` in `onAddTrack`:

```kotlin
// New state:
private val _remoteAudioTrack = MutableStateFlow<AudioTrack?>(null)
val remoteAudioTrack: StateFlow<AudioTrack?> = _remoteAudioTrack.asStateFlow()

// In createPeerConnection's observer:
override fun onAddTrack(
    receiver: RtpReceiver?,
    streams: Array<out MediaStream>?
) {
    receiver?.track()?.let { track ->
        when (track) {
            is VideoTrack -> handler.post { _remoteVideoTrack.value = track }
            is AudioTrack -> {
                track.setEnabled(true)
                track.setVolume(1.0)
                handler.post { _remoteAudioTrack.value = track }
            }
        }
    }
}
```

Also handle `onAddStream` (legacy path, line 314-317):
```kotlin
override fun onAddStream(stream: MediaStream?) {
    stream?.let { s ->
        s.videoTracks?.firstOrNull()?.let { track ->
            handler.post { _remoteVideoTrack.value = track }
        }
        s.audioTracks?.firstOrNull()?.let { track ->
            track.setEnabled(true)
            handler.post { _remoteAudioTrack.value = track }
        }
    }
}
```

**Why `setEnabled(true)` and `setVolume(1.0)`:**
- `setEnabled(true)` ensures the track is active for playout. Some WebRTC implementations deliver tracks in a disabled state by default.
- `setVolume(1.0)` sets the playout volume to 100%. The default may be 0.0 on some configurations.

### 6.3 Fix #2: Explicit JavaAudioDeviceModule

**Problem:** The default ADM is created without audio focus, without proper mode, and initializes its AudioTrack in `MODE_NORMAL`.

**Solution:** Create an explicit ADM with hardware AEC/NS:

```kotlin
// In initializeWebRtc():
val audioDeviceModule = JavaAudioDeviceModule.builder(context)
    .setUseHardwareAcousticEchoCanceler(true)
    .setUseHardwareNoiseSuppressor(true)
    .createAudioDeviceModule()

peerConnectionFactory = PeerConnectionFactory.builder()
    .setAudioDeviceModule(audioDeviceModule)
    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
    .setOptions(PeerConnectionFactory.Options().apply {
        disableEncryption = false
        disableNetworkMonitor = false
    })
    .createPeerConnectionFactory()

// Store for cleanup:
this.audioDeviceModule = audioDeviceModule
```

**Import:** `import org.webrtc.audio.JavaAudioDeviceModule`

**Why hardware AEC/NS:**
- `setUseHardwareAcousticEchoCanceler(true)` — uses the device's built-in echo cancellation (more effective than software on most devices).
- `setUseHardwareNoiseSuppressor(true)` — uses the device's hardware noise suppression.

These only affect the **capture** side (microphone), but they're important for the child (sender) too. The same fix should be applied to `WebRtcPeerConnectionManager.kt` on the child side.

### 6.4 Fix #3: Audio Focus + Mode BEFORE initializeWebRtc()

**Problem:** `MODE_IN_COMMUNICATION` + speakerphone are set AFTER the connection is established, which is too late for the ADM's internal AudioTrack initialization.

**Solution:** Move the audio configuration to the **start** of `connect()`, before `initializeWebRtc()`:

```kotlin
suspend fun connect(childDeviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        debugLog("connect: start childId=$childDeviceId")
        disconnect()

        // ── NEW: Configure audio routing BEFORE WebRTC init ──
        configureAudioForCall()

        withTimeout(60_000) {
            _connectionState.value = LiveConnectionState.CONNECTING
            initializeWebRtc()
            // ... rest of connect logic
```

New helper method:
```kotlin
private var audioFocusRequest: AudioFocusRequest? = null

private fun configureAudioForCall() {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Save previous state
    previousAudioMode = am.mode
    wasSpeakerphoneOn = am.isSpeakerphoneOn

    // Request audio focus (required on Samsung One UI)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { /* handle focus loss if needed */ }
            .build()
        am.requestAudioFocus(audioFocusRequest!!)
    }

    // Set communication mode + speakerphone
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = true
    debugLog("connect: audio configured (mode=IN_COMMUNICATION, speaker=ON, focus=acquired)")
}
```

**Required imports:**
```kotlin
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Build
```

**Why `AUDIOFOCUS_GAIN_TRANSIENT`:**
- The call is a temporary session — we don't want to permanently claim audio focus.
- `USAGE_VOICE_COMMUNICATION` tells the system this is a VoIP call, which enables the communication audio path.
- `CONTENT_TYPE_SPEECH` enables speech-optimized processing.

### 6.5 Fix #4: Restore AudioManager on Disconnect

**Problem:** `disconnect()` doesn't restore the AudioManager state, leaving the parent in `MODE_IN_COMMUNICATION` with speakerphone on.

**Solution:**
```kotlin
private fun restoreAudioAfterCall() {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Abandon audio focus
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // Restore previous state
    am.isSpeakerphoneOn = wasSpeakerphoneOn
    am.mode = previousAudioMode
    debugLog("disconnect: audio restored (mode=$previousAudioMode, speaker=$wasSpeakerphoneOn)")
}
```

Add call in `disconnect()`:
```kotlin
fun disconnect() {
    synchronized(lock) {
        iceCollectionJob?.cancel()
        iceCollectionJob = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        dataChannel?.close()
        dataChannel = null
        _remoteVideoTrack.value = null
        _remoteAudioTrack.value = null    // ← NEW
        _connectionState.value = LiveConnectionState.CLOSED
        sessionId = null
    }
    restoreAudioAfterCall()               // ← NEW (outside lock to avoid blocking)
}
```

### 6.6 Fix #5: Child Should Not Force Speakerphone

**File:** `AudioDeviceManager.kt:150-161`

**Current:**
```kotlin
private fun configureAudioManagerForCall() {
    val am = audioManager ?: return
    previousAudioMode = am.mode
    wasSpeakerphoneOn = am.isSpeakerphoneOn
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = true  // ← REMOVE
}
```

**Fixed:**
```kotlin
private fun configureAudioManagerForCall() {
    val am = audioManager ?: return
    previousAudioMode = am.mode
    wasSpeakerphoneOn = am.isSpeakerphoneOn
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    // Do NOT force speakerphone on the sender — the child only captures audio,
    // it doesn't play audio. Forcing speakerphone risks acoustic echo feedback
    // and may alter microphone AGC profiles on some devices.
}
```

The `restoreAudioManager()` method already restores `wasSpeakerphoneOn`, so no change needed there.

### 6.7 Fix #6: Dispose ADM on fullCleanup

```kotlin
private var audioDeviceModule: org.webrtc.audio.JavaAudioDeviceModule? = null

fun fullCleanup() {
    disconnect()
    peerConnectionFactory?.dispose()
    peerConnectionFactory = null
    audioDeviceModule?.release()
    audioDeviceModule = null
    eglBase?.release()
    eglBase = null
}
```

### 6.8 Complete Audio Fix: State Flow After Fixes

```
PARENT (receiver) — AFTER FIXES
┌──────────────────────────────────────────────┐
│ 1. configureAudioForCall() — BEFORE init     │
│    → requestAudioFocus(VOICE_COMMUNICATION)  │
│    → am.mode = MODE_IN_COMMUNICATION         │
│    → am.isSpeakerphoneOn = true              │
│                                              │
│ 2. initializeWebRtc()                        │
│    → JavaAudioDeviceModule created           │
│    → ADM's AudioTrack initialized with       │
│      MODE_IN_COMMUNICATION routing (speaker) │
│    → PeerConnectionFactory with explicit ADM │
│                                              │
│ 3. createPeerConnection()                    │
│    → addTransceiver(AUDIO, RECV_ONLY)        │
│    → addTransceiver(VIDEO, RECV_ONLY)        │
│                                              │
│ 4. createOffer() → sendOffer()               │
│ 5. pollAnswerDirect() → setRemoteDescription │
│ 6. ICE CONNECTED                             │
│                                              │
│ 7. onAddTrack()                              │
│    → AudioTrack: setEnabled(true)            │
│    → AudioTrack: setVolume(1.0)              │
│    → _remoteAudioTrack.value = track         │
│                                              │
│ 8. ADM plays decoded audio → SPEAKER ✓       │
│    (focus acquired, mode correct, routing    │
│     set before ADM init)                     │
│                                              │
│ 9. disconnect()                              │
│    → abandonAudioFocus                       │
│    → am.mode = MODE_NORMAL                   │
│    → isSpeakerphoneOn = false                │
└──────────────────────────────────────────────┘
```

---

## 7. Implementation Specifications

### 7.1 File Change Summary

#### Audio Fixes (do first)

| File | Changes | New Lines |
|------|---------|-----------|
| `LiveViewConnectionManager.kt` | Add `_remoteAudioTrack` StateFlow, handle AudioTrack in `onAddTrack`/`onAddStream`, create explicit ADM, `configureAudioForCall()`, `restoreAudioAfterCall()`, dispose ADM in `fullCleanup()` | ~60 |
| `AudioDeviceManager.kt` | Remove `am.isSpeakerphoneOn = true` from `configureAudioManagerForCall()` | ~2 (deletion) |
| `WebRtcPeerConnectionManager.kt` | Add explicit ADM (child side — improves capture quality) | ~10 |

#### Video Fixes (Option A — do second)

| File | Changes | New Lines |
|------|---------|-----------|
| `CameraXVideoCapturer.kt` | **NEW** — CameraX Preview → SurfaceTextureHelper → WebRTC VideoSource adapter | ~120 |
| `CameraCaptureManager.kt` | Add `startCaptureFromCameraX()` alternative to `startCapture()` | ~30 |
| `CallManager.kt` | Uncomment camera capture in `handleIncomingOffer()`, use new adapter | ~10 |
| `MonitoringCoordinator.kt` | Modify `suspendCameraForCall()` to only suspend `ImageAnalysis`, not `Preview` | ~15 |
| `CameraPipeline.kt` | Add method to bind `Preview` use case alongside `ImageAnalysis` | ~30 |
| `ChildAppModule.kt` | Provide `CameraXVideoCapturer` | ~10 |

### 7.2 Key Code Snippets

#### 7.2.1 CameraXVideoCapturer (NEW — Option A core)

```kotlin
class CameraXVideoCapturer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    suspend fun startCapture(
        factory: PeerConnectionFactory,
        eglBase: EglBase?,
        lifecycleOwner: LifecycleOwner
    ): VideoTrack? {
        stopCapture()

        this.lifecycleOwner = lifecycleOwner

        // 1. Create SurfaceTextureHelper (WebRTC's frame bridge)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CameraXCapture",
            eglBase?.eglBaseContext
        )

        // 2. Create WebRTC VideoSource
        videoSource = factory.createVideoSource(false)

        // 3. Connect SurfaceTextureHelper to VideoSource
        // The SurfaceTextureHelper will call capturerObserver.onFrameCaptured()
        // for each frame from the CameraX Preview
        surfaceTextureHelper?.setListener(
            videoSource!!.capturerObserver,
            Handler(Looper.getMainLooper())
        )
        videoSource!!.capturerObserver.onCapturerStarted(true)

        // 4. Create CameraX Preview use case
        preview = Preview.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .build()
            .also { p ->
                p.setSurfaceProvider { request ->
                    // Provide the SurfaceTexture from SurfaceTextureHelper
                    val surface = Surface(surfaceTextureHelper!!.surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
                }
            }

        // 5. Get ProcessCameraProvider and bind Preview
        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = providerFuture.await()

        // Bind ONLY Preview here — ImageAnalysis is bound by CameraPipeline
        // CameraX will merge them if both are bound to the same provider
        try {
            cameraProvider!!.bindToLifecycle(
                lifecycleOwner,
                currentCameraSelector,
                preview
            )
        } catch (e: Exception) {
            stopCapture()
            return null
        }

        // 6. Create VideoTrack
        videoTrack = factory.createVideoTrack("video0", videoSource)
        videoTrack!!.setEnabled(true)

        return videoTrack
    }

    fun stopCapture() {
        try {
            cameraProvider?.unbind(preview)
            preview = null

            videoTrack?.dispose()
            videoTrack = null

            videoSource?.dispose()
            videoSource = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            cameraProvider = null
        } catch (e: Exception) {
            // Best effort
        }
    }

    fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val provider = cameraProvider ?: return
        val lo = lifecycleOwner ?: return
        try {
            provider.unbind(preview)
            provider.bindToLifecycle(lo, currentCameraSelector, preview)
        } catch (e: Exception) {
            // Best effort
        }
    }

    val currentVideoTrack: VideoTrack? get() = videoTrack
}
```

**Note:** The actual implementation may need adjustments based on how CameraX's `Preview.SurfaceProvider` interacts with `SurfaceTextureHelper`. An alternative approach is to use a `VideoProcessor` on the `VideoSource` that receives CameraX `ImageProxy` frames and converts them to `VideoFrame.I420Buffer`. This avoids the `SurfaceTexture` indirection but requires YUV conversion.

#### 7.2.2 CameraPipeline — Add Preview Binding Support

```kotlin
// In CameraPipeline.kt:

private var previewUseCase: Preview? = null

/**
 * Bind a CameraX Preview use case alongside the existing ImageAnalysis.
 * Used by WebRTC video capture to share the camera without conflict.
 */
fun bindPreview(preview: Preview) {
    previewUseCase = preview
    rebindCamera()
}

fun unbindPreview() {
    val provider = cameraProvider ?: return
    provider.unbind(previewUseCase)
    previewUseCase = null
}

private fun rebindCamera() {
    val provider = cameraProvider ?: return
    val lo = currentLifecycleOwner ?: return

    provider.unbindAll()
    val useCases = mutableListOf<androidx.camera.core.UseCase>()
    imageAnalysis?.let { useCases.add(it) }
    previewUseCase?.let { useCases.add(it) }

    if (useCases.isNotEmpty()) {
        provider.bindToLifecycle(lo, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
    }
}
```

#### 7.2.3 MonitoringCoordinator — Refined suspendCameraForCall

```kotlin
suspend fun suspendCameraForCall() {
    Log.i(TAG, "Suspending motion detection for call (camera stays bound for Preview)")
    motionEventJob?.cancel()
    motionEventJob = null
    motionDetector.stopDetection()
    // Do NOT stop camera analysis — the Preview use case stays bound
    // for WebRTC video. Only stop the ImageAnalysis analyzer.
    withContext(Dispatchers.Main) {
        cameraPipeline.suspendImageAnalysisOnly()
    }
}

fun resumeCameraAfterCall() {
    val config = _currentConfig ?: return
    if (monitoringState.value !is MonitoringState.Active) return
    try {
        val lifecycleOwner = cameraPipeline.getSavedLifecycleOwner()
        if (lifecycleOwner != null && config.motionEnabled) {
            cameraPipeline.resumeImageAnalysis()
            motionDetector.startDetection(config, lifecycleOwner)
            motionEventJob = scope.launch {
                motionDetector.motionEvents.collect { eventPipeline.submitMotionEvent(it) }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resume motion detection after call", e)
    }
}
```

---

## 8. Verification & Testing Strategy

### 8.1 Audio Verification

**Manual test procedure:**
1. Build and install parent + child APKs.
2. Start monitoring on child.
3. Initiate Live View from parent.
4. Verify audio is heard on parent speaker.
5. Test with child in a different room (real-world scenario).
6. Disconnect and verify AudioManager state is restored (make a normal phone call afterward — should route correctly).
7. Reconnect and verify audio works on second call.

**Automated checks:**
- `adb shell dumpsys audio` — verify `mode: MODE_IN_COMMUNICATION` during call and `MODE_NORMAL` after disconnect.
- WebRTC stats: verify `inbound-rtp` audio packets are being received:
  ```
  adb -s RZCX426L2BL shell "run-as com.childhelper.app.parent cat files/lv_debug.txt"
  ```
- Add audio level logging in `onAddTrack`:
  ```kotlin
  if (track is AudioTrack) {
      debugLog("onAddTrack: AudioTrack received, enabled=${track.enabled()}, id=${track.id()}")
  }
  ```

**Device-specific testing:**
- Samsung S24 (One UI 6) — primary test device, strictest audio focus enforcement.
- Xiaomi 12 (MIUI) — child device, verify mic capture quality.
- Pixel (stock Android) — baseline, should work without audio focus.

### 8.2 Video Verification

**Manual test procedure (Option A):**
1. Start monitoring on child (motion detection active).
2. Initiate Live View from parent.
3. Verify video is displayed on parent (no crash).
4. Verify motion detection continues during the call (trigger motion, parent should receive alert).
5. Test camera switch (front ↔ back) during call.
6. End call — verify motion detection resumes normally.

**Crash verification:**
- Monitor `adb logcat | grep -i "SIGSEGV\|libjingle"` during call start/stop — should be clean.
- Check `call_trace.txt` for native crash traces.

**Performance verification:**
- Video latency: should be < 500ms end-to-end.
- Frame rate: should be 15-24 fps (configured in `startCapture`).
- CPU usage: CameraX Preview + ImageAnalysis should be < 15% total CPU.

### 8.3 Regression Tests

| Scenario | Expected Behavior |
|----------|-------------------|
| Monitoring ON → Live View → End call | Motion detection resumes, no crash |
| Monitoring OFF → Live View → End call | No motion detection (was never on), clean cleanup |
| Live View → background → foreground | Connection state preserved or reconnected |
| Two consecutive Live Views | Second call works (AudioManager restored between calls) |
| Phone call during Live View | Audio focus handled gracefully (ducking or pause) |
| Child device reboot during Live View | Parent detects disconnection, shows error state |

---

## 9. Risk Analysis & Edge Cases

### 9.1 Audio Fix Risks

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| `JavaAudioDeviceModule` API differs in getstream 1.3.7 | Low | Medium | Verify class path `org.webrtc.audio.JavaAudioDeviceModule` exists in the AAR |
| Audio focus denied by system (DND mode) | Medium | Low | Handle `AUDIOFOCUS_REQUEST_FAILED` — fall back to `MODE_NORMAL` + media volume |
| `MODE_IN_COMMUNICATION` not supported on child device | Very Low | Low | All Android 8.0+ devices support this mode |
| Echo on child if speakerphone was masking a routing issue | Medium | Medium | Keep hardware AEC enabled; test on Xiaomi 12 |
| Audio plays through earpiece despite speakerphone setting | Medium (Samsung) | High | Verify with `adb shell dumpsys audio` — check `Speaker: on` |
| `AudioFocusRequest` not available on API 26 | None | N/A | `AudioFocusRequest` requires API 26 — matches our `minSdk = 26` |

### 9.2 Video Fix Risks (Option A)

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| CameraX `Preview.SurfaceProvider` incompatible with `SurfaceTextureHelper` | Medium | High | Use `VideoProcessor` approach as fallback (feed `ImageProxy` → `I420Buffer`) |
| Frame rate from `Preview` is lower than `CameraVideoCapturer` | Low | Low | Set `Preview.Builder().setTargetResolution(Size(640, 480))` for 30fps |
| CameraX binding fails when both `Preview` and `ImageAnalysis` request different resolutions | Medium | Medium | Use `ResolutionSelector` with `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` |
| `ProcessCameraProvider.await()` blocking | Low | Low | Already on `Dispatchers.IO` in `connect()` |
| Motion detector receives fewer frames when `Preview` is bound | Low | Low | CameraX shares frames between use cases; ImageAnalysis gets its own copy |
| Camera switch during call doesn't work with CameraX | Low | Medium | Use `cameraProvider.unbind(preview)` + `bindToLifecycle(newSelector, preview)` |

### 9.3 Edge Cases

**Audio:**
- **Bluetooth headset connected:** `MODE_IN_COMMUNICATION` routes to Bluetooth SCO by default. The user may expect speaker output. Need to handle `SCO_AUDIO_STATE_CONNECTED` and route accordingly.
- **Wired headphones:** Audio should route to wired headset. Verify `isSpeakerphoneOn = true` doesn't force speaker when wired headset is connected.
- **Do Not Disturb mode:** Audio focus request may be denied. Handle gracefully.
- **Phone call interrupting Live View:** `PHONE_STATE` broadcast should trigger `disconnect()` or audio ducking.

**Video:**
- **Child device in low-power mode (`PowerMode.CRITICAL`):** Camera is unbound. Live View video should fail gracefully (audio-only).
- **Thermal throttling (HOT state):** Camera is stopped. Same as above.
- **Multiple concurrent Live View sessions:** Not supported — `handleIncomingOffer` skips duplicates (issue #8 fix).
- **Child screen off (Doze mode):** CameraX may stop delivering frames. Use wake lock (already in `MonitoringService`).

---

## 10. Implementation Sequence

### Phase 1: Audio Fix (Estimated: 2-3 hours)

```
Step 1.1  → Fix #1: Handle remote AudioTrack in onAddTrack
            Verify: AudioTrack is stored, enabled, volume set
            File: LiveViewConnectionManager.kt

Step 1.2  → Fix #2: Create explicit JavaAudioDeviceModule
            Verify: ADM created with hardware AEC/NS
            File: LiveViewConnectionManager.kt, WebRtcPeerConnectionManager.kt

Step 1.3  → Fix #3: configureAudioForCall() before initializeWebRtc()
            Verify: dumpsys audio shows MODE_IN_COMMUNICATION during call
            File: LiveViewConnectionManager.kt

Step 1.4  → Fix #4: restoreAudioAfterCall() in disconnect()
            Verify: dumpsys audio shows MODE_NORMAL after disconnect
            File: LiveViewConnectionManager.kt

Step 1.5  → Fix #5: Remove speakerphone on child sender
            Verify: No echo on parent side
            File: AudioDeviceManager.kt

Step 1.6  → Build + deploy + test on Samsung S24 (parent) + Xiaomi 12 (child)
            Verify: Audio IS heard on parent speaker

Step 1.7  → Fix #6: Dispose ADM in fullCleanup()
            File: LiveViewConnectionManager.kt
```

### Phase 2: Video Fix — Option A (Estimated: 4-6 hours)

```
Step 2.1  → Create CameraXVideoCapturer.kt
            Verify: Compiles, unit-testable
            File: NEW — CameraXVideoCapturer.kt

Step 2.2  → Add bindPreview/unbindPreview to CameraPipeline.kt
            Verify: CameraX binds Preview + ImageAnalysis simultaneously
            File: CameraPipeline.kt

Step 2.3  → Modify MonitoringCoordinator.suspendCameraForCall()
            Verify: Only ImageAnalysis suspended, Preview stays bound
            File: MonitoringCoordinator.kt

Step 2.4  → Update CallManager.handleIncomingOffer() to use CameraXVideoCapturer
            Verify: Video track created from CameraX frames
            File: CallManager.kt

Step 2.5  → Provide CameraXVideoCapturer via Hilt
            Verify: DI graph compiles
            File: ChildAppModule.kt

Step 2.6  → Build + deploy + test
            Verify: Video displays on parent, no native crash

Step 2.7  → Verify motion detection during call
            Verify: Motion alert received by parent during Live View

Step 2.8  → Verify camera switch during call
            Verify: Front/back camera toggle works
```

### Phase 3: Hardening (Estimated: 1-2 hours)

```
Step 3.1  → Handle Bluetooth audio routing
Step 3.2  → Handle phone call interruption (audio focus loss)
Step 3.3  → Handle low-power mode during Live View (graceful video fallback)
Step 3.4  → Add audio level logging in onAddTrack for diagnostics
Step 3.5  → Restore arePaired() server check (P1 from LIVEVIEW_DEBUG_LOG.md)
```

---

## Appendix A: Key File References

| File | Path | Relevance |
|------|------|-----------|
| `CallManager.kt` | `app/child/.../ui/call/CallManager.kt` | Child-side call orchestration; video disabled at :359-365 |
| `CameraCaptureManager.kt` | `app/child/.../ui/call/CameraCaptureManager.kt` | WebRTC Camera2 capturer (conflicting client) |
| `CameraPipeline.kt` | `app/child/.../detection/CameraPipeline.kt` | CameraX ImageAnalysis (monitoring camera client) |
| `AudioDeviceManager.kt` | `app/child/.../ui/call/AudioDeviceManager.kt` | Child audio capture; speakerphone issue at :157 |
| `WebRtcPeerConnectionManager.kt` | `app/child/.../ui/call/WebRtcPeerConnectionManager.kt` | Child PC factory; no ADM |
| `MonitoringCoordinator.kt` | `app/child/.../service/MonitoringCoordinator.kt` | Camera suspend/resume for calls |
| `LiveViewConnectionManager.kt` | `app/parent/.../ui/liveview/LiveViewConnectionManager.kt` | Parent PC factory; onAddTrack; audio routing |
| `LiveViewViewModel.kt` | `app/parent/.../ui/liveview/LiveViewViewModel.kt` | Parent Live View state management |
| `TalkBackManager.kt` | `app/parent/.../ui/liveview/TalkBackManager.kt` | Parent→child audio via data channel |
| `LiveViewScreen.kt` | `app/parent/.../ui/liveview/LiveViewScreen.kt` | Parent video renderer (SurfaceViewRenderer) |
| `ChildAppModule.kt` | `app/child/.../di/ChildAppModule.kt` | Hilt DI for child app |
| `libs.versions.toml` | `gradle/libs.versions.toml` | WebRTC version: io.getstream:stream-webrtc-android:1.3.7 |

## Appendix B: WebRTC Library API Reference

- **`JavaAudioDeviceModule`**: `org.webrtc.audio.JavaAudioDeviceModule`
  - Builder: `JavaAudioDeviceModule.builder(context)`
  - Methods: `.setUseHardwareAcousticEchoCanceler(true)`, `.setUseHardwareNoiseSuppressor(true)`, `.createAudioDeviceModule()`
  - Must be released: `audioDeviceModule.release()`

- **`SurfaceTextureHelper`**: `org.webrtc.SurfaceTextureHelper`
  - Create: `SurfaceTextureHelper.create(threadName, eglBaseContext)`
  - Set listener: `setListener(capturerObserver, handler)`
  - Dispose: `dispose()`

- **`VideoSource`**: `org.webrtc.VideoSource`
  - Create: `factory.createVideoSource(isScreencast)`
  - Capturer observer: `videoSource.capturerObserver` — receives `VideoFrame` objects
  - Dispose: `videoSource.dispose()`

- **`AudioTrack`**: `org.webrtc.AudioTrack`
  - Enable: `track.setEnabled(true)`
  - Volume: `track.setVolume(1.0)` (0.0 to 1.0)
  - Does NOT require a sink (unlike VideoTrack) — audio is auto-played by the ADM

## Appendix C: Android AudioManager Reference

| Mode | Use Case | Audio Routing |
|------|----------|---------------|
| `MODE_NORMAL` | Media playback, notifications | Media speaker/headphones |
| `MODE_IN_COMMUNICATION` | VoIP calls | Communication path (earpiece or speaker if `isSpeakerphoneOn`) |
| `MODE_RINGTONE` | Incoming call ringing | Ringtone speaker |
| `MODE_IN_CALL` | Cellular call | Cellular audio path |

**Audio Focus levels:**
- `AUDIOFOCUS_GAIN` — Permanent focus (music apps)
- `AUDIOFOCUS_GAIN_TRANSIENT` — Temporary focus (VoIP calls)
- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — Temporary, others can duck (navigation)
- `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` — Temporary, no one else (recording)

**For VoIP:** Use `AUDIOFOCUS_GAIN_TRANSIENT` with `USAGE_VOICE_COMMUNICATION`.

---

*End of analysis document.*
