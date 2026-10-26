[← Back to README](../README.md)

---

# 🛠️ MCP Tools Reference (33 Tools)

> **Input extension (NB-6, v0.5.0):** `input_text` now accepts `element_id`, `bounds`
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
