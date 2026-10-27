[← Back to README](../README.md)

---

# 🛠️ MCP Tools Reference (34 Tools)

> **Batch execution (v0.6.0):** `android_sequence` runs an ordered list of UI
> actions in one MCP call, returning per-step results and one final UI tree.
> See [Batch execution with `sequence`](#-sequence--batch-ui-actions-v060) below.
>
> **Input extension (NB-6, v0.5.0):** `input_text` accepts `element_id`, `bounds`
> and `class_name`, performs `ACTION_SET_TEXT` with read-back, and returns structured
> `before`/`after`/`method`/`target` diagnostics. See
> [INPUT_EXTENSION_SPEC.md](INPUT_EXTENSION_SPEC.md) for the full contract.

Every tool is callable by your AI agent through MCP. Tools accept **selectors** (text, resource ID, content description, element ID, or bounds) so your agent never needs to hardcode pixel coordinates.

---

### 👁️ Observe — See what's on screen

| Tool | Description | Typical Latency |
|---|---|---|
| `screenshot` | Capture screen as JPEG (full or thumbnail quality) | ~60ms |
| `get_ui_tree` | Full UI hierarchy with element IDs, text, bounds | 18-33ms |
| `find_elements` | Search by text/ID/description/class; returns `elementId`, `className`, `editable`, `focusable`, `enabled`, `visible`, `bounds` | <10ms |
| `get_focused_element` | Read the focused input element (password text masked) | <10ms |
| `get_screen_context` | Screenshot + simplified UI tree in one call | ~70ms |
| `get_device_info` | Manufacturer, model, Android version, screen size | <5ms |
| `get_notifications` | Read notification titles, text, and actions | <10ms |
| `get_recent_toasts` | Capture toast messages shown on screen | <5ms |

---

### 👆 Act — Touch, type, and interact

| Tool | Description | Typical Latency |
|---|---|---|
| `tap` | Tap by selector or coordinates | ~2ms |
| `long_press` | Long press (default 1000ms hold) | ~1000ms |
| `double_tap` | Double tap gesture | ~150ms |
| `swipe` | Swipe between two points with duration control | ~2ms |
| `pinch` | Pinch zoom (scale >1.0 = in, <1.0 = out) | ~300ms |
| `drag` | Drag from point A to point B | ~500ms |
| `input_text` | Type into a field by `element_id` / `resource_id` / `bounds` / `element_text`; ACTION_SET_TEXT + read-back | ~3ms |
| `press_key` | Press system keys (back, home, enter, etc.) | <5ms |
| `set_clipboard` | Set clipboard content | <10ms |

#### `input_text` targeting (v0.5.0)

Target priority: `element_id` > `resource_id` > `bounds` (+ `class_name`) >
`element_text` > focused editable field. The first supplied selector is
authoritative, so stale bounds fail instead of typing into another field.

- `bounds` accepts `"[left,top][right,bottom]"`, `"[left,top,right,bottom]"`, or
  `{"left":..,"top":..,"right":..,"bottom":..}`. `bounds_tolerance` defaults to 2 px.
- `element_id` comes from `find_elements` / `get_ui_tree`.
- `read_back` (default `true`) compares the node text and returns `before`/`after`.
- `allow_clipboard` (default `true`) permits an `ACTION_PASTE` fallback when
  `ACTION_SET_TEXT` is rejected.
- Success payload: `success`, `method` (`action_set_text` / `clipboard`),
  `target` (`elementId`, `resourceId`, `className`, `editable`, `bounds`),
  `before`, `after`, `latency_ms`.
- Error codes: `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`, `INPUT_NOT_EDITABLE`,
  `INPUT_REJECTED`, `INPUT_VERIFY_FAILED`, `INVALID_BOUNDS`.

For custom `EditText`s without a `resourceId` (e.g. 同花顺 order forms), call
`find_elements(class_name="EditText", find_all=true)` and pass the returned
`bounds` plus `class_name="EditText"` to `input_text`.

---

### 📱 Manage — Control apps and device

| Tool | Description | Typical Latency |
|---|---|---|
| `launch_app` | Launch app by package name | <100ms |
| `close_app` | Close app (graceful or force-stop via ADB) | ~200ms |
| `open_url` | Open URL in default browser | <100ms |
| `global_action` | System actions: back, home, recents, notifications | <10ms |
| `list_apps` | List installed apps (all or by filter) | ~200ms |

---

### ⏳ Wait — Synchronize with the UI

| Tool | Description | Typical Latency |
|---|---|---|
| `wait_for_element` | Wait until element appears (with timeout) | up to 5s |
| `wait_for_gone` | Wait until element disappears | up to 5s |
| `wait_for_idle` | Wait until UI stabilizes (no changes for 300ms) | up to 5s |
| `scroll_to_element` | Scroll through lists until element is found | up to 30s |

---

### 🧩 Sequence — Batch UI actions (v0.6.0)

| Tool | Description | Typical Latency |
|---|---|---|
| `sequence` | Execute up to 50 UI actions in one MCP call; per-step results + one final UI tree | 6-8 steps ≤ 2s on LAN |

`sequence` collapses the request/response overhead of multi-step flows (e.g.
filling an order form) into one call. It is a generic UI-action tool — no
business package names or control IDs are built in.

#### Top-level parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `steps` | array (1-50) | required | Ordered actions. Each item requires `action`. |
| `stop_on_error` | boolean | `true` | Stop at the first failure. `false` runs all steps and reports failures. |
| `observe` | `none` / `final` / `each` | `final` | Return no tree, one final tree, or a tree after every step (debug only). |
| `observe_filter` | `all` / `interactive` / `text` | `text` | Same filter semantics as `get_ui_tree`. |
| `include_screenshot` | boolean | `false` | Append one thumbnail screenshot to the final result. |
| `timeout_ms` | integer | `30000` | Overall hard deadline. |
| `settle_ms` | integer | `0` | Optional UI-idle wait before the first step. |

The returned `final` tree is **structurally identical** to `get_ui_tree`
(`format/app/total/shown/filter/elements`), so existing parsers work unchanged.

#### Step actions

| Action | Key fields | Notes |
|---|---|---|
| `tap` | `x`+`y`, `bounds`, or selectors | Coordinates win; otherwise the element centre is tapped. |
| `input_text` | `text` + (`element_id` / `resource_id` / `bounds` / `element_text`) | Same targeting rules as `input_text`. Targets are resolved at step execution time. |
| `wait_for_element` | selectors, `timeout_ms` | Returns `STEP_TIMEOUT` on timeout — never success. |
| `wait_for_gone` | selectors, `timeout_ms` | Also `STEP_TIMEOUT` unless the element is confirmed gone. |
| `wait_for_idle` | `timeout_ms` | `STEP_TIMEOUT` if the UI never stabilises. |
| `press_key` | `key` | Same keys as `press_key`. |
| `swipe` | `start_x`,`start_y`,`end_x`,`end_y`,`duration_ms` | |
| `scroll_to_element` | selectors, `direction`, `max_scrolls`, `timeout_ms` | `STEP_TIMEOUT` on deadline, `STEP_TARGET_NOT_FOUND` after `max_scrolls`. |
| `sleep` | `duration_ms` | Diagnostic only. Prefer `wait_for_element`; never guess timings. |
| `assert_text` | selectors + `text_equals` / `equals` / `text_not_equals` | Read-only. Failure → `STEP_ASSERT_FAILED`. |

Every step also accepts `timeout_ms` (per-step deadline) and `description`
(a short debug label — never put passwords or account data in it).

#### AND / OR selector semantics

Inside `sequence`, multiple selector fields (`text` + `resource_id` +
`content_desc` + `class_name` + `element_id`) are **AND-matched by default**.
This is intentionally stricter than the single-action tools, which keep their
existing OR/priority semantics. Pass `"match": "or"` on a step to opt into OR.

```json
{"action": "tap", "text": "买入", "resource_id": "btn_buy"}
```

matches only an element whose text contains `买入` **and** whose resource ID
ends with `btn_buy`.

#### Example — fill a form in one call

```json
{
  "name": "android_sequence",
  "arguments": {
    "steps": [
      {"action": "tap", "bounds": "[100,200][400,300]"},
      {"action": "input_text", "text": "600000", "resource_id": "auto_stockcode"},
      {"action": "wait_for_element", "resource_id": "stockcode_tv", "timeout_ms": 600},
      {"action": "tap", "text": "600000", "match": "and"},
      {"action": "input_text", "text": "9.40", "bounds": "[100,400][400,460]",
       "class_name": "EditText", "read_back": true},
      {"action": "input_text", "text": "100", "bounds": "[100,500][400,560]",
       "class_name": "EditText", "read_back": true},
      {"action": "assert_text", "resource_id": "stockname", "text_not_equals": "股票名称"}
    ],
    "observe": "final",
    "observe_filter": "text"
  }
}
```

#### Result

```json
{
  "success": true,
  "completed": 7,
  "total": 7,
  "elapsed_ms": 812,
  "steps": [
    {"index": 0, "action": "tap", "ok": true, "elapsed_ms": 4},
    {"index": 1, "action": "input_text", "ok": true, "elapsed_ms": 18,
     "detail": {"method": "action_set_text", "before": "", "after": "600000"}}
  ],
  "final": {"format": "compact", "app": "com.example.app", "total": 206,
            "shown": 49, "filter": "text", "elements": "IDX | resource_id | ..."}
}
```

On failure the response includes `failed_step` and a stable error object, and
(by default) no later step runs:

```json
{
  "success": false,
  "completed": 2,
  "total": 6,
  "failed_step": 3,
  "error": {"code": "STEP_TARGET_NOT_FOUND",
            "message": "tap target not found: resource_id=btn_transaction",
            "step": 3}
}
```

#### Error codes

`INVALID_SEQUENCE`, `INVALID_STEP`, `STEP_TARGET_NOT_FOUND`,
`STEP_TARGET_AMBIGUOUS`, `STEP_TIMEOUT`, `STEP_ASSERT_FAILED`,
`STEP_INPUT_REJECTED`, `STEP_NOT_EDITABLE`, `STEP_INPUT_VERIFY_FAILED`,
`STEP_GESTURE_FAILED`, `SEQUENCE_TIMEOUT`, `SEQUENCE_BUSY`,
`SEQUENCE_CANCELLED`.

#### Guarantees

- Mutating steps execute **once** and are never retried automatically.
- Wait timeouts are failures, never success.
- Only one sequence may run per device; concurrent calls get `SEQUENCE_BUSY`.
- Input text is never written to logs, `CommandLog`, or error messages.
- Sequence never auto-confirms/submits anything; callers must list each step.
- `observe=none` skips tree capture entirely; `final` captures one tree.

#### Device notes

- `wait_for_idle` is event-based. Screens with a blinking text cursor can keep
  emitting events and hit `STEP_TIMEOUT`; prefer `wait_for_element` when waiting
  for a specific result.
- Clearing a field uses the existing `input_text` read-back check. If an app
  re-exposes its hint as `text` after clearing, the step can return
  `STEP_INPUT_VERIFY_FAILED` even though the field is visually empty.

---

### 🧪 Test — Validate and debug

| Tool | Description | Typical Latency |
|---|---|---|
| `screenshot_diff` | Compare screenshots for visual regression | ~100ms |
| `accessibility_audit` | Audit screen for a11y issues (touch targets, labels) | <50ms |
| `enable_events` | Toggle real-time event streaming (UI changes, toasts) | <5ms |

---

### 📡 Device — Multi-device management

| Tool | Description |
|---|---|
| `list_devices` | List all connected Android devices with status |
| `select_device` | Switch active device for all subsequent commands |

---

### 🔍 Meta — Discover and explore tools

| Tool | Description | Typical Latency |
|---|---|---|
| `search_tools` | Search available tools by name, description, or category | <5ms |
| `describe_tools` | Get detailed description of one or all tools | <5ms |
