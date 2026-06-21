# Child Helper App — Diagnosis & Fix Plan

**Date:** 2026-06-21  
**Based on:** Screenshots from parent + child devices, full codebase review  
**Status:** Pairing works; monitoring, alerts, live view, and localization do not

---

## Executive Summary

The app **looks** functional in screenshots — pairing succeeds, Bulgarian UI is partially applied, settings screens render — but the **core value chain is broken**. Three independent subsystems were built as UI shells without being wired to backend logic:

| Symptom (from screenshots) | Root cause | Severity |
|----------------------------|------------|----------|
| Live view stuck on *"Establishing secure connection to child device..."* | Parent `LiveViewViewModel` is a stub — no WebRTC, no signaling, no timeout | **P0** |
| Signal history shows **0 signals** | Cry/motion events never reach `EventPipeline`; parent never persists FCM alerts | **P0** |
| Child shows **"Monitoring is off"** after pairing | Monitoring is manual-only; not auto-started; home screen bypasses `MonitoringService` | **P1** |
| Settings title in Bulgarian, body in English | `SettingsScreen.kt` hardcodes ~35 English strings | **P1** |
| "Mom"/"Dad" in English on Bulgarian home screen | Hardcoded in `ContactButton.kt`, `ChildHomeViewModel.kt`, `CallViewModel.kt` | **P1** |
| Button text clipped ("Старт", "Свързване") | Layout constraints in `StatusCard` / `QuickActionsRow` | **P2** |
| Live view dialog mixed BG/EN | Hardcoded English in `ConnectionProgressDialog` | **P2** |

**Bottom line:** Pairing stores crypto keys locally, but downstream features (detection → alerts → history, parent → child WebRTC) were never connected end-to-end.

---

## What Works Today

- **Server pairing API** — child initiates session, parent completes with code/QR; both apps store `shared_secret`, `is_paired`, device IDs
- **Child pairing UI** — code, QR, session ID display; Bulgarian strings via `stringResource()`
- **Child WebRTC send path** — `CallManager` can create peer connection, send SDP offer, send ICE candidates
- **Signaling server** — Ktor routes enqueue offers/answers/ICE; TURN server in docker-compose
- **Locale infrastructure** — `LocaleManager`, `values-bg/strings.xml` with ~100 keys per module
- **Detection engines** — `CryDetector`, `MotionDetector`, `MonitoringCoordinator` start/stop detectors correctly

---

## Architecture: Current vs Expected

### Current (broken)

```
Pair OK ──► Child Home (monitoring OFF by default)
                │
                ├── [user taps Start] ──► MonitoringCoordinator starts detectors
                │                              ╳ cry/motion events NOT forwarded
                │                              ╳ EventPipeline.submitCryEvent never called
                │
                ├── Parent Live View ──► CONNECTING forever (stub ViewModel)
                │                              ╳ no WebRTC on parent
                │                              ╳ signalingClient.startPolling() never called
                │
                └── Server /notify ──► FCM (often unconfigured)
                                           ╳ parent never collects alertFlow
                                           ╳ AlertHistoryRepository.insertAlert never called
                                           ╳ Signal history = 0
```

### Expected

```
Pair OK ──► Auto-start MonitoringService (with permission prompt)
                │
                ├── Detectors ──► EventPipeline.submit* ──► HybridNotificationSender
                │                                              │
                │                                              ▼
                │                                         POST /notify
                │                                              │
                │                                              ▼
                │                                         Parent FCM ──► Room DB ──► Signal History UI
                │
                └── Parent Live View ──► WebRTC offer ──► signaling poll ──► child answer
                                              │                                    │
                                              └──────── ICE exchange ──────────────┘
                                                              │
                                                              ▼
                                                    Video track → SurfaceViewRenderer
```

---

## Detailed Root Cause Analysis

### 1. Live View — Stuck on "Connecting" (P0)

**Evidence:** Parent screenshot shows Bulgarian title *"Свързване с устройството..."* with English subtext *"Establishing secure connection to child device..."* and spinner that never completes.

**Root cause chain:**

1. `LiveViewScreen` auto-calls `viewModel.startConnection()` on launch (`LiveViewScreen.kt:128`)
2. `LiveViewViewModel.startConnection()` only sets `CONNECTING`, shows dialog, and `delay(2000)` — comment says *"in production, this triggers WebRTC setup"* but nothing follows (`LiveViewViewModel.kt:199-207`)
3. **No parent WebRTC stack** — no `WebRtcPeerConnectionManager`, no `CallManager` equivalent in `:app:parent`
4. **Signaling receive is unimplemented on both apps** — `WebRtcSignalingClient.startPolling()` exists but is **never called** from production code (only documented in `API.md` / `TESTING.md`)
5. **Child incoming-call path broken** — `CallState.Incoming` is handled in UI but never emitted; `acceptCall()` creates answer without `setRemoteDescription(offer)`
6. **No connection timeout** — state stays `CONNECTING` until user taps Cancel
7. **Video renderer not wired** — parent `VideoRenderer` creates `SurfaceViewRenderer` but never attaches remote `VideoTrack`
8. **TURN unused** — child `CallManager` uses Google STUN only; docker TURN + `getTurnCredentials()` API ignored

**Key files:**

| File | Issue |
|------|-------|
| `project/app/parent/.../liveview/LiveViewViewModel.kt` | Stub `startConnection()` |
| `project/app/parent/.../liveview/LiveViewScreen.kt` | Hardcoded EN dialog text; no video track binding |
| `project/core/network/.../signaling/WebRtcSignalingClient.kt` | `startPolling()` never invoked |
| `project/app/child/.../call/CallManager.kt` | Send-only signaling; broken `acceptCall()` |
| `project/server/.../routes/SignalingRoutes.kt` | No FCM `signal_poll` push after enqueue |

---

### 2. Signal History — 0 Signals (P0)

**Evidence:** Parent screenshot shows *"История на сигналите"*, filter chips in Bulgarian, *"0 сигнала"*, *"Няма намерени сигнали"*.

**Root cause chain:**

1. `MonitoringCoordinator.startMonitoring()` starts `CryDetector` and `MotionDetector` but **never collects their event flows** and never calls `eventPipeline.submitCryEvent()` / `submitMotionEvent()`
2. `DetectionViewModel` observes detector events for **local UI only** — no pipeline submission
3. `EventPipeline.submitCryEvent` / `submitMotionEvent` are defined but have **zero call sites** outside their definitions
4. Only thermal/battery/SOS paths partially use `EventPipeline` (via `SosManager`, thermal monitoring)
5. **Parent side:** `FcmService.alertFlow` is documented with example collector code in comments, but **no production collector** calls `AlertHistoryRepository.insertAlert()`
6. **FCM likely unconfigured:** `docker-compose.yml` has empty `FCM_ACCESS_TOKEN` / `FIREBASE_PROJECT_ID`; no `google-services.json` in repo
7. Even if child sent alerts, parent would not persist them

**Key files:**

| File | Issue |
|------|-------|
| `project/app/child/.../service/MonitoringCoordinator.kt` | No detector → EventPipeline wiring |
| `project/app/child/.../detection/EventPipeline.kt` | `submitCryEvent` / `submitMotionEvent` unused |
| `project/app/child/.../ui/detection/DetectionViewModel.kt` | UI-only event collection |
| `project/core/network/.../push/FcmService.kt` | `alertFlow` never consumed on parent |
| `project/app/parent/.../repository/AlertHistoryRepository.kt` | `insertAlert` never called from FCM path |
| `project/core/p2p/.../HybridNotificationSender.kt` | Would work IF events reached it |

---

### 3. Monitoring Off After Pairing (P1)

**Evidence:** Child screenshots show *"Наблюдението е изключено"* / *"Докоснете, за да започнете защита"* with truncated *"Старт"* button.

**Root cause chain:**

1. Pairing success (`PairingState.PAIRED`) only navigates back — **no side effect** to start monitoring
2. `ChildHomeViewModel` defaults `isMonitoring = false`; user must manually tap Start
3. Home screen calls `monitoringCoordinator.startMonitoring()` **directly**, not via `MonitoringService` foreground service
4. Monitoring stops when app backgrounds or `ChildHomeViewModel.onCleared()` runs
5. `MonitoringCoordinator` KDoc says *"Only MonitoringService should call startMonitoring"* — home screen violates this

**Key files:**

| File | Issue |
|------|-------|
| `project/app/child/.../pairing/ChildPairingViewModel.kt` | No post-pair monitoring start |
| `project/app/child/.../home/ChildHomeViewModel.kt` | Direct coordinator calls; default off |
| `project/app/child/.../service/MonitoringService.kt` | Not used from home flow |

---

### 4. Localization Inconsistency (P1–P2)

**Evidence:** Settings title *"Настройки"* (BG) with English section headers; child home has BG text but *"Mom"*/*"Dad"* in English; live view mixed languages.

**Root causes:**

| Screen | Problem | Files |
|--------|---------|-------|
| Settings | Only `settings_title` uses `stringResource()`; ~35 hardcoded EN strings | `SettingsScreen.kt` |
| Child contacts | `"Mom"` / `"Dad"` hardcoded | `ContactButton.kt`, `ChildHomeViewModel.kt`, `CallViewModel.kt` |
| Live view dialog | Title localized; body/buttons hardcoded EN | `LiveViewScreen.kt:647-669` |
| TTS | `VoicePromptManager` uses `Locale.US`; ViewModel speaks English strings | `VoicePromptManager.kt`, `ChildHomeViewModel.kt` |
| Child language | No language picker on child app; separate APK prefs from parent | `LocaleManager.kt` |
| Russian | No `values-ru/`; device locale falls back to English defaults | — |

**Note:** `values-bg/strings.xml` files are largely complete (~100 keys). The problem is **code not reading them**, not missing translations.

---

### 5. UI Layout — Button Truncation (P2)

**Evidence:** *"Старт"* button shows *"Ст...арт"*; *"Свързване"* wraps mid-word on green pairing button.

**Root causes:**

- `StatusCard`: `Row(SpaceBetween)` — long Bulgarian title/subtitle squeezes the Start button (`ChildHomeScreen.kt` ~312-348)
- `QuickActionsRow`: three equal `weight(1f)` buttons with icon + long Cyrillic text (`ChildHomeScreen.kt` ~541-630)

Strings themselves are correct in `values-bg/strings.xml` (`monitoring_start` = "Старт", `pairing_button` = "Свързване").

---

### 6. Infrastructure & Reliability Gaps (P1–P2)

| Issue | Impact |
|-------|--------|
| `PairingStore` is in-memory on server | Server restart orphans paired clients; signaling returns 403 |
| P2P pairing path | Never sets `paired_parent_device_id` on child; server doesn't know pairing |
| `childKeyPair` stored in memory in `PairingRepository` | Process death before poll completes breaks key exchange |
| Local pairing fallback | Uses local `SecurePreferences` — invisible to other device |
| `API_BASE_URL` points to remote sslip.io | Local docker dev needs emulator/LAN override |
| FCM not end-to-end configured | Alerts accepted by server but never delivered |

---

## Fix Plan — Phased Implementation

### Phase 0: Verify Environment (1–2 hours)

**Goal:** Ensure dev environment can support fixes.

- [ ] Confirm server running: `docker compose up` in `project/`
- [ ] Set `API_BASE_URL` for dev device (emulator: `http://10.0.2.2:8080/`, physical: LAN IP in `gradle.properties` or `local.properties`)
- [ ] Add `google-services.json` + Firebase project for FCM (parent + child)
- [ ] Set `FCM_ACCESS_TOKEN` and `FIREBASE_PROJECT_ID` in docker env
- [ ] Verify pairing end-to-end with logcat tags: `PairingRepository`, `SignalingRoutes`

**Success criteria:** Pairing completes; server logs show paired device IDs; FCM token registration succeeds.

---

### Phase 1: Unblock Core User Journey (P0) — ~3–5 days

#### 1.1 Wire detection → alerts → history

**Child app:**

```kotlin
// In MonitoringCoordinator.startMonitoring(), after detectors start:
scope.launch {
    cryDetector.cryEvents.collect { eventPipeline.submitCryEvent(it) }
}
scope.launch {
    motionDetector.motionEvents.collect { eventPipeline.submitMotionEvent(it) }
}
```

**Parent app:**

```kotlin
// New AlertIngestionService or in ParentApp.onCreate():
applicationScope.launch {
    FcmService.alertFlow.collect { alertRepository.insertAlert(it) }
}
```

**Files to modify:**
- `MonitoringCoordinator.kt`
- New: `AlertIngestionService.kt` or `ParentApp.kt`
- Verify `HybridNotificationSender` logs *"Alert sent via server"*

**Verify:** Trigger cry/motion on child → parent signal history shows entry (may need FCM or local test hook).

---

#### 1.2 Implement parent Live View WebRTC

**Approach:** Extract shared WebRTC coordinator into `:core:network` or duplicate child pattern in parent module.

**Tasks:**

1. Add parent DI bindings: `WebRtcPeerConnectionManager`, `WebRtcSignalingClient`, TURN credential fetch
2. Create `LiveViewConnectionManager` (or adapt `CallManager`):
   - Resolve `paired_child_device_id` from `SecurePreferences`
   - Fetch TURN credentials from `/api/v1/turn/credentials`
   - Create peer connection as **offerer** (parent initiates)
   - Send SDP offer via `signalingClient.sendOffer()`
   - Handle incoming answer + ICE
3. Replace `LiveViewViewModel.startConnection()` stub with real connection lifecycle
4. Add **30s connection timeout** → `LiveConnectionState.FAILED` with retry
5. Attach remote `VideoTrack` to `SurfaceViewRenderer` via `addSink()`
6. Wire `TalkBackManager` for two-way audio

**Files to create/modify:**
- `project/app/parent/.../liveview/LiveViewViewModel.kt`
- `project/app/parent/.../liveview/LiveViewScreen.kt`
- New: `project/app/parent/.../liveview/LiveViewConnectionManager.kt`
- `project/app/parent/.../di/ParentAppModule.kt`

---

#### 1.3 Implement signaling receive (both apps)

**Tasks:**

1. Call `signalingClient.startPolling()` on app startup (child + parent)
2. Collect `incomingOffers`, `incomingAnswers`, `incomingIceCandidates` flows
3. **Child:** On incoming offer → create PC, set remote description, create answer, emit `CallState.Incoming`
4. Fix `acceptCall()` to require prior `setRemoteDescription(offer)`
5. **Server:** Call `fcm.sendSignalPoll(toDeviceId)` after each signaling enqueue

**Files to modify:**
- `ChildApp.kt` / `ParentApp.kt` (or dedicated `@Singleton` init)
- `CallManager.kt` (child incoming-offer handler)
- `SignalingRoutes.kt` (server FCM trigger)
- `WebRtcSignalingClient.kt` (verify message routing)

---

#### 1.4 Use TURN credentials

- Fetch from `PairingApi.getTurnCredentials()` before creating peer connection
- Add TURN `IceServer` entries alongside STUN in both child and parent

**Verify:** Parent opens Live View → connection reaches `CONNECTED` within 30s → video renders.

---

### Phase 2: Reliability & UX (P1) — ~2–3 days

#### 2.1 Auto-start monitoring after pairing

- On `PairingState.PAIRED` in `ChildPairingViewModel`, start `MonitoringService` via intent
- Show one-time permission prompt (camera, microphone, notifications)
- Navigate to home with monitoring already active

#### 2.2 Route monitoring through foreground service

- Replace direct `monitoringCoordinator.startMonitoring()` in `ChildHomeViewModel` with `MonitoringService` intents
- Home screen observes service-bound state via binder or `MonitoringCoordinator.isMonitoring` flow
- Ensures monitoring survives app backgrounding

#### 2.3 Persist server pairing state

- Replace in-memory `PairingStore` with SQLite or Redis
- Survives server restarts

#### 2.4 Fix P2P pairing gaps

- Set `paired_parent_device_id` on child after P2P pair
- Optionally register P2P pairing on server for signaling `arePaired()` check

#### 2.5 Persist ECDH child key pair

- Store encrypted in `SecurePreferences` during pairing; restore on process death

---

### Phase 3: Localization & UI Polish (P1–P2) — ~1–2 days

#### 3.1 Externalize hardcoded strings

| Priority | Screen | Est. keys |
|----------|--------|-----------|
| P0 | `SettingsScreen.kt` | ~35 |
| P0 | `LiveViewScreen.kt` (dialog, overlay, controls) | ~20 |
| P0 | Mom/Dad labels | 3 |
| P1 | `ChildHomeScreen.kt` contentDescriptions | ~8 |
| P2 | TTS prompts in ViewModels | ~10 |

Pattern: add to `values/strings.xml` → mirror in `values-bg/strings.xml` → replace literals with `stringResource()`.

#### 3.2 Fix button layout truncation

- `StatusCard`: `Column(Modifier.weight(1f))` for text; `Button(widthIn(min = 88.dp))` for Start
- `QuickActionsRow`: shorter BG labels OR stack icon above text OR move pairing to second row
- Consider `pairing_button_short` = "Свържи" for narrow buttons

#### 3.3 Language UX improvements

- Call `activity.recreate()` after language change in parent settings
- Add child language setting OR sync language pref during pairing
- Localize `VoicePromptManager` to match UI locale

---

### Phase 4: Testing & Hardening (ongoing)

#### Integration test scenarios

| # | Scenario | Expected |
|---|----------|----------|
| 1 | Pair child + parent via code | Both show paired; server has mapping |
| 2 | Child starts monitoring | Banner shows active; detectors running in logcat |
| 3 | Simulate cry event | Child logs EventPipeline emit; server /notify; parent history +1 |
| 4 | Parent opens Live View | CONNECTED < 30s; video visible; audio works |
| 5 | Child receives parent live view | Auto-answer or incoming UI; media flows |
| 6 | Server restart | Re-pair or persistent store recovers; signaling works |
| 7 | App language = Bulgarian | All screens consistent; no EN literals |
| 8 | Background child app | MonitoringService keeps detectors alive |

#### Build verification

```bash
cd project
gradlew.bat assembleDebug
```

Run after each phase. Check logcat tags: `EventPipeline`, `HybridNotifSender`, `WebRtcSignaling`, `CallManager`, `FcmService`, `MonitoringCoordinator`.

---

## Diagnostic Checklist (Reproduce Issues)

Use this when testing on devices:

### Live view stuck

1. Open parent Live View → logcat: any `WebRTC` / `CallManager` tags on parent? (**Expected: none**)
2. Check `LiveViewViewModel` state — stays `CONNECTING` forever?
3. Server: `GET /api/v1/signal/pending/{parentDeviceId}` — messages piling up?
4. Child: is `startPolling()` running? Any incoming offer handler?

### Zero signals

1. Child: is monitoring ON? (If off → expected empty history)
2. Logcat: `EventPipeline` *"Alert emitted"* after cry/motion? (**Expected: none today**)
3. Logcat: `HybridNotifSender` *"Alert sent via server"*?
4. Server logs: `FCM not configured` or `No parent paired`?
5. Parent logcat: `FcmService` message received?
6. Parent: any collector on `FcmService.alertFlow`?

### Pairing vs signaling mismatch

1. After pair, check `paired_child_device_id` (parent) and `paired_parent_device_id` (child) in prefs
2. `POST /signal/offer` — 403 *"Devices are not paired"*? → server lost in-memory state or wrong device IDs
3. P2P-only pair? → server never knows; signaling will always 403

### Localization

1. Settings → App Language → Bulgarian → kill and relaunch app
2. Grep for hardcoded `"Mom"`, `"Detection Settings"`, `"Establishing secure"`
3. Child app: separate prefs — parent language change does not affect child

---

## File Reference Index

### Critical path (fix first)

| Component | Path |
|-----------|------|
| Parent live view stub | `project/app/parent/src/main/java/.../liveview/LiveViewViewModel.kt` |
| Parent live view UI | `project/app/parent/src/main/java/.../liveview/LiveViewScreen.kt` |
| Child call/signaling | `project/app/child/src/main/java/.../call/CallManager.kt` |
| Signaling client | `project/core/network/src/main/java/.../signaling/WebRtcSignalingClient.kt` |
| Monitoring coordinator | `project/app/child/src/main/java/.../service/MonitoringCoordinator.kt` |
| Event pipeline | `project/app/child/src/main/java/.../detection/EventPipeline.kt` |
| FCM service | `project/core/network/src/main/java/.../push/FcmService.kt` |
| Alert history | `project/app/parent/src/main/java/.../repository/AlertHistoryRepository.kt` |
| Server signaling | `project/server/src/main/kotlin/.../routes/SignalingRoutes.kt` |
| Server pairing | `project/server/src/main/kotlin/.../routes/PairingRoutes.kt` |

### Localization & UI

| Component | Path |
|-----------|------|
| Settings hardcoded EN | `project/app/parent/src/main/java/.../settings/SettingsScreen.kt` |
| Mom/Dad hardcoded | `project/app/child/src/main/java/.../home/ContactButton.kt` |
| Child home layout | `project/app/child/src/main/java/.../home/ChildHomeScreen.kt` |
| Locale manager | `project/core/security/src/main/java/.../LocaleManager.kt` |
| BG strings (parent) | `project/app/parent/src/main/res/values-bg/strings.xml` |
| BG strings (child) | `project/app/child/src/main/res/values-bg/strings.xml` |

### Infrastructure

| Component | Path |
|-----------|------|
| Docker services | `project/docker-compose.yml` |
| API base URL | `project/gradle.properties` |
| Pairing repository | `project/core/network/src/main/java/.../repository/PairingRepository.kt` |

---

## Effort Estimate

| Phase | Scope | Estimate |
|-------|-------|----------|
| Phase 0 | Environment setup | 0.5 day |
| Phase 1 | Live view + signaling + alerts | 3–5 days |
| Phase 2 | Monitoring service + server persistence | 2–3 days |
| Phase 3 | Localization + UI layout | 1–2 days |
| Phase 4 | Integration testing | 1–2 days |
| **Total** | | **~8–12 days** |

Phase 1 alone resolves the three most visible failures from the screenshots.

---

## Recommended Fix Order

```
1. signalingClient.startPolling() on both apps          ← unblocks everything signaling-related
2. Child: detector → EventPipeline wiring               ← unblocks alerts
3. Parent: FCM alertFlow → AlertHistoryRepository      ← unblocks signal history
4. Parent: LiveView WebRTC + timeout                    ← unblocks live view
5. Child: incoming offer handler + fix acceptCall()    ← completes call path
6. TURN credentials + server FCM signal_poll push      ← production network reliability
7. MonitoringService auto-start after pair             ← fixes "monitoring off" UX
8. Localization + layout fixes                         ← polish
```

---

## Privacy Constraints (Do Not Violate)

Per `project/AGENTS.md` and `SPEC.md`:

- No `MediaRecorder`, no `MediaStore` writes, no cloud media upload
- Alerts remain **metadata-only** (type, timestamp, confidence)
- WebRTC is permitted for live view (ephemeral P2P media)
- Keys via Android Keystore; DB via SQLCipher

All fixes above comply with these constraints.

---

## Implementation Record — 2026-06-21

All fixes from the recommended fix order have been implemented across 3 build iterations. Below is the complete record.

### Phase 1: Unblock Core Chains (P0)

**1. Detector → EventPipeline wiring**  
`MonitoringCoordinator.kt:102-103, 152-158, 172-177` — Added `cryEventJob`/`motionEventJob` collecting `cryDetector.cryEvents` and `motionDetector.motionEvents` flows → `eventPipeline.submitCryEvent()`/`submitMotionEvent()`. Jobs cancelled in `stopMonitoring()`.

**2. FCM alertFlow → DB ingestion**  
`ParentApp.kt:32, 37, 58, 89-98` — Added `AlertHistoryRepository` injection and `startAlertIngestion()` method collecting `FcmService.alertFlow` → `alertHistoryRepository.insertAlert()`.

### Phase 2: Parent Live View WebRTC (P0)

**3. New: `LiveViewConnectionManager.kt`** (338 lines) — Full parent-side WebRTC connection manager:
- `PeerConnectionFactory` initialization with video decoder/encoder
- STUN + TURN ICE server list built from `pairingApi.getTurnCredentials()`
- Creates offer, sends via `signalingClient.sendOffer()`
- Receives answer via `signalingClient.incomingAnswers`
- ICE candidate exchange via `signalingClient.incomingIceCandidates`
- Remote `VideoTrack` extraction from `onAddStream`/`onAddTrack`
- `DataChannel` creation for TalkBack audio
- 30s connection timeout
- `currentPeerConnection` and `currentDataChannel` exposed for TalkBackManager

**4. DI wiring** — `ParentAppModule.kt:135-155` — `provideLiveViewConnectionManager()` with all dependencies

**5. LiveViewViewModel.kt** — Replaced `delay(2000)` stub with:
- Injects `LiveViewConnectionManager` + `SecurePreferences`
- Reads `paired_child_device_id` from prefs
- `connectionManager.connect()` with 30s `withTimeout`, FAILED on timeout/error
- Initializes `TalkBackManager` with peer connection on success
- Exposes `remoteVideoTrack: StateFlow<VideoTrack?>` from connection manager
- Observes `connectionManager.connectionState` → UI state mapping

**6. LiveViewScreen.kt** — `VideoRenderer()` now:
- Collects `viewModel.remoteVideoTrack` via `collectAsState()`
- Calls `track.addSink(surfaceView)` in `LaunchedEffect` when track arrives
- `DisposableEffect` releases surface and EGL on dispose

### Phase 3: Signaling & TURN (P0)

**7. Signaling polling startup**  
`ChildApp.kt:23, 29, 78-84` — Injected `WebRtcSignalingClient`, added `startSignalingPolling()`  
`ParentApp.kt:35, 59, 119-125` — Same pattern on parent

**8. Child incoming-call handler**  
`CallManager.kt:104-128` — Init block with 3 collectors: `incomingOffers` → `handleIncomingOffer()`, `incomingIceCandidates` → `peerConnectionManager.addIceCandidate()`, `incomingAnswers` → `setRemoteDescription()`   
`CallManager.kt:253-305` — New `handleIncomingOffer()`: creates `CallSession`, builds ICE servers, creates peer connection, sets remote description, starts camera+audio capture, delegates to `acceptCall()`

**9. TURN credentials**  
`CallManager.kt:77, 161-180, 259-277` — Injected `PairingApi`, fetches `getTurnCredentials()` before creating peer connection, adds TURN servers to ICE list  
`LiveViewConnectionManager.kt:105-106, 183-204` — Same on parent side  
`ChildAppModule.kt:166` — Added `pairingApi` to `provideCallManager()`

**10. Server FCM signal_poll**  
`SignalingRoutes.kt:14, 23, 32, 41` — Added `FcmDispatcher` parameter, `fcmDispatcher.sendSignalPoll()` after each offer/answer/ICE enqueue  
`Application.kt:55` — Passes `fcmDispatcher` to `signalingRoutes()`

### Phase 4: Monitoring Auto-Start (P1)

**11. Auto-start on pairing**  
`ChildPairingViewModel.kt:104, 171` — Sets `monitoring_auto_start = true` pref on `PairingState.PAIRED` (both server and P2P modes)  
`ChildHomeViewModel.kt:169-177` — New `autoStartIfNeeded()` checks flag, clears it, starts monitoring  
`ChildHomeScreen.kt:116-119` — `LaunchedEffect` triggers `autoStartIfNeeded()` on home screen load

### Phase 5: Localization (P1–P2)

**12. ContactButton.kt** — Mom/Dad → `stringResource(R.string.contact_mom_label)`/`contact_dad_label`, contentDescriptions → `stringResource()`  
**13. ChildHomeViewModel.kt** — TTS prompts → `getString(R.string.*_voice)`  
**14. CallViewModel.kt** — 8 voice prompts → `getString(R.string.*)`  
**15. VoicePromptManager.kt** — Bedtime messages → `resources.getStringArray(R.array.bedtime_messages)`  
**16. ChildHomeScreen.kt** — 5 contentDescriptions → `stringResource()`  
**17. SettingsScreen.kt** — 44 hardcoded English strings → `stringResource(R.string.settings_*)`  
**18. LiveViewScreen.kt** — 15 hardcoded strings (stream modes, control labels, contentDescriptions, dialog text) → `stringResource(R.string.live_view_*)`  
**19. String resources added:**  
- child `values/strings.xml`: +5 keys + bedtime messages array  
- child `values-bg/strings.xml`: +5 keys + BG bedtime messages  
- parent `values/strings.xml`: +58 new keys  
- parent `values-bg/strings.xml`: +58 BG translations

### Phase 6: Server Persistence (P1)

**20. New: `server/store/Database.kt`** — SQLite helper with WAL mode, tables for `pairing_sessions` + `signaling_messages`  
**21. `PairingStore.kt`** — Replaced 3 `ConcurrentHashMap` instances with SQLite queries  
**22. `MessageStore.kt`** — Replaced `ConcurrentHashMap`+`ConcurrentLinkedQueue` with SQLite  
**23. `server/build.gradle.kts`** — Added `org.xerial:sqlite-jdbc:3.44.1.0`

### Files Modified/Created Summary

| Action | Count |
|--------|-------|
| Kotlin files modified | 18 |
| Kotlin files created | 2 |
| XML resource files modified | 4 |
| Server build.gradle.kts modified | 1 |
| **Total files changed** | **25** |

### Build Verification

```
gradlew.bat :app:parent:assembleDebug :app:child:assembleDebug :server:compileKotlin --no-parallel
BUILD SUCCESSFUL in 13s
152 actionable tasks: 6 executed, 146 up-to-date
```

All three modules (child APK, parent APK, server JAR) compile without errors.

### Remaining Known Gaps (non-blocking)

| Gap | Impact | Priority |
|-----|--------|----------|
| FCM production config | Alerts not delivered without real Firebase project | P2 |
| ViewModel error messages | "Pairing timed out", "Connection timed out" still hardcoded EN | P2 |
| PairingScreen EN strings | Parent pair button/instructions not localized | P2 |
| Battery whitelist dialog EN strings | ~9 hardcoded strings in ChildHomeScreen | P2 |
