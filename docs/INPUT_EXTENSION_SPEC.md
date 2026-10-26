# NeuralBridge 输入能力扩展规格（NB-6）

> 状态：**已实施（0.5.0 / Companion 0.2.0），真机 20/20 验收通过**  
> 起草：2026-10-26  
> 实施/验收：2026-10-26（见 [NB6_ACCEPTANCE.md](NB6_ACCEPTANCE.md)）  
> 触发方：`/opt/ai/stock/thsTrade` 的 P2 下单流程被自定义数字键盘阻塞  
> 目标版本：NeuralBridge MCP Server **0.5.0** / Companion **0.2.0**  
> 关联：`/opt/ai/stock/thsTrade/docs/NEURALBRIDGE_BACKLOG.md` NB-6  
> 约束：不引入 ADB、root、Instrumentation、INJECT_EVENTS；不改变现有工具语义

---

## 0. 一句话结论

给 `android_input_text` 增加**按 `bounds` / `element_id` 精确定位**目标节点并回读结果的能力，
并在 `android_find_elements` 返回 `className`/`editable` 等字段；这样客户端可以直接对
同花顺买卖表单里**无 resourceId 的自定义 EditText** 执行 `ACTION_SET_TEXT`，不再依赖
坐标点击自定义数字键盘。

### 0.1 新会话启动提示（可直接粘贴）

```text
在 /opt/ai/others/NeuralBridge_mcp 中实现 docs/INPUT_EXTENSION_SPEC.md。
先阅读 McpToolHandler.kt、InputEngine.kt、UiTreeWalker.kt、McpToolRegistry.kt
和现有测试，按规格第 7 节顺序实现；不要引入 ADB/root/Instrumentation。
先跑 JVM 测试，再用真机同花顺 11.63.02 模拟账户做 bounds 输入 20/20 验收；
未通过前不要开启 thsTrade 的 execution.enabled。
```

---

## 1. 问题与证据

### 1.1 真机现象（同花顺 11.63.02 / 模拟账户）

- 买入/卖出表单数量、价格字段：
  - `get_ui_tree(filter="all")` 能看到 `stockprice` / `stockvolume` 容器；
  - `find_elements(class_name="EditText")` 能返回实际输入框，但**没有 resourceId**，
    只有 `text`（空时为 hint）和 `bounds`；
  - `android_input_text(resource_id="stockvolume", text="100")` → `Input text failed`；
  - `android_input_text(element_text="数量", text="100")` → `Could not get node reference
    for element`；
  - `android_press_key("select_all"/"paste")` → `no focused input field`；
  - 点击自定义键盘坐标可能穿透到背后的持仓列表，误选股票（已实际发生）。
- 结论：当前 `input_text` 的定位路径只覆盖 resourceId / text 查找，无法命中
  “无 rid、hint 不进节点文本、父容器无编辑能力”的自定义输入框。

### 1.2 现有实现定位

| 文件 | 关键位置 | 问题 |
|---|---|---|
| `McpToolHandler.kt` | `handleInputText` | 只接受 `element_text` / `resource_id`；`findNodeByElement` 只按 viewId/text 查 |
| `McpToolHandler.kt` | `findNodeByElement` | 无 bounds/class/editable 匹配；取第一个匹配节点，可能是父容器 |
| `UiTreeWalker.kt` | `extractElement` / `generateElementId` | 元素 id 是属性哈希；当前没有“id → 节点”的解析路径 |
| `InputEngine.kt` | `inputText` | 对非 editable 节点仍尝试；失败后只回退剪贴板，且不校验结果 |
| `McpToolRegistry.kt` | `android_input_text` / `android_find_elements` | schema 没有 `element_id`、`bounds`、`class_name`；find 不返回 editable/class |

### 1.3 为什么优先改 `input_text` 而不是键盘

- 同花顺的自定义键盘不在 `rootInActiveWindow` 的可访问树中，无法结构化定位。
- Android `EditText` 本身支持 `ACTION_SET_TEXT`；之前失败是**定位到了错误节点**
  （父容器），不是 `ACTION_SET_TEXT` 不可用。
- 坐标方案换机型/版本即失效，且误点风险高；输入框 bounds 在表单内稳定，可由
  `find_elements` 直接获得。

---

## 2. 目标与非目标

### 2.1 目标

1. `android_input_text` 支持用 `bounds`（或 `element_id`）锁定目标节点。
2. 定位时优先可编辑节点（`isEditable == true`），必要时从父容器向下找到实际 EditText。
3. 输入后回读节点文本，返回 `before`/`after`/`method`/`target`，失败给出结构化原因。
4. `android_find_elements` 返回 `className`、`editable`、`focusable` 等诊断字段。
5. 新增 `android_get_focused_element`，便于排查“无焦点/焦点在错误节点”。
6. 完全向后兼容：所有新参数可选，旧调用行为不变。
7. thsTrade 可删除坐标/键盘兜底，改用 `bounds` 输入。

### 2.2 非目标

- 不实现 ADB、root、`Instrumentation.sendKeySync`、`INJECT_EVENTS`。
- 不模拟 IME 按键事件；不做坐标点击键盘。
- 不新增登录/切号/多设备能力。
- 不改变 `tap`/`swipe`/`scroll`/`wait` 语义。
- 不保证所有第三方自定义控件都接受 `ACTION_SET_TEXT`；失败要可诊断、可回退。

---

## 3. API 变更（向后兼容）

### 3.1 `android_input_text`

新增可选参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `element_id` | string | `find_elements`/`get_ui_tree` 返回的 elementId（16 位十六进制） |
| `bounds` | string \| object | `"[l,t][r,b]"`、`"[l,t,r,b]"` 或 `{left,top,right,bottom}` |
| `class_name` | string | 可选类名过滤（contains，如 `EditText`） |
| `read_back` | boolean | 默认 `true`；输入后读回目标节点文本 |
| `allow_clipboard` | boolean | 默认 `true`；`ACTION_SET_TEXT` 失败时是否允许剪贴板兜底 |
| `bounds_tolerance` | integer | 默认 `2` px；bounds 匹配容差 |

目标解析优先级：

```text
element_id
  > resource_id
  > bounds (+ class_name)
  > element_text
  > 当前 focused editable 节点
```

`element_id` 的实现方式：先 `walkTree` 找到对应 `UiElement`，再复用 `bounds`/`class_name`
定位真实节点（不需要维护节点引用表；elementId 是属性哈希，不保证跨屏唯一，必须校验）。

成功响应由纯 latency 改为：

```json
{
  "success": true,
  "method": "action_set_text",
  "target": {
    "elementId": "419d3afcc925d3d1",
    "resourceId": "com.hexin.plat.android:id/stockvolume",
    "className": "android.widget.EditText",
    "editable": true,
    "bounds": "[147,632][478,721]"
  },
  "before": "",
  "after": "100",
  "latency_ms": 3
}
```

失败响应（示例）：

```json
{
  "success": false,
  "error_code": "INPUT_NOT_EDITABLE",
  "message": "matched a non-editable wrapper; no editable descendant found",
  "target": { "...": "..." }
}
```

建议错误码：

| code | 含义 |
|---|---|
| `TARGET_NOT_FOUND` | 所有定位条件都未命中 |
| `TARGET_AMBIGUOUS` | 多个 bounds 相同节点且无法用 class/editable 消歧 |
| `INPUT_NOT_EDITABLE` | 命中节点及后代都不可编辑 |
| `INPUT_REJECTED` | 节点拒绝 `ACTION_SET_TEXT` 且剪贴板兜底失败 |
| `INPUT_VERIFY_FAILED` | 操作返回成功但回读文本不一致 |
| `INVALID_BOUNDS` | bounds 字符串/对象非法 |

### 3.2 `android_find_elements`（仅新增返回字段）

现有请求参数不变；返回元素增加：

```json
{
  "elementId": "...",
  "resourceId": "...",
  "className": "android.widget.EditText",
  "text": "",
  "contentDescription": null,
  "bounds": "[147,632][478,721]",
  "clickable": true,
  "editable": true,
  "focusable": true,
  "enabled": true,
  "visible": true
}
```

可选新增请求参数：

- `editable: boolean`：只返回可编辑节点；
- `visible: boolean`：默认只返回 visible。

注意：`find_all=false` 时仍只返回第一个，但建议内部先按
`editable > visible > focusable > 面积最小` 排序，避免返回父容器。该排序是行为改进，
需在 changelog 中注明；如需严格兼容，可加 `prefer_editable=true` 默认 true。

### 3.3 新增 `android_get_focused_element`

无需参数，返回：

```json
{
  "focused": true,
  "elementId": "...",
  "resourceId": null,
  "className": "android.widget.EditText",
  "text": "100",
  "hint": "数量",
  "editable": true,
  "bounds": "[147,632][478,721]",
  "package": "com.hexin.plat.android"
}
```

用途：客户端在 `input_text` 前确认焦点，或在 `press_key` 失败时诊断。
不产生点击/输入，只读。

### 3.4 `android_press_key`（可选增强）

建议新增：

- `key="clear"`：对当前 focused editable 执行 `ACTION_SET_TEXT("")`；
- 数字键 `0`..`9`：仅当存在 focused editable 时，向其追加对应字符
  （实现为 `ACTION_SET_TEXT` 追加，不模拟 IME 按键）。

数字键不是 NB-6 的必需项；如果 `bounds` 输入可用，可暂不实现，避免扩大改动面。
无论是否实现，都**不要**用坐标点击自定义键盘。

### 3.5 版本

- `McpHttpServer.SERVER_VERSION`：`0.4.0` → `0.5.0`
- `android/app/build.gradle.kts`：`versionCode 1 → 2`，`versionName 0.1.0 → 0.2.0`
- 工具 schema 增加字段；旧客户端忽略即可。

---

## 4. 实现设计

### 4.1 目标解析（`McpToolHandler.kt`）

新增数据类：

```kotlin
private data class TargetSpec(
    val elementId: String? = null,
    val resourceId: String? = null,
    val bounds: Rect? = null,
    val className: String? = null,
    val text: String? = null,
    val tolerance: Int = 2
)
```

新增函数（伪代码）：

```kotlin
private fun findTargetNode(
    root: AccessibilityNodeInfo,
    tree: UiTree,
    spec: TargetSpec
): Pair<UiElement?, AccessibilityNodeInfo?> {
    val candidateElement: UiElement? = when {
        spec.elementId != null -> tree.elements.firstOrNull { it.elementId == spec.elementId }
        spec.resourceId != null -> tree.elements.firstOrNull {
            it.resourceId?.endsWith(spec.resourceId) == true
        }
        spec.bounds != null -> tree.elements
            .filter { it.bounds.matches(spec.bounds, spec.tolerance) }
            .filter { spec.className == null || it.className?.contains(spec.className) == true }
            .sortedWith(compareByDescending<UiElement> { it.editable }
                .thenByDescending { it.visible }
                .thenByDescending { it.focusable }
                .thenBy { it.bounds.width * it.bounds.height })
            .firstOrNull()
        spec.text != null -> tree.elements.firstOrNull {
            it.text?.contains(spec.text, ignoreCase = true) == true
        }
        else -> null
    }

    val node = candidateElement?.let { findNodeForElement(root, it, spec.tolerance) }
    return candidateElement to node
}
```

`findNodeForElement` 必须：

1. 优先按 `resourceId` 找（若存在）；
2. 否则递归遍历节点，比较 `getBoundsInScreen()`（容差内）+ `className`；
3. 同一 bounds 多节点时，优先 `isEditable`，其次 `isVisibleToUser`、`isFocusable`；
4. 若命中节点不可编辑但其后代存在可编辑节点，返回该后代；
5. 返回前 `refresh()`，避免节点失效；
6. 明确 `recycle()` 生命周期（只回收自己创建的节点；不要回收 `rootInActiveWindow`）。

> `UiElement` 当前没有 `editable` 字段；新增 `val editable: Boolean = false`，
> 在 `extractElement` 中赋值 `node.isEditable`。这是内部数据类变更，不影响 MCP 兼容性。

### 4.2 `handleInputText` 改造

```kotlin
val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
val readBack = args["read_back"]?.jsonPrimitive?.booleanOrNull ?: true
val allowClipboard = args["allow_clipboard"]?.jsonPrimitive?.booleanOrNull ?: true
val tolerance = args["bounds_tolerance"]?.jsonPrimitive?.intOrNull ?: 2

val root = service.rootInActiveWindow ?: return errorResult("No active window")
val tree = uiTreeWalker.walkTree(root)

val target = resolveTarget(tree, args, tolerance)
    ?: return inputError("TARGET_NOT_FOUND", ...)
val node = findNodeForElement(root, target, tolerance)
    ?: return inputError("TARGET_NOT_FOUND", ...)

val editableNode = findEditableNode(node) ?: return inputError("INPUT_NOT_EDITABLE", ...)
val before = editableNode.text?.toString().orEmpty()

val method = when {
    inputEngine.trySetText(editableNode, text, append) -> "action_set_text"
    allowClipboard && inputEngine.tryClipboard(editableNode, text, append) -> "clipboard"
    else -> return inputError("INPUT_REJECTED", ...)
}

if (readBack) {
    editableNode.refresh()
    val after = editableNode.text?.toString().orEmpty()
    if (!textMatches(after, text, append, before)) {
        return inputError("INPUT_VERIFY_FAILED", ..., before, after)
    }
}

return textResult(buildJsonObject { ... target, before, after, method, latency_ms ... })
```

`textMatches` 规则：

- `append=false`：`after == text`；
- `append=true`：`after == before + text`；
- 某些控件会格式化（千分位、小数位），允许 `normalize(after) == normalize(text)`；
  否则返回 `INPUT_VERIFY_FAILED`，让客户端决定是否中止。

### 4.3 `InputEngine` 改造

新增：

```kotlin
fun trySetText(node: AccessibilityNodeInfo, text: String, append: Boolean): Boolean {
    if (!node.isEditable) return false
    node.performAction(ACTION_FOCUS)
    val args = Bundle().apply {
        putCharSequence(
            ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            if (append) (node.text?.toString().orEmpty() + text) else text
        )
    }
    return node.performAction(ACTION_SET_TEXT, args)
}

fun tryClipboard(node: AccessibilityNodeInfo, text: String, append: Boolean): Boolean {
    // 现有 inputViaClipboard 逻辑；仅对 editable 节点调用
}
```

保留现有 `inputText` 作为兼容入口，但内部改为调用 `trySetText`/`tryClipboard`，
并增加 `isEditable` 前置检查。

### 4.4 `findNodeForElement` 与 bounds 解析

新增工具函数：

```kotlin
private fun parseBounds(value: JsonElement?): Rect? // 支持 string/object
private fun Rect.matches(other: Rect, tolerance: Int): Boolean
private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo?
```

递归深度上限沿用 `MAX_RECURSION_DEPTH = 50`；只向下找 `isEditable` 的后代。

### 4.5 `handleFindElements` 响应

在现有字段上增加：

```kotlin
put("className", e.className ?: "")
put("editable", e.editable)
put("focusable", e.focusable)
put("enabled", e.enabled)
put("visible", e.visible)
```

排序建议（仅 `find_all=true` 时）：

```text
editable desc, visible desc, focusable desc, area asc, tree index asc
```

`find_all=false` 时返回排序后的第一个，避免父容器。

### 4.6 `android_get_focused_element`

```kotlin
val focused = service.rootInActiveWindow?.findFocus(FOCUS_INPUT)
if (focused == null) return textResult("""{"focused":false}""")
...
```

返回 `resourceId`、`className`、`text`、`hintText`（API 26+）、`editable`、`bounds`、
`packageName`。不要返回密码字段内容：若 `isPassword` 为 true，`text` 返回 `"***"`。

### 4.7 安全与日志

- 严禁把 `text` 或 arguments 写入日志；现有 MCP 客户端已不打印 args，Android 端也应
  只记录长度、tool、耗时、成功/失败。
- `get_focused_element` 对 `isPassword` 脱敏。
- 不新增权限；不引入 ADB/root。
- bounds 输入只影响本机 UI，不扩大攻击面；MCP 仍未鉴权，继续依赖网络隔离。

---

## 5. 测试计划

### 5.1 JVM 单元测试（`android/app/src/test`）

- `McpToolRegistryTest`：
  - `android_input_text` schema 包含 `element_id`、`bounds`、`class_name`、`read_back`、
    `allow_clipboard`、`bounds_tolerance`；
  - `android_find_elements` schema 包含 `editable`、`visible`；
  - `android_get_focused_element` 存在且无必填参数。
- 新增纯函数测试（不依赖设备）：
  - `parseBounds("[10,20][30,40]")`、`"[10,20,30,40]"`、object、非法值；
  - `Rect.matches` 容差边界；
  - `textMatches` replace/append/格式化归一化；
  - `normalize` 去逗号/空格/小数点尾零。

### 5.2 Instrumentation / 手工验收

准备一个测试 Activity：一个普通 `EditText`（有 id）、一个无 id `EditText`、一个
外层 `FrameLayout` 与内层 `EditText` 同 bounds。

| 用例 | 期望 |
|---|---|
| `input_text(resource_id=...)` | 仍工作 |
| `input_text(element_id=...)` | 命中并写入 |
| `input_text(bounds=..., class_name="EditText")` | 命中内层 EditText，不是 FrameLayout |
| `append=true` | 保留原文本并追加 |
| `read_back=true` 返回 after | 与输入一致 |
| 非 editable 容器无后代 | `INPUT_NOT_EDITABLE` |
| 过期 bounds | `TARGET_NOT_FOUND`，不误点 |
| 两个同 bounds 节点 | 优先 editable；无法消歧返回 `TARGET_AMBIGUOUS` |
| `get_focused_element` 无焦点 | `{"focused":false}` |
| 密码框 | text 脱敏 |

### 5.3 同花顺真机验收（关键）

在模拟账户买入表单，不使用坐标键盘：

1. `find_elements(class_name="EditText", find_all=true)` 取数量/价格实际 EditText 的 bounds；
2. `input_text(bounds=..., class_name="EditText", text="100")`；
3. 回读 `after == "100"`；
4. 设置价格 9.63，回读一致；
5. 连续 20 次进入/退出买入表单，20/20 成功；
6. 不误点最终“买入/确认”，不产生委托；
7. 再用 thsTrade `OrderPage` 做 dry-run，最终取消；
8. 换一台同版本同花顺设备复测（至少 1 台）或明确记录仅单机通过。

### 5.4 回归

- `tap`、`swipe`、`long_press`、`scroll_to_element`、`wait_*`、截图、UI 树不变；
- `android_input_text` 旧参数（`element_text`/`resource_id`）行为不变；
- `find_elements` 旧字段不删除；新增排序仅影响第一个结果的选取，需 changelog 说明。

---

## 6. thsTrade 侧配套（另一个会话可一起做）

### 6.1 客户端

- `thstrade/mcp_client.py`：无需改 JSON-RPC；`device.input_text` 增加 `element_id`、
  `bounds`、`class_name`、`read_back` 参数。
- `thstrade/pages/order.py`：
  - `_set_numeric_field` 改为：

    ```python
    self.device.input_text(
        value,
        bounds=field.bounds.to_bounds(),
        class_name="android.widget.EditText",
        append=False,
    )
    ```

  - 删除 `set_clipboard`/`press_key("select_all")`/重复 delete 的兜底；
  - 依赖 `input_text` 的 `after` 回读做校验；失败抛 `OrderNotSubmitted`。
- `thstrade/device.py`：`input_text` 透传新参数，并解析响应 `target/before/after/method`。
- 解除 `[execution].enabled=false` 的前提：真机 20/20 + dry-run 通过 + 人工复核。

### 6.2 文档/状态

- `docs/NEURALBRIDGE_BACKLOG.md` NB-6 标记完成；
- `PLAN.md` 8.14 更新为“NB-6 已解决，P2 继续”；
- `docs/THSAUTO_REFERENCE.md` 更新“自定义键盘”说明；
- 版本固定：记录新 NeuralBridge commit/tag 与 APK SHA256。

---

## 7. 实施顺序（建议）

1. `UiElement` 增加 `editable`；`extractElement` 赋值；`find_elements` 返回新字段。
2. `parseBounds` / `Rect.matches` / `findEditableNode` 纯函数 + JVM 测试。
3. `findNodeForElement` 支持 bounds/class/editable，优先 editable。
4. `handleInputText` 支持 `element_id`/`bounds`，接入 `trySetText`/回读/错误码。
5. 新增 `get_focused_element`。
6. 更新 `McpToolRegistry` schema、`docs/TOOLS.md`、版本号。
7. 手工测试 Activity + 同花顺真机验收。
8. thsTrade 客户端切换，跑 334+ 测试与 dry-run。
9. 提交/打 tag/记录 APK SHA256；再开启 `execution.enabled`。

---

## 8. 验收清单

- [x] `android_input_text` 可按 `bounds` 命中无 resourceId 的 EditText。
- [x] `android_input_text` 可按 `element_id` 命中。
- [x] 父容器与子 EditText 同 bounds 时优先子节点（真机 `bounds + class_name` 命中 EditText；resolver 按 editable/visible/focusable/面积排序）。
- [x] `append=true` 与 `read_back` 正确。
- [x] 失败返回稳定错误码，不误点、不落键盘坐标（真机验证 `TARGET_NOT_FOUND` / `INVALID_BOUNDS` / `INPUT_NOT_EDITABLE`；`INPUT_REJECTED` / `INPUT_VERIFY_FAILED` 已实现）。
- [x] `find_elements` 返回 `className`/`editable`/`focusable`/`enabled`/`visible`。
- [x] `get_focused_element` 可用，密码脱敏（脱敏由 JVM 单测覆盖）。
- [x] 旧调用/旧字段完全兼容（真机验证 `element_text`；`resource_id` 路径语义未改）。
- [ ] JVM 测试 + 真机同花顺 20/20 + dry-run 通过（JVM 63 项与真机 20/20 已通过；thsTrade dry-run 待在 thsTrade 会话执行）。
- [ ] thsTrade `execution.enabled` 可在验收后开启（保持 `false`，由 thsTrade 会话在 dry-run 后决定）。
- [ ] 文档、版本、APK 哈希、tag 更新（文档/版本/APK SHA256 已记录；tag 未创建，提交/打 tag 需单独授权）。

> 完整证据与限制见 [NB6_ACCEPTANCE.md](NB6_ACCEPTANCE.md)。

---

## 9. 开放问题

1. `element_id` 是属性哈希，跨屏/布局变化可能碰撞或失效；是否只把它作为辅助，
   主推荐 `bounds + class_name`？（建议：主推 bounds，element_id 作为便捷别名。）
2. `find_elements` 默认排序改为 editable 优先是否算行为变更？建议加
   `prefer_editable=true` 并写 changelog；旧客户端如需严格顺序可显式关闭。
3. 数字键 `press_key` 是否值得实现？如果 bounds 输入通过，可暂缓，减少改动面。
4. 是否把 `get_ui_tree` 增加 `include_class` 列？会改变表格格式，建议暂不做；
   `find_elements` 已足够。
5. 自定义控件拒绝 `ACTION_SET_TEXT` 时，是否允许 `ACTION_PASTE` 兜底？
   建议允许，但必须在响应中返回 `method="clipboard"`，且 thsTrade 记录审计。
