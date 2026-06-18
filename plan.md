# Privacy-First Child Helper Android App — Implementation Plan

## Overview
Implement the Android MVP (MVP 1) of the Privacy-First Child Helper app as described in the implementation plan PDF. This is a multi-module Android project with child-facing and parent-facing modes, on-device ML detection, WebRTC calling, and privacy-first architecture.

## Architecture Summary
- **:app:child** — Child-facing Android module (minSdk 26, targetSdk 36)
- **:app:parent** — Parent-facing Android module (minSdk 26, targetSdk 36)
- **:core:common** — Shared data models, utilities, event definitions
- **:core:network** — Retrofit + OkHttp client, WebRTC signaling
- **:core:security** — Keystore wrapper, encryption, pairing logic

## Implementation Stages

### Stage 1: Project Skeleton & Infrastructure
- Multi-module Gradle build with version catalogs
- Hilt DI graph with @Singleton scoped modules
- EncryptedPreferences wrapper (DataStore + EncryptedFile + Keystore)
- PairingRepository with QR generation, key exchange, revocation
- SecurityModule with Keystore-backed key generation
- Unit tests for crypto operations

### Stage 2: Core Modules
- :core:common — Data models (Alert, DeviceStatus, PairingSession, etc.), event definitions
- :core:security — Keystore integration, encryption/decryption, pairing crypto
- :core:network — Retrofit API definitions, WebRTC signaling client, FCM push handling

### Stage 3: Child UI/UX Module
- ChildHomeScreen composable with large Mom/Dad/SOS buttons
- SOSManager with hold-to-activate, guardian notification
- BedtimeModeScreen with calming UI, dimming, voice prompts
- WebRTC calling integration (one-tap calling, auto-answer)
- Voice prompt assets (TTS)
- Accessibility support (TalkBack, font scaling)

### Stage 4: Detection & ML Pipeline
- CryDetector with AudioRecord + LiteRT inference
- MotionDetector with CameraX ImageAnalysis + frame differencing
- EventPipeline with metadata-only alert formatting
- Sensitivity configuration (low/normal/high)

### Stage 5: Parent App Module
- ParentDashboardScreen with device status, alert feed
- LiveViewScreen with WebRTC video renderer + audio toggle
- TalkBackManager for two-way audio
- SettingsScreen with sensitivity, retention, SOS config
- AlertHistoryRepository with Room + configurable retention

### Stage 6: Integration & Validation
- Integration tests for alert → push → live view flow
- Privacy compliance audit (zero cloud media)
- Security scan checks
- Performance validation
- Final APK build

## Skill Usage
- **Capability**: `vibecoding-general-swarm` for the Android codebase generation
- **Artifact**: N/A (code project, not a document)

## Validation Strategy
- Iteration 1: Build and compile all modules
- Iteration 2: Fix integration issues between modules
- Iteration 3: Privacy and security audit
- Iteration 4: Final polish and test coverage
