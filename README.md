# FatigueSenseEnterprise Android prototype

Independent Kotlin/Compose copy of FatigueSense. The original Android app is unchanged.
Application ID: `com.fatiguesense.enterprise`. Label: `FatigueSenseEnterprise`.
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

## Driver and web integration

The 2026-10-29 update connects this driver-only client to the sibling
`sentry-drive-backend` and `sentry-drive-web` projects. Company Admins invite drivers
by email and assign a vehicle/ESP. The driver sets a password, signs in, verifies
the ESP and starts a server-authorized session. Heartbeats and revisioned event
uploads feed the connected web dashboard and recent detection history.

See [the integration setup and validation](docs/driver-integration.md) and
[the exact API contract](docs/integration-contract.md). The backend and web source
are implemented and tested with isolated Firebase services. Apply the backend's
rules/indexes and rebuild/restart it before real-project use. No live Firebase
deployment occurred. ESP identity/mDNS extensions and physical hardware validation
remain separate firmware requirements; identity checks are retained.

With a placeholder backend URL, the APK opens to a login screen explaining incomplete configuration.
No mock account or authentication bypass is provided.

## Build and configuration

Use JDK 17 or 21 and Android SDK 36. Gradle 8.13 does not support running on JDK 25.
Set the SDK path in ignored `local.properties`, then run:

```bash
JAVA_HOME=/path/to/jdk-21 bash ./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
```

On Windows, set `JAVA_HOME` to JDK 17 or 21 and use `gradlew.bat`.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Download the Firebase Android configuration for `com.fatiguesense.enterprise` from
the same Firebase project as the backend and place it at ignored
`app/google-services.json`. The Google Services Gradle plugin processes this file;
builds fail if it is missing or has no matching Android package. Do not edit the
downloaded package name to work around a registration mismatch.

Copy `enterprise.properties.example` to ignored `enterprise.properties` and replace:

| Key | Placeholder |
| --- | --- |
| BACKEND_URL | https://YOUR_BACKEND_HOST.invalid/api |

The backend placeholder is also the Gradle fallback. Firebase settings come only
from the JSON; legacy `FIREBASE_*` properties are no longer read. Cloud APIs require
HTTPS in release builds. Debug builds also accept a reachable local HTTP backend.
The server root and `/api` base are accepted and normalized. A physical phone
needs the development computer's reachable LAN address and port, not localhost.
Local ESP HTTP is retained. Never include Admin SDK keys.

Firebase Auth uses the default Firebase app initialized from generated resources.
The Kotlin namespace remains `com.example.fatiguesense.enterprise`; the installed
application ID matches the Firebase registration. This installs separately from
the earlier `com.example.fatiguesense.enterprise` prototype and does not migrate
its saved events, pairing, or sign-in state. Export any needed history first.
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
