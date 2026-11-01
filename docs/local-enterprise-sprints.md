# Local and Enterprise mode sprint runs

Created: 2026-11-01. Branch at planning: `new`. Baseline: `c0e3d71`.

This is the current plan for the optional-enterprise changes agreed with the user.
The root `plan.md` remains the historical enterprise integration plan. Where its
mandatory-login requirements differ, this document describes the new behavior.

## Execution and tracking

These runs are created as sprint containers. Creation initializes their scope;
it does not authorize starting every sprint concurrently. Each run should read
this document, acknowledge its scope and prerequisites, and stop until the user
starts that sprint. No application or firmware implementation is part of run
initialization.

Run implementation sequentially in the shared checkout. Check the current branch,
working tree, and completed prerequisite evidence before editing. Never discard
unrelated changes. Record the actual changes, checks, remaining issues, and any
commit reference in the sprint's completion record. Do not mark hardware or
network behavior verified based only on compilation. Do not commit or push
without an instruction covering these changes.

| Run | Sprint | Prerequisites | Implementation status | Task ID |
| --- | --- | --- | --- | --- |
| RUN-01 | App mode and cloud isolation | None | Implemented; automated checks passed; runtime proof in RUN-06 | `01a1930a-e1c8-74b1-b2a9-7ccaf853ec08` |
| RUN-02 | Shared ESP provisioning and reconnection | RUN-01 | Implemented; unit/build checks passed; physical checks in RUN-06 | `01a1930a-e31f-7da3-ac42-bac262c7d2c3` |
| RUN-03 | Independent local monitoring | RUN-01, RUN-02 | Implemented; unit/build checks passed; device checks in RUN-06 | `01a1930a-e370-7870-99c4-4fde50509573` |
| RUN-04 | Local history, export, and data separation | RUN-03 | Implemented; Room/export runtime checks passed; live account checks pending | `01a1930a-e3c0-7d20-a719-472873be3333` |
| RUN-05 | Default local UI and enterprise access in Settings | RUN-01 through RUN-04 | Implemented; UI/recreation runtime checks passed; live access checks pending | `01a1930a-e40f-7032-a6bf-5be87c588324` |
| RUN-06 | Regression, privacy, and phone/ESP validation | RUN-01 through RUN-05 | Automated/emulator checks passed; physical and live enterprise validation pending | `01a1930a-e468-7591-b7fd-f0999630158e` |

Acceptance checkmarks below reflect the evidence in each completion record and
[RUN-06 validation](local-mode-validation.md). Simulated ESP and Room results do not
establish physical hardware or live enterprise behavior. Unchecked items retain
external/runtime requirements; implemented source alone is not a passing claim.

## Agreed behavior and constraints

- Fresh installs and upgrades without an explicit new mode preference start in
  Local mode. An existing Firebase login alone must not enable cloud activity.
- Local mode opens the dashboard with Calibration, Logs, and Settings available.
  It requires neither an account nor internet. Local Wi-Fi remains necessary for
  the ESP connection.
- Settings offers enterprise sign-in. Successful authentication and access checks
  enable Enterprise mode; remember that choice across restarts. Cancelled or
  failed login leaves Local mode available. Persisted Enterprise mode that cannot
  restore access must offer a return to Local mode without requiring a successful
  server call. Do not silently change data attribution on connection loss.
- Local mode sends no backend requests, authentication refreshes, heartbeats, or
  uploads, including through old WorkManager jobs. Cloud authentication requests
  are permitted during the user's explicit enterprise sign-in/recovery flow;
  failed or cancelled entry must stop that flow and return to local operation.
- Local events never enter the enterprise outbox, including after later login.
  Enterprise records retain their original account, organization, and session.
- Preserve the current model, calibration formulas and thresholds, streaming
  protocol, phone EAR alarm, ESP buzzer commands, and foreground monitoring.
- Both modes share the Wi-Fi provisioning and connection implementation. Local
  mode selects a device without administrator assignment; Enterprise mode retains
  assigned-device matching and online session authorization.
- Stop monitoring and await cleanup before switching modes. Finish or durably
  queue enterprise closure before Local mode becomes active; no server closure
  request may continue after Local mode is active. Unsent enterprise records stay
  pending until an authorized enterprise session can sync them.
- Preserve existing databases and history without destructive migration or
  automatically relabeling historical records. The existing general local log
  database may contain enterprise-era events; do not assume those rows are new
  Local-mode records.
- No backend, website, ESP protocol, or firmware edits are planned in these six
  runs. No firmware flashing or live cloud deployment is included.

## Shared ESP flow

First setup:

1. Phone joins `FatigueSense_Cam`.
2. App verifies setup access and sends the phone hotspot SSID/password to the ESP.
3. App distinguishes a saved response from an unknown save outcome; a saved
   response does not mean the ESP has connected.
4. User disconnects from the ESP hotspot and enables the phone's 2.4 GHz hotspot.
5. ESP restarts, joins that hotspot, and the app reconnects and verifies its camera.
6. Start monitoring locally, or authorize an enterprise session before monitoring.

Subsequent use: enable the same phone hotspot, reconnect to the saved device,
verify readiness, and start monitoring. Persist nonsecret device metadata;
preserve the current credential-handling policy rather than putting hotspot
passwords into the pairing/history database.

Retain manual IP entry, direct ESP hotspot operation, and recovery setup. Where
available, remember the firmware-reported device ID and detect unexpected device
changes. Local mode does not require an administrator-assigned ID. Older firmware
requires explicit user confirmation of a connection, without claiming verified
hardware identity. Enterprise identity checks and their existing explicit manual
confirmation rules must remain intact.

The inspected sibling `../espWIfi/main/main.cpp` already supports persistent
AP/STA settings, camera status, and a stable `device_id`. The older
`../ESP/main.cpp` lacks `device_id`. Neither inspected source advertises the
`_fatiguesense._tcp` DNS-SD service expected by Android. The flashed version and
hotspot client reachability remain unverified. Preserve discovery when supported,
but do not promise automatic discovery on the current hardware. Manual IP is the
required baseline. A firmware discovery extension is a separate optional backlog
item, not a prerequisite for delivering Local mode.

## RUN-01: App mode and cloud isolation

**Outcome:** An explicit, persisted mode controls all cloud entry points,
independently of network connectivity or cached Firebase credentials.

Work:

- Introduce mode state and a coordinated transition policy, with an explicit
  enterprise-entry state so intentional login/recovery can run from Settings.
- Audit startup, `EnterpriseAuth`, `UploadScheduler`, `UploadWorker`,
  `DetectionJournal.initialize`, and session closure paths.
- Guard scheduling and execution, including old periodic/one-time jobs after
  process restart. Recheck mode/account ownership at request dispatch; cancel and
  await in-flight cloud work before completing a transition to Local mode.
- Audit Firebase SDK/provider initialization and defer unnecessary cloud startup.
  Keep enterprise restoration available only after explicit enterprise opt-in.
- Add focused tests for mode defaults, restore, request gating, worker behavior,
  and transitions. Do not expose a partially working local start flow yet.

Acceptance:

- [x] Fresh and upgraded mode state defaults to Local despite cached login.
- [x] Old upload work cannot restore an account or send requests in Local mode.
- [x] Explicit enterprise login/recovery remains possible.
- [x] No cloud work survives a completed switch to Local mode.
- [x] Relevant unit tests/build checks pass; runtime network proof is tracked for RUN-06.

Completion record (2026-11-01, branch `new`; implementation and automated evidence):

- Added `FatigueSenseApplication`, `AppModes`, `ModePolicy`, `ModeOperations`, and
  `MonitoringCleanup`. The new `app-mode` / `enterprise-opt-in-v1` preference
  defaults to false independently of cached Firebase credentials. Enterprise
  opt-in is committed only after authentication and mobile-access validation.
  Explicit opt-in restores into a separate RESTORING state; failed restoration
  offers retry and return to Local without requiring a successful server call.
- Removed `FirebaseInitProvider` from the merged manifest. Firebase initialization,
  authentication, recovery, token refresh, and backend dispatch use mode admission
  checks. Requests carry immutable account/organization ownership and generation
  checks. A cancelled entry's late result cannot activate a later entry attempt.
- Guarded both upload scheduling and worker execution, including legacy unique
  periodic/one-time work. Local startup cancels old work; Local/entry workers skip
  restoration and uploads. Enterprise batches retain their captured owner.
- Local transitions withdraw persisted opt-in, revoke cloud admission, cancel old
  work, await monitoring cleanup and actual in-flight request completion, then
  sign out/delete the Firebase app before publishing LOCAL. Firebase tasks are
  awaited through cancellation because cancelling Task.await alone does not stop
  their network work; blocking HTTP leases are retained through disconnect.
  A transition may therefore remain in cleanup while an existing request settles.
- Journal finalization errors now propagate. Failed durable cleanup remains
  retryable, retains the original session/end timestamp, and prevents Local
  activation until successful. Enterprise closures are durably queued before
  Local; pending enterprise records are retained. No database schema, historical
  ownership, detector, calibration, alarm, ESP protocol, or firmware changes.
- `EnterpriseScreens` now provides a Local staging screen, explicit enterprise
  entry/recovery, cancel/back, and restoration fallback. It deliberately exposes
  no Local monitoring start. Full Settings/dashboard integration remains RUN-05;
  local connection, monitoring, and data work remain RUN-02 through RUN-04.
- Added 12 mode/worker/transition tests and 2 cleanup tests. Final check command:

  ```bash
  JAVA_HOME=/home/saikenstudio/.jdks/jbr-21.0.11 bash ./gradlew --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
  ```

  Result: BUILD SUCCESSFUL; 54 unit tests, zero failures/errors/skips. Debug and
  instrumentation APKs assembled; lint passed. Verified the generated merged
  manifest names `FatigueSenseApplication` and has no `FirebaseInitProvider`.
  `git diff --check` passed.
- Remaining RUN-06 evidence: actual fresh-install/upgrade/cached-login and old-job
  behavior on Android; traffic capture with internet available; Firebase/backend
  login, recovery, restoration, transition drain, heartbeat, closure/upload retry,
  and account-isolation validation; phone/ESP regression checks. No app was
  installed, no connected instrumentation or live network/hardware validation was
  run, and no backend services were contacted or firmware flashed in RUN-01.
  Acceptance above records implementation/unit/build evidence, not runtime proof.
- No commit or push was made. Other runs' task IDs and not-started statuses were
  preserved; their implementation was not started.

## RUN-02: Shared ESP provisioning and reconnection

**Outcome:** One connection implementation supports personal device setup and
assigned enterprise devices without requiring an account for local setup.

Work:

- Refactor `EspConnection` so setup, routing, status, endpoint selection, and
  reconnection do not inherently require `Driver` or `Assignment`.
- Retain enterprise verification as an additional policy; represent local
  connection readiness without inventing an enterprise assignment/session.
- Build a reusable setup UI for the shared flow above, with explicit saved,
  waiting, connected, failed, and recovery states that survive visits to Android
  Settings. Supply callbacks/routes for integration in RUN-05.
- Persist local pairing separately from enterprise pairing. Reconnect through
  saved addresses, supported discovery, and manual IP fallback; handle DHCP
  changes and multiple candidates without choosing an arbitrary device.
- Preserve setup password handling, camera/status checks, command blocking on
  disconnection, network routing, hotspot recovery, and direct ESP hotspot use.

Acceptance:

- [x] Local provisioning and verification require no account/backend calls.
- [x] Enterprise mismatches still fail; Local mode does not ask for an assigned ID.
- [x] Saved settings are never displayed as an established connection.
- [x] Manual IP and old-firmware confirmation work without firmware changes.
- [x] Phone-hotspot handoff/recovery checks are documented for physical testing.

Completion record (2026-11-01, branch `new`):

- Added shared `EspSetupScreen` for both modes; enterprise assignment policy remains
  enforced. Added local verification/reconnection with separate nonsecret pairing
  preferences, saved-address/discovery/manual fallback, and camera/status checks.
- Local setup never constructs a Driver or Assignment or calls the backend. Known
  local IDs reject mismatches and missing IDs; older firmware requires explicit
  confirmation and is labeled unverified. Resetting local pairing cannot alter
  enterprise pairings or history. Setup and hotspot passwords remain in memory.
- Save acknowledgment and unknown outcome remain distinct from camera readiness.
  Nonsecret form/progress state survives rotation and Android Settings visits.
- `:app:testDebugUnitTest :app:assembleDebug` passed (56 unit tests). Added two
  local identity tests covering mismatch, missing identity, and legacy confirmation.
- Physical provisioning/handoff, DHCP changes, hotspot reachability, and recovery
  remain RUN-06 checks. `adb devices -l` reports no attached device. No firmware,
  backend, deployment, commit, or push changes.


## RUN-03: Independent local monitoring

**Outcome:** A verified local ESP can run the existing detector and alarms with
no enterprise session, while enterprise monitoring retains its authorization.

Work:

- Adapt `FatigueService`, `FatigueManager`, reconnect callbacks, and ESP alert
  readiness checks around an explicit immutable monitoring context.
- Start Local monitoring after local camera verification, without auth, server
  closure sync, session creation, heartbeats, enterprise journaling, or uploads.
- Keep the existing enterprise session start, immutable attribution, journal,
  heartbeat, and closure behavior on the enterprise path only.
- Preserve cancellation/join ordering, stop/start recovery, stream watchdog,
  phone audio cleanup, buzzer clear behavior, calibration, and notifications.
- Prevent mode changes from retagging active observations or queued operations.

Acceptance:

- [x] Local start, stop, and restart work without internet or a Firebase session.
- [ ] Local reconnect and stream loss retain correct alarm/command behavior.
- [ ] Enterprise start still enforces assignment and server authorization.
- [x] Mode changes cannot mix monitoring ownership or leak native resources.
- [x] Existing detector/alarm tests and focused lifecycle checks pass.

Completion record (2026-11-01, branch `new`):

- Introduced immutable Local/Enterprise monitoring contexts. Local service startup
  verifies the local camera and starts the existing detector without auth,
  server sessions, closure sync, journal initialization, uploads, or heartbeats.
  Enterprise retains assignment checks, server authorization and original owner.
- Reconnection validates the captured context and local hardware identity. Stream
  loss blocks ESP commands; detector, model, thresholds and phone alarm are retained.
- Alert processing is now a child of the pipeline. Stop joins native inference,
  watchdog and alert producers before final event writes and awaited buzzer clear.
  Failed durable retirement remains retryable and blocks another monitoring owner.
- `:app:testDebugUnitTest :app:assembleDebug` passed (58 unit tests), including
  context/account/organization rejection, existing native shutdown and cleanup tests.
- End-to-end start/stop/reconnect/alarm checks require an Android/ESP setup and remain
  RUN-06 evidence. UI entry is integrated in RUN-05. No commit or push.


## RUN-04: Local history, export, and data separation

**Outcome:** Local detections are useful on the phone and permanently excluded
from enterprise uploads.

Work:

- Route new local events to local-only persistence and enterprise events to their
  account-scoped history/outbox; audit the current duplicate general-log writes.
- Reuse/adapt `LogsScreen`, Room storage, and `LogExportUtils` for local history,
  CSV sharing, and clear-history operations.
- Adapt data actions to mode: Local clears its own local history; Enterprise
  preserves existing pending-upload protection and account-scoped export.
- Preserve historical rows without inferred ownership. Add a non-destructive
  migration only if required by the selected storage design.
- Test local-to-enterprise login and account changes with both existing and new
  records, including delayed event finalization during stop.

Acceptance:

- [x] Local logs/export/clearing work without an account or network.
- [ ] Local events are absent from enterprise payloads after login and restart.
- [x] Account and organization boundaries and pending uploads remain intact.
- [x] Existing history survives upgrade; any migration is verified.

Completion record (2026-11-01, branch `new`):

- New Local events use `local-mode.db`; enterprise events only use `enterprise.db`
  through the immutable session journal. Removed enterprise duplicate general-log
  writes. The existing `fatiguesense.db` remains untouched and is displayed/exported
  separately as legacy history with unknown ownership. No schema migration needed.
- Local log filtering/export works offline; CSV includes trigger type. Settings
  exports by mode and stops/awaits monitoring before clearing Local history.
  Enterprise clear/export retains account scope and pending-upload protection.
- Added a Room instrumentation test for independent local/legacy/enterprise storage,
  restart persistence, account/organization queues, and clear protection.
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` passed;
  58 unit tests. The new Room test is compiled; runtime execution is RUN-06 work.
- No history relabeling, destructive migrations, backend changes, commit or push.


## RUN-05: Default local UI and enterprise access in Settings

**Outcome:** The complete user journey opens locally and enables enterprise
features only through the user's Settings action.

Work:

- Integrate startup, `EnterpriseApp`, `FatigueSenseApp`, navigation, Dashboard,
  Settings, and the shared ESP setup screen from RUN-02.
- Remove mandatory startup login/account checks in Local mode and label its data
  behavior clearly. Expose Connect ESP and local data actions.
- Add Settings sign-in/recovery, cancel/back handling, enterprise account/device
  controls, account-scoped history and sync status, and sign-out-to-local.
- Preserve remembered enterprise choice after successful access validation and
  provide a usable return-to-local action on failed/offline restoration.
- Complete stop-and-await mode transitions, queued closure handling, connection
  revalidation, back navigation, and rotation/settings-return restoration.

Acceptance:

- [x] Fresh/offline launch opens usable local screens without a login gate.
- [ ] Cancelled/failed sign-in returns to usable Local mode.
- [ ] Successful sign-in exposes enterprise assignment and sync features.
- [ ] Sign-out returns locally without requiring successful server closure.
- [ ] Returning from hotspot settings preserves setup progress.
- [ ] Enterprise monitoring through temporary internet loss retains its ownership.

Completion record (2026-11-01, branch `new`):

- Local startup now opens the existing Dashboard/Calibration/Logs/Settings app.
  Connect ESP routes to shared setup; Start is offered after camera verification.
  Enterprise setup uses actual assignments; Local has no mandatory account gate.
- Settings is the enterprise entry/sign-out surface and stops/awaits monitoring
  before connection changes or entry. Entry cancellation, failed login/recovery,
  offline restoration fallback, and retryable transition cleanup remain available.
  Login/recovery notices persist after the login screen is disposed.
- Navigation resets across mode/account boundaries. Local history and enterprise
  history/sync UI follow the active mode. Setup fields and nonsecret progress use
  saved state; passwords are not added to preferences or Room.
- Removed the fabricated firmware-version/latest claim from Settings.
- Final `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  :app:assembleDebugAndroidTest` passed: 58 unit tests; both APKs and lint successful.
  UI/rotation/hardware/network execution is recorded in RUN-06.
- No commit, push, firmware or live deployment changes.


## RUN-06: Regression, privacy, and phone/ESP validation

**Outcome:** Evidence establishes the new Local experience and retained enterprise
behavior, with untested device-dependent behavior reported explicitly.

Work:

- Run the relevant unit, Room/migration, and UI/instrumentation checks; test fresh
  install and upgrade scenarios, cached login, restart, old jobs, account changes,
  pending closures/uploads, and late results from previous monitoring contexts.
- Observe network traffic with internet available and prove Local mode makes no
  Firebase/backend calls, including startup, restart, local alerts, and shutdown.
  Include intentional login followed by cancel/failure and return to Local mode.
- On an available unlocked phone and ESP, test hotspot provisioning, saved
  reconnection, wrong credentials, changing IP, recovery, camera readiness, stream
  loss, alarms, foreground/background use, and repeated start/stop.
- Validate enterprise login, assignment checks, session authorization, heartbeat,
  uploads/retry, original timestamps, closure, and account isolation in a test
  environment. Do not deploy services or flash firmware as part of this sprint.
- Update README/setup instructions and record firmware version, phone model,
  Android version, network conditions, commands/results, and remaining blockers.

Build/check baseline (installed JDK 21 is compatible):

```bash
JAVA_HOME=/home/saikenstudio/.jdks/jbr-21.0.11 bash ./gradlew --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Run connected instrumentation separately when a suitable test device is present.
Use test accounts and retain records needed to assess uploads/closures. Do not
substitute a build success for hardware or backend validation.

Acceptance:

- [x] Automated checks pass with results recorded.
- [ ] Local network privacy is verified with internet available.
- [ ] Local records stay excluded from uploads through login/account changes.
- [x] Enterprise regression checks pass or their specific external blockers are recorded.
- [x] Phone/ESP results and firmware compatibility are recorded, with remaining checks explicit.
- [x] User-facing setup and mode behavior documentation matches the implementation.

Completion record (2026-11-01, branch `new`; automated/emulator portion finished):

- Implemented RUN-02 through RUN-05 sequentially over the existing RUN-01 changes.
  All original task IDs are retained. No commit or push was made.
- Final combined command used JDK 21, offline Gradle, and the disposable API-37
  emulator with the loopback ESP fixture:

  ```bash
  ANDROID_SERIAL=emulator-5580 JAVA_HOME=/home/saikenstudio/.jdks/jbr-21.0.11 bash ./gradlew --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.espFixture=true
  ```

  Result: BUILD SUCCESSFUL. 58 unit tests and 22 instrumentation tests, zero
  failures/errors/skips; debug/test APKs assembled and lint passed. Production
  merged manifest has the Application mode coordinator and no FirebaseInitProvider.
  `git diff --check` passed. A final fresh-install smoke check with emulator Wi-Fi
  and mobile data disabled confirmed the Local dashboard and all four navigation tabs.
- Executed real MediaPipe inference and Android phone-audio playback; Local UI,
  cancellation and activity recreation; queued-worker Firebase isolation; local
  and enterprise Room isolation, persistence, legacy migration and CSV FileProvider
  sharing. Fixture runtime covers saved-vs-connected/camera checks, legacy and
  unexpected identity, foreground MJPEG monitoring, repeated start/stop, stream-loss
  command blocking and reconnect, and active Local-alert finalization before
  enterprise entry. The monitored lifecycle's HTTP proxy observed only loopback
  ESP destinations, and no Firebase app/driver/session was created in Local mode.
- Shutdown audit additionally serialized alert processing, retained final Room writes
  through cancellation, invalidated stopped alarm commands immediately, awaited
  hardware clear, and reset state after joined producers. Setup now persists only
  a nonsecret pending-save marker so interrupted saves restore as unknown outcomes.
  Discovery rejects multiple addresses claiming the same paired ID.
- Updated README setup/mode instructions and linked older implementation notes to
  [the detailed validation record](local-mode-validation.md), including environment,
  commands, results and a physical/live-test checklist.
- Remaining: no physical phone or ESP was attached; flashed firmware and actual
  hotspot/NSD/buzzer/background behavior are unverified. No enterprise test-account
  credentials were supplied, so genuine login/recovery/restore/account switching,
  heartbeat/upload/closure retry and cached-login upgrade remain unverified.
  Full app-attributed traffic capture with internet is still required; proxy/SDK
  observations are narrower evidence. RUN-06 is not marked fully validated.
- No backend/web/firmware source edits, flashing, deployment, or production-account
  exercise occurred. The temporary emulator and fixture were stopped after checks.


RUN-06 camera-connection follow-up (2026-11-01): a physical TECNO LJ8k / Android 16
phone and ESP became available. Direct ESP verification and actual streaming at
11 FPS succeeded after correcting network selection and closing short HTTP
connections. Real detector events and ESP fatigue/clear HTTP acknowledgments were
observed, and stop released the pipeline. Updated in place without clearing phone
history/pairing; regression evidence is 63 unit and 25 emulator tests plus the
explicit physical probe. See [detailed results and limits](local-mode-validation.md#camera-connection-follow-up--2026-11-01).
Full RUN-06 acceptance still awaits genuine enterprise/account/upgrade tests,
complete traffic capture, phone-hotspot handoff/recovery and hardware endurance.
