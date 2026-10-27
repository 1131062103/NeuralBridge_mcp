# Changelog

All notable changes to NeuralBridge are documented here.

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
