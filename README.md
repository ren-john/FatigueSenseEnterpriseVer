# FatigueSenseEnterprise Android prototype

Independent Kotlin/Compose copy of FatigueSense. The original Android app is unchanged.
Application ID: `com.fatiguesense.enterprise`. Label: `FatigueSenseEnterprise`.
Face detection, eye/mouth points, and head pose use the bundled MediaPipe Face
Landmarker. Calibration, streaming and local alarms remain local. Camera frames
stay local; cloud payloads contain detection records only.

## App features

- Local-first dashboard, calibration, monitoring and history without an account or internet.
- Optional enterprise sign-in/recovery from Settings, with driver claims and server assignment checks.
- Separate Local-mode history, preserved legacy history, and account-owned enterprise uploads.
- Saved-IP check, mDNS/Android NSD discovery and manual IP fallback, checking ESP identity by default.
- Native Wi-Fi provisioning, save/restart status, AP/STA switching, forget/recovery controls.
- Dynamic stream/control address and automatic identity verification on reconnection.
- Durable eye/yawn trigger edges and continuous three-minute no-face observation.
- Background batch upload with original timestamps, revision acknowledgments and retries.
- Offline Local history/export/clearing; enterprise account history, sync status and pending-upload protection.
- Event-time flags: 3 EAR/60 seconds, 6 yawns/600 seconds, 180 seconds without a face.

## Local mode and ESP setup

Fresh installs and upgrades without a new explicit enterprise preference open in
Local mode, even if the phone has an older Firebase login. Calibration, Logs and
Settings are available immediately. Local monitoring needs local Wi-Fi to an ESP;
it does not need internet or an account. Local events stay in `local-mode.db` and
never enter the enterprise upload queue after later sign-in. Earlier general logs
remain separately available under **Logs → View preserved legacy history**, with
unknown ownership; clearing Local history preserves that archive.

For first setup:

1. Open **Connect ESP** on the dashboard or **Settings → Connect / manage ESP**.
2. In **Camera setup**, tap **Open Wi-Fi settings** and join `FatigueSense_Cam`.
   The default Wi-Fi password is `safedriving`. Choose **Stay connected** if Android warns that it has no internet.
3. Return to the app. Leave **Camera IP address** at `192.168.4.1` and tap
   **Connect camera**. The default setup password is already filled in under
   **Connection options**; change it there only if needed.
4. When **Camera is ready** appears, tap **Continue to dashboard** and start monitoring.
   Stop monitoring before changing the paired device.

**Use your phone’s hotspot** is optional. Expand that section, enter your phone’s
2.4 GHz hotspot name/password, and tap **Save and restart camera**. Turn on the
phone hotspot after saving, wait for the camera to join, then select
**Reconnect saved camera** or enter its new IP address. A save acknowledgement
confirms settings were received; the camera must still reconnect and be verified.
If a save result is unclear, check camera status in **Connection options** before
retrying. On subsequent use, enable the same hotspot and reconnect the saved camera.

**Troubleshooting & device settings** contains recovery instructions and controls
for switching to direct camera Wi-Fi, removing the camera’s saved hotspot, or
pairing a different camera. These changes explain their effects before applying.
For recovery, wait about 30 seconds, join `FatigueSense_Cam`, and connect at
`192.168.4.1`. Nonsecret fields and expanded sections survive Android Settings
visits/rotation. Passwords are kept in memory; hotspot passwords are not put into
pairing/history storage.

Manual IP is the baseline. Automatic discovery only works with firmware advertising
`_fatiguesense._tcp`; the inspected firmware does not advertise it. A known device ID
must match on reconnect. Older firmware without `device_id` requires explicit camera
confirmation and is labeled as having unverified hardware identity. Reset Local
pairing to select different hardware. Enterprise mode additionally requires the
administrator-assigned ID, including exact manual ID entry for older firmware.

For **CameraOnly** firmware that returns HTTP 404 for `/api/wifi`, use Local mode,
expand **Connection options**, enable **My camera uses older firmware**, and tap
**Connect camera**. The app verifies a decoded video frame from port 81 `/stream`
before pairing and on reconnect, and remembers the manual pairing choice. Wi-Fi
provisioning/reset controls are unavailable on this firmware. A previously saved
hardware ID cannot be bypassed: use **Pair a different camera** when intentionally
changing hardware or firmware that no longer reports that ID. Enterprise setup
continues to require the camera status endpoint and assigned identity.

Use **Settings → Enterprise sign-in** to opt in to enterprise features. Successful
access validation remembers Enterprise mode across restarts. Cancelled/failed entry
returns to Local mode; failed restoration offers retry and **Return to Local mode**.
**Settings → Sign out and return to Local mode** stops monitoring and durably queues
enterprise closure before Local activates. Pending enterprise records retain their
original account/organization/session. Temporary internet loss during enterprise
monitoring keeps that ownership. Local activation waits for in-flight cloud requests
and monitoring cleanup to settle; a cleanup error offers retry.

## Landmark detection

MediaPipe handles full-frame face detection and replaces the custom trained point
models. Its eye and lip landmarks feed
the existing EAR/MAR, calibration, PERCLOS and alert pipeline. Complete a fresh
Face Setup calibration after installing this version; settings from custom models
are kept separate. See [model details and validation](MODEL_SWITCHING.md).

## Phone alerts (ESP buzzer temporarily disabled)

The phone plays `asset/ndrrmc-alert-1.mp3` for EAR drowsiness and yawn alerts.
The bundled copy is `app/src/main/res/raw/ndrrmc_alert_1.mp3`; both alert types use
this clip until a separate yawn MP3 is supplied. It loops while either detector
alert is active and stops on recovery, stream loss/watchdog timeout, or monitoring
shutdown. ESP buzzer activation commands and alert heartbeats are suppressed in
the Android app. Clear/off commands remain enabled on startup, recovery, reconnect,
and shutdown to silence any previously latched buzzer. ESP firmware is unchanged. Playback uses
the phone's alarm audio settings and requests temporary audio focus; it pauses
when another audio source temporarily takes focus. Audio errors do not block
ESP32 clear commands or event logging.

## Repeated eye-closure popup

During monitoring, a popup appears after **3 qualified eye closures within 60
seconds** by default. In **Calibration → Eye-closure popup**, edit the closure
count and time window, then tap **Save eye-closure alert**. Settings persist across
app restarts and work in Adaptive and Manual mode. Resetting calibration defaults
also restores these limits.

A closure counts once after meeting the existing EAR threshold and consecutive-frame
sensitivity; eyes must visibly reopen before another closure counts. The window
uses elapsed time rather than frame rate. The popup works across app screens and
stays until acknowledged. Acknowledgement leaves detection and audio running;
another popup can fire after the count falls below the limit and reaches it again.
Saving popup limits, recalibration, stream loss, and a new/stopped monitoring session
start a fresh counting window. This does not enable ESP buzzer activation.

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
remain separate firmware requirements. Strict identity checking is the default;
an explicit temporary manual confirmation supports firmware that omits `device_id`.
A firmware-reported mismatch is always rejected.

### Monitoring and hardware updates — 2026-10-31

Stop now persists and sends session closure independently of detection backlog.
Start retries pending closures and rechecks the ESP before authorizing a new
session; native pipeline cleanup completes before replacement monitoring starts.
Offline closure remains queued. Authentication, session exclusivity, historical
event uploads, and detection/calibration behavior are retained.

After an Admin or Supervisor replaces hardware in web **Vehicles → Edit**, open
**Settings → Connect / manage ESP → Refresh assignments**, reset the old local pairing, then
verify the replacement IP and setup password. Settings displays the assigned ID.

For firmware without `device_id`, enable the manual assigned-ID confirmation, enter
the administrator-assigned ID exactly (case-sensitive), then verify the IP.
This confirms your input, not the camera's physical identity. Camera/network and
backend assignment checks still apply. Confirmation survives reconnects in memory
and clears on setup changes/sign-out/process restart; it does not bypass login.
Prefer firmware identity support for ongoing use.

Rebuild/restart the sibling backend and apply its updated Firestore rules before
using hardware replacement. Install the rebuilt Android APK. No live Firebase
deployment is performed by these source changes.

With a placeholder backend URL, Local mode remains usable. Enterprise sign-in explains
that backend configuration is incomplete.

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

Firebase Auth uses the default Firebase app initialized from generated resources only
after explicit enterprise entry/restoration. Local startup does not initialize Firebase.
The Kotlin namespace remains `com.example.fatiguesense.enterprise`; the installed
application ID matches the Firebase registration. This installs separately from
the earlier `com.example.fatiguesense.enterprise` prototype and does not migrate
its saved events, pairing, or sign-in state. Export any needed history first.
Release signing remains undecided. The existing app's signing material was not copied.

Firebase BoM 33.7.0 is pinned for the cloned Kotlin 2.0.21/KSP toolchain. Tested BoM
34.19.0 failed because its Auth artifact requires Kotlin 2.3 metadata. Upgrade those
dependencies together in a separate change when needed.

## Validation

Unit tests cover MediaPipe point mapping and detector geometry plus event-time boundaries,
deduplication, driver/session isolation, no-face continuity, Wi-Fi input and CSV escaping.
Room instrumentation tests cover queue isolation, exact-revision acknowledgment,
safe clearing, crash recovery and persistent ESP identity. Run on a test device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The Local-mode sprint evidence, emulator/fixture commands, and remaining physical
phone/ESP and enterprise test-environment checks are in
[Local/Enterprise validation](docs/local-mode-validation.md). Current sprint tracking
is in [the sprint plan](docs/local-enterprise-sprints.md); older enterprise integration
results remain in [implementation status](docs/implementation-status.md).
Original source hashes are recorded in `docs/source-baseline.json`.
