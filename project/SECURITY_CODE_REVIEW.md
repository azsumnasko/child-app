# State-of-the-Art Security Code Review
## Privacy-First Child Helper Android App

**Audit Date:** 2025-01-16
**Auditor:** AI Security Researcher (BlackHat/DEF CON Level)
**Files Reviewed:** 80 Kotlin files across 5 modules
**Risk Score:** 6.8 / 10 (Moderate-High)

---

## 1. Executive Summary

The Privacy-First Child Helper Android application demonstrates a **privacy-centric architecture** with several commendable security practices: hardware-backed keystore integration, AES-256-GCM authenticated encryption, SQLCipher database encryption, and a strict metadata-only alert policy that never transmits raw audio/video. However, the codebase contains **multiple security issues ranging from Medium to High severity** that must be addressed before production deployment. The most critical finding is a **placeholder certificate pinning hash** that effectively nullifies all certificate pinning protections if deployed as-is.

### Key Concerns
- Placeholder certificate pinning hash renders pinning ineffective
- Use of RSA-PKCS#1 v1.5 (Bleichenbacher-vulnerable) instead of RSA-OAEP
- Unencrypted pre-pairing storage for sensitive device identifiers
- Debug builds log full HTTP bodies including potentially sensitive metadata
- No anti-tampering, root detection, or code obfuscation protections

### Architecture Overview
- **5 modules:** app/child, app/parent, core/security, core/network, core/common
- **Cryptography:** RSA-2048 (Keystore) + ECDH key agreement + AES-256-GCM + HKDF-SHA256
- **Database:** Room + SQLCipher with passphrase from SecurePreferences
- **Communication:** WebRTC (peer-to-peer) + Firebase Cloud Messaging (signaling triggers)
- **Storage:** Jetpack DataStore with AES-256-GCM encryption (post-pairing)

---

## 2. Critical Findings

### C-001: Placeholder Certificate Pinning Hash (HIGH)
| | |
|---|---|
| **Severity** | HIGH |
| **CWE** | CWE-295: Improper Certificate Validation |
| **CVSS 3.1** | 7.4 (High) |
| **File** | `core/network/src/main/java/com/childhelper/core/network/di/NetworkModule.kt:101` |

**Description:** The certificate pinner is configured with a placeholder SHA-256 hash:
```kotlin
"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
```
This is a literal placeholder string (`AAAAAAAA...`) that will **never match any real certificate**. If deployed without replacement:
1. All certificate pinning validation will fail, causing connection failures, OR
2. Worse, if the developer removes pinning entirely to "fix" the failures, the app becomes vulnerable to MITM attacks

**Exploit Scenario:** An attacker on the same network (public Wi-Fi, rogue AP, ARP spoofing) can use a self-signed or fraudulently obtained certificate to intercept all HTTPS traffic between the app and the pairing/signaling backend, including public key exchanges during pairing.

**Remediation:**
```kotlin
// BEFORE (vulnerable):
"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

// AFTER (fixed):
// Generate real hash:
// openssl s_client -connect api.childhelper.com:443 </dev/null 2>/dev/null | \
//   openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
//   openssl dgst -sha256 -binary | openssl enc -base64
"sha256/REAL_HASH_HERE="  // Pin at least 2 backup hashes
```
Also implement a secondary backup pin (intermediate CA) to prevent lockout on certificate rotation.

---

### C-002: RSA-PKCS#1 v1.5 Padding (Bleichenbacher Vulnerability) (HIGH)
| | |
|---|---|
| **Severity** | HIGH |
| **CWE** | CWE-327: Use of a Broken or Risky Cryptographic Algorithm |
| **CVSS 3.1** | 6.5 (Medium) |
| **File** | `core/security/src/main/java/com/childhelper/core/security/KeystoreManager.kt:103` |

**Description:** The KeystoreManager uses `RSA/ECB/PKCS1Padding` for RSA operations. PKCS#1 v1.5 padding is known to be vulnerable to **Bleichenbacher padding oracle attacks** (since 1998). While the Android Keystore protects the private key from extraction, the padding oracle can still be exploited if the attacker can observe decryption behavior (e.g., timing differences in error responses).

**Code:**
```kotlin
private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"  // VULNERABLE
```

**Remediation:**
```kotlin
// Use RSA-OAEP with SHA-256 (secure)
private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
```

When creating the KeyGenParameterSpec, also add:
```kotlin
setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
setDigests(KeyProperties.DIGEST_SHA256)
```

---

## 3. High Findings

### H-001: `Math.random()` Used for Cryptographic ID Generation (HIGH)
| | |
|---|---|
| **Severity** | HIGH |
| **CWE** | CWE-338: Use of Cryptographically Weak PRNG |
| **CVSS 3.1** | 5.3 (Medium) |
| **File** | `core/network/src/main/java/com/childhelper/core/network/push/FcmService.kt:174` |

**Description:** The alert ID generation uses `Math.random()` which is **not cryptographically secure** and is predictable:
```kotlin
private fun generateAlertId(): String =
    "alert-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
```
While alert IDs are not authentication tokens, predictability could allow:
1. Alert ID enumeration/guessing attacks
2. Log injection if alert IDs are used in any security context
3. Statistical analysis to de-anonymize alert patterns

**Remediation:**
```kotlin
private fun generateAlertId(): String =
    "alert-${System.currentTimeMillis()}-${CryptoUtil.secureRandomBytes(4).toHex()}"
```

---

### H-002: HKDF Extract Phase Uses Null Salt (HIGH)
| | |
|---|---|
| **Severity** | HIGH |
| **CWE** | CWE-1240: Use of a Cryptographic Primitive with a Risky Implementation |
| **CVSS 3.1** | 5.9 (Medium) |
| **File** | `core/security/src/main/java/com/childhelper/core/security/EncryptionManager.kt:129` |

**Description:** The HKDF-SHA256 implementation uses `salt = null`, which causes the code to use a **zero-filled salt** (32 zero bytes). RFC 5869 Section 3.1 strongly recommends using a random salt to ensure the extraction phase produces unique PRKs even when the IKM is the same across sessions. Without random salt, the HKDF output becomes deterministic for the same ECDH shared secret, weakening forward secrecy guarantees.

**Code:**
```kotlin
return hkdfSha256(ecdhOutput, salt = null, info = "ChildHelper-v1".toByteArray(Charsets.UTF_8))
```

**Remediation:**
```kotlin
// Generate a random 32-byte salt per pairing session
val salt = CryptoUtil.secureRandomBytes(32)
// Store the salt alongside the encrypted data or derive it from session context
return hkdfSha256(ecdhOutput, salt = salt, info = "ChildHelper-v1".toByteArray(Charsets.UTF_8))
```

---

### H-003: ECDH Uses Generic "EC" Algorithm (HIGH)
| | |
|---|---|
| **Severity** | HIGH |
| **CWE** | CWE-327: Use of a Broken or Risky Cryptographic Algorithm |
| **CVSS 3.1** | 5.9 (Medium) |
| **File** | `core/security/src/main/java/com/childhelper/core/security/EncryptionManager.kt:78` |

**Description:** The key agreement uses algorithm string `"EC"` which is a generic JCA identifier that may map to different curves depending on the provider. This creates curve ambiguity and potential downgrade attacks. The code should explicitly specify the intended curve (e.g., P-256 or X25519).

**Code:**
```kotlin
private const val ECDH_ALGORITHM = "EC"  // Ambiguous - could be any curve
```

**Remediation:**
```kotlin
// Explicitly use ECDH with curve specification
private const val ECDH_ALGORITHM = "ECDH"
// Or better, use X25519 for modern key agreement:
// private const val ECDH_ALGORITHM = "X25519"

// And when generating keys, specify the curve:
val keyPairGenerator = KeyPairGenerator.getInstance("EC")
val ecSpec = ECGenParameterSpec("secp256r1") // P-256
keyPairGenerator.initialize(ecSpec)
```

---

## 4. Medium Findings

### M-001: UnpairedSecurePreferences Stores Sensitive Data in Plaintext (MEDIUM)
| | |
|---|---|
| **Severity** | MEDIUM |
| **CWE** | CWE-312: Cleartext Storage of Sensitive Information |
| **CVSS 3.1** | 5.3 (Medium) |
| **File** | `core/security/src/main/java/com/childhelper/core/security/SecurePreferences.kt:172-217` |

**Description:** The `UnpairedSecurePreferences` class stores the `device_id` and pairing state in **completely unencrypted** DataStore files. While this is intended for pre-pairing use only, the `device_id` is a persistent unique identifier that links the device to the child. On a rooted device or via Android backup extraction, this plaintext data reveals:
- The existence of the child monitoring app
- The persistent device identifier
- Pairing state information

**Remediation:** Even pre-pairing data should use Android Keystore-protected encryption. Use a key derived from a hardcoded passphrase combined with device-specific attributes (e.g., `Build.SERIAL` / `Settings.Secure.ANDROID_ID`) as a temporary encryption key before pairing completes.

---

### M-002: Debug HTTP Body Logging Leaks Sensitive Metadata (MEDIUM)
| | |
|---|---|
| **Severity** | MEDIUM |
| **CWE** | CWE-532: Insertion of Sensitive Information into Log File |
| **CVSS 3.1** | 4.3 (Medium) |
| **File** | `core/network/src/main/java/com/childhelper/core/network/di/NetworkModule.kt:108-113` |

**Description:** Debug builds log full HTTP request/response bodies at `Level.BODY`. While the production API shouldn't contain highly sensitive data, this could leak:
- Pairing session IDs
- Device identifiers in request URLs
- Alert metadata payloads
- TURN server credentials (if present in responses)

**Code:**
```kotlin
if (BuildConfig.DEBUG) {
    val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY  // Logs everything
    }
    builder.addInterceptor(loggingInterceptor)
}
```

**Remediation:**
```kotlin
if (BuildConfig.DEBUG) {
    val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS  // Safer: only headers, no bodies
    }
    builder.addInterceptor(loggingInterceptor)
}
```

---

### M-003: `fallbackToDestructiveMigration()` Destroys Encrypted Data on Schema Change (MEDIUM)
| | |
|---|---|
| **Severity** | MEDIUM |
| **CWE** | CWE-1288: Improper Validation of Consistency within Input |
| **CVSS 3.1** | 4.0 (Medium) |
| **File** | `app/parent/src/main/java/com/childhelper/app/parent/db/AppDatabase.kt:42` |

**Description:** The Room database is configured with `.fallbackToDestructiveMigration()`, which **silently deletes all data** when the database schema version changes. For a child safety app, this means all alert history could be irretrievably lost during an app update with schema changes.

**Remediation:** Implement proper migration strategies:
```kotlin
// Remove fallbackToDestructiveMigration()
// Add explicit migrations:
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
// Or use AutoMigration:
@Database(
    entities = [AlertEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
```

---

### M-004: Settings Stored in Unencrypted DataStore (MEDIUM)
| | |
|---|---|
| **Severity** | MEDIUM |
| **CWE** | CWE-312: Cleartext Storage of Sensitive Information |
| **CVSS 3.1** | 4.0 (Medium) |
| **File** | `app/parent/src/main/java/com/childhelper/app/parent/ui/settings/SettingsViewModel.kt` |

**Description:** The settings ViewModel uses standard Jetpack DataStore (not SecurePreferences) for storing sensitive settings including location sharing enabled, SOS escalation order, and detection sensitivity. These are stored in plaintext Protocol Buffer files that can be read on rooted devices.

**Remediation:** Replace with `SecurePreferences` for all settings, or encrypt sensitive individual settings before storing.

---

### M-005: `sendOffer()` Misused for HangUp Signaling (MEDIUM)
| | |
|---|---|
| **Severity** | MEDIUM |
| **CWE** | CWE-115: Misinterpretation of Input |
| **CVSS 3.1** | 4.3 (Medium) |
| **File** | `core/network/src/main/java/com/childhelper/core/network/signaling/WebRtcSignalingClient.kt:196-206` |

**Description:** The `sendHangUp()` method repurposes the `sendOffer()` endpoint to send hang-up messages by encoding a JSON string as an SDP offer:
```kotlin
signalingApi.sendOffer(
    SdpMessage(
        // ...
        sdp = """{"type":"hangup","reason":"${reason.name}"}"""
    )
)
```
This is fragile and could confuse the receiving peer's WebRTC stack, potentially causing unexpected behavior or state machine desynchronization.

**Remediation:** Implement a dedicated `/api/v1/signal/hangup` endpoint on the backend and call it properly:
```kotlin
@POST("/api/v1/signal/hangup")
suspend fun sendHangUp(@Body hangUp: HangUpMessage)
```

---

## 5. Low Findings

### L-001: No Anti-Tampering or Root Detection (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-693: Protection Mechanism Failure |
| **CVSS 3.1** | 3.7 (Low) |

**Description:** The app contains no code tampering detection, root/jailbreak detection, or integrity verification. On a rooted device:
- The SQLCipher-encrypted database can be extracted and brute-forced
- The Keystore keys may be extractable (depending on hardware backing)
- The app's code can be modified/repacked
- The DataStore files can be read directly

**Remediation:** Consider integrating a root detection library (e.g., SafetyNet/Play Integrity API, RootBeer) for defense in depth. Note that root detection should not be considered a primary security control.

---

### L-002: No Code Obfuscation (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-656: Reliance on Security Through Obscurity |
| **CVSS 3.1** | 3.1 (Low) |

**Description:** The codebase contains no ProGuard/R8 obfuscation rules. Reverse engineering the app to understand the API endpoints, data models, and security architecture is trivial using standard tools like JADX.

**Remediation:** Enable R8 code shrinking and obfuscation in `build.gradle`:
```groovy
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

---

### L-003: `PermissionManager` Uses Standard SharedPreferences (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-312: Cleartext Storage of Sensitive Information |
| **CVSS 3.1** | 2.5 (Low) |
| **File** | `app/child/src/main/java/com/childhelper/app/child/permission/PermissionManager.kt:163-171` |

**Description:** The permission manager tracks requested permissions using standard (unencrypted) SharedPreferences:
```kotlin
private val prefs by lazy {
    activity.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
}
```
While permission request history is not highly sensitive, this is inconsistent with the app's encrypted storage philosophy.

**Remediation:** Use `SecurePreferences` or encrypt the preference file.

---

### L-004: Wake Lock Without Strong Timeout Protection (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-400: Uncontrolled Resource Consumption |
| **CVSS 3.1** | 2.7 (Low) |
| **File** | `app/child/src/main/java/com/childhelper/app/child/service/MonitoringService.kt:197` |

**Description:** The wake lock is acquired with a 10-minute timeout, then re-acquired every 5 minutes. If the re-acquisition logic fails (e.g., exception), the wake lock expires after 10 minutes. However, the re-acquisition loop has no upper bound on total acquisition time, potentially draining battery over extended periods.

**Remediation:** Add a daily maximum wake lock duration or implement exponential backoff during periods of inactivity.

---

### L-005: Incomplete `toggleMute()`/`toggleSpeaker()` Stubs in CallService (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-710: Improper Adherence to Coding Standards |
| **CVSS 3.1** | 1.8 (Low) |
| **File** | `app/child/src/main/java/com/childhelper/app/child/service/CallService.kt:176-184` |

**Description:** The `toggleMute()` and `toggleSpeaker()` methods are empty stubs:
```kotlin
private fun toggleMute() {
    // Mute toggle is handled by the CallManager/ViewModel
    // This action is for notification button only
}
```
While not a direct security issue, incomplete functionality in call controls could be a safety concern if a child cannot mute during an emergency call.

---

### L-006: `CallViewModel` Uses String Matching for Contact Resolution (LOW)
| | |
|---|---|
| **Severity** | LOW |
| **CWE** | CWE-90: Improper Neutralization of Special Elements |
| **CVSS 3.1** | 2.0 (Low) |
| **File** | `app/child/src/main/java/com/childhelper/app/child/ui/call/CallViewModel.kt:191-201` |

**Description:** Contact names are resolved using substring matching:
```kotlin
when {
    contactId.contains("mom", ignoreCase = true) -> "Mom"
    contactId.contains("dad", ignoreCase = true) -> "Dad"
    else -> "Guardian"
}
```
This is fragile and could produce incorrect results with unusual contact IDs.

---

## 6. OWASP Mobile Top 10 Mapping

| ID | Category | Status | Notes |
|---|---|---|---|
| M1 | Improper Platform Usage | **PARTIAL RISK** | `usesCleartextTraffic="false"` is set. All services `exported="false"`. ChildHomeActivity is `exported="true"` with MAIN/LAUNCHER (required). FcmService correctly handles `com.google.firebase.MESSAGING_EVENT`. |
| M2 | Insecure Data Storage | **MITIGATED** | SQLCipher encrypts database. SecurePreferences encrypts values with AES-256-GCM. `allowBackup="false"` in both manifests. **Gap:** UnpairedSecurePreferences stores plaintext pre-pairing. |
| M3 | Insecure Communication | **AT RISK** | HTTPS enforced via `usesCleartextTraffic="false"`. Certificate pinning structure exists. **Critical gap:** Placeholder pin hash means pinning is ineffective. FCM payloads are metadata-only (good). |
| M4 | Insecure Authentication | **MITIGATED** | Pairing uses ephemeral ECDH + 6-digit code with constant-time comparison and 5-minute expiry. Code entropy: 32^6 = ~1 billion combinations. HKDF derives shared secret. **Gap:** Null HKDF salt weakens extraction. |
| M5 | Insufficient Cryptography | **AT RISK** | AES-256-GCM with random 12-byte IVs is correct. **Issues:** RSA-PKCS#1 v1.5 (Bleichenbacher), ambiguous "EC" algorithm for ECDH, null HKDF salt. |
| M6 | Insecure Authorization | **MITIGATED** | No auth bypass paths identified. Pairing revocation requires session ID. Signaling messages include device ID checks. |
| M7 | Client Code Quality | **GOOD** | No SQL injection (Room parameterized queries). No Intent injection (all Intents validated). No command injection. Proper exception handling. |
| M8 | Code Tampering | **NO PROTECTION** | No anti-tampering, no root detection, no integrity checks. SafetyNet/Play Integrity API not integrated. |
| M9 | Reverse Engineering | **NO PROTECTION** | No code obfuscation (R8/ProGuard not configured). No anti-debugging. No native code obfuscation. Reverse engineering is trivial. |
| M10 | Extraneous Functionality | **PARTIAL RISK** | `simulateMockAlert()` in `ParentDashboardViewModel.kt:157` is a test method that could be triggered in production. `emitTestAlert()` in `FcmService.kt:180` is marked `internal` but could be accessed. Debug logging with BODY level. |

---

## 7. Positive Security Practices Observed

The following security practices are **exemplary** and should be maintained:

### Cryptography
- **AES-256-GCM with unique IVs:** Every encryption operation generates a fresh random 12-byte IV via `CryptoUtil.secureRandomBytes()` (line 88, EncryptionManager.kt)
- **Hardware-backed Keystore:** StrongBox preferred with TEE fallback. `setIsStrongBoxBacked(true)` on API 28+ (KeystoreManager.kt:120)
- **Constant-time comparison:** `CryptoUtil.constantTimeEquals()` prevents timing attacks on pairing codes (PairingCrypto.kt:95-98)
- **No user authentication required:** Correctly uses `setUserAuthenticationRequired(false)` since child devices lack biometrics
- **Key invalidation:** `removeKey()` properly deletes Keystore entries (KeystoreManager.kt:153-157)

### Privacy Architecture
- **Metadata-only alerts:** `Alert` and `AlertEntity` contain zero audio/video/image data. Only event type, timestamp, confidence, and device status
- **No persistent audio/video storage:** `AudioPipeline` and `CameraPipeline` discard buffers immediately after analysis
- **No cloud media storage:** WebRTC is peer-to-peer; no media passes through backend servers
- **SQLCipher for local DB:** Parent app's alert history is encrypted at rest (AppDatabase.kt)
- **Retention policy enforcement:** Automatic pruning of old alerts with configurable retention periods

### Communication Security
- **WebRTC DTLS-SRTP:** Default encryption for peer-to-peer calls (CallManager.kt sets `disableEncryption = false`)
- **FCM payloads are metadata-only:** No sensitive data in push notification payloads (FcmService.kt)
- **`usesCleartextTraffic="false"`:** Both child and parent manifests enforce HTTPS-only communication
- **`allowBackup="false"`:** Prevents cloud backup of local encrypted data in both manifests
- **All services `exported="false"`:** Properly restricts service accessibility

### Input Validation
- **Parameterized Room queries:** All `@Query` annotations use parameterized queries (AlertDao.kt) -- no SQL injection possible
- **Pairing code validation:** Regex validation `^[A-HJ-NP-Z2-9]{6}$` before constant-time comparison (PairingCrypto.kt:90)
- **Shared secret length validation:** `require(sharedSecret.size == SHARED_SECRET_LENGTH)` before encryption (EncryptionManager.kt:84-86)
- **Ciphertext length validation:** `require(combined.size > GCM_IV_LENGTH_BYTES)` prevents short ciphertext attacks (EncryptionManager.kt:105-107)

### Architecture
- **Clean separation of concerns:** Security module is isolated from network and UI layers
- **Dependency injection:** Hilt provides testable, replaceable security components
- **Interface-based design:** `KeystoreManager`, `EncryptionManager`, `PairingCrypto`, `SecurePreferences` all use interfaces enabling mock/test implementations
- **Coroutine-safe design:** Mutex locks protect concurrent access to crypto state and caches

---

## 8. Remediation Plan

### Immediate (Pre-Release Blockers)

| Priority | ID | Action | Effort |
|---|---|---|---|
| P0 | C-001 | Replace placeholder certificate pinning hash with real SHA-256 pin + backup pin | 30 min |
| P0 | C-002 | Replace RSA-PKCS#1 v1.5 with RSA-OAEP (SHA-256) in KeystoreManager | 2 hours |
| P0 | H-001 | Replace `Math.random()` with `CryptoUtil.secureRandomBytes()` in FcmService | 15 min |
| P0 | H-002 | Add random 32-byte HKDF salt per pairing session | 2 hours |
| P0 | H-003 | Specify explicit ECDH curve (P-256 or X25519) instead of generic "EC" | 1 hour |

### Short-Term (Within 2 Weeks)

| Priority | ID | Action | Effort |
|---|---|---|---|
| P1 | M-001 | Encrypt UnpairedSecurePreferences with a device-bound temporary key | 4 hours |
| P1 | M-002 | Change debug HTTP logging to HEADERS only, never BODY | 15 min |
| P1 | M-003 | Replace `fallbackToDestructiveMigration()` with explicit Room migrations | 4 hours |
| P1 | M-004 | Migrate SettingsViewModel to use SecurePreferences for all settings | 4 hours |
| P1 | M-005 | Implement dedicated hang-up signaling endpoint | 2 hours |

### Medium-Term (Within 1 Month)

| Priority | ID | Action | Effort |
|---|---|---|---|
| P2 | L-001 | Integrate Play Integrity API for root/tamper detection | 1 day |
| P2 | L-002 | Enable R8 code shrinking and obfuscation for release builds | 4 hours |
| P2 | L-003 | Migrate PermissionManager to use encrypted preferences | 2 hours |
| P2 | L-004 | Add wake lock duration limits and battery-aware backoff | 2 hours |
| P2 | L-005 | Complete CallService toggleMute/toggleSpeaker implementations | 2 hours |
| P2 | L-006 | Implement proper contact resolution from encrypted storage | 2 hours |
| P2 | M10 | Remove or guard `simulateMockAlert()` and `emitTestAlert()` with BuildConfig.DEBUG | 30 min |

---

## 9. Cryptographic Configuration Summary

| Component | Current | Recommended | Status |
|---|---|---|---|
| RSA Key Size | 2048 bits | 2048+ bits | PASS |
| RSA Padding | PKCS#1 v1.5 | OAEP (SHA-256) | **FAIL** |
| AES Mode | GCM (authenticated) | GCM | PASS |
| IV Generation | SecureRandom, 12 bytes | SecureRandom, 12 bytes | PASS |
| IV Uniqueness | Per-encryption | Per-encryption | PASS |
| GCM Tag Length | 128 bits | 128 bits | PASS |
| Key Derivation | HKDF-SHA256 | HKDF-SHA256 | PASS |
| HKDF Salt | null (zeros) | Random 32 bytes | **FAIL** |
| ECDH Curve | "EC" (ambiguous) | P-256 or X25519 | **FAIL** |
| Pairing Code Entropy | 32^6 = ~1B combos | Acceptable for 5-min window | PASS |
| Pairing Code Comparison | Constant-time | Constant-time | PASS |
| Code Expiry | 5 minutes enforced | 5 minutes | PASS |
| Keystore Hardware Backing | StrongBox/TEE | StrongBox/TEE | PASS |

---

## 10. File-by-File Audit Checklist

### Core Security Module
| File | Key Findings | Status |
|---|---|---|
| `KeystoreManager.kt` | RSA-PKCS#1 v1.5 padding (Bleichenbacher). Good: 2048-bit keys, StrongBox/TEE, no auth required, proper key invalidation | NEEDS FIX |
| `EncryptionManager.kt` | Null HKDF salt, ambiguous "EC" algorithm. Good: AES-256-GCM, unique IVs, 128-bit tags, shared secret validation | NEEDS FIX |
| `PairingCrypto.kt` | Good: SecureRandom, constant-time comparison, regex validation, expiry enforcement | PASS |
| `SecurePreferences.kt` | UnpairedSecurePreferences stores plaintext. Good: AES-256-GCM encryption, per-value IVs, mutex protection | PARTIAL |
| `SecurityModule.kt` | Good: Singleton DI, Keystore integration, fallback handling | PASS |

### Core Network Module
| File | Key Findings | Status |
|---|---|---|
| `NetworkModule.kt` | **Placeholder certificate pin!** Debug BODY logging. Good: timeouts, pinning structure | **CRITICAL** |
| `FcmNotificationSender.kt` | Good: Metadata-only payloads, retry with exponential backoff, no sensitive data in notifications | PASS |
| `WebRtcSignalingClient.kt` | sendOffer() misused for hangup. Good: Proper polling lifecycle, shutdown cleanup, coroutine scoping | PARTIAL |
| `FcmService.kt` | Math.random() for alert IDs. Good: Metadata-only payloads, alert parsing validation | NEEDS FIX |
| `PairingApi.kt` / `SignalingApi.kt` | Good: HTTPS endpoints, parameterized paths, no sensitive data in signatures | PASS |

### Child App Module
| File | Key Findings | Status |
|---|---|---|
| `CallManager.kt` | Good: DTLS enabled (`disableEncryption=false`), proper resource cleanup, adaptive bitrate | PASS |
| `MonitoringService.kt` | Wake lock timing could be improved. Good: Foreground service types, thermal monitoring, START_STICKY | PASS |
| `AudioPipeline.kt` / `CameraPipeline.kt` | Good: Privacy-first (no persistent storage), AudioRecord not MediaRecorder, buffer discarding | PASS |
| `CryDetector.kt` / `MotionDetector.kt` | Good: On-device ML, no raw data emission, sustained-confidence logic prevents false positives | PASS |
| `EventPipeline.kt` | Good: Metadata-only alerts, device status enrichment, no media in notifications | PASS |
| `SosManager.kt` | Good: Best-effort location, metadata-only, no persistent storage | PASS |

### Parent App Module
| File | Key Findings | Status |
|---|---|---|
| `AppDatabase.kt` | `fallbackToDestructiveMigration()` destroys data. Good: SQLCipher encryption, secure passphrase generation | NEEDS FIX |
| `AlertHistoryRepository.kt` | Good: Retention policy enforcement, parameterized queries, no SQL injection | PASS |
| `SettingsViewModel.kt` | Unencrypted DataStore for settings. Good: Data deletion support | NEEDS FIX |
| `ParentDashboardViewModel.kt` | `simulateMockAlert()` test function present in production code | NEEDS FIX |

---

*This audit was conducted against all 80 Kotlin files in the project. Each finding includes the specific file path and line number for direct reference. Remediation estimates are approximate and depend on team velocity and testing requirements.*
