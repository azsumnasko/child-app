# AGENTS.md — Privacy-First Child Helper App

## Project-Specific Instructions

This is a multi-module Android project (Kotlin + Jetpack Compose + Hilt) implementing a privacy-first child helper application. See `SPEC.md` in the parent directory for the full specification.

### Architecture
- **:app:child** — Child-facing APK (minSdk 26, targetSdk 36)
- **:app:parent** — Parent-facing APK (minSdk 26, targetSdk 36)
- **:core:common** — Shared data models, utilities, event definitions
- **:core:security** — Keystore wrapper, encryption, pairing logic
- **:core:network** — Retrofit + OkHttp, WebRTC signaling, FCM push

### Module Dependencies
```
:app:child  --> :core:common, :core:security, :core:network
:app:parent --> :core:common, :core:security, :core:network
:core:security --> :core:common
:core:network  --> :core:common
```

### Privacy Constraints (MANDATORY — DO NOT VIOLATE)
- **NO** MediaRecorder usage anywhere
- **NO** MediaStore writes for audio/video
- **NO** cloud upload APIs for media
- **NO** persistent audio/video files on disk
- All alerts are metadata-only (event type, timestamp, confidence, device status)
- Raw audio buffers discarded immediately after analysis
- Camera frames discarded immediately after analysis
- Android Keystore for all key storage
- SQLCipher for all local database encryption
- Encrypted SharedPreferences/DataStore for settings

### Tech Stack
- Kotlin 2.0+ | Jetpack Compose + Material 3 | Hilt DI
- CameraX (Preview + ImageAnalysis) | AudioRecord (Builder, not deprecated constructor)
- LiteRT / TensorFlow Lite (quantized INT8) | WebRTC (getstream/webrtc-android)
- Firebase Cloud Messaging | Room + SQLCipher
- Retrofit + OkHttp | kotlinx.serialization | JUnit + MockK

### Build Commands
```bash
# IMPORTANT: Always use --no-parallel for clean builds.
# Hilt/KSP annotation processors can deadlock with parallel builds (org.gradle.parallel=true)
# when multiple modules compete for KSP resources simultaneously.
# Incremental builds without clean work fine with parallel; clean builds require --no-parallel.

# Windows — incremental build (parallel OK)
gradlew.bat :app:child:assembleDebug
gradlew.bat :app:parent:assembleDebug

# All at once — incremental (parallel OK)
gradlew.bat assembleDebug

# Clean build REQUIRES --no-parallel to avoid KSP deadlock
gradlew.bat clean
gradlew.bat :app:child:assembleDebug :app:parent:assembleDebug --no-parallel
```

### Build Troubleshooting
- **"Processing did not complete" / KSP hang**: Use `--no-parallel`. Kill stale daemons with `gradlew.bat --stop`.
- **Build appears stuck**: Check if Gradle daemon memory is exhausted. Default is 8GB in `gradle.properties`.
- **Clean build slower than expected**: First clean build compiles all modules from scratch (~30s). Subsequent incremental builds are fast (~10s).

### Build Prerequisites
- Android SDK (API 36), JDK 17+
- `local.properties` with `sdk.dir` set
- Gradle wrapper JAR (run `gradle\wrapper\download-wrapper.bat` if missing)

---

## Behavioral Guidelines (LLM Coding Best Practices)

These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:
- **State your assumptions explicitly.** If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.
- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

When editing existing code:
- **Don't "improve" adjacent code, comments, or formatting.**
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

Define success criteria. Loop until verified.

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

### 5. Verify Before Declaring Done

- **Always attempt a build** after making changes to Kotlin/Gradle files.
- Run `gradlew.bat assembleDebug` to verify compilation.
- Check for lint errors, unused imports, and type mismatches.
- If the build fails, fix the error and rebuild — don't assume it's "probably fine".

### 6. Security Awareness for This Project

- Never introduce `MediaRecorder`, `MediaStore`, or cloud upload code.
- Never commit secrets, API keys, or real certificate hashes.
- All key material must go through `KeystoreManager` — no raw KeyPair caching.
- Alert payloads must remain metadata-only — never attach raw audio or video data.
