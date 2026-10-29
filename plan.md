# FatigueSenseEnterprise implementation plan

Date: 2026-10-17. Implementation update: 2026-10-18.

Status update, 2026-10-29: the user authorized the company-to-driver-to-dashboard flow across Android, `sentry-drive-backend` and `sentry-drive-web`. That source integration is now implemented, preserving driver-only Android access and existing staff permissions. See [current setup and validation](docs/driver-integration.md). No live Firebase deployment or ESP firmware change occurred. The remaining sections record the original plan and historical scope decisions; they are not a claim that all planned reporting, custom rules or firmware work is complete.

## 1. Goal and boundaries

Create a separate enterprise Android application named **FatigueSenseEnterprise**, based on the existing FatigueSense Android app. Add individual Firebase login and reliable reporting of Alert Detection and Yawn Detection triggers so organization administrators can identify which users trigger alarms frequently.

Integrate ESP Wi-Fi provisioning into the enterprise app: configure the ESP to join the phone's hotspot, switch between ESP hotspot and saved-network modes, and reconnect streaming and alarm controls after the address changes. Use the selected Wi-Fi setup approach; BLE is not part of this feature.

Confirmed reporting rules: flag a driver as **Drowsy** after at least 3 distinct EAR detections within 1 minute, **Yawning constantly** after at least 6 distinct yawn detections within 10 minutes, and **No face detected** after 3 continuous minutes without a detected face during valid monitoring. Retain original event times during offline batch upload so historical episodes are flagged correctly after reconnection. These are monitoring labels, not medical diagnoses.

- Original app, read-only reference: `C:\Users\RJ\StudioProjects\FatigueSense-Thesis`.
- Enterprise project: `C:\RJ\Coding\FatigueSenseEnterprise`.
- Existing website: `C:\RJ\Coding\FatigueSenseWebsite\sentry-drive-web`.
- Preserve the original Android source, Git history, configuration, models, signing material, and installed application. All Android implementation will occur in the separate enterprise project.
- Preserve the local app's monitoring, calibration, models, settings, dashboard, logs, CSV export, foreground service, and ESP32 alarm behavior. Cloud availability must not control whether an already-running local detector can sound its alarm.
- Camera frames stay on the local ESP-to-Android connection for on-device detection. The website and administrators receive detection logs, timestamps, derived flags, counts, and connection/sync status only. Do not upload images/video, expose the ESP stream to the internet, or add a web camera viewer. References to live telemetry in this plan mean numerical/status updates, never a live camera feed.
- Website, backend, and firmware extensions are future integration dependencies; current implementation is limited to the enterprise Android project.

## 2. What the existing projects actually implement

### Website architecture

The website is React 19 with Vite, JavaScript modules, feature folders, shared layouts, and repositories for connected data. The enterprise app should reuse its identity model, tenant boundaries, data contracts, and backend conventions while retaining native Kotlin/Compose UI.

| Concern | Verified architecture and implementation status |
| --- | --- |
| Authentication | Firebase email/password login, recovery, logout, token observation, and access refresh exist in `src/features/auth/`. |
| Identity | Server-issued `{ orgId, role }` claims; current accepted staff roles are `admin`, `supervisor`, and `dispatcher`. Backend membership activation is required after login. |
| Persistent data | Firestore under `/organizations/{orgId}/...`; connected vehicle registry and staff operations exist. |
| Trusted backend | The accepted 2026-10-09 decision in `requirements.md` uses a separately hosted Node backend, with Render for testing, and real Firebase on the free plan. This supersedes the earlier Cloud Functions/Blaze prescription. |
| HTTP convention | `/api/<action>`, bearer Firebase ID token, request body `{ data: ... }`, and a `result` or `error` response. Organization and role come from verified tokens. |
| Live monitoring | Intended RTDB paths are `/fleet/{orgId}/{vehicleId}/...`. Connected live telemetry is not implemented in the inspected frontend. |
| Detection history | Intended Firestore collection is `/organizations/{orgId}/fatigue_events`. Connected history and frequency reporting are not implemented. Demo fixtures are not live data. |
| Connected routes | `ConnectedApp.jsx` currently connects account, vehicle registry, and staff views; other pages show an unavailable notice. |
| Backend availability | The user supplied sibling `sentry-drive-backend` on 2026-10-18. Inspected HTTP envelopes, role gate, event ingestion and alert-rule schemas. Existing ingestion is vehicle/gateway-based; driver roles, mobile sessions, revisioned batches and no-face reporting need extensions. Existing EAR windows are fixed at five minutes. Deployment and real Firebase settings remain unverified. See `docs/integration-contract.md`. |

Existing stable requirements to retain: FR-1.1–1.6 (authentication/tenancy), FR-2.1–2.4 (vehicles/presence), FR-3.1–3.4 (telemetry/acknowledgment), FR-4.1–4.3 (web thresholds), FR-5.1–5.3 (history/export), FR-6.1–6.11 (onboarding), NFR-2.1–2.2 (sync/bandwidth), NFR-3.2 (heartbeat), and NFR-4.2–4.6 (identity/access).

### Original Android app

- Native Kotlin, Jetpack Compose, Compose Navigation, a foreground `FatigueService`, Room, local ML inference, and ESP32 streaming/alarm control.
- Current namespace and application ID: `com.example.fatiguesense`; minimum SDK 24 and target/compile SDK 36 in the inspected build file.
- `FatigueManager.kt` detects alert/yawn transitions, updates session counters, sends ESP32 commands, and persists completed events through `persistAndEmitEvent`.
- Eye-closure events use `EAR` / `FATIGUE_ALERT`; individual yawn events use `MAR` / `WARNING`. There is also separate repeated-yawn alarm escalation logic. Its existence must not be mistaken for a separately persisted event in every case.
- `FatigueEventEntity.kt` stores local ID, start timestamp, duration in milliseconds, EAR, severity, and trigger type. It currently lacks authenticated user identity, organization, vehicle/session identity, MAR, a globally stable event ID, and upload state.
- `FatigueDatabase.kt` is Room schema version 2. Existing completed-event persistence happens when a trigger clears, including normal stop handling; durable trigger-start tracking is needed to preserve a trigger across a process crash.
- ESP32 connections use a selected Wi-Fi network that may have no internet. Cloud connectivity must be tested alongside ESP32 Wi-Fi and mobile data.
- The Android README describes older UI scaffolding; implementation files are the stronger evidence for current functionality.

## 3. Target architecture

```text
ESP32 camera / alarm hardware
            ↕ local Wi-Fi
FatigueSenseEnterprise Android app
  ├─ Firebase Authentication → individual user + server-issued claims
  ├─ ESP Wi-Fi setup / connection state / shared current ESP endpoint
  ├─ Existing detector / foreground service / local alarm control
  ├─ Room event journal + durable upload queue
  └─ Authenticated HTTPS → existing Node backend, extended for mobile ingestion
                              ├─ Firestore: authoritative event history / sessions
                              └─ RTDB: current vehicle telemetry / presence
                                           ↓
                             SentryDrive connected admin views
                             User identity, history, counts, frequent triggers
```

The app sends data to shared Firebase-backed services; it does not post data to a browser page. Register the enterprise Android application in the same Firebase project used by the corresponding website environment, with its own Android registration and application ID.

For initial provisioning the phone joins the ESP access point. For the intended operating arrangement, the ESP joins the phone's 2.4 GHz hotspot, the app accesses the ESP locally, and the phone uses its internet connection for Firebase/backend traffic. Actual hotspot client reachability and simultaneous cloud access require hardware verification; they are not established by a successful build.

**Recommended writer ownership:** use authenticated backend ingestion for events and telemetry. The backend verifies identity and assignment, writes Firestore through the Admin SDK, and projects current telemetry into RTDB. This fits the accepted standalone backend and centralizes attribution, validation, deduplication, and gateway revocation.

**Alternative:** direct Android Firestore/RTDB writes can reduce backend dependence, but require a complete gateway-specific security policy, assignment/revocation enforcement, and consistent duplicate handling across both stores. The website's starter rules are explicitly incomplete and must not be enabled unchanged. Use backend ingestion as the planning default; inspect the actual backend before finalizing contracts.

## 4. Safe cloning and enterprise identity

Perform this phase only when implementation is requested:

1. Record the original app's commit and working-tree status. Inventory source and required assets, including models, before copying.
2. Create an independent checkout/source copy inside `FatigueSenseEnterprise`, preserving this `plan.md`. If the original has uncommitted functional changes, account for them explicitly; a Git clone alone will not include them.
3. Copy source, resources, required ML assets, Gradle wrapper, and build definitions. Exclude build outputs, `.gradle`, `.kotlin`, `.idea`, machine-specific `local.properties`, credentials, and signing keystores. Do not blindly copy everything or overwrite the original.
4. Use independent version control for the enterprise project; do not copy the original `.git` directory. Record the source baseline for future selective updates.
5. Set project/display name to `FatigueSenseEnterprise`. Proposed application ID/namespace: `com.example.fatiguesense.enterprise`; confirm the final distributable identifier before Firebase registration.
6. Update enterprise-only package references, app label, icons as needed, provider authorities, build configuration, and release signing configuration. Use an enterprise Firebase Android registration matching the final application ID. Never ship an Admin SDK key or server credential in the APK.
7. Build and install both apps side by side. Verify separate storage, preferences, providers, and package identity. Do not automatically import historical logs from the original app or assign them to the newly logged-in user.
8. Compare the original source/status against the recorded baseline to confirm no original-app changes.

## 5. Authentication, driver accounts, and assignment

The website's present staff roles are not a driver authorization model. A free-text vehicle `driverName` is also not proof of which Firebase user generated an event.

Approved extension: introduce a least-privilege `driver` role with server-issued `{ orgId, role: 'driver' }` claims, organization membership, and server-managed vehicle assignments. The user approved administrator-created/invited driver accounts and assignment to the vehicle/ESP. This is an accepted requirement for future implementation, not existing functionality. Drivers may use the mobile app and submit their own assigned-session data; they do not gain staff management, fleet-wide reads, threshold edits, or acknowledgment permissions.

- Extend trusted onboarding for admin-created driver accounts using the existing password-setup/recovery approach. No public signup, shared fleet passwords, client-created claims, or default passwords. Keep existing staff permissions unchanged; supervisors do not gain driver-management privileges implicitly.
- Keep the web operator sign-in guard restricted to operator roles. Driver authentication must not accidentally expose the operator portal. Update account-management validation/rules deliberately to support driver records.
- Login sequence: email/password → Firebase authentication → token claims → backend membership/access check → authorized vehicle assignment → ESP connection/setup when needed → existing local application screens. Complete online identity/assignment validation before temporarily joining the ESP's no-internet hotspot; returning from Android settings must preserve that identity and setup progress.
- Handle loading, incorrect credentials, offline login, missing claims, pending activation, missing assignment, disabled membership, token expiration, and revoked sessions. A first login and a new authorized monitoring session require online validation under the proposed policy.
- Allow an already-authorized monitoring session to continue local detection through transient internet loss, retaining its immutable attribution. Pause cloud upload on authorization failure and require reauthentication before a new enterprise session. Define the permitted offline session duration before release.
- Give each monitoring session a stable `sessionId`, bound by the backend to UID, organization, vehicle, ESP32 device, and a revocable gateway/installation binding. Validate assignment server-side; an entered device ID or selected vehicle is not sufficient authorization.
- Default to one active monitoring session per vehicle; enforce this server-side. Define how an administrator ends/replaces a stale session.
- Logout or account switching must close the current monitoring session and finalize its local events before replacing identity. Clear user-scoped views and cancel upload work for that account. Retain unsent events under their original UID/org/session; never upload them using the next user's token.
- Reuse the existing `revokeGateway` concept after inspecting its actual server implementation. Unbinding, archiving, removal, or revocation must prevent new unauthorized telemetry without deleting historical events.

## 6. Detection event contract and counting semantics

Plan a versioned backend event schema while retaining the website's existing event fields:

| Field | Proposed meaning / authority |
| --- | --- |
| `eventId` | Stable client-generated UUID at trigger start; reused for retries and completion updates. Backend uniqueness includes organization and submitting UID. |
| `schemaVersion` | Payload version for explicit validation and compatibility. |
| `userId` | Firebase UID, stamped from the verified caller and checked against session ownership. |
| `userDisplayName` | Optional historical display snapshot from trusted membership; UID remains the grouping key. |
| `vehicleId`, `deviceId`, `sessionId` | Validated against the server-managed session and assignment. |
| `timestamp` | Trigger start time, sent as UTC epoch milliseconds and persisted as a Firestore timestamp. |
| `receivedAt` | Server time when the backend accepted the event; distinguishes delayed uploads from new activity. |
| `type` | Existing `Eye Closure Alert` / `Yawn Warning`, plus the required `No Face Detected` extension. Update web/backend validation and filters accordingly. |
| `triggerType` | `EAR`, `MAR`, or new `NO_FACE`; repeated-yawn escalation represented separately from individual yawn counts. |
| `severity` | Validated mapping from local detection semantics; web frequency warnings remain a separate calculation. |
| `EAR`, `MAR` | Finite measured values captured at trigger start; unknown values are null, never fabricated zeroes. Add MAR capture in the enterprise model. |
| `duration` | Seconds for the website/history/CSV contract; convert from local milliseconds explicitly. Null while open or when final duration is unknown. |
| `status`, `revision` | Open/completed/interrupted state and monotonic revision; stale retries cannot overwrite completion. |
| `alarmKind` | Optional indication of eye-closure, single-yawn, or repeated-yawn escalation command; does not claim confirmed hardware actuation unless supported by acknowledgment. |

Counting rules:

- One eye-closure trigger onset equals one Alert Detection event. One individual yawn onset equals one Yawn Detection event. A sustained trigger spanning many frames is one event.
- Persist the start immediately, then complete the same event when it clears. Recovery after a crash marks an unfinished event interrupted with an unknown end, rather than silently losing it or inventing a duration.
- Repeated-yawn escalation must not add another individual yawn or eye-closure count. The requested report counts detections and the flags below; a separate hardware-command frequency report is not required. Preserve existing local alarm behavior.
- Upload retries, duplicate network responses, and completion revisions must not increment frequency counts again.
- Retain occurrence time and receipt time. Validate clock skew and invalid/future timestamps; delayed history must not create a current live alarm merely because it arrived now.

### 6.1. Confirmed driver flag rules and offline evaluation

| Flag | Confirmed condition | Evidence |
| --- | --- | --- |
| Drowsy | At least 3 distinct EAR trigger onsets within a rolling 60 seconds | Original event IDs and occurrence times |
| Yawning constantly | At least 6 distinct individual yawn onsets within a rolling 600 seconds | Original event IDs and occurrence times |
| No face detected | No face for at least 180 continuous seconds while valid camera frames are being analyzed | Start, threshold-crossing, and end/recovery time of the no-face interval |

- Group evaluation by organization, authenticated driver UID, and monitoring session. Never combine different drivers or unrelated sessions. Use inclusive threshold boundaries: exactly 3 events spanning 60 seconds, 6 spanning 600 seconds, or 180 seconds of continuous absence qualify.
- Evaluate the timeline of original detection timestamps, not upload arrival times. Example: EAR events at 10:00:00, 10:00:20, and 10:00:50 uploaded at 10:30 flag a drowsy episode at 10:00:50. EAR events spread over ten minutes do not qualify merely because they arrive in one batch.
- Re-evaluate affected time windows when out-of-order or split batches arrive, including earlier stored events on both sides of a batch boundary. Deduplicate event IDs before counting; a completion revision is not another trigger.
- Persist derived flag episodes under the organization (proposed `/organizations/{orgId}/driver_flags/{flagId}`), with driver/session/vehicle, rule identifier and version, actual threshold/window used, original occurrence/qualification time, evidence references, and server detection time. These are new reporting records, separate from raw detections.
- Make flag creation/update idempotent. A continuous breach updates an existing episode rather than adding one flag per frame, retry, or overlapping window. Define episode recovery when the rolling count drops below its threshold; a later new breach can create a new episode. Historical views retain past episodes after recovery.
- The backend evaluates durable history after ingestion and records the authoritative historical result. The website uses the same versioned rules for its live display; retain web-side visual evaluation and do not replace it with an unrelated second threshold policy.
- On the website, show delayed flags at their actual occurrence times with a delayed-upload indicator. A past drowsy episode remains visible in driver history; it must not claim the driver is currently drowsy at upload time. Current status requires fresh telemetry and a currently qualifying window.
- Add a no-face interval tracker to the enterprise app. Start it when valid analyzed frames contain no face, reset it when a face returns, and emit one durable qualifying event at 180 seconds with a later completion update. Preserve evidence across ordinary backgrounding while monitoring continues.
- Camera disconnection, failed inference, restart, deliberate pause, stopped monitoring, or missing frames are monitoring-unavailable conditions, not proof of no face. Interrupt the interval when observation is unavailable; do not fill an unobserved gap with assumed no-face time. Use monotonic elapsed time for the local duration and retain UTC timestamps for reporting.
- Do not inflate eye/yawn totals with no-face events or derived flags. Extend history filters and display missing EAR/MAR as empty/null. Keep the existing CSV column contract while supporting the new event type.
- These requested values supersede the demo's 5-alert/5-minute and 10-yawn/10-minute defaults for enterprise reporting. Retain the existing organization-scoped vehicle-rule/fleet-default locations and manager permissions; extend their schema for the 60-second EAR and 180-second no-face rules. Persist rule versions so delayed evaluation uses the policy applicable at the event time. No arbitrary historical backdating of rule edits.
- This requirement adds reporting flags. It does not authorize changing the original app or the ESP's existing local buzzer thresholds.

## 7. Offline persistence and synchronization

- Add an enterprise Room migration for identity fields, stable event IDs, event lifecycle, and an upload outbox. Persist event changes and corresponding queue entries atomically. Do not use destructive migration for existing enterprise data.
- Keep cloud operations off the inference/alarm execution path. Use background retry work for durable uploads, with connectivity constraints, bounded batches, exponential backoff, and per-record success acknowledgment.
- Only mark a queued revision uploaded after server confirmation. Support partial batch acceptance and retries after an ambiguous timeout. Permanently invalid records remain visible as failed; they are not retried forever.
- Offline detections and qualifying no-face intervals must upload automatically in bounded batches after connectivity and authorized access return, retaining every original timestamp, duration, UID, and session. Evaluate the rules in Section 6.1 even when the monitoring session has already ended; normal session closure must not reject legitimate backlog. Revoked or invalid access remains a separate authorization condition, not a reason to change ownership.
- Backend idempotency must transactionally protect event creation and any summary increments. Repeating the same event/revision returns the accepted result; conflicting ownership or content is rejected.
- Room queue survives process death and device restart. Show pending count, last successful sync, and actionable failure status in the app.
- **Confirmed local device storage:** store the paired ESP ID in the enterprise Android Room database, not just memory or a last-IP preference. Proposed device record: `espId`, `orgId`, `vehicleId`, display label, `lastKnownIp`, connection mode, `pairedAt`, and `lastVerifiedAt`. Keep driver authorization/assignment separate from device identity; a locally saved record does not grant access to another organization or user. Do not store Wi-Fi passwords in this record.
- Persist the ESP ID after pairing and assignment validation. On reconnect, compare the device's reported ID with the saved ID before attaching its stream/controls, then update only the verified address/status. DHCP changes must not replace the stored ESP ID. An explicit authorized re-pair operation is required to replace the device; retain the old ID on historical events. Include the ID in detection logs and retain the existing server-side assignment checks.
- Partition queue processing by UID and organization. A previous user's unsent logs stay quarantined until that identity can validly resume; never rewrite ownership or silently discard them on logout.
- Local log clearing must not delete pending uploads or cloud audit history. Define explicit retention and disk-pressure behavior; preserve unsent records and show storage failures.
- Use the active foreground monitoring service for the normal 30-second telemetry schedule, within the website's 30–60-second contract. Background retry work is not a promise of precise 30-second scheduling.
- Test ESP32 Wi-Fi without internet alongside mobile internet. Keep ESP32 network selection scoped to local hardware traffic; avoid binding all cloud requests to the ESP32 access point.

### 7.1. ESP Wi-Fi provisioning and connection switching

#### Reference implementation and evidence

Adapt the standalone test implementation into the enterprise app's Kotlin/Compose architecture; do not substitute the Java test app for the enterprise clone.

- Firmware reference: `C:\RJ\Coding\espWIFI\main\main.cpp` and `main\wifi_setup.html`; project instructions are in `C:\RJ\Coding\espWIFI\README.md`.
- Android reference: `C:\RJ\Coding\espWIFI\android-app`, particularly `MainActivity.java`, `EspClient.java`, `EspClientTest.java`, and its README.
- The handoff reports successful firmware/APK builds, seven passing client/validation tests, lint with zero errors and five warnings, and installation on a Samsung Galaxy A55. These results were reported by the handoff, not independently rerun for this plan.
- Firmware flashing, successful provisioning, actual client-mode connectivity, and streaming over the phone hotspot remain unverified. No Enterprise integration exists yet.
- The reference firmware targets ESP32-S3 with the reference camera pin mapping, 16 MB flash, and octal PSRAM. Confirm the actual board before any future flash. Preserve `C:\RJ\Coding\newESP` and the original Android app.
- The test app's minimum API is 26 while the original Android app supports API 24. Audit reused APIs and provide compatibility handling; do not silently raise the enterprise minimum SDK by copying the test project's build configuration.

#### Enterprise setup flow

Expose setup from the enterprise device-connection screen and Settings, after account/vehicle access is established. A returning user with a reachable configured ESP can proceed through connection verification without re-entering credentials.

1. Guide the user to join `FatigueSense_Cam` (reference hotspot password: `safedriving`) and stay connected despite Android's no-internet warning.
2. Contact `192.168.4.1` and read `/api/wifi`. Show current mode and camera status; do not infer readiness from `/health` alone.
3. Ask for the **phone hotspot's** SSID/password, distinct from the ESP setup password. Preserve spaces and punctuation. The app does not read the phone's hotspot password automatically.
4. Send the selected settings to the ESP and distinguish a confirmed save from an ambiguous network failure. A successful save schedules a restart in about two seconds.
5. After a confirmed save, guide the user into Android settings to enable their hotspot at 2.4 GHz, initially using WPA2-Personal. Enabling the hotspot is manual; no automatic hotspot activation is promised.
6. Show that the ESP is restarting/waiting for the hotspot. The ESP retries if the hotspot is not ready. Return from settings without losing setup progress or displaying stale connection success.
7. Obtain the current address using the approved sequence: try the last verified IP, discover through mDNS/DNS-SD using Android NSD, then offer manual IP entry if necessary. Verify the saved ESP ID before accepting any candidate. The reference DHCP hostname `fatiguesense-cam` alone is not discovery; add firmware advertising and Android discovery during implementation because neither exists in the reference app.
8. Contact the ESP at the new address, require `/api/wifi` to report `connected: true` with a client IP for the client-mode success state, check `camera_ready`, and verify stream/control reachability before presenting monitoring as ready.
9. Remember non-secret connection preferences such as last IP and SSID. The ESP persists credentials and selected mode across power cycles, so repeat provisioning only when necessary. Revalidate a cached address because DHCP can change it.

Use explicit states such as contacting ESP, saving, save outcome unknown, saved/restarting, waiting for hotspot, awaiting IP, verifying, connected/camera unavailable, ready, and recovery/error. **SAVED is not CONNECTED**, and a reachable ESP is not necessarily camera-ready.

#### Firmware contract to preserve

Settings use port 80: `GET /wifi` serves the browser setup page, `GET /api/wifi` reads status without returning the Wi-Fi password, and `POST /api/wifi` saves settings and restarts. The enterprise screen should use this same API directly; the browser page remains a fallback. This preserves the chosen Wi-Fi provisioning approach without requiring BLE.

Settings authentication is HTTP Basic with username `admin` and the ESP setup password (currently the reference hotspot password). POST headers are `Content-Type: application/json`, `X-Requested-With: FatigueSense`, and `Authorization: Basic <encoded setup credentials>`.

| Operation | Request body |
| --- | --- |
| Save and join a new network | `{"mode":"sta","ssid":"Phone hotspot name","password":"phone-hotspot-password"}` |
| Reuse saved credentials | `{"mode":"sta"}` |
| Switch to ESP hotspot, retain credentials | `{"mode":"ap"}` |
| Forget saved credentials and use ESP hotspot | `{"mode":"ap","forget":true}` |

- Save response: `{"saved":true,"restart_seconds":2}`. It confirms persistence, not a successful network join.
- Status fields: `mode`, `ssid`, `connected`, `ip`, `hotspot_active`, `camera_ready`, `restarting`, and `disconnect_reason`.
- SSID validation: 1–32 UTF-8 bytes. Password: empty for an open network, 8–63 bytes, or 64 hexadecimal digits for a raw PSK. Validate bytes rather than character count and avoid trimming meaningful whitespace.
- In `ap` mode, a false client `connected` value is expected and must not block otherwise working AP-mode monitoring. Apply connection checks according to the requested mode.
- After roughly 30 seconds without a client IP, the ESP enables its recovery hotspot while continuing saved-network attempts. Retries occur with a five-second delay and stalled connection/DHCP attempts restart after 30 seconds.
- The recovery hotspot stays enabled for that boot even if the ESP later joins the target network. Selecting saved Wi-Fi again restarts into client-only mode; simultaneous hotspot activity does not itself mean client connection failed.

#### Shared endpoint, routing, and monitoring continuity

Approved approach: mDNS/DNS-SD with Android NSD, preceded by a saved-IP check and followed by manual entry as fallback. The other approaches below are alternatives for reference, not additional implementation requirements. No discovery code has been implemented yet.

| Method | Proposed operation | Trade-off |
| --- | --- | --- |
| mDNS/DNS-SD with Android NSD — approved | ESP advertises a named local service and device ID; Android discovers/resolves it, checks the saved ESP ID through its local API, and uses the resolved address/port. | Implement firmware advertising and app discovery. Verify discovery on the phone while it hosts the hotspot, not only when both devices join a router. |
| Custom UDP discovery/announcement | App sends a local discovery query and ESP replies with identity, or ESP announces itself and app captures the sender address. Verify the candidate through the ESP API. | Requires a new protocol on both sides, bounded retries, correct interface/permissions, and real hotspot broadcast/multicast tests. |
| ESP callback to the hotspot phone | Once joined, ESP contacts an app listener at the phone's reachable hotspot address and reports its ID. | Avoids broad searching, but needs gateway/phone address handling, a running app listener, and tested client-to-host reachability. It is a proposed protocol, not an existing firmware endpoint. |
| Bounded local address probing | Try likely addresses on the actual local subnet and verify candidates using the device-ID endpoint. | Slower and more network traffic; a fallback only. Do not assume a fixed hotspot subnet or treat generic `/health` success as device identification. |

Approved connection sequence:

1. Try the Room record's last verified IP with a bounded timeout; check the firmware-reported ESP ID against the paired record.
2. If unreachable or mismatched, use Android NSD to discover the ESP's advertised mDNS/DNS-SD service. Match the saved ID, verify it through the local device API, and update `lastKnownIp` only after verification. Reject unrelated devices rather than selecting the first response.
3. If discovery times out or is unavailable, offer manual IP entry and apply the same identity check. Show a useful retry/recovery state if the address or identity cannot be verified.
4. Reconnect stream and controls through the shared endpoint, then stop discovery work. Repeat the sequence after network changes as needed; do not assume DHCP preserves the address.

Retain manual entry as a prototype fallback. Samsung documents a connected-devices list, but the inspected support page does not establish that every model/One UI version exposes the client IP; verify the Galaxy A55's actual settings before relying on it. A user-visible settings entry also does not establish a supported API for the app to read that list.

Discovery is local and carries no camera footage to the website. Review Android local-network permissions for the actual OS/target SDK and stop discovery/listeners when no longer needed. Firmware currently has no mDNS/discovery implementation, so adding discovery is future integration work, not a config-only switch.

- Introduce one enterprise connection component holding the selected ESP host, mode, route, and verified state. Streaming, alert sender, output tests, status checks, and UI must consume it; remove operational assumptions that the ESP always lives at `192.168.4.1`.
- Preserve MJPEG at `http://<ESP-IP>:81/stream`, the control page at `/`, `/health`, `/alert?type=...&seq=...&outputs=buzzer`, and POST output tests `/red`, `/green`, `/buzzer`, `/all`. The firmware allows one streaming client; stop the old stream before opening the replacement.
- Run setup/status requests off the main thread with bounded timeouts (the reference uses five-second connect/read timeouts). Re-evaluate the network route per request. Use the matching local Wi-Fi network when the phone is an ESP AP client, and normal unbound routing when the phone hosts the hotspot.
- Do not bind the entire process to the ESP network. Firebase/backend traffic must remain independently routed; local provisioning uses ESP credentials, never Firebase bearer tokens. Do not send setup credentials to cloud endpoints.
- Disable redirects on authenticated setup requests. Do not automatically retry a settings POST after a disconnect: the ESP may already have saved/restarted. Reconnect and inspect state before offering a deliberate retry.
- Treat connection switching as a planned monitoring interruption: finish or mark active events interrupted, stop old stream/control work, show detection unavailable during restart, then re-verify before resuming. Do not count the interruption as a fatigue trigger or allow queued commands for an old address to target a new device.
- Preserve the authenticated user's attribution through the switch. Define whether an authorized session resumes or is replaced, without resetting or double-counting durable events. Heartbeats must not claim active monitoring while the camera is unavailable.
- `/health` can return `ok` when camera initialization failed; inspect `camera_ready` and handle stream HTTP 503 independently from Wi-Fi success.

#### Recovery, identity, and credential handling

- If the target hotspot is unavailable or credentials are wrong, guide the user to wait for recovery, reconnect to `FatigueSense_Cam`, inspect status, and correct credentials. Also expose deliberate actions to reuse saved credentials, return to AP mode, and forget credentials.
- Do not assume every phone reveals client IP addresses or supports the required hotspot routing. Validate the approved saved-IP → mDNS/NSD → manual-entry flow on supported phones, including the Galaxy A55 hosting the hotspot.
- An IP address, SSID, hostname, or setup password does not prove the ESP is the server-assigned hardware. The supplied status contract lacks a stable device identifier. Resolve device identity verification and its mapping to the authorized vehicle before enabling enterprise monitoring; any firmware extension is separate future work.
- A firmware-reported ESP device ID is the interface to plan around. The ESP's station-interface MAC is one possible source for that ID, subject to checking the firmware/board; it is not a requirement to obtain the Android phone's MAC. Use one documented identifier consistently across AP/STA operation, rather than assuming the currently visible interface address is the same in both modes. The app would read the ID from the ESP API and compare it with the registered device. The MAC/device ID identifies a device for matching; it is not a password or proof of authorized access, and it does not replace the local IP needed for HTTP communication. Selection of a MAC-based identifier remains unconfirmed.
- Do not persist hotspot/setup passwords in ordinary preferences, logs, analytics, or cloud event records. Keep reference behavior of remembering only IP/SSID; clear sensitive form state when no longer needed.
- The reference uses Basic authentication over local cleartext HTTP, unencrypted NVS settings, and unauthenticated stream/output endpoints. Treat it as development/test provisioning, not production-hardened enterprise access. Before release, decide device-specific setup credentials, local access protection, credential storage, and how to limit cleartext traffic while supporting dynamic local IPs. Cloud APIs remain HTTPS.
- The user confirms the product is still a prototype. Defer production hardening and deployment choices to a later release review; do not block prototype planning on those choices. Retain ordinary credential hygiene, Firebase authorization, and accurate interruption/reconnection states during prototype implementation. No deployment or production-readiness claim is part of the current scope.

## 8. Backend and Firebase integration work

1. Backend source inspection completed on 2026-10-18; implementation is deferred per the user's app-only scope. The user confirms the website is not hosted yet. Use the documented placeholders in Section 12.1 until real configuration is supplied; hosting is not a prerequisite for preparing code/configuration templates, but placeholders cannot support real sign-in or upload. Complete driver membership, invitation, gateway revocation, rules, and response-contract integration before connected validation.
2. Extend server-authoritative driver onboarding and assignments. Record proposed extensions with new requirement IDs without repurposing existing staff requirements.
3. Add proposed mobile session, event-batch ingestion, and telemetry endpoints under `/api/`, following the existing envelope. Exact action names are to be finalized against the backend.
4. Verify Firebase tokens, revocation, active membership, org claims, session ownership, gateway binding, and assignment on every ingestion. Ignore client-provided authority fields; reject cross-organization or other-user writes.
5. Validate enums, lengths, timestamps, metrics, nonnegative durations/counts, revision transitions, request sizes, and rate limits. Do not trust aggregate counters as historical audit records.
6. Write history to `/organizations/{orgId}/fatigue_events/{eventId}` and session/assignment data beneath the same organization. Define required Firestore indexes for user, vehicle, event type, and time filters.
7. Project validated live state to `/fleet/{orgId}/{vehicleId}/connection_state` and `metrics/{past_5_min,past_10_min,session}`. Include current user/session, last heartbeat, and monotonically ordered telemetry so delayed batches cannot replace a newer session.
   Extend this contract with `metrics.past_1_min` for the requested EAR rule and separate no-face observation/status fields with timestamps. Keep the existing 5/10-minute/session metrics for compatibility; a 5-minute aggregate cannot establish whether three events occurred within one minute.
8. Keep Firestore history authoritative. Firestore and RTDB writes are not a shared transaction: record projection work durably and make retries idempotent, with a reconciliation path for partial failures. Do not rely on an always-running in-memory worker on Render Free.
9. Update and test security rules: clients cannot write claims, roles, authoritative events, or arbitrary telemetry; operators read only their organization; driver access is limited to explicitly authorized personal/assignment data. Admin SDK endpoints enforce authorization themselves.
10. Separate heartbeat freshness from event receipt time. Use the existing 75-second grace-period constant for a 30-second heartbeat cadence, and preserve idle/buzzer/monitoring distinctions. Periodically publish zeroed rolling windows after old events expire.
11. Add idempotent event-time flag evaluation and organization-scoped flag persistence from Section 6.1. Validate no-face interval evidence, support completed-session backlog, and recalculate affected history across batch boundaries without overwriting current telemetry with old state.

## 9. Website integration: identify frequent-trigger users

This is required to deliver the user's outcome; Android uploads alone will not populate the currently unavailable connected pages.

- Connect incident history to organization-scoped Firestore records and live fleet cards to RTDB. Reuse existing feature/repository boundaries and dispose listeners when identity changes.
- Show authenticated driver name plus UID-linked identity, vehicle, event type, occurrence time, duration, EAR/MAR, and sync freshness. Preserve historical identity across vehicle reassignment, account removal, or later profile-name changes.
- Add a user filter and per-user counts for a selected date range: Alert Detection count, Yawn Detection count, total detections, and last trigger. Aggregate by UID across vehicles; do not aggregate by mutable driver name.
- Allow sorting by each count so administrators can see who triggers most frequently. Use a clearly displayed date/time range, defaulting to today in the portal's displayed timezone; query using UTC boundaries. Provide a recent-window view for current supervision.
- Apply the confirmed rules from Section 6.1: 3 EAR detections/1 minute, 6 yawns/10 minutes, and 3 continuous minutes without a face. Keep the website's rule storage, vehicle-over-fleet precedence, role restrictions, and existing session-cap capability; extend metrics and validators instead of treating the previous demo defaults as the requested behavior.
- Add driver flag history with occurrence time, delayed-upload status, evidence, and recovery state. Display no-face separately from drowsiness and connection loss. Allow administrators to find both frequent detections and historical qualifying episodes.
- Keep active-session thresholds scoped to the appropriate driver/session when a vehicle changes drivers. Show historical per-user totals separately from a vehicle's current-session warning.
- Use deduplicated Firestore events for historical reports; RTDB counters are current-state snapshots and cannot replace event history. Start with bounded queries for small datasets, then introduce idempotent per-user/day summaries if measurements require them.
- Retain exact existing CSV columns `timestamp, vehicle_id, event_type, EAR, MAR, duration` and formula protection. Exports honor the user filter; a report with additional user columns requires a separately defined export format.
- Web threshold changes affect web supervision only. They do not change Android detector thresholds or ESP32 actuation. Web acknowledgment records the operator action and clears the web warning; it does not erase detections or silence physical alarms.
- Preserve staff access restrictions. Admins can see their organization's drivers; existing supervisor/dispatcher monitoring permissions remain as documented, without expanding driver permissions.

## 10. Implementation sequence and deliverables

| Phase | Deliverable | Completion condition |
| --- | --- | --- |
| 0. Contract review | Backend inspection, driver-role/assignment extension, event lifecycle and API contract | Record the decisions below; distinguish new requirements from existing contracts. |
| 1. Independent clone | Enterprise Android project with separate identity | Both applications install together; original files remain unchanged; baseline local behavior works. |
| 2. Login and sessions | Firebase login, recovery, access gate, server-managed assignment/session | Correct user and organization established before monitoring; invalid access rejected. |
| 2a. ESP provisioning | Native setup flow, shared ESP endpoint, AP/STA switching, firmware mDNS advertising, Android NSD, Room-backed identity verification and manual fallback | On compatible hardware, ESP joins the phone hotspot and the approved connection sequence reconnects all stream/control consumers; saved and connected states remain distinct; cloud connectivity works alongside local access. |
| 3. Durable event capture | Identity-bound Room events, MAR capture, no-face interval tracker, start/completion journal, outbox | Eye/yawn triggers and qualifying no-face intervals survive interruption without counting unobserved time or duplicates. |
| 4. Secure ingestion | Backend validation, Firestore persistence, retry/idempotency, rules | Online/offline/retry and cross-tenant tests pass. |
| 5. Connected reporting | Web history, driver filtering, per-user counts, event-time flag evaluation | Admin sees correct drowsy/yawn/no-face flags from both live and delayed batches. |
| 6. Live integration | RTDB projection, rolling windows, heartbeat, warning/acknowledgment wiring | Live data and freshness behave correctly without affecting local alarms. |
| 7. Release validation | Regression evidence, setup notes, independent enterprise build | End-to-end checks pass and original-app comparison confirms no changes. |

## 11. Full-system acceptance tests (see status for executed checks)

- Clone isolation: original and enterprise packages coexist; original Git status/source baseline is unchanged; no original signing material or local user data is copied into the enterprise release.
- Local parity: ESP32 connect/reconnect, streaming, calibration, EAR/yawn detection, escalation, physical alarm commands, model selection, settings, local logs/export, and foreground monitoring still work.
- Authentication: valid login, wrong password, recovery, missing claims, invited/disabled membership, expired/revoked tokens, logout, process restart, and account switching behave as specified.
- Attribution: two drivers use one phone sequentially; the first driver's queued logs never appear under the second. One driver uses two vehicles; user totals remain correctly grouped.
- Security: cross-org reads/writes, spoofed UID/org, unassigned vehicles, stale session/binding, archived vehicles, and driver attempts at staff actions are denied server-side.
- Event lifecycle: sustained alerts count once; consecutive yawns count separately; repeated-yawn escalation does not double-count; normal stop/crash/restart preserve event identity and completion state.
- Delivery: offline detection, reconnect, duplicate submission, lost response, out-of-order completion, partial batch rejection, app death, and partial Firestore/RTDB failure produce no lost acknowledged events or inflated counts.
- Connectivity: local ESP32 access and cloud upload coexist on representative devices; internet loss does not delay or suppress local alarms.
- Camera privacy/scope: inspect outbound cloud requests and stored records to confirm only logs/metrics/status are sent, with no frames, images, video, or remotely accessible camera feed. Local Android detection and stream/control functionality must still work.
- Provisioning: validate UTF-8 byte boundaries and whitespace, setup-auth failures, save/restart success, lost save response without automatic resubmission, manual hotspot enablement, and process recreation/return from Android settings.
- Mode switching: AP → phone hotspot → AP, reuse saved credentials, forget credentials, and power cycling preserve the intended mode/settings. Wrong credentials and a late hotspot produce recoverable states; recovery AP plus a successful client connection is displayed correctly.
- Address/routing: DHCP address changes update stream, alerts, controls, and status together; no old stream or stale command remains active. Verify AP-local routing with cellular as internet default and phone-hosted hotspot routing with simultaneous Firebase/backend access.
- Saved ESP identity: Room retains the paired ID across app/process/phone restart; a changed DHCP address updates `lastKnownIp` without changing identity. Reject an ID mismatch even when the saved IP responds. Test multiple discovered ESPs, user switching, and authorized re-pairing without rewriting historical device IDs.
- Approved discovery flow: test saved-IP success, stale/mismatched saved IP followed by successful NSD discovery, DHCP changes, several advertising ESPs, discovery timeout/permission failure followed by manual entry, and cleanup after success/cancellation. Validate the phone-hosted hotspot path on real hardware; a router-only test is insufficient.
- Hardware evidence: on a confirmed compatible board, verify actual flashing, provisioning, client connection, camera stream, and LED/buzzer controls. A save acknowledgment or APK installation is not a substitute for these tests. Cover camera initialization failure (`camera_ready: false`, stream 503) despite successful `/health`.
- Provisioning identity/security: reject or block an unverified replacement device at a reused IP; confirm setup credentials never enter cloud payloads/logs, redirects do not forward authentication, and local connectivity never grants organization authorization.
- Reporting: with one user producing 6 eye alerts and 3 yawns and another producing 1 eye alert and 1 yawn in the same selected range, the web shows exact per-type totals and the expected sorting. Retrying all records leaves totals unchanged.
- Confirmed flag boundaries: 2 EAR events do not qualify; 3 within exactly 60 seconds do, while 3 spanning more than 60 seconds do not. Likewise test 5 versus 6 yawns and the 600-second boundary. Test no-face at 179 versus 180 seconds, face reappearance, and interruptions in valid-frame analysis.
- Offline historical flags: upload the 10:00:00/10:00:20/10:00:50 EAR example at 10:30; show one historical Drowsy episode with its original time, not a current alarm. Repeat with split, reversed, duplicate, and completed-session batches; final results must match chronological ingestion. Test yawns and no-face intervals the same way.
- Observation gaps: camera unplugged, stream 503, paused setup, or failed inference for 3 minutes must show monitoring unavailable rather than No face detected. A valid, continuously analyzed stream with no face for 3 minutes must create the separate flag.
- Placeholder handling: clearly report incomplete configuration, avoid network requests to placeholder addresses, and ensure no placeholder or backend credential is mistaken for working production configuration.
- Time: UTC/date boundaries, phone clock skew, delayed uploads, rolling-window expiration, new session reset, and heartbeat loss do not produce false live frequency spikes.
- Web behavior: connected views show real records, enforce organization filters, apply threshold equality correctly, preserve historical identity, export the required columns, and retain history after member/vehicle removal.
- Validation commands during implementation: appropriate Android unit/instrumentation tests and debug build; website `npm test`, `npm run lint`, `npm run build`, and affected browser flows; backend authorization/idempotency/rules tests using isolated test infrastructure. Emulators may be used for tests, while application use follows the existing real-Firebase decision.
- Measure latency, bandwidth, battery, and representative fleet load before claiming existing NFR targets. The small telemetry payload target does not imply an entire history batch fits within 500 bytes. Free test hosting does not establish production availability.

## 12. User decisions and remaining explanations

1. **Deployment/configuration:** website is not hosted yet. Use clearly labeled placeholders for all unavailable configuration; record each one in Section 12.1. Do not invent live endpoints or claim connectivity. Backend source is now available and inspected; driver/mobile integration and deployed configuration remain pending.
2. **Application ID/signing:** no final choice yet. Keep the proposed enterprise identifier provisional; defer production Firebase Android registration and release signing until the identifier/owner is settled.
3. **Who gets an account and what they can do — approved:** administrators create/invite driver accounts and assign the permitted vehicle/ESP. Drivers set their own passwords, sign in, and submit their own detection logs. Administrators see their organization's reports; drivers do not receive administrator permissions. Implement the approved role/assignment extension in Section 5 when coding is requested.
4. **Offline reporting:** confirmed. Store logs locally with original times, upload in batches, and evaluate 3 EAR events/1 minute, 6 yawns/10 minutes (time window explicitly confirmed), and 3 continuous minutes without a face. See Section 6.1. The response resolves reporting behavior; maximum offline authorization duration, revoked-account backlog, and stale-session takeover remain separate access policies to define before release.
5. **Yawn reporting:** covered by item 4. A separate count of physical alarm commands is not required; preserve the underlying local alarm behavior and avoid double-counting yawns.
6. **Architecture:** follow the website's existing Firebase Auth, organization-scoped Firestore/RTDB, standalone backend, role restrictions, history preservation, rule precedence, and export conventions. Introduce only the explicit new detection/flag requirements. Inherit existing policies where defined; do not invent a retention duration or hosting guarantee where the website has none. Keep timezone explicit as already planned.
7. **Automatic local discovery — approved:** try the last verified IP → discover with firmware mDNS/DNS-SD and Android NSD → offer manual entry if needed. Match the Room-stored ESP ID before connecting and update the cached IP after verification. Add both firmware advertising and Android discovery in the future integration phase. This is only for the Android-to-ESP connection; the website/admins receive logs and derived reporting data, never camera footage.
8. **ESP ID in Android database — confirmed:** persist the paired ESP ID in Room with its organization/vehicle association and last verified IP. Match that ID on reconnect, keep it unchanged across IP changes, and attach it to logs. The ESP station MAC could supply the ID through firmware; an explicit provisioned device ID is another option, still to select. This is the ESP's identity, not the phone's MAC. The reference API needs an identity extension. Knowing a device ID does not authenticate it or automatically reveal its current IP.
9. **Production security — deferred by user:** this product is not production/deployment ready. Keep production hardening and hosting decisions as later release work rather than a prerequisite for the prototype. Preserve the documented test limitations. For normal functioning now, still show an interruption during ESP restart, retain logs, reconnect and verify camera availability, keep secrets out of logs, and use HTTPS for cloud services.

### 12.1. Placeholder register

The Android client uses four placeholders in `enterprise.properties.example` and the fallback values in `app/build.gradle.kts`: `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`, and `BACKEND_URL`. Copy the example to ignored `enterprise.properties` when real settings are available. Firebase is initialized programmatically, so a placeholder `google-services.json` is unnecessary. The other entries below are future deployment settings, not active app dependencies. Unresolved active placeholders prevent connected operation. A Firebase configuration value is not a substitute for authentication/authorization.

| Setting / consumer | Placeholder value | Replacement source / note |
| --- | --- | --- |
| Website public URL / onboarding links | `https://YOUR_WEBSITE_HOST.invalid` | Future website host; HTTPS origin used for allowed redirects/links. |
| Backend API base / Android + web | `https://YOUR_BACKEND_HOST.invalid/api` | Future backend host; web key is `VITE_BACKEND_API_URL`, Android key is `BACKEND_URL`. |
| Firebase project / both clients | `YOUR_FIREBASE_PROJECT_ID` | Firebase project shared by matching web/Android environments; web key `VITE_FIREBASE_PROJECT_ID`. |
| Firebase web API key | `YOUR_FIREBASE_WEB_API_KEY` | Firebase web configuration; `VITE_FIREBASE_API_KEY`. |
| Firebase auth domain | `YOUR_FIREBASE_PROJECT_ID.firebaseapp.com` | Firebase web configuration; `VITE_FIREBASE_AUTH_DOMAIN`. |
| Firebase web app ID | `YOUR_FIREBASE_WEB_APP_ID` | Firebase web registration; `VITE_FIREBASE_APP_ID`. |
| Firebase Android API key | `YOUR_FIREBASE_ANDROID_API_KEY` | Final Android registration's Firebase configuration; do not assume identical to the web key. |
| Firebase Android app ID | `YOUR_FIREBASE_ANDROID_APP_ID` | Final Android registration's Firebase configuration. |
| Firebase project number / sender ID | `YOUR_FIREBASE_PROJECT_NUMBER` | Project configuration; include in Android config where required, without adding a messaging feature. |
| Firebase RTDB URL | `https://YOUR_FIREBASE_DATABASE_HOST.invalid` | Exact database URL from Firebase; region-dependent. Existing frontend currently has no RTDB initialization, so wire an explicit config key later. |
| Final Android application ID | `YOUR_ENTERPRISE_APPLICATION_ID` | Current proposal `com.example.fatiguesense.enterprise` is provisional, not a registered production ID. |
| Backend service-account credential location | `YOUR_BACKEND_CREDENTIALS_PATH` | Server-only deployment configuration. Never place private keys in frontend variables or the APK; actual credential format depends on inspected backend. |
| Release keystore path / alias | `YOUR_ENTERPRISE_KEYSTORE_PATH` / `YOUR_ENTERPRISE_KEY_ALIAS` | Enterprise release owner; passwords supplied through protected build configuration, not this document. |

Use a clearly marked example/template for `google-services.json` if needed, never present fabricated identifiers as a downloaded working Firebase file. Optional SDK configuration fields discovered later must be added to this register before use. Do not add Storage, Analytics, messaging, or other services solely because a sample config mentions them. `VITE_APP_MODE=firebase` is a real mode selection, not a placeholder; local web `/api` is a real development proxy path, not an Android-reachable backend URL.

## 13. Inspection references

Website-relative references:

- `AGENTS.md`, `requirements.md` (especially the accepted runtime decision).
- `src/features/auth/firebaseClient.js`, `session.js`, `ConnectedApp.jsx`.
- `src/features/vehicles/vehicleRepository.js`, `src/layouts/ConnectedFleetLayout.jsx`.
- `src/features/fleet/model.js`, `src/data/demo/fleet.js`.

Original Android-relative references:

- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`.
- `app/src/main/java/com/example/fatiguesense/ml/FatigueManager.kt`.
- `app/src/main/java/com/example/fatiguesense/db/FatigueEventEntity.kt`, `FatigueDatabase.kt`.
- `app/src/main/java/com/example/fatiguesense/ui/FatigueSenseApp.kt`, `ui/navigation/AppNavigation.kt`.

Wi-Fi provisioning references from the user-provided handoff (contracts and verification claims to recheck against source before implementation):

- `C:\RJ\Coding\espWIFI\README.md`, `main\main.cpp`, `main\wifi_setup.html`.
- `C:\RJ\Coding\espWIFI\android-app\README.md`.
- `C:\RJ\Coding\espWIFI\android-app\app\src\main\java\com\fatiguesense\wifitest\MainActivity.java` and `EspClient.java`.
- `C:\RJ\Coding\espWIFI\android-app\app\src\test\java\com\fatiguesense\wifitest\EspClientTest.java`.

The enterprise source copy and app implementation now exist. Original source baseline and SHA-256 hashes are recorded in `docs/source-baseline.json`. The original project's source, signing material and Git history were not modified. Full-system completion remains subject to the acceptance tests above; the current app-only result and remaining integration work are recorded in `docs/implementation-status.md`.

Discovery documentation consulted for planning:

- [Samsung connection and hotspot settings](https://www.samsung.com/us/support/answer/ANS10002546/).
- [Android network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd).
- [Espressif mDNS component](https://docs.espressif.com/projects/esp-protocols/mdns/docs/latest/en/index.html).
- [Android local-network permissions](https://developer.android.com/privacy-and-security/local-network-permission).
