# App integration contract

The driver API is implemented in the sibling `sentry-drive-backend` repository as
of 2026-10-29, alongside connected driver management, live fleet and recent history
in `sentry-drive-web`. See [setup and validation](driver-integration.md) and the
backend's `docs/driver-integration.md` for the current implementation. No live
Firebase deployment or ESP firmware change occurred.

## Legacy gateway contract

The original operator/gateway API remains supported. Driver requests use explicit
mobile endpoints and separate authorization, driver records, assignments and
sessions. They are not sent directly to the old gateway inbox. Relevant backend
files are `functions/src/drivers/`, `server/http.ts`, `index.ts` and
`gateway/inbox.ts`.

- HTTP POST uses `Authorization: Bearer <Firebase ID token>`, JSON `{data: ...}`,
  success `{result: ...}`, failure `{error: {code, message}}`; max body is 16 KiB.
  The app uses the same envelope and bounded batches with a byte-size check.
- Existing staff dispatch accepts admin/supervisor/dispatcher claims. Mobile
  routes accept only driver claims and validate active driver membership. Driver
  records are separate from the staff roster; staff accounts cannot sign in to
  Android and drivers cannot invoke staff management.
- Legacy event ingestion is RTDB `events_inbox` processed by a worker, with
  vehicle identity only, 20-character push IDs, a 500-byte payload limit and a
  24-hour timestamp acceptance window. It lacks driver UID, sessions, revisioned
  UUID records, unknown metrics/durations, no-face intervals and driver flags.
- Stored history uses lowercase `ear`/`mar` and integer millisecond durations.
  The app contract below uses `EAR`/`MAR` and duration in **seconds**. The new
  mobile ingestion converts units explicitly and stores original nullable metrics.
- Legacy configurable eye rules retain their five-minute window. Driver monitoring
  implements the approved one-minute eye rule and separate no-face observation.

## Required mobile HTTP endpoints

All endpoints below are implemented and tested with isolated Firebase services.
The backend does
verify tokens with revocation, active membership, organization, assignments and
session ownership. UID/org/roles are derived from the token, not event input.

| POST `/api/…` | `data` | `result` |
| --- | --- | --- |
| `mobileAccess` | `{}` | `{displayName: string}` after active/activated driver access validation |
| `mobileAssignments` | `{}` | `{assignments: [{vehicleId, deviceId, label}]}` restricted to that driver |
| `mobileStartSession` | `{requestId, vehicleId, deviceId}` | `{sessionId}` after server assignment and exclusive vehicle-session check; requestId is a UUID reused on retries |
| `mobileHeartbeat` | `{sessionId, streaming, alertActive}` | `{success: true}` after current session/assignment checks; refreshes live status |
| `mobileUploadEvents` | `{events: [...]}` | `{accepted: [{eventId, revision}], rejected?: [{eventId, revision, permanent, message}]}` |
| `mobileEndSession` | `{sessionId, endedAt}` | `{success: true}` idempotently closing the owned session |

IDs must be bounded safe strings (at most 128 characters). Event IDs are UUIDs.
Persist acceptance before replying. Repeating an identical event revision must
return acceptance without incrementing counters. Reject conflicting ownership or
revision content. Accept legitimate backlog after normal session closure; deny
unauthorized/revoked callers. A temporary failure must not be reported as permanent.

Example submitted event:

```json
{
  "eventId": "1f5a824f-8dce-49ed-96ae-18d99af81d35",
  "schemaVersion": 1,
  "sessionId": "server-issued-session",
  "vehicleId": "assigned-vehicle",
  "deviceId": "assigned-esp-id",
  "timestamp": 1792288800000,
  "triggerType": "EAR",
  "EAR": 0.18,
  "MAR": 0.3,
  "duration": 2.5,
  "status": "completed",
  "revision": 2
}
```

`timestamp` is original UTC epoch milliseconds. `duration` is seconds or null
when unknown; `EAR`/`MAR` are measurements or null. Types: EAR, MAR, NO_FACE.
Revision 1 records an onset; revision 2 completes or interrupts that same ID.
No-face onset is written only after 180 seconds of continuous observed absence,
with its original interval start time and at least 180 seconds of known duration.
After crash recovery, `interrupted` preserves known evidence but does not invent
an end time. The backend stamps authenticated user/org and server receipt time.

Local event rows are the outbox: event changes and upload state are one Room row
write. WorkManager acknowledges exactly the submitted revision, so a late reply
cannot mark a newer completion uploaded. Unaccepted records remain queued.
Permanent rejections are displayed and retained. Offline session-end records
are queued separately; events upload before normal session closure.

The active service requests an upload attempt every 30 seconds, plus event-edge
requests. WorkManager connectivity/backoff controls actual timing. A 15-minute
periodic job provides recovery; it does not promise exact periodic scheduling.
Android does not write RTDB directly. All cloud operations use the authenticated
HTTP boundary above; the backend publishes RTDB live state for web subscribers.

## Required ESP identity and discovery extension

Current `espWIFI` status API/provisioning requests are preserved, with these
pending additions required to use the enterprise identity gate:

- `GET /api/wifi` returns `device_id` in addition to the existing fields.
  It must equal the backend-assigned and Room-saved device ID in both AP/STA mode.
  A fixed canonical ESP station MAC or provisioned ID can be the source; select
  that format when updating firmware. The app treats it as an opaque exact string.
- Advertise DNS-SD service `_fatiguesense._tcp`, port 80, and TXT field
  `device_id=<same ID>`. Use a unique service/host name. Android filters matching
  advertisements, resolves IPv4, then verifies the ID via the HTTP status API.
- The firmware must keep the same ID after network changes and power cycling.
  Advertising an ID is matching, not cryptographic authentication. Development
  Basic authentication and local HTTP remain the reference behavior.
- Streaming remains port 81 `/stream`; alarm/control paths remain unchanged.
  The app does not upload camera data or use Firebase tokens with the ESP.

No firmware changes or claims of successful hotspot discovery are made here.
