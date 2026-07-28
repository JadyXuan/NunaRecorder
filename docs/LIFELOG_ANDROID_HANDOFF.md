# Lifelog Android V1 Handoff

Branch: `feature/lifelog-android-v1`

This branch implements the Android side of the active-annotation loop defined
in `audio-rag/docs/LIFELOG_INTEGRATION_HANDOFF.md`.

## Implemented

- A fourth bottom-navigation destination: **生活**.
- Today's timestamped activity timeline.
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
  - exact manifest timestamps and a cross-session unique remote filename.
- Versioned `/api/v1/*` endpoints with temporary fallback to the Mac prototype:
  - `/api/timeline`
  - `/api/pending`
  - `/api/annotate`
- Development `X-User-ID` header. This is not production authentication.
- JSON fixtures and parser/API contract unit tests.

## Contract currently consumed

Primary routes:

- `GET /api/v1/timeline?date=YYYY-MM-DD`
- `GET /api/v1/annotations/pending`
- `POST /api/v1/annotations`

Required timeline fields:

- `schema_version`
- `date`
- `taxonomy[]: {id, name}`
- `segments[]`
  - `segment_id`
  - `t_start_ms`
  - `t_end_ms`
  - `pred_label`
  - `pred_conf`
  - `pred_source`
  - `label`
  - `asr_text`
  - `sound_events[]: {name, prob|probability}`

Required pending fields:

- `schema_version`
- `taxonomy[]`
- `pending[]`
  - `event_id`
  - `kind`
  - `question`
  - `suggested_label`
  - `suggested_name`
  - `t_start_ms`
  - optional `t_end_ms`
  - `asr_text`
  - `created_ms`
  - optional `expires_ms`

Annotation request:

```json
{
  "event_id": 42,
  "action": "confirm|correct|skip",
  "label": "work or null"
}
```

## Build verification

```bash
./gradlew testDebugUnitTest assembleDebug
```

Verified on 2026-07-28:

- Kotlin/Compose compilation passes.
- JSON fixture tests pass.
- v1 route and legacy fallback tests pass.
- Debug APK generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Deliberately not implemented yet

### Automatic upload scope

V1 automatic upload sends sealed Opus audio only. It does not automatically
upload context/GPS/VAD files. The existing manual session sync remains available
for explicit full-session uploads.

The server should deduplicate using the `Idempotency-Key` header or the
`metadata.clientUploadId` field. A worker crash after server acceptance but
before local state persistence must not create a duplicate session.

### Production authentication

`X-User-ID` is a development bridge only. The server must define authentication
before multi-user deployment. Android should then use an access token and must
not trust a mutable user ID header.

### Server push

V1 uses 15-minute WorkManager polling. FCM/WebSocket push can replace this
later, but is not required for the research prototype.

### Diary screen

The current destination focuses on timeline and active annotation. The server
diary endpoint can be added as a second view once the final response contract
is published.

## Integration checklist

1. Server publishes OpenAPI and fixtures with a schema version.
2. Compare those fixtures with `app/src/test/resources/lifelog/`.
3. Run unit tests.
4. Point Settings to the test server.
5. Upload a non-sensitive Opus fixture through the existing recordings screen.
6. Verify timeline, block query, correction, skip, and notification dedup.
7. Verify that a correction changes later `pred_source=rag` predictions.
