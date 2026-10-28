[← Back to README](../README.md)

# 🔧 Troubleshooting

### 🌐 Cannot connect to MCP server (HTTP)

```bash
# 1. Check that both devices are reachable on the same network
#    The app shows its IP on the main screen

# 2. Verify the server is running (use the IP shown in the app)
curl http://<device-ip>:7474/health
```

Check the persistent **MCP Server** notification: it shows the listening URL, or
the exact error (for example `port 7474 already in use`). The server runs only
while the app's master switch is ON. The notification's **Restart** action
retries without reopening the app.

---

### ♿ AccessibilityService not running

```bash
# Check current status
adb shell settings get secure enabled_accessibility_services

# Re-enable
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

On Android 15+, you may also need to enable **"Allow restricted settings"** for NeuralBridge in Settings > Apps.

---

### 📸 Screenshots use the slow accessibility fallback

MediaProjection needs explicit user consent since 0.7.0. Open **Setup →
MediaProjection → GRANT** and accept the system dialog. The grant is single-use
and resets when the app process dies or the device reboots, so repeat it after a
crash or reboot. Without it, screenshots still work through
`AccessibilityService.takeScreenshot()` (slower).

If the grant succeeds but Setup stays at 3/4, check the other cards — the count
includes accessibility, notification access, battery optimization and
MediaProjection.

---

### 🐌 High latency on first screenshot

The first screenshot after MediaProjection setup creates the VirtualDisplay and
may take 100-300ms. Subsequent captures return the buffered frame in roughly
20-30ms on a 896×1848 device.

---

### 💥 Companion app crashes or stops responding

Since 0.7.0 the MCP server recovers on its own:

- After a low-memory kill or crash, `START_STICKY` recreates the foreground
  service and the watchdog/monitor restarts the HTTP server (measured ~2s on a
  HUAWEI NOH-AN01). The persistent notification reappears automatically.
- After a reboot or app update, `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` restarts
  the server when the master switch is ON.
- `am force-stop` is different: Android never restarts a force-stopped app. Open
  NeuralBridge once; the server starts immediately.

```bash
# Check crash logs
adb logcat -s NeuralBridge:V McpForegroundService:V McpServerController:V

# Manual recovery (opening the app is enough in normal cases)
adb shell am start -n com.neuralbridge.companion/.MainActivity

# Accessibility may need re-enabling after a crash/force-stop
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

If the app is killed repeatedly by the OEM's background manager, allow it in
**Settings → Battery → App launch → NeuralBridge → Manage manually** (Auto-launch,
Secondary launch, Run in background) and exempt it from battery optimization.
