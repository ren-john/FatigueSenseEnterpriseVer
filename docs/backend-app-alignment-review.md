# Backend and Android alignment review

Historical review before implementation. The user subsequently authorized the
driver flow on 2026-10-29; its source changes and current verification are recorded
in [driver-integration.md](driver-integration.md). The findings below describe the
baseline that motivated that work, not the updated repositories.

Reviewed 2026-10-29 against the current source in `FatigueSenseEnterpriseVer`
and the sibling `sentry-drive-backend` and `sentry-drive-web` repositories.
Configuration values are omitted.

## Intended architecture

The Android application is exclusively for drivers. The web application serves
admins, supervisors and dispatchers. The backend authorizes both clients and
connects driver activity to organization-scoped records and monitoring data.
Different client roles are intentional; Android must retain its driver-only gate.

The current web architecture combines trusted backend HTTP actions with direct,
rules-protected Firebase access. Vehicle and staff lists use Firestore listeners;
vehicle registry changes use Firestore transactions. The backend does not proxy
every web database operation. Extending this existing approach allows the backend
to validate Android submissions and publish data that the web can subscribe to.

## Result

The existing web staff/vehicle integration shares the backend HTTP envelope and
Firebase identity model. The driver integration remains unimplemented in the
backend, and the web monitoring/history consumers are still demo-only.
Correcting configuration or deploying the backend will not, by itself, enable
driver sign-in, assignments, sessions, uploads or connected monitoring views.

## Blocking findings

1. **All five Android mobile endpoints are missing.** Android calls
   `mobileAccess`, `mobileAssignments`, `mobileStartSession`,
   `mobileUploadEvents` and `mobileEndSession`. None occurs in backend TypeScript
   source. The server registers staff-management handlers plus `provisionGateway`
   and `revokeGateway`. Its router returns 404 for unregistered endpoints before
   authentication. Sources: Android `EnterpriseAuth.kt:46`, `EnterpriseAuth.kt:59`,
   `UploadWorker.kt:48`, `UploadWorker.kt:69`; backend
   `functions/src/index.ts:24`, `functions/src/server/http.ts:45`.

2. **The backend is missing the required driver authorization path.** Android
   correctly requires `role=driver`. Backend human roles are currently only
   `admin`, `supervisor` and `dispatcher`, including
   the user-creation schema. The HTTP dispatcher applies that operator-only
   claims validator before invoking handlers. Adding endpoint names alone will
   therefore still reject driver tokens with 403. Giving a driver an operator
   role also fails the Android check. Sources: Android `EnterpriseAuth.kt:46`;
   backend `functions/src/config.ts:1`, `functions/src/lib/auth.ts:10`,
   `functions/src/server/http.ts:75`, `functions/src/users/service.ts:23`.

   Keep staff endpoints restricted to staff and add explicit driver-authorized
   mobile endpoints; do not make Android accept staff accounts or give drivers
   management privileges.

3. **Driver assignments and monitoring sessions have no corresponding backend
   implementation.** Android expects an authorized list of vehicles/devices and
   a server-issued session ID, then closes that session after uploading events.
   Backend vehicles contain a display-only `driverName`, not a driver UID
   assignment. Existing gateway provisioning is a vehicle-scoped machine
   identity, not the Android human driver/session contract. Sources: Android
   `EnterpriseAuth.kt:59`; backend `functions/src/types.ts:19`,
   `functions/src/gateway/service.ts`, `README.md:417`.

4. **The upload transport, event schema and acknowledgments differ.** Android
   submits HTTP batches and requires accepted `{eventId, revision}` pairs.
   Backend ingestion uses gateway-only RTDB `events_inbox` writes and a worker.
   Its strict schema cannot accept Android events unchanged.

   | Field or behavior | Android | Backend gateway ingestion |
   | --- | --- | --- |
   | Event identity | UUID plus revision | 20-character RTDB push ID; create once |
   | Event type | `triggerType`: `EAR`, `MAR`, `NO_FACE` | `type`: `Eye Closure Alert`, `Yawn Warning` |
   | Metrics | `EAR`, `MAR`; nullable | `ear`, `mar`; required numbers |
   | Duration | Seconds; fractional or null | Integer milliseconds, 0–3,600,000 |
   | Lifecycle | Onset, completion/interruption, revision | Single historical event |
   | Attribution | Vehicle, device and driver session | Vehicle; no driver/session attribution |
   | Severity | Not sent | Required `warning` or `critical` |
   | Offline backlog | Persistent queue without a client age cutoff | Timestamp limited to previous 24 hours / next 5 minutes at receipt |

   Sources: Android `DetectionJournal.kt:70`, `UploadWorker.kt:55`; backend
   `functions/src/gateway/inbox.ts:8`, `database.rules.json:90`,
   `functions/src/types.ts:33`, `README.md:412`.

5. **Current URL configuration does not match the router.** The app's configured
   URL has no `/api` suffix and ends with `/`. Android concatenates `/$action`,
   while the server matches exactly `/api/<name>`. Configure the API base with
   `/api` and no trailing slash. The backend itself serves HTTP; Android explicitly
   requires HTTPS for backend calls. Local integration needs a trusted HTTPS
   proxy or a deliberate debug-only HTTP configuration. Sources: Android
   `EnterpriseAuth.kt:33`, `EnterpriseAuth.kt:84`; backend
   `functions/src/server/http.ts:1`, `functions/src/server/http.ts:45`.

## Additional differences

- Android flags use 3 eye events/60 seconds, 6 yawns/600 seconds and 180 seconds
  without a face. Backend defaults are 5 eye events/5 minutes and 10 yawns/10
  minutes. Its stored rule schema fixes the eye window at five minutes and has no
  no-face rule. Updating counts alone cannot reconcile the eye window.
  Sources: Android `FlagRules.kt:11`; backend `functions/src/config.ts:12`,
  `functions/src/types.ts:23`, `firestore.rules:95`.
- Backend fleet monitoring depends on gateway RTDB connection state and counters.
  This Android implementation uploads historical events only and has no RTDB
  telemetry client. Adding mobile event storage alone will not populate the
  existing live dashboard. Sources: backend `database.rules.json:5`,
  `README.md:389`; Android `UploadWorker.kt` and the application source tree.

## Web integration findings

| Area | Current source behavior |
| --- | --- |
| Staff sign-in and access | Firebase sign-in plus backend `markActive`; staff claims only |
| Staff management | Backend `createOrgUser`, `resendInvite`, `updateOrgUserRole`, `removeOrgUser`; Firestore roster listener |
| Vehicle registry | Organization-scoped Firestore listeners and transactions; backend `revokeGateway` after removal |
| Local backend access | Vite proxies `/api` to `http://127.0.0.1:8081`; native Android does not share this proxy |
| Live fleet | Demo context and fixture metrics; `/fleet` redirects to `/vehicles` in connected mode |
| Incident history and warning rules | No connected route implementation; unsupported connected routes display “Page unavailable” |
| Driver account administration | No driver invitation role or authenticated driver assignment field |

Evidence: web `src/features/auth/session.js:30`,
`src/features/auth/firebaseClient.js:48`,
`src/features/vehicles/vehicleRepository.js:8`,
`src/features/staff/connectedModel.js:30`, `dev-proxy.js:4`,
`src/features/auth/ConnectedApp.jsx:111`,
`src/features/auth/ConnectedApp.jsx:155`, `src/App.jsx:5`,
`src/features/fleet/pages/LiveFleetPage.jsx:28`.

**Shared membership migration must account for the existing web parser.**
The staff repository subscribes to the entire organization `members` collection
and maps every record through `readMember`. That parser accepts only the three
staff roles and fails the roster when any record is invalid. Simply inserting
driver memberships into this collection will break the Users list. A separate
organization `drivers` collection avoids changing the staff roster contract;
sharing `members` instead requires deliberate query/parser, permission, and
management changes. Either approach needs explicit driver account and assignment
management while keeping staff access restricted. Sources: web
`src/features/staff/staffRepository.js:8`,
`src/features/staff/staffRepository.js:23`,
`src/features/staff/connectedModel.js:14`, `src/features/staff/model.js:1`.

## What already aligns

- POST JSON with `Authorization: Bearer <Firebase ID token>`.
- Exactly `{data: ...}` requests, `{result: ...}` success responses and
  `{error: {code, message}}` failures.
- Android batches stay within the backend's 16 KiB request-body limit.
- `enterprise.properties` now exists and its four required values are set.
- Android, web and backend configuration reference the same Firebase project.
- The web configuration selects connected mode and uses the local `/api` proxy.
- The local backend `.env` selects production Firebase, with emulator host
  variables unset. Local Node hosting does not imply Firebase emulator usage;
  the app's missing Auth emulator support is not a blocker for this configuration.

## Work needed

For the existing driver application, extend the backend with driver identity,
membership/assignment authorization and the five mobile handlers, preserving
existing staff API behavior. Add web driver administration without breaking its
staff roster. Define session
ownership and revision acknowledgments, then implement event conversion/storage
with explicit units and backlog behavior. Reconcile flag rules and decide how
mobile activity should populate live fleet status. Implement connected web
subscriptions and views for driver history, flags and live monitoring. Configure
local connectivity and run authenticated integration tests against a dedicated
test environment.

## Verification limits

This was a source and masked-configuration review. An automated source scan
confirmed all five mobile endpoint names are absent from backend TypeScript;
the web source has no `firebase/database` imports or `fatigue_events` references.
The connected route switch and demo source independently confirm the missing
monitoring/history integration. All six existing web backend action names are
implemented, including the property-assigned `revokeGateway` handler.
No credentials were printed, no backend or web files were changed, and no live Firebase
requests or authenticated end-to-end tests were performed. The shell has no
`node` command, and the backend checkout has neither compiled server artifacts nor
installed test dependencies, so backend and web runtime tests were not run.
