# Local and optional Enterprise mode validation

Date: 2026-11-01. Branch: `new`. Baseline before these sprints: existing uncommitted
RUN-01 implementation. No commit or push was made.

RUN-02 through RUN-05 are implemented. RUN-06 automated and emulator validation is
recorded below; physical phone/ESP checks and live enterprise test-environment
checks remain pending. See [sprint scope and completion records](local-enterprise-sprints.md)
and [user setup instructions](../README.md#local-mode-and-esp-setup).

## Environment and commands

- JDK: `/home/saikenstudio/.jdks/jbr-21.0.11` (21).
- Installed compile/target SDK: 36; minimum SDK: 24.
- Runtime: fresh, disposable `FatigueSense_Run06` AVD; Android 17 / API 37,
  Google Play x86_64 image; Android Emulator 37.2.12.0.
- Emulator: 1080 × 2400 (`adb shell wm size`), density 320, 2 GB RAM, two cores, software graphics,
  headless with host audio disabled. Android media playback/focus still tested.
- AVD created under ignored `build/run06/avds`, using the installed image. The user's
  existing `Medium_Phone` AVD/userdata was not reused or modified.
- Network: emulator internet capability plus process-local HTTP proxy to a loopback
  ESP fixture via ADB reverse. No physical Wi-Fi/hotspot or ESP was attached.
- Firmware: simulated `/api/wifi`, `/health`, `/alert` and port-81 MJPEG responses.
  The fixture's real-face image is derived from the repository's retained
  `landmark32_input.bin` validation crop; its other frame is a blank JPEG.
  Physical flashed firmware version is unknown.

Automated checks:

```bash
JAVA_HOME=/home/saikenstudio/.jdks/jbr-21.0.11 bash ./gradlew --no-daemon --offline \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Fixture execution in a separate terminal, on a **dedicated test emulator/device**:

```bash
python3 scripts/run06_esp_fixture.py
adb -s emulator-5580 reverse tcp:18080 tcp:18080
ANDROID_SERIAL=emulator-5580 JAVA_HOME=/home/saikenstudio/.jdks/jbr-21.0.11 \
  bash ./gradlew --no-daemon --offline :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.espFixture=true
```

The fixture binds only host loopback port 18080 and requires Python's standard
library. The test installs/restores a process-local HTTP proxy, so production
ESP addresses/ports and app behavior are unchanged. Fixture tests clear only the
test app's local pairing and restore temporary calibration overrides. Without the
`espFixture=true` argument, the four fixture-dependent tests skip intentionally;
the ordinary UI, Room, configuration, audio, model and worker tests can run alone.
The test share/export case captures the chooser intent without sending a file.

## Results and their limits

- Unit tests: **58 passed**, zero failures/errors/skips. Covers mode defaults,
  entry/restore gates, old workers, cloud request draining, account/organization
  ownership, native shutdown, retryable durable cleanup, detector/flag boundaries,
  local/enterprise identity compatibility and input validation.
- Instrumentation: **22 passed**, zero failures/errors/skips, with the fixture
  argument above. Includes existing MediaPipe real-face/blank-frame inference,
  Android media playback/focus, Room enterprise ownership/closure/revision checks,
  plus the Local-mode cases below.
- Debug APK, instrumentation APK and lint: **passed**. APK:
  `app/build/outputs/apk/debug/app-debug.apk`.
- Additional fresh-install/offline smoke check: installed the final debug APK into
  the disposable emulator, disabled Wi-Fi and mobile data, force-stopped/relaunched
  it, and checked the UI hierarchy/screenshot. Local dashboard, Calibration, Logs
  and Settings were present. Screenshot and UI dump are in ignored
  `build/run06/local-dashboard.png` and `build/run06/offline-ui.xml`.
- `git diff --check`: passed. Merged production manifest has
  `FatigueSenseApplication` and no `FirebaseInitProvider`.

Runtime evidence:

| Area | Executed evidence | Remaining limit |
| --- | --- | --- |
| Local startup/navigation | Local dashboard, Calibration, Local Logs and Settings usable with no driver; Firebase app list remains empty | One fresh emulator/API level; actual cached-login upgrade still pending |
| Enterprise entry cancellation | Settings entry followed by cancel returns to usable Local dashboard; Firebase remains uninitialized | No real credentials or cancelled in-flight server login exercised |
| Setup recreation | Manual IP and hotspot SSID retained after ActivityScenario recreation | Physical Android hotspot Settings handoff and process-kill during actual ESP save pending |
| Provisioning readiness | Simulated saved STA settings with `connected=false` cannot grant readiness; failed camera rejected | Physical save/restart, wrong credentials and recovery AP pending |
| Identity | Known local ID rejects mismatch/missing ID even with legacy confirmation; legacy requires explicit confirmation | Physical board identity, DHCP changes and NSD/multiple advertisers pending |
| Local monitoring | Real foreground service, native detector, simulated MJPEG, three repeated start/stop cycles, stream-loss command blocking and verified reconnection | Physical stream latency, ESP outputs and background/locked-phone endurance pending |
| Transition finalization | Active EAR alert from real model inference closes into Local Room history before enterprise entry; no enterprise queue entry; returning Local preserves row | Genuine enterprise login/account switching/upload after local events pending |
| Storage and migration | Separate Local/legacy/enterprise stores; Local survives close/reopen; Local clear preserves legacy and enterprise; v1 legacy migration retains rows | Real installed-app upgrade with existing user data pending |
| CSV sharing | Original timestamp, duration, severity and trigger type exported through real FileProvider URI with read permission | No third-party receiving app exercised |
| Worker/privacy | A queued UploadWorker completes in Local mode without creating Firebase/driver; monitored lifecycle HTTP proxy destinations restricted to loopback ESP endpoints | Proxy observation and SDK checks are not a complete device-wide packet capture |

The configuration test previously initialized Firebase directly; it now validates
`FirebaseOptions.fromResource` without starting the SDK. Initial UI test selectors
were corrected to match the existing uppercase navigation labels. An emulator
restriction prevented binding stream port 81 through ADB reverse; the test-local
proxy resolved that harness issue without a production port/protocol change.
Final passing results include these corrections and shutdown ordering fixes.

## Checks that require external equipment or test access

During the initial run only the disposable emulator was available. A physical
TECNO phone and ESP became available during the camera-connection follow-up below.
Direct-hotspot verification, live streaming, detector events and stop cleanup now
have physical evidence. The flashed firmware version and enterprise test credentials
remain unavailable. These checks still require additional physical/test access:

1. On the target phone, record model/Android version, ESP board/flashed firmware,
   router/hotspot conditions and cellular/internet availability. Test setup AP →
   phone 2.4 GHz hotspot → verified camera, saved reconnect, wrong password, stale
   IP, multiple devices, legacy confirmation, unavailable camera, and recovery.
2. Exercise repeated foreground/background/locked-phone start/stop, stream loss,
   reconnect, phone EAR audio, physical ESP buzzer/clear, and calibration parity.
3. Capture traffic attributed to the app with internet available. Cover fresh
   install, actual cached-login upgrade, process restart, old WorkManager jobs,
   local alerts/stop, and real login/recovery cancellation/failure. Confirm no
   Firebase/backend requests after Local activates. Retain sanitized capture evidence.
4. In the enterprise **test environment**, validate successful/failed login and
   recovery, remembered mode, offline restoration fallback, assignment mismatch,
   session authorization, heartbeat, offline upload/closure retry, original event
   times, revoked access and account/organization switching. Local events must stay
   out of payloads after successful login and restart.
5. Retain an upgrade database backup; verify legacy history, existing enterprise
   pending records, and new Local history remain distinct without relabeling.

No firmware flashing, live deployment, production-account exercise, backend/web
source changes, or transmission of camera frames to a backend occurred.

## Camera connection follow-up — 2026-11-01

The user's TECNO LJ8k running Android 16 / API 36 was connected by ADB later in the
session. Its direct ESP Wi-Fi link used `FatigueSense_Cam` at 2.4 GHz, phone address
`192.168.4.2`, and ESP address `192.168.4.1`. A reported ESP32-S3 device ID was verified;
the flashed firmware build/version remains unknown.

Observed and corrected:

- The initial "Could not reconnect" message came from the saved-device path.
  First-time setup now puts **Verify entered IP** first, disables Local saved
  reconnect until pairing exists, and keeps operation messages above the form.
- The phone returned to regular Wi-Fi after the ESP was power-cycled. After
  reconnecting it to the ESP Wi-Fi, explicitly Wi-Fi-bound read-only checks returned
  HTTP 200 for `/health`, `/api/wifi`, and `/`. The app then reported **Device
  identity and camera verified**. The standard setup AP address now requires its
  matching Wi-Fi route, with a clear instruction to stay connected without internet;
  requests to that address cannot silently fall back to an unrelated network.
- A real monitoring start still hit TCP resets/early EOF during status rechecks.
  Short ESP setup/control requests now send `Connection: close` and disable caching.
  This resolved the failure in the physical test without changing ESP firmware,
  request payloads, calibration formulas, or identity requirements. The configured
  embedded server has limited sockets, but socket exhaustion was not independently
  proven from firmware logs. See [ESP HTTP server session documentation](https://docs.espressif.com/projects/esp-idf/en/v5.4/esp32/api-reference/protocols/esp_http_server.html)
  for context, not proof of this device's underlying failure.
- Saved-reconnect failures retain their cause in diagnostics instead of hiding it
  behind a generic message. An active recovery AP is accepted when the saved mode
  is STA and the camera is ready; disconnected STA settings without an active AP,
  restarting hardware, and unavailable cameras remain rejected.

Physical results on the updated app:

- Real stream connected and UI showed **STREAMING · 11 FPS**.
- Real detector EAR events were logged, and fatigue/clear ESP commands received
  HTTP 200 acknowledgments. Audible buzzer/phone volume and detection accuracy were
  not independently confirmed by this evidence.
- Stop issued a forced clear acknowledged with HTTP 200; the service joined its
  pipeline and reported **Monitoring resources and final events released**. The
  app returned to idle and was left stopped after the brief test.
- The app was updated in place (`adb install -r`); its existing pairing/history
  was not cleared. Full instrumentation ran only on the disposable emulator.
- Follow-up automated results: 63 unit tests, 25 emulator instrumentation tests;
  debug/test APKs and lint passed. The 25-test emulator run preceded the final
  short-connection header adjustment; the final adjustment was rebuilt, passed
  unit/lint/APK checks, and was verified by the real streaming/alert/stop test above.
  The emulator was no longer attached for another full-suite run. The explicit read-only physical connectivity
  probe also completed. The probe is intentionally excluded from ordinary suites
  with `-e notClass com.example.fatiguesense.enterprise.enterprise.EspDeviceProbe`.
  To invoke it alone on a connected test phone:

  ```bash
  adb -s PHONE_SERIAL shell am instrument -w \
    -e class com.example.fatiguesense.enterprise.enterprise.EspDeviceProbe \
    -e espProbe true -e ip 192.168.4.1 \
    com.fatiguesense.enterprise.test/androidx.test.runner.AndroidJUnitRunner
  ```

Evidence retained under ignored `build/run06/`: `camera-fix-instrumentation.txt`,
`phone-live-stream-text.txt`, `phone-after-stream-stop.txt`, and diagnostic UI
snapshots. Live camera pixels were not exported for this validation. Existing
physical hotspot handoff/recovery/endurance, firmware-version confirmation, full
traffic capture and genuine enterprise/account/upgrade checks remain open.

## Camera setup usability update — 2026-11-01

Replaced the long setup form with two numbered steps: join `FatigueSense_Cam`,
then connect to the camera. The screen now shows the default Wi-Fi password,
Android's no-internet guidance, one primary connection action, and a persistent
**Continue to dashboard** action after successful verification. Connection
passwords, legacy identity confirmation, optional phone-hotspot configuration,
and recovery controls are grouped in expandable sections. Reset/restart actions
explain their effects in confirmation dialogs. Connection errors direct users to
the relevant controls; identity and camera-readiness validation remain enforced.

Validation of this update:

- Final debug APK and test APK assembled; lint completed successfully.
- 63 unit tests passed, with no failures or skips.
- All 5 `LocalModeUiTest` instrumentation tests passed on the dedicated emulator,
  including field restoration, collapsed optional controls, visible connection
  errors, and cancellation/confirmation of pairing reset.
- Visually inspected a 360 dp-wide screen at normal and 150% font size, including
  the expanded hotspot form. The local HTTP fixture also reached the success
  state and exposed the persistent dashboard action through the actual UI.
- Evidence is under ignored `build/run06/setup-ui-*` files. Updated source and APK
  were not installed on the physical phone: only the emulator was visible to ADB
  during this update. These UI checks do not complete the remaining RUN-06 live
  service, network-traffic, hotspot-handoff, or physical endurance checks.
