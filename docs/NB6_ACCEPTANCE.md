# NB-6 实施与真机验收记录

> 日期：2026-10-26  
> 规格：[INPUT_EXTENSION_SPEC.md](INPUT_EXTENSION_SPEC.md)  
> 结果：**JVM 63/63 通过；同花顺真机 bounds 输入 20/20 通过；未产生任何委托。**

---

## 1. 被测产物

| 项 | 值 |
|---|---|
| MCP Server | `0.5.0` |
| Companion | `0.2.0`（versionCode `2`） |
| 被测 APK | `android/app/build/outputs/apk/debug/app-debug.apk`（debug 签名） |
| APK SHA256 | `f15c222d202a3d17f39650d024e9a13fad116ba1f11d4c61c9daa91c9862084a` |
| JDK / Gradle | Android Studio JBR 21.0.10 / Gradle 8.13 |

> debug APK 的哈希仅对应本次验收产物；后续 release 构建会不同。

## 2. 设备与账户

| 项 | 值 |
|---|---|
| 设备 | HUAWEI NOH-AN01（`192.168.2.3:5555`） |
| Android | 12（SDK 31） |
| 屏幕 | 896 × 1755（override，density 373） |
| 目标应用 | 同花顺 `com.hexin.plat.android` 11.63.02（versionCode 5252） |
| 账户 | 「模拟炒股」`**1000`（模拟账户） |

## 3. JVM 测试

`gradle testDebugUnitTest`：**63 tests, 0 failures, 0 errors, 0 skipped**。

其中新增/扩展：

- `InputTargetingTest`（17）：bounds 字符串/对象解析、非法值、容差匹配、面积、
  文本归一化、`textMatches` 的 replace/append/格式化、密码脱敏、bounds 格式化。
- `McpToolRegistryTest`：`input_text` 新增 6 个参数、`find_elements` 新增
  `editable`/`visible`/`prefer_editable`、`get_focused_element` 无必填参数。

## 4. 真机同花顺 bounds 输入 20/20

每轮流程（全程未点击红色「买入」提交按钮）：

1. 在「查询」页点击顶部「买入」标签进入买入表单；
2. `find_elements(class_name="EditText", find_all=true)` 发现数量/价格实际 EditText
   的 bounds（两者均无 `resourceId`）；
3. `input_text(text="100", bounds=<数量>, class_name="EditText")`；
4. `input_text(text="9.63", bounds=<价格>, class_name="EditText")`；
5. 校验响应 `success=true`、`method="action_set_text"`、`after` 与输入一致；
   再用 `find_elements` 独立回读，并用 `get_focused_element` 确认焦点在价格框；
6. 点击顶部「查询」标签退出买入表单，进入下一轮。

结果：**20/20 全部通过**，每轮数量=`100`、价格=`9.63`、焦点回读=`9.63`、
独立 `find_elements` 回读一致。

表单中实测 bounds：

| 字段 | bounds | resourceId | text（空时） |
|---|---|---|---|
| 价格 | `[302,440][478,529]` | 无 | `价格`（hint） |
| 数量 | `[147,587][478,676]` | 无 | `数量`（hint） |
| 股票代码 | `[58,329][567,418]` | `com.hexin.plat.android:id/auto_stockcode` | `股票代码/简拼` |

## 5. 错误码与回归

真机验证：

| 场景 | 结果 |
|---|---|
| 过期 bounds | `TARGET_NOT_FOUND` |
| 非法 bounds 字符串 | `INVALID_BOUNDS` |
| 非可编辑 TextView（「可买」） | `INPUT_NOT_EDITABLE`（target 带 `editable=false`） |
| 旧参数 `element_text="数量"` | 成功，`after="100"` |
| bounds 为 JSON object | 成功 |
| `read_back=false` | 成功 |
| `allow_clipboard=false` | `action_set_text` 成功 |
| `element_id` 定位 + `append=true` | `before="100"` → `after="1005"` |

## 6. 无委托确认

验收前后均进入「查询 → 当日委托」，页面显示 **「没有委托数据」**。
20 轮输入仅使用 `ACTION_SET_TEXT` 修改表单字段，从未点击最终「买入」按钮，
未产生委托或成交。

## 7. 未完成 / 限制

- 仅在单台设备（HUAWEI NOH-AN01）完成验收；未做第二台同版本设备复测。
- thsTrade 客户端切换、334+ 测试与 `OrderPage` dry-run 未执行（属于 thsTrade 会话）。
- thsTrade `[execution].enabled` **保持 `false`**；需在 thsTrade 侧 dry-run 与人工复核后再开启。
- 未创建 git tag、未提交代码（提交/打 tag 需单独授权）。
- 未新增 ADB、root、Instrumentation、`INJECT_EVENTS`；输入仅走
  `AccessibilityNodeInfo.ACTION_SET_TEXT`，可回退 `ACTION_PASTE`。
