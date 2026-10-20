# FatigueSenseEnterprise Android prototype

Independent Kotlin/Compose copy of FatigueSense. The original Android app is unchanged.
Package: `com.example.fatiguesense.enterprise`. Label: `FatigueSenseEnterprise`.
ML assets, calibration, streaming and local alarm behavior are retained. Camera frames
stay local; cloud payloads contain detection records only.

## App features

- Firebase email/password login/recovery, driver claims and server access/assignment gate.
- Room database for paired ESP ID, verified IP, identity-bound events and pending uploads.
- Saved-IP check, mDNS/Android NSD discovery and manual fallback, all checking ESP identity.
- Native Wi-Fi provisioning, save/restart status, AP/STA switching, forget/recovery controls.
- Dynamic stream/control address and automatic identity verification on reconnection.
- Durable eye/yawn trigger edges and continuous three-minute no-face observation.
- Background batch upload with original timestamps, revision acknowledgments and retries.
- Personal history, sync status, CSV sharing and safe clearing of uploaded local history.
- Event-time flags: 3 EAR/60 seconds, 6 yawns/600 seconds, 180 seconds without a face.

## What is not connected yet

The user asked to focus on Android and skip backend work. The newly supplied backend
was inspected read-only. It has operator/gateway contracts but does not support the
driver role or mobile endpoints required by this plan. The reference ESP firmware
also lacks the device-ID and mDNS extensions. Neither repository was modified.

See [the exact API contract and compatibility gaps](docs/integration-contract.md).
The app intentionally does not bypass identity checks to connect to those older
contracts. Real sign-in, sessions, upload and hardware pairing require the documented
backend/firmware extensions plus real Firebase configuration. No deployment occurred.

With placeholders, the APK opens to a login screen explaining incomplete configuration.
No mock account or authentication bypass is provided.

## Build and configuration

Use Android Studio's JDK (Java 17+) and Android SDK 36. Set the SDK path in ignored
`local.properties`, then run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Copy `enterprise.properties.example` to ignored `enterprise.properties` and replace:

| Key | Placeholder |
| --- | --- |
| FIREBASE_API_KEY | YOUR_FIREBASE_ANDROID_API_KEY |
| FIREBASE_APP_ID | YOUR_FIREBASE_ANDROID_APP_ID |
| FIREBASE_PROJECT_ID | YOUR_FIREBASE_PROJECT_ID |
| BACKEND_URL | https://YOUR_BACKEND_HOST.invalid/api |

The same placeholders are Gradle fallbacks. These are all active app placeholders;
`plan.md` lists future website/deployment settings separately. Cloud APIs require
HTTPS. Local ESP HTTP is retained for the prototype. Never include Admin SDK keys.

Firebase is initialized programmatically using `FirebaseOptions`; no fabricated
`google-services.json` is needed. Register the final application ID before release.
Release signing remains undecided. The existing app's signing material was not copied.

Firebase BoM 33.7.0 is pinned for the cloned Kotlin 2.0.21/KSP toolchain. Tested BoM
34.19.0 failed because its Auth artifact requires Kotlin 2.3 metadata. Upgrade those
dependencies together in a separate change when needed.

## Validation

Unit tests cover existing detector/model behavior plus event-time boundaries,
deduplication, driver/session isolation, no-face continuity, Wi-Fi input and CSV escaping.
Room instrumentation tests cover queue isolation, exact-revision acknowledgment,
safe clearing, crash recovery and persistent ESP identity. Run on a test device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Real ESP streaming, NSD while hosting a phone hotspot, camera/alarm hardware and real
Firebase/backend flow need integration testing. See [implementation status](docs/implementation-status.md).
Original source hashes are recorded in `docs/source-baseline.json`.
