# Lifelog Android V1 Handoff

Branch: `feature/lifelog-android-v1`

This branch implements the Android side of the active-annotation loop defined
in `audio-rag/docs/LIFELOG_INTEGRATION_HANDOFF.md`.

## Implemented

- A fourth bottom-navigation destination: **生活**.
- Today's timestamped activity timeline.
- Server-generated daily diary entries.
- Pending block/point annotation cards.
- `confirm`, `correct`, and `skip` semantics.
- Taxonomy-driven label selection; labels are not hard-coded in the UI.
- WorkManager polling every 15 minutes while network is available.
- Deduplicated local notifications for new pending prompts.
- Notification tap opens the Lifelog destination.
- Settings switches for Lifelog UI and background annotation polling.
- Optional periodic upload of sealed Opus segments:
  - disabled by default because it transfers private raw audio;
  - Wi-Fi/unmetered network by default;
  - battery-not-low constraint;
  - uploads at most 12 segments per run;
  - never reads the currently open segment;
  - local path-to-SHA state plus a stable `Idempotency-Key`;
  - epoch timestamps derived from session start + relative segment offset;
  - end time derived from the actual count of complete 80-byte frames;
  - frozen six-field metadata JSON and a cross-session unique remote filename.
  - upload receipt state V2 invalidates the earlier relative-time receipts so
    affected pilot files are retried once with corrected epoch timestamps.
- BLE frame-ID continuity and fixed 80-byte frame validation:
  - an integrity failure stops the recording session;
  - invalid segments are retained locally but excluded from VAD and auto upload.
- Versioned `/api/v1/*` endpoints with temporary fallback to the Mac prototype:
  - `/api/timeline`
  - `/api/pending`
  - `/api/annotate`
- Development `X-User-Id` header. This is not production authentication.
- UTC date query/display for the first pilot, visibly labelled in the UI.
- JSON fixtures and parser/API contract unit tests.

## Contract currently consumed

Primary routes:

- `GET /api/v1/timeline?date=YYYY-MM-DD`
- `GET /api/v1/annotations/pending`
- `POST /api/v1/annotations`
- `GET /api/v1/diary?date=YYYY-MM-DD`

Required timeline fields:

- `schema_version`
- `date`
- `taxonomy[]: {id, display_name}`
- `segments[]`
  - `segment_id`
  - `start_time_ms`
  - `end_time_ms`
  - `predicted_label`
  - `confidence`
  - `source`
  - `reviewed_label`
  - `review_action`
  - `asr_text`
  - `top_sound_events[]: {label, confidence}`

Required pending fields:

- `schema_version`
- `items[]`
  - `event_id`
  - `kind`
  - `question`
  - `suggested_label`
  - `suggested_display_name`
  - `start_time_ms`
  - optional `end_time_ms`
  - `asr_context`
  - `created_at_ms`
  - optional `expires_at_ms`

Annotation request:

```json
{
  "event_id": 42,
  "action": "confirm|correct|skip",
  "label": "work or null"
}
```

Annotation response:

```json
{
  "annotation_id": 9,
  "event_id": 42,
  "action": "confirm",
  "effective_label": "meeting",
  "memory_updated": true
}
```

The upload `metadata` file contains exactly `userId`, `name`, `startTime`,
`endTime`, `mac`, and `size`. Unknown fields are forbidden by the server.
Retries reuse identical bytes and timestamps; server deduplication is based on
user, content hash, and time range.

## Build verification

```bash
./gradlew testDebugUnitTest assembleDebug
```

Verified on 2026-07-30:

- Kotlin/Compose compilation passes.
- JSON fixture tests pass.
- authoritative server fixture, route, diary, upload, timestamp, and BLE
  integrity tests pass.
- Debug APK generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Deliberately not implemented yet

### Automatic upload scope

V1 automatic upload sends sealed Opus audio only. It does not automatically
upload context/GPS/VAD files. The existing manual session sync remains available
for explicit full-session uploads.

The App sends a stable `Idempotency-Key` as a transport hint, but does not add it
to metadata. A worker crash after server acceptance but before local state
persistence is safe only if the exact same bytes, user, and timestamps are
retried.

### Production authentication

`X-User-Id` is a development bridge only. The server must define authentication
before multi-user deployment. Android should then use an access token and must
not trust a mutable user ID header.

### Server push

V1 uses 15-minute WorkManager polling. FCM/WebSocket push can replace this
later, but is not required for the research prototype.

### Processing status

Upload success means durable acceptance, not completed inference. The server
does not yet expose a session-status endpoint, so the App retains local files
and cannot distinguish processing from terminal failure.

## Integration checklist

1. Server publishes OpenAPI and fixtures with a schema version.
2. Compare those fixtures with `app/src/test/resources/lifelog/`.
3. Run unit tests.
4. Point Settings to the test server.
5. Upload a non-sensitive Opus fixture through the existing recordings screen.
6. Verify timeline, block query, correction, skip, and notification dedup.
7. Verify that a correction changes later `source=personal_rag` predictions.
8. Compare upload size and frame-derived duration with server ingest records.
