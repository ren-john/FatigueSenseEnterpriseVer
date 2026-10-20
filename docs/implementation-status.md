# Android implementation status — 2026-10-18

Scope: implement the enterprise Android app. Backend work was explicitly deferred.
The subsequently supplied `sentry-drive-backend` was inspected to check compatibility;
no backend, website or ESP firmware files were changed. This is an app prototype,
not a deployed or end-to-end verified system.

## Implemented in the enterprise copy

- Independent source copy from `FatigueSense-Thesis`, with application ID
  `com.example.fatiguesense.enterprise` and launcher label `FatigueSenseEnterprise`.
  Initialized a separate Git repository on `codex/enterprise-app`; no commits or
  remote push were made. Original signing material, installed data and Git metadata
  were not copied. Detector models and calibration behavior were retained.
- Firebase email/password sign-in and password recovery; driver-role, organization,
  access, assignment and session checks through an authenticated HTTP client.
  The login screen reports unresolved configuration. No mock login is present.
- Native ESP Wi-Fi setup and AP/STA/forget actions. A save acknowledgment means
  settings were saved, not that the ESP joined the phone hotspot. The user enables
  the phone's 2.4 GHz hotspot in Android Settings.
- Room stores the paired ESP ID with organization/vehicle, last verified IP and
  pairing time. Connection tries the saved IP, then Android NSD, then manual entry.
  Every path verifies the assigned ID and camera availability. Reconnecting the
  stream repeats verification; control requests use the verified address.
- Separate Room event journal with driver, organization, vehicle, ESP and session
  attribution. Stable event IDs record EAR/yawn onset and completion revisions.
  No-face timing requires 180 seconds of continuous valid observations; inference
  failures, frame gaps and stream loss interrupt it.
- WorkManager submits bounded batches with original UTC timestamps, token
  authentication, exact-revision acknowledgments, retries and persistent errors.
  Session ends are queued until preceding events are processed. A new driver's
  account never selects or uploads the previous driver's queued events.
- Personal history, pending/failed counts, last successful sync, retry control,
  CSV sharing and clearing of only acknowledged, closed local records.
- Local historical flags: 3 EAR detections within 60 seconds, 6 yawns within
  600 seconds, and continuous no-face observation for 180 seconds. Revisions are
  deduplicated and separate driver sessions are evaluated independently.
- Camera frames remain on the local connection and on-device analysis path.
  The cloud uploader serializes event records only.

## Validation evidence

- `assembleDebug`: passed; APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `testDebugUnitTest`: 30 tests passed, including 11 new tests for event-time
  boundaries, duplicate revisions, session/driver separation, no-face continuity,
  Wi-Fi input and CSV escaping. Existing analyzer/model/shutdown tests also pass.
- `assembleDebugAndroidTest`: passed. Five Room tests cover account/organization
  isolation, late acknowledgment races, safe clearing, crash recovery and paired
  device persistence. These tests have **not run on a device**.
- `lintDebug`: passed after correcting Windows SDK path escaping. The report
  retains 56 warnings and one hint, with no errors; see the limitations below.
- ADB returned no connected devices; no configured emulator was found. No APK was
  installed and no ESP firmware was flashed.
- All 80 copied original tracked files match the SHA-256 baseline; the original
  repository's Git status remains clean. See `source-baseline.json`.

## Dependencies and remaining checks

1. Supply the four real values documented in `enterprise.properties.example`.
   Firebase Android registration must match the provisional enterprise package ID.
   With placeholders the APK is limited to the configuration/login screen.
2. Extend the backend for driver access/assignment, mobile sessions and revisioned
   event batches. The existing backend has staff/gateway authorization and a
   different vehicle-only inbox contract; these are not interchangeable. Implement
   authoritative historical flags and web driver reporting in a later phase.
3. Extend ESP firmware to return a stable `device_id` from `/api/wifi` and advertise
   `_fatiguesense._tcp` with a matching TXT identity. The current firmware cannot
   pass the app's identity gate. Refer to `integration-contract.md` for exact
   proposed endpoints and compatibility gaps.
4. Run Room instrumentation tests and real-device tests for login/account switching,
   process restart, offline batches, lost/partial responses, ESP provisioning,
   DHCP changes, NSD while hosting a hotspot, simultaneous internet/local routing,
   calibration, streaming and physical buzzer behavior. API and hardware tests are
   not established by compilation or unit tests.
5. Production signing, deployment, revocation/backlog policy and performance review
   remain deferred. Lint identifies inherited LiteRT x86_64 native libraries that
   are not 16 KiB aligned; review runtime compatibility before release. Other
   warnings concern dependency updates, Compose conventions, unused resources,
   application-context singleton storage and KTX/version-catalog suggestions.

No RTDB live telemetry/heartbeat client or web integration is implemented in this
phase. Local event-time flags are useful app history; administrator reporting will
require the future backend and website changes described in `../plan.md`.
