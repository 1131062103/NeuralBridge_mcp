# Changelog

All notable changes to NeuralBridge are documented here.

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
