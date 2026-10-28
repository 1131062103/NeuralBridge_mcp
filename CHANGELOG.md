# Changelog

All notable changes to NeuralBridge are documented here.

## 0.7.0 — 2026-10-28

### Added

- `McpForegroundService`: a dedicated foreground service that owns the MCP HTTP
  server. It is independent of the AccessibilityService binding, uses
  `START_STICKY`, survives task removal, and keeps a persistent notification
  with **Stop** / **Restart** actions.
- `McpServerController`: single, concurrency-safe owner of the HTTP server with
  a `StateFlow<McpServerState>` for the UI, bounded retry/backoff on start
  failures, and a localhost TCP probe so a silently dead engine is recreated.
- Watchdog in the foreground service: checks every 20 s, restarts with
  exponential backoff, pauses after 5 consecutive failures and asks the user to
  tap **Restart**.
- `BootReceiver` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`: restarts the
  MCP service when the master switch is ON, with the Android 12+
  `ForegroundServiceStartNotAllowedException` handled by a user-visible
  notification.
- `ACCESSIBILITY_NOT_CONNECTED` error for accessibility-dependent tools. The MCP
  server keeps answering (and tools such as `get_device_info`, `list_apps`,
  `launch_app`, `get_notifications` keep working) while the accessibility
  service is unbound.
- Pre-bind port probe so an occupied port 7474 is reported as
  `port 7474 already in use` instead of crashing inside Ktor's accept coroutine.

### Fixed

- MediaProjection on Android 12: the foreground service now carries
  `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` (three-arg `startForeground` on API
  29+) before `getMediaProjection()`, so the system consent no longer ends in
  `Media projections require a foreground service of type ...`. The type is
  dropped again when the projection ends.
- Screenshots now return the already-buffered ImageReader frame instead of
  draining it and waiting for a new display update; static screens no longer
  stall the fast path.
- Late MediaProjection consent results are still usable after a UI round-trip
  (`MainActivity.onResume` / lazy consumption on capture), and the Setup tab's
  permission counter refreshes on its own.

### Changed

- `NeuralBridgeAccessibilityService` no longer starts or owns the HTTP server or
  a foreground notification. `onServiceConnected` only publishes its component
  bundle and calls `McpForegroundService.ensureRunning()`; `onUnbind`/`onDestroy`
  never stop the MCP service.
- MediaProjection consent is now granted explicitly from the Setup tab. It is no
  longer auto-requested on every accessibility reconnect or toggle, which used
  to pop a system dialog after crash recovery and interrupt automation.
- Android companion app `0.3.0` → `0.4.0` (versionCode 4); MCP server
  `0.6.0` → `0.7.0`. All 34 existing tools, schemas and responses are unchanged.

### Verified on device

HUAWEI Mate 40 Pro (NOH-AN01), HarmonyOS 4.2.0 / Android 12 (API 31), 896×1848:

- Service crash (`adb shell am crash`): process died with importance FGS; the
  accessibility rebind started the server again in **2 s** (the system had
  scheduled the FGS restart for 11 s; `ensureRunning()` is idempotent).
- `am force-stop` does not auto-restart (Android semantics): port closed; opening
  the app restored the server in **2 s**. Documented, not bypassed.
- Reboot (`adb reboot`): with the switch ON, the server was listening again
  after boot **without opening the app** (BOOT_COMPLETED → `START_STICKY`).
- Port 7474 held by another process: 4 bounded attempts, no crash, notification
  `MCP Server failed — port 7474 already in use`; the watchdog recovered in
  **2 s** after the port was released.
- 10 rapid switch toggles: stable PID, exactly one notification, no port
  conflict and no crash.
- Accessibility off/on while the server was running: server stayed up; tool
  calls returned `ACCESSIBILITY_NOT_CONNECTED` while unbound.
- MediaProjection granted from Setup: **PERMISSIONS 4/4**, `dumpsys
  media_projection` shows an active `TYPE_SCREEN_CAPTURE` session, and thumbnail
  screenshots measured **P50 22 ms / P95 26 ms** (vs the accessibility fallback).

## 0.6.0 — 2026-10-27

### Added

- `android_sequence`: execute up to 50 ordered UI actions in one MCP call
  (`tap`, `input_text`, `wait_for_element`, `wait_for_gone`, `wait_for_idle`,
  `press_key`, `swipe`, `scroll_to_element`, `sleep`, `assert_text`).
  - Per-step `index`/`action`/`ok`/`elapsed_ms` results plus `failed_step` and
    stable `STEP_*` / `SEQUENCE_*` error codes.
  - `stop_on_error` (default `true`), overall `timeout_ms`, per-step timeouts,
    optional `settle_ms`.
  - `observe` = `none` / `final` / `each`; the final tree is structurally
    identical to `android_get_ui_tree`.
  - `include_screenshot` appends one thumbnail at the end (off by default).
  - Multiple selector fields default to **AND** inside a sequence; pass
    `"match": "or"` explicitly for OR.
  - One sequence per device at a time; concurrent calls return `SEQUENCE_BUSY`.
  - Mutating steps are never retried automatically, and `input_text` values are
    never written to logs or error messages.
- `CommandLog.Category.SEQUENCE` and a non-sensitive `detail` field
  (`steps=N`) for sequence entries.
- Unit tests for sequence validation, selector AND/OR matching, executor
  ordering, stop-on-error, timeouts, observation, and the single-sequence gate.

### Verified on device

HUAWEI Mate 40 Pro (NOH-AN01), HarmonyOS 4.2.0 / Android 12, 896×1848:

- `tools/list` returns 34 tools including `android_sequence`.
- A 9-step Settings sequence (back → wait → tap → wait → assert → back → wait →
  tap → wait) completed 9/9 in **2149ms** (internal). The same work as 10
  individual MCP calls took **2626ms**; excluding the four business waits, the
  mechanical actions took **283ms inside the sequence vs 808ms as individual
  calls** (~65% less).
- Verified: stop-on-error skips later steps, `stop_on_error=false` continues,
  `INVALID_SEQUENCE` / `INVALID_STEP`, AND vs `match:"or"`, screenshot append,
  `SEQUENCE_BUSY` on concurrent sequences, and `observe=final` tree shape.
- `input_text` set/append/read-back verified in the SMS compose screen; the
  input value did not appear in logcat (only character counts are logged).
- Note: `wait_for_idle` can hit `STEP_TIMEOUT` on screens with a blinking text
  cursor because it is event-based; prefer `wait_for_element`. Clearing a field
  whose app re-exposes its hint as `text` returns `STEP_INPUT_VERIFY_FAILED`
  (pre-existing `android_input_text` read-back semantics).

### Fixed

- Restored the missing `android/gradle/wrapper/gradle-wrapper.jar` and corrected
  the `.gitignore` exception, so `./gradlew test` works from a fresh clone.

### Changed

- MCP server version `0.5.0` → `0.6.0`; Android companion app `0.2.0` → `0.3.0`
  (versionCode 3).
- Shared input/tree/press-key logic extracted into internal helpers so
  `android_sequence` and the single-action tools cannot drift apart. Existing
  tool output and schemas are unchanged.

## 0.5.0 — 2026-10-26

### Added

- `android_input_text` now accepts `element_id`, `bounds` (string or object),
  `class_name`, `read_back`, `allow_clipboard`, and `bounds_tolerance` for precise
  targeting of custom `EditText`s that have no `resourceId` (e.g. 同花顺 order forms).
- Structured input responses: `method` (`action_set_text` / `clipboard`),
  `target` (elementId/resourceId/className/editable/bounds), `before`, `after`,
  and `latency_ms`.
- Stable input error codes: `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`,
  `INPUT_NOT_EDITABLE`, `INPUT_REJECTED`, `INPUT_VERIFY_FAILED`, `INVALID_BOUNDS`.
- `android_get_focused_element` for diagnosing focus state before input or after a
  failed `press_key`; password text is masked as `***`.
- `android_find_elements` now returns `className`, `editable`, `focusable`,
  `enabled`, and `visible`, and accepts `editable`, `visible`, and
  `prefer_editable` filters.

### Changed

- `android_find_elements` sorts matches by editable → visible → focusable → smaller
  area when `prefer_editable=true` (default), so `find_all=false` no longer returns a
  parent container when a child input exists. Pass `prefer_editable=false` for the
  legacy order.
- Bounds in `android_find_elements` / `android_get_focused_element` / `input_text`
  responses use the `[left,top][right,bottom]` form. `input_text` accepts either that
  form, `[left,top,right,bottom]`, or `{left,top,right,bottom}`.
- `InputEngine.inputText` now refuses non-editable nodes instead of attempting input
  on them.
- Android companion app version `0.1.0` → `0.2.0` (versionCode 2); MCP server
  `0.4.0` → `0.5.0`.
