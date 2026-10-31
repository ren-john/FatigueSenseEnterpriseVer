# Driver-to-dashboard integration — 2026-10-29

The authorized flow is implemented across `FatigueSenseEnterpriseVer`,
`sentry-drive-backend` and `sentry-drive-web`:

1. Company provisioning creates its organization and initial Admin using the
   existing trusted backend tool.
2. The Admin registers a vehicle/ESP and invites an employee under web **Drivers**.
3. The employee receives Firebase password setup, chooses a password and signs
   in to the driver-only Android app.
4. The Admin's vehicle assignment authorizes ESP verification and monitoring.
5. Android sends authenticated session heartbeats and durable detection batches.
6. The backend stores attributed event history and publishes live monitoring.
7. Staff see **Live fleet** alerts and **Detection history**, acknowledge a current
   web alert, and see a new alert after another detection.

Staff accounts and their existing permissions remain in `members`. Drivers use a
separate `drivers` collection. Android rejects staff sign-in and the web rejects
driver sign-in. Company Admins administer drivers; all staff can monitor their
organization. Driver authority and event ownership are enforced server-side.

## Android changes

- `EnterpriseAuth` adds retry-stable session request IDs and authenticated
  `mobileHeartbeat` calls.
- `FatigueService` publishes camera/alert status every 30 seconds independently of
  event upload. Revoked or Admin-ended sessions stop monitoring on the next
  heartbeat response. Network loss retains local monitoring and queues detections.
- `BackendAddress` normalizes a backend root or `/api` base, rejects embedded
  credentials/query/fragment addresses and permits HTTP only in debug builds.
- Existing on-device detection, local alarms, Room journal, revision-aware upload,
  firmware identity gate and original timestamps are retained.

## Setup before using real accounts

1. Keep Firebase project IDs aligned across all three repositories. Download the
   Android registration's config for `com.fatiguesense.enterprise` into ignored
   `app/google-services.json`; Firebase Auth uses the default app configured by
   the Google Services Gradle plugin. Web client config stays in `.env.local`.
   Private service-account credentials remain backend-only.
2. Build and restart the updated local Node backend. Preserve its real Firebase
   `.env`; no Auth/Firestore/RTDB emulator hosts are needed for application use.
3. Review and apply the backend's updated Firestore rules/indexes and RTDB rules
   to the intended Firebase project. The backend never deploys them at startup.
4. Run the web app with its existing local `/api` proxy. Its monitoring database
   address comes from the authenticated backend configuration endpoint.
5. Set Android `BACKEND_URL` to the API server reachable from the phone, for
   example `http://<development-PC-LAN-IP>:8081/api` in a debug build. The web's
   relative `/api` proxy is not a native Android address. Rebuild after changes.
   Release builds require a trusted HTTPS backend endpoint.
   Android `enterprise.properties` now supplies only `BACKEND_URL`; legacy
   `FIREBASE_*` entries are ignored. The new application ID installs separately
   from the previous `com.example.fatiguesense.enterprise` prototype.
6. Register the ESP's stable device ID on the vehicle. Firmware must expose that
   matching `device_id`; automatic discovery additionally needs the documented
   mDNS extension. Strict verification is the default. For older firmware omitting
   `device_id`, the temporary manual-ID option requires explicit confirmation of
   the exact assigned ID; a reported mismatch is always rejected. Camera readiness,
   connectivity, password and backend authorization remain required.

No user configuration values, live accounts, real emails, Firebase rules or live
records were modified during the 2026-10-29 source implementation and isolated testing.

## Validation

- Android revalidation on 2026-10-30: debug APK, test APK, all 33 unit tests and
  lint pass. Lint reports 66 warnings and one hint, with no errors.
- All 9 Android instrumentation tests pass on the available API 37 emulator,
  including the application ID and default Firebase Auth configuration checks.
  Generated Firebase resources were also compared with the downloaded JSON.
  Real-account Firebase sign-in and backend access were not tested in this check.
- The backend and web results below are from the 2026-10-29 integration validation.
- All 398 backend tests pass: 247 server/service/integration tests and 151 rules
  tests against isolated Auth, Firestore and RTDB services. They include actual
  sign-in tokens through HTTP, existing staff/gateway behavior and the driver flow.
- All 81 web unit/interaction tests and 13 vehicle repository/rules integration
  tests pass. The production build and lint pass. Final backend/web checks run
  from the actual repositories using Node 22.23.4.
- A headless browser exercised owner login, driver invitation and assignment,
  driver login/session requests, uploaded detections, live RTDB alerts,
  acknowledgment, renewed alerts and history at desktop and mobile sizes, with
  no page errors. Accounts and detections were synthetic test fixtures.

The backend's `docs/driver-integration.md` documents all endpoints and persistence
details; the web's matching guide documents its views and access rules.

## Remaining limits

No live Firebase deployment, real email delivery or complete physical ESP/phone
road test occurred. Firmware was outside the authorized three-repository change.
The current web history is explicitly limited to the latest 200 organization
records; filtering and CSV export operate on that set. Full history pagination,
all-time aggregates and connected custom rule editing are separate work.
Legacy gateway ingestion remains available, but a vehicle cannot run a legacy
gateway and a mobile session together. Admins can end an abandoned mobile session;
offline timeouts do not silently transfer session ownership.
