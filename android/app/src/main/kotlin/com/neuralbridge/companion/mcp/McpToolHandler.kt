package com.neuralbridge.companion.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.input.InputTargeting
import com.neuralbridge.companion.input.InputTargeting.area
import com.neuralbridge.companion.input.InputTargeting.formatBounds
import com.neuralbridge.companion.input.InputTargeting.matches
import com.neuralbridge.companion.input.InputTargeting.textMatches
import com.neuralbridge.companion.notification.NotificationListener
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import com.neuralbridge.companion.service.AccessibilityEventListener
import com.neuralbridge.companion.service.CompanionServices
import com.neuralbridge.companion.service.GestureResultCallback
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import com.neuralbridge.companion.service.Bounds
import com.neuralbridge.companion.service.ScreenshotQuality
import com.neuralbridge.companion.service.UiElement
import com.neuralbridge.companion.service.UiTree
import com.neuralbridge.companion.uitree.UiTreeWalker
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import android.accessibilityservice.GestureDescription
import kotlin.coroutines.resume

class McpToolHandler(
    private val appContext: Context,
    private val servicesProvider: () -> CompanionServices?
) {
    companion object {
        private const val TAG = "McpToolHandler"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val POLL_INTERVAL_MS = 300L
        private const val MAX_RECURSION_DEPTH = 50

        /** Stable error code when the AccessibilityService is not bound. */
        const val ACCESSIBILITY_NOT_CONNECTED = "ACCESSIBILITY_NOT_CONNECTED"

        /**
         * Tools that work without the AccessibilityService. Everything else
         * answers ACCESSIBILITY_NOT_CONNECTED instead of throwing an NPE.
         */
        private val ACCESSIBILITY_INDEPENDENT_TOOLS = setOf(
            "android_get_notifications",
            "android_get_recent_toasts",
            "android_launch_app",
            "android_open_url",
            "android_set_clipboard",
            "android_list_apps",
            "android_get_device_info",
            "android_list_devices",
            "android_select_device",
            "android_search_tools",
            "android_describe_tools"
        )
    }

    // Resolved per call so the MCP server survives accessibility reconnects.
    private val companionServices: CompanionServices
        get() = servicesProvider()
            ?: throw IllegalStateException("Accessibility service is not connected")

    private val service: NeuralBridgeAccessibilityService get() = companionServices.service
    private val gestureEngine: GestureEngine get() = companionServices.gestureEngine
    private val uiTreeWalker: UiTreeWalker get() = companionServices.uiTreeWalker
    private val inputEngine: InputEngine get() = companionServices.inputEngine
    private val screenshotPipeline: ScreenshotPipeline get() = companionServices.screenshotPipeline

    private val sequenceGate = SequenceGate()

    suspend fun handleToolCall(toolName: String, arguments: JsonObject?): McpToolCallResult {
        val args = arguments ?: JsonObject(emptyMap())
        if (toolName !in ACCESSIBILITY_INDEPENDENT_TOOLS && servicesProvider() == null) {
            return accessibilityNotConnectedResult()
        }
        return try {
            when (toolName) {
                // OBSERVE
                "android_get_ui_tree" -> handleGetUiTree(args)
                "android_screenshot" -> handleScreenshot(args)
                "android_find_elements" -> handleFindElements(args)
                "android_get_focused_element" -> handleGetFocusedElement()
                "android_get_screen_context" -> handleGetScreenContext(args)
                "android_get_notifications" -> handleGetNotifications(args)
                "android_accessibility_audit" -> handleAccessibilityAudit(args)
                "android_screenshot_diff" -> handleScreenshotDiff(args)
                "android_get_recent_toasts" -> handleGetRecentToasts(args)

                // ACT
                "android_tap" -> handleTap(args)
                "android_long_press" -> handleLongPress(args)
                "android_double_tap" -> handleDoubleTap(args)
                "android_swipe" -> handleSwipe(args)
                "android_pinch" -> handlePinch(args)
                "android_drag" -> handleDrag(args)
                "android_input_text" -> handleInputText(args)
                "android_press_key" -> handlePressKey(args)
                "android_global_action" -> handleGlobalAction(args)

                // MANAGE
                "android_launch_app" -> handleLaunchApp(args)
                "android_close_app" -> handleCloseApp(args)
                "android_open_url" -> handleOpenUrl(args)
                "android_set_clipboard" -> handleSetClipboard(args)
                "android_list_apps" -> handleListApps(args)

                // WAIT
                "android_wait_for_element" -> handleWaitForElement(args)
                "android_wait_for_gone" -> handleWaitForGone(args)
                "android_wait_for_idle" -> handleWaitForIdle(args)
                "android_scroll_to_element" -> handleScrollToElement(args)
                "android_sequence" -> handleSequence(args)

                // DEVICE (stubs — we are the device)
                "android_list_devices" -> handleListDevices()
                "android_select_device" -> textResult("{\"success\":true,\"message\":\"Already connected to this device\"}")

                // META
                "android_search_tools" -> handleSearchTools(args)
                "android_describe_tools" -> handleDescribeTools(args)

                // TEST
                "android_enable_events" -> handleEnableEvents(args)
                "android_get_device_info" -> handleGetDeviceInfo()

                else -> errorResult("Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool error: $toolName", e)
            errorResult("Tool error: ${e.message}")
        }
    }

    private fun accessibilityNotConnectedResult(): McpToolCallResult = errorResult(
        buildJsonObject {
            put("success", false)
            putJsonObject("error") {
                put("code", ACCESSIBILITY_NOT_CONNECTED)
                put(
                    "message",
                    "Accessibility service is not connected. " +
                        "Enable NeuralBridge in Settings > Accessibility, then retry."
                )
            }
        }.toString()
    )

    // =====================================================================
    // GESTURE BRIDGE: callback → suspend
    // =====================================================================

    private suspend fun executeGestureAndWait(block: (GestureResultCallback) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            block(object : GestureResultCallback {
                override fun onCompleted(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            // AccessibilityService gestures cannot be cancelled once dispatched;
            // the callback guards with isActive so the result is silently discarded.
            cont.invokeOnCancellation { /* no-op: gesture runs to completion */ }
        }

    // =====================================================================
    // SELECTOR RESOLUTION
    // =====================================================================

    private fun resolveSelector(tree: UiTree, text: String?, resourceId: String?, contentDesc: String?): UiElement? {
        return tree.elements.firstOrNull { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true || e.resourceId == resourceId)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true)
        }
    }

    // =====================================================================
    // OBSERVE TOOLS
    // =====================================================================

    private suspend fun handleGetUiTree(args: JsonObject): McpToolCallResult {
        val includeInvisible = args["include_invisible"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 0
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "interactive"

        val result = buildUiTreeJson(filter, includeInvisible, maxDepth)
            ?: return errorResult("No active window available")
        return textResult(result.toString())
    }

    /**
     * Shared UI-tree snapshot. The JSON is identical to android_get_ui_tree so
     * sequence results can reuse the same client parser.
     */
    private suspend fun buildUiTreeJson(
        filter: String,
        includeInvisible: Boolean = false,
        maxDepth: Int = 0
    ): JsonObject? {
        val rootNode = service.rootInActiveWindow ?: return null

        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible, maxDepth)

        val filtered = when (filter) {
            "all" -> tree.elements
            "text" -> tree.elements.filter { !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
            else -> tree.elements.filter { it.clickable || it.focusable || it.scrollable || it.checkable || !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
        }

        val table = buildString {
            append("IDX | resource_id | text | desc | flags | bounds\n")
            filtered.forEachIndexed { idx, e ->
                val flags = buildString {
                    if (e.clickable) append("c")
                    if (e.focusable) append("f")
                    if (e.scrollable) append("s")
                    if (e.checkable) append("k")
                }
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: ""
                append("$idx | ${e.resourceId ?: ""} | ${e.text ?: ""} | ${e.contentDescription ?: ""} | $flags | $bounds\n")
            }
        }

        return buildJsonObject {
            put("format", "compact")
            put("app", tree.foregroundApp)
            put("total", tree.totalNodes)
            put("shown", filtered.size)
            put("filter", filter)
            put("elements", table.trimEnd())
        }
    }

    private suspend fun currentTreeOrNull(): UiTree? {
        val rootNode = service.rootInActiveWindow ?: return null
        return uiTreeWalker.walkTree(rootNode)
    }

    private suspend fun handleScreenshot(args: JsonObject): McpToolCallResult {
        val quality = if (args["quality"]?.jsonPrimitive?.contentOrNull == "thumbnail")
            ScreenshotQuality.THUMBNAIL else ScreenshotQuality.FULL

        val jpegBytes = screenshotPipeline.capture(quality)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dm = service.resources.displayMetrics
        val meta = buildJsonObject {
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            put("format", "jpeg")
        }.toString()
        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg"),
            McpContentBlock(type = "text", text = meta)
        ))
    }

    private suspend fun handleFindElements(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val className = args["class_name"]?.jsonPrimitive?.contentOrNull
        val findAll = args["find_all"]?.jsonPrimitive?.booleanOrNull ?: false
        val editableOnly = args["editable"]?.jsonPrimitive?.booleanOrNull ?: false
        val visibleOnly = args["visible"]?.jsonPrimitive?.booleanOrNull ?: true
        val preferEditable = args["prefer_editable"]?.jsonPrimitive?.booleanOrNull ?: true

        if (text == null && resourceId == null && contentDesc == null && className == null) {
            return errorResult("At least one selector (text, resource_id, content_desc, class_name) required")
        }

        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible = !visibleOnly)

        var matches = tree.elements.filter { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true) ||
            (className != null && e.className?.contains(className) == true)
        }.filter { e ->
            (!editableOnly || e.editable) && (!visibleOnly || e.visible)
        }

        if (preferEditable) {
            matches = matches.sortedWith(
                compareBy<UiElement> { if (it.editable) 0 else 1 }
                    .thenBy { if (it.visible) 0 else 1 }
                    .thenBy { if (it.focusable) 0 else 1 }
                    .thenBy { it.bounds?.area() ?: Long.MAX_VALUE }
            )
        }

        val returned = if (findAll) matches else matches.take(1)

        val result = buildJsonObject {
            putJsonArray("elements") {
                returned.forEach { e ->
                    addJsonObject {
                        put("elementId", e.elementId)
                        put("resourceId", e.resourceId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("className", e.className?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("text", e.text?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("contentDescription", e.contentDescription?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("bounds", e.bounds?.let { JsonPrimitive(formatBounds(it)) } ?: JsonNull)
                        put("clickable", e.clickable)
                        put("editable", e.editable)
                        put("focusable", e.focusable)
                        put("enabled", e.enabled)
                        put("visible", e.visible)
                    }
                }
            }
            put("total_matches", matches.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleGetScreenContext(args: JsonObject): McpToolCallResult {
        val includeAll = args["include_all_elements"]?.jsonPrimitive?.booleanOrNull ?: false
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)
        val jpegBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val filtered = if (includeAll) tree.elements
        else tree.elements.filter { it.clickable || it.focusable || it.checkable || it.scrollable || !it.text.isNullOrEmpty() }

        val meta = buildJsonObject {
            putJsonObject("app_info") { put("package_name", tree.foregroundApp) }
            putJsonObject("ui_tree") {
                put("total_elements", tree.totalNodes)
                put("filtered_elements", filtered.size)
                putJsonArray("elements") {
                    filtered.forEach { e ->
                        addJsonObject {
                            e.resourceId?.let { put("resourceId", it) }
                            e.text?.let { put("text", it) }
                            e.bounds?.let { b ->
                                put("center_x", (b.left + b.right) / 2)
                                put("center_y", (b.top + b.bottom) / 2)
                                put("bounds", "[${b.left},${b.top},${b.right},${b.bottom}]")
                            }
                        }
                    }
                }
            }
        }.toString()

        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "text", text = meta),
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg")
        ))
    }

    private fun handleGetNotifications(args: JsonObject): McpToolCallResult {
        val notifications = NotificationListener.instance?.getActiveNotificationsList() ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("notifications") {
                notifications.forEach { n ->
                    addJsonObject {
                        put("package_name", n.packageName)
                        put("title", n.title)
                        put("text", n.text)
                        put("post_time", n.postTime)
                        put("ongoing", n.ongoing)
                        put("clearable", n.clearable)
                    }
                }
            }
            put("count", notifications.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleAccessibilityAudit(args: JsonObject): McpToolCallResult {
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible = false)

        val issues = mutableListOf<String>()
        tree.elements.forEach { e ->
            if (e.clickable && e.contentDescription.isNullOrEmpty() && e.text.isNullOrEmpty()) {
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "unknown"
                issues.add("Missing content description on clickable element (${e.className ?: "unknown"}) at $bounds")
            }
            e.bounds?.let { b ->
                val width = b.right - b.left
                val height = b.bottom - b.top
                val dm = service.resources.displayMetrics
                val minPx = (48 * dm.density).toInt()
                if (e.clickable && (width < minPx || height < minPx)) {
                    issues.add("Small touch target ${width}x${height}px (min ${minPx}px) on ${e.text ?: e.contentDescription ?: e.className ?: "element"}")
                }
            }
        }

        val result = buildJsonObject {
            put("issues_found", issues.size)
            put("pass", issues.isEmpty())
            putJsonArray("issues") { issues.forEach { add(JsonPrimitive(it)) } }
        }
        return textResult(result.toString())
    }

    private suspend fun handleScreenshotDiff(args: JsonObject): McpToolCallResult {
        val referenceBase64 = args["reference_base64"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("reference_base64 is required")
        val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.95

        val currentBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val referenceBytes = Base64.decode(referenceBase64, Base64.DEFAULT)

        val currentBitmap = android.graphics.BitmapFactory.decodeByteArray(currentBytes, 0, currentBytes.size)
            ?: return errorResult("Failed to decode current screenshot as bitmap")
        val referenceBitmap = android.graphics.BitmapFactory.decodeByteArray(referenceBytes, 0, referenceBytes.size)
            ?: return errorResult("Failed to decode reference_base64 as bitmap")

        // Scale reference to current dimensions if needed
        val scaledRef = if (referenceBitmap.width != currentBitmap.width ||
                            referenceBitmap.height != currentBitmap.height) {
            android.graphics.Bitmap.createScaledBitmap(
                referenceBitmap, currentBitmap.width, currentBitmap.height, true)
        } else referenceBitmap

        val width = currentBitmap.width
        val height = currentBitmap.height
        val currentPixels = IntArray(width * height)
        val refPixels = IntArray(width * height)
        currentBitmap.getPixels(currentPixels, 0, width, 0, 0, width, height)
        scaledRef.getPixels(refPixels, 0, width, 0, 0, width, height)

        // Count pixels within ±10 per channel (RGB)
        var matching = 0L
        for (i in currentPixels.indices) {
            val c1 = currentPixels[i]; val c2 = refPixels[i]
            val rDiff = ((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF)).let { if (it < 0) -it else it }
            val gDiff = ((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF)).let { if (it < 0) -it else it }
            val bDiff = ((c1 and 0xFF) - (c2 and 0xFF)).let { if (it < 0) -it else it }
            if (rDiff + gDiff + bDiff <= 30) matching++
        }
        val similarity = if (currentPixels.isNotEmpty()) matching.toDouble() / currentPixels.size else 0.0
        val matches = similarity >= threshold

        val result = buildJsonObject {
            put("similarity", similarity)
            put("threshold", threshold)
            put("matches", matches)
        }
        return textResult(result.toString())
    }

    private fun handleGetRecentToasts(args: JsonObject): McpToolCallResult {
        val sinceMs = args["since_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        val cutoff = System.currentTimeMillis() - sinceMs
        val toasts = NeuralBridgeAccessibilityService.recentToasts.filter { it.second >= cutoff }
        val result = buildJsonObject {
            putJsonArray("toasts") {
                toasts.forEach { (text, time) ->
                    addJsonObject {
                        put("text", text)
                        put("timestamp", time)
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    // =====================================================================
    // ACT TOOLS
    // =====================================================================

    private suspend fun handleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull

        val (tapX, tapY) = if (x != null && y != null) {
            x.toFloat() to y.toFloat()
        } else {
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found: text=$text, resource_id=$resourceId, content_desc=$contentDesc")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeTap(tapX, tapY, cb) }
        } ?: false

        return if (success) textResult("{\"latency_ms\":0}")
        else errorResult("Tap gesture was cancelled or timed out")
    }

    private suspend fun handleLongPress(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val (lx, ly) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, null)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeLongPress(lx, ly, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Long press cancelled")
    }

    private suspend fun handleDoubleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()

        val (dx, dy) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDoubleTap(dx, dy, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Double tap cancelled")
    }

    private suspend fun handleSwipe(args: JsonObject): McpToolCallResult {
        val startX = args["start_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_x required")
        val startY = args["start_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_y required")
        val endX = args["end_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_x required")
        val endY = args["end_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeSwipe(startX, startY, endX, endY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Swipe cancelled")
    }

    private suspend fun handlePinch(args: JsonObject): McpToolCallResult {
        val centerX = args["center_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_x required")
        val centerY = args["center_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_y required")
        val scale = args["scale"]?.jsonPrimitive?.floatOrNull ?: return errorResult("scale required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executePinch(centerX, centerY, scale, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Pinch cancelled")
    }

    private suspend fun handleDrag(args: JsonObject): McpToolCallResult {
        val fromX = args["from_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_x required")
        val fromY = args["from_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_y required")
        val toX = args["to_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_x required")
        val toY = args["to_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDrag(fromX, fromY, toX, toY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Drag cancelled")
    }

    private suspend fun handleInputText(args: JsonObject): McpToolCallResult {
        args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")

        return when (val outcome = performInputText(args)) {
            is InputOutcome.Success -> {
                val payload = buildJsonObject {
                    put("success", true)
                    put("method", outcome.method)
                    putTarget(outcome.target)
                    put("before", outcome.before)
                    put("after", outcome.after)
                    put("latency_ms", outcome.latencyMs)
                }
                textResult(payload.toString())
            }

            is InputOutcome.Failure -> {
                if (outcome.code == "NO_ACTIVE_WINDOW") {
                    errorResult(outcome.message)
                } else {
                    inputFailure(outcome.code, outcome.message, outcome.target, outcome.before, outcome.after)
                }
            }
        }
    }

    /**
     * Shared implementation for android_input_text and sequence input_text steps.
     * Returns structured diagnostics without changing the single-tool JSON.
     */
    private suspend fun performInputText(args: JsonObject): InputOutcome {
        val startedAt = System.currentTimeMillis()
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return InputOutcome.Failure("INVALID_STEP", "text required")
        val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
        val readBack = args["read_back"]?.jsonPrimitive?.booleanOrNull ?: true
        val allowClipboard = args["allow_clipboard"]?.jsonPrimitive?.booleanOrNull ?: true
        val tolerance = (args["bounds_tolerance"]?.jsonPrimitive?.intOrNull ?: 2).coerceAtLeast(0)

        val elementId = args["element_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val elementText = args["element_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val className = args["class_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val boundsArg = args["bounds"]
        val bounds = if (boundsArg == null || boundsArg is JsonNull) {
            null
        } else {
            InputTargeting.parseBounds(boundsArg)
                ?: return InputOutcome.Failure(
                    "INVALID_BOUNDS",
                    "bounds must be \"[l,t][r,b]\", \"[l,t,r,b]\" or {left,top,right,bottom}"
                )
        }

        val rootNode = service.rootInActiveWindow
            ?: return InputOutcome.Failure("NO_ACTIVE_WINDOW", "No active window")
        val tree = uiTreeWalker.walkTree(rootNode)
        val spec = TargetSpec(elementId, resourceId, bounds, className, elementText, tolerance)

        when (val resolution = resolveTarget(rootNode, tree, spec)) {
            is TargetResolution.Failure ->
                return InputOutcome.Failure(
                    resolution.code,
                    resolution.message,
                    resolution.target
                )

            is TargetResolution.Found -> {
                val node = resolution.node
                try {
                    val target = snapshotTarget(node, resolution.element)
                    val before = readNodeText(node)

                    val method = when {
                        inputEngine.trySetText(node, text, append) -> "action_set_text"
                        allowClipboard && inputEngine.tryClipboard(node, text, append) -> "clipboard"
                        else -> return InputOutcome.Failure(
                            "INPUT_REJECTED",
                            if (allowClipboard) {
                                "ACTION_SET_TEXT and clipboard fallback both failed"
                            } else {
                                "ACTION_SET_TEXT rejected and clipboard fallback is disabled"
                            },
                            target
                        )
                    }

                    var after = before
                    if (readBack) {
                        var verified = false
                        for (attempt in 0 until 3) {
                            if (attempt > 0) delay(50L)
                            refreshNode(node)
                            after = readNodeText(node)
                            if (textMatches(after, text, append, before)) {
                                verified = true
                                break
                            }
                        }
                        if (!verified) {
                            return InputOutcome.Failure(
                                "INPUT_VERIFY_FAILED",
                                "operation reported success but read-back did not match the requested text",
                                target,
                                before,
                                after
                            )
                        }
                    } else {
                        refreshNode(node)
                        after = readNodeText(node)
                    }

                    return InputOutcome.Success(
                        method = method,
                        target = target,
                        before = before,
                        after = after,
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                } finally {
                    recycle(node)
                }
            }
        }
    }

    private sealed class InputOutcome {
        data class Success(
            val method: String,
            val target: TargetSnapshot,
            val before: String,
            val after: String,
            val latencyMs: Long
        ) : InputOutcome()

        data class Failure(
            val code: String,
            val message: String,
            val target: TargetSnapshot? = null,
            val before: String? = null,
            val after: String? = null
        ) : InputOutcome()
    }

    private data class TargetSpec(
        val elementId: String? = null,
        val resourceId: String? = null,
        val bounds: Bounds? = null,
        val className: String? = null,
        val text: String? = null,
        val tolerance: Int = 2
    )

    private sealed class TargetResolution {
        data class Found(
            val element: UiElement?,
            val node: AccessibilityNodeInfo
        ) : TargetResolution()

        data class Failure(
            val code: String,
            val message: String,
            val target: TargetSnapshot? = null
        ) : TargetResolution()
    }

    private data class TargetSnapshot(
        val elementId: String?,
        val resourceId: String?,
        val className: String?,
        val editable: Boolean,
        val bounds: String?
    )

    private data class ElementSelection(
        val element: UiElement?,
        val ambiguous: Boolean = false
    )

    private data class NodeLookup(
        val node: AccessibilityNodeInfo? = null,
        val ambiguous: Boolean = false
    )

    /**
     * element_id > resource_id > bounds (+ class_name) > element_text > focused editable.
     * The first supplied selector is authoritative: stale bounds must fail instead of
     * silently typing into a different field.
     */
    private fun resolveTarget(
        root: AccessibilityNodeInfo,
        tree: UiTree,
        spec: TargetSpec
    ): TargetResolution {
        spec.elementId?.let { elementId ->
            val candidates = tree.elements.filter { it.elementId == elementId }
            if (candidates.isEmpty()) {
                return TargetResolution.Failure(
                    "TARGET_NOT_FOUND",
                    "No element with elementId=$elementId"
                )
            }
            val selection = selectElement(candidates, detectAmbiguity = true)
            if (selection.ambiguous) {
                return TargetResolution.Failure(
                    "TARGET_AMBIGUOUS",
                    "Multiple elements share elementId=$elementId; target by bounds instead",
                    selection.element?.toSnapshot()
                )
            }
            return resolveNodeForElement(root, selection.element!!, spec.tolerance)
        }

        spec.resourceId?.let { resourceId ->
            val candidates = tree.elements.filter { it.resourceId?.endsWith(resourceId) == true }
            if (candidates.isEmpty()) {
                return TargetResolution.Failure(
                    "TARGET_NOT_FOUND",
                    "No element with resource_id=$resourceId"
                )
            }
            val element = selectElement(candidates, detectAmbiguity = false).element!!
            return resolveNodeForElement(root, element, spec.tolerance)
        }

        spec.bounds?.let { bounds ->
            val candidates = tree.elements.filter { e ->
                e.bounds?.matches(bounds, spec.tolerance) == true &&
                    (spec.className == null || e.className?.contains(spec.className) == true)
            }
            if (candidates.isEmpty()) {
                return TargetResolution.Failure(
                    "TARGET_NOT_FOUND",
                    "No element matched the supplied bounds/class_name (stale bounds?)"
                )
            }
            val selection = selectElement(candidates, detectAmbiguity = true)
            if (selection.ambiguous) {
                return TargetResolution.Failure(
                    "TARGET_AMBIGUOUS",
                    "Multiple nodes share these bounds; add class_name or use element_id",
                    selection.element?.toSnapshot()
                )
            }
            return resolveNodeForElement(root, selection.element!!, spec.tolerance)
        }

        spec.text?.let { text ->
            val element = tree.elements.firstOrNull {
                it.text?.contains(text, ignoreCase = true) == true
            } ?: return TargetResolution.Failure(
                "TARGET_NOT_FOUND",
                "No element with text=$text"
            )
            return resolveNodeForElement(root, element, spec.tolerance)
        }

        return resolveFocusedOrLegacy(root, tree, spec.tolerance)
    }

    private fun resolveFocusedOrLegacy(
        root: AccessibilityNodeInfo,
        tree: UiTree,
        tolerance: Int
    ): TargetResolution {
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }

        if (focused != null) {
            val element = matchElementForNode(tree, focused)
            val editable = findEditableNode(focused)
            if (editable == null) {
                val target = snapshotTarget(focused, element)
                recycle(focused)
                return TargetResolution.Failure(
                    "INPUT_NOT_EDITABLE",
                    "Focused node is not editable",
                    target
                )
            }
            if (editable !== focused) recycle(focused)
            refreshNode(editable)
            return TargetResolution.Found(element, editable)
        }

        // Legacy no-selector behavior: first focusable input-like element.
        val legacy = tree.elements.firstOrNull { e ->
            e.focusable && (
                e.className?.contains("EditText") == true ||
                    e.className?.contains("TextField") == true ||
                    e.className == "android.widget.AutoCompleteTextView" ||
                    e.className?.contains("SearchView") == true
                )
        } ?: tree.elements.firstOrNull { it.editable }

        if (legacy == null) {
            return TargetResolution.Failure(
                "TARGET_NOT_FOUND",
                "No focused editable element found. Specify element_id, resource_id, bounds or element_text."
            )
        }
        return resolveNodeForElement(root, legacy, tolerance)
    }

    private fun resolveNodeForElement(
        root: AccessibilityNodeInfo,
        element: UiElement,
        tolerance: Int
    ): TargetResolution {
        val lookup = findNodeForElement(root, element, tolerance)
        if (lookup.ambiguous) {
            return TargetResolution.Failure(
                "TARGET_AMBIGUOUS",
                "Multiple nodes match this target; add class_name or use element_id",
                element.toSnapshot()
            )
        }

        val node = lookup.node ?: return TargetResolution.Failure(
            "TARGET_NOT_FOUND",
            "Element is no longer present on screen (stale bounds?)",
            element.toSnapshot()
        )

        val editable = findEditableNode(node)
        if (editable == null) {
            val target = snapshotTarget(node, element)
            recycle(node)
            return TargetResolution.Failure(
                "INPUT_NOT_EDITABLE",
                "matched a non-editable wrapper; no editable descendant found",
                target
            )
        }
        if (editable !== node) recycle(node)
        refreshNode(editable)
        return TargetResolution.Found(element, editable)
    }

    private fun selectElement(
        candidates: List<UiElement>,
        detectAmbiguity: Boolean
    ): ElementSelection {
        if (candidates.isEmpty()) return ElementSelection(null)
        val comparator = compareBy<UiElement>(
            { if (it.editable) 0 else 1 },
            { if (it.visible) 0 else 1 },
            { if (it.focusable) 0 else 1 },
            { it.bounds?.area() ?: Long.MAX_VALUE }
        )
        val sorted = candidates.sortedWith(comparator)
        val best = sorted.first()
        val ambiguous = detectAmbiguity &&
            sorted.size > 1 &&
            comparator.compare(best, sorted[1]) == 0
        return ElementSelection(best, ambiguous)
    }

    /**
     * Locate the real node for a UiElement: resourceId first, then a recursive
     * bounds+className scan. Prefer editable, visible, focusable, then smaller area.
     */
    private fun findNodeForElement(
        root: AccessibilityNodeInfo,
        element: UiElement,
        tolerance: Int
    ): NodeLookup {
        val bounds = element.bounds ?: return NodeLookup()
        val className = element.className
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        element.resourceId?.let { viewId ->
            val results = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (e: Exception) {
                emptyList()
            }
            results.forEach { node ->
                if (nodeMatches(node, bounds, className, tolerance)) {
                    candidates.add(node)
                } else {
                    recycle(node)
                }
            }
        }

        if (candidates.isEmpty()) {
            collectNodesByBounds(root, bounds, className, tolerance, 0, candidates)
        }

        if (candidates.isEmpty()) return NodeLookup()

        val comparator = compareBy<AccessibilityNodeInfo>(
            { if (it.isEditable) 0 else 1 },
            { if (it.isVisibleToUser) 0 else 1 },
            { if (it.isFocusable) 0 else 1 },
            { nodeArea(it) }
        )
        val sorted = candidates.sortedWith(comparator)
        val best = sorted.first()

        if (sorted.size > 1 && comparator.compare(best, sorted[1]) == 0) {
            candidates.forEach { recycle(it) }
            return NodeLookup(ambiguous = true)
        }

        candidates.forEach { if (it !== best) recycle(it) }
        return NodeLookup(node = best)
    }

    private fun collectNodesByBounds(
        node: AccessibilityNodeInfo,
        bounds: Bounds,
        className: String?,
        tolerance: Int,
        depth: Int,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (depth > MAX_RECURSION_DEPTH) return

        if (nodeMatches(node, bounds, className, tolerance)) {
            @Suppress("DEPRECATION")
            out.add(AccessibilityNodeInfo.obtain(node))
        }

        val childCount = try {
            node.childCount
        } catch (e: Exception) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                collectNodesByBounds(child, bounds, className, tolerance, depth + 1, out)
            } finally {
                recycle(child)
            }
        }
    }

    private fun nodeMatches(
        node: AccessibilityNodeInfo,
        bounds: Bounds,
        className: String?,
        tolerance: Int
    ): Boolean {
        if (className != null && node.className?.toString() != className) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Bounds(rect.left, rect.top, rect.right, rect.bottom).matches(bounds, tolerance)
    }

    private fun findEditableNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > MAX_RECURSION_DEPTH) return null
        if (node.isEditable) return node

        val childCount = try {
            node.childCount
        } catch (e: Exception) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            val found = findEditableNode(child, depth + 1)
            if (found != null) {
                if (found !== child) recycle(child)
                return found
            }
            recycle(child)
        }
        return null
    }

    private fun matchElementForNode(tree: UiTree, node: AccessibilityNodeInfo): UiElement? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        val className = node.className?.toString()
        val resourceId = node.viewIdResourceName
        val text = node.text?.toString()

        return tree.elements.firstOrNull { e ->
            e.bounds?.matches(bounds, 0) == true &&
                e.className == className &&
                (resourceId == null || e.resourceId == resourceId)
        } ?: tree.elements.firstOrNull { e ->
            resourceId != null && e.resourceId == resourceId
        } ?: tree.elements.firstOrNull { e ->
            e.bounds?.matches(bounds, 0) == true && e.text == text
        }
    }

    private fun snapshotTarget(
        node: AccessibilityNodeInfo,
        element: UiElement?
    ): TargetSnapshot {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (e: Exception) {
            // Keep zeros; fall back to the UiElement bounds below.
        }

        val outputBounds = if (rect.right > rect.left && rect.bottom > rect.top) {
            formatBounds(Bounds(rect.left, rect.top, rect.right, rect.bottom))
        } else {
            element?.bounds?.let { formatBounds(it) }
        }

        return TargetSnapshot(
            elementId = try {
                element?.elementId ?: uiTreeWalker.elementIdFor(node)
            } catch (e: Exception) {
                element?.elementId
            },
            resourceId = node.viewIdResourceName?.takeIf { it.isNotEmpty() } ?: element?.resourceId,
            className = node.className?.toString()?.takeIf { it.isNotEmpty() } ?: element?.className,
            editable = try {
                node.isEditable
            } catch (e: Exception) {
                element?.editable ?: false
            },
            bounds = outputBounds
        )
    }

    private fun UiElement.toSnapshot(): TargetSnapshot = TargetSnapshot(
        elementId = elementId,
        resourceId = resourceId,
        className = className,
        editable = editable,
        bounds = bounds?.let { formatBounds(it) }
    )

    private fun inputFailure(
        code: String,
        message: String,
        target: TargetSnapshot? = null,
        before: String? = null,
        after: String? = null
    ): McpToolCallResult {
        val payload = buildJsonObject {
            put("success", false)
            put("error_code", code)
            put("message", message)
            if (target != null) putTarget(target)
            if (before != null) put("before", before)
            if (after != null) put("after", after)
        }
        return errorResult(payload.toString())
    }

    private fun JsonObjectBuilder.putTarget(target: TargetSnapshot) {
        putJsonObject("target") {
            put("elementId", target.elementId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("resourceId", target.resourceId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("className", target.className?.let { JsonPrimitive(it) } ?: JsonNull)
            put("editable", target.editable)
            put("bounds", target.bounds?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    private fun readNodeText(node: AccessibilityNodeInfo): String = try {
        node.text?.toString().orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun refreshNode(node: AccessibilityNodeInfo) {
        try {
            node.refresh()
        } catch (e: Exception) {
            // Node may be stale after the input operation; readNodeText handles that.
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (e: Exception) {
            // Already recycled / not owned by us; nothing to do.
        }
    }

    private fun nodeArea(node: AccessibilityNodeInfo): Long {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (e: Exception) {
            return Long.MAX_VALUE
        }
        val width = (rect.right - rect.left).coerceAtLeast(0).toLong()
        val height = (rect.bottom - rect.top).coerceAtLeast(0).toLong()
        return width * height
    }

    private suspend fun handleGetFocusedElement(): McpToolCallResult {
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val focused = try {
            rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        } ?: return textResult("{\"focused\":false}")

        return try {
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = matchElementForNode(tree, focused)
            val rect = Rect()
            focused.getBoundsInScreen(rect)
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focused.hintText?.toString()
            } else {
                null
            }

            val payload = buildJsonObject {
                put("focused", true)
                put(
                    "elementId",
                    (element?.elementId ?: uiTreeWalker.elementIdFor(focused)).let { JsonPrimitive(it) }
                )
                put("resourceId", focused.viewIdResourceName?.let { JsonPrimitive(it) } ?: JsonNull)
                put("className", focused.className?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "text",
                    InputTargeting.maskedText(focused.text?.toString(), focused.isPassword)
                        ?.let { JsonPrimitive(it) } ?: JsonNull
                )
                put("hint", hint?.let { JsonPrimitive(it) } ?: JsonNull)
                put("editable", focused.isEditable)
                put("bounds", formatBounds(Bounds(rect.left, rect.top, rect.right, rect.bottom)))
                put("package", focused.packageName?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            textResult(payload.toString())
        } finally {
            recycle(focused)
        }
    }

    private fun handlePressKey(args: JsonObject): McpToolCallResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return errorResult("key required")
        val error = pressKeyInternal(key)
        return if (error == null) textResult("{\"key\":\"$key\",\"latency_ms\":0}")
        else errorResult(error)
    }

    /** Returns null on success, or a client-safe error message. */
    private fun pressKeyInternal(key: String): String? {
        // Global actions first (don't need a focused node)
        val globalAction = when (key.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (globalAction != null) {
            service.performGlobalAction(globalAction)
            return null
        }

        // For other keys, delegate to InputEngine
        val focusedNode = service.rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
        )
        val success = inputEngine.pressKey(key, focusedNode)
        @Suppress("DEPRECATION")
        focusedNode?.recycle()
        return if (success) null
        else "Key '$key' not supported or no focused input field. Supported: back, home, recents, notifications, power (global); enter, delete/backspace, tab, escape, space, select_all, cut, copy, paste (requires focused field)."
    }

    private fun handleGlobalAction(args: JsonObject): McpToolCallResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return errorResult("action required")
        val globalAction = when (action.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return errorResult("Unknown action: $action. Valid: back, home, recents, notifications, quick_settings")
        }
        val success = service.performGlobalAction(globalAction)
        return if (success) textResult("{\"action\":\"$action\",\"latency_ms\":0}")
        else errorResult("Global action '$action' failed")
    }

    // =====================================================================
    // MANAGE TOOLS
    // =====================================================================

    private fun handleLaunchApp(args: JsonObject): McpToolCallResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull ?: return errorResult("package_name required")
        val clearTask = args["clear_task"]?.jsonPrimitive?.booleanOrNull ?: false

        val pm = appContext.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return errorResult("No launch intent found for package: $packageName")
        if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        else launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        return textResult("{\"package_name\":\"$packageName\",\"latency_ms\":0}")
    }

    private fun handleCloseApp(args: JsonObject): McpToolCallResult {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return textResult("{\"success\":true,\"note\":\"Moved to background via HOME.\"}")
    }

    private fun handleOpenUrl(args: JsonObject): McpToolCallResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return errorResult("url required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        return textResult("{\"url\":\"$url\",\"latency_ms\":0}")
    }

    private fun handleSetClipboard(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NeuralBridge", text))
        return textResult("{\"success\":true}")
    }

    private fun handleListApps(args: JsonObject): McpToolCallResult {
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "all"
        val pm = appContext.packageManager
        val packages = pm.getInstalledPackages(0)
        val filtered = when (filter) {
            "system" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            "third_party" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            else -> packages
        }
        val result = buildJsonObject {
            putJsonArray("apps") {
                filtered.forEach { pkg ->
                    addJsonObject {
                        put("package_name", pkg.packageName)
                        put("version_name", pkg.versionName ?: "")
                        put("is_system", (pkg.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                }
            }
            put("count", filtered.size)
        }
        return textResult(result.toString())
    }

    // =====================================================================
    // WAIT TOOLS
    // =====================================================================

    private suspend fun handleWaitForElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) {
                    return textResult("{\"found\":true,\"element\":{\"text\":\"${found.text}\",\"bounds\":\"${found.bounds}\"}}")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":false,\"timeout_ms\":$timeoutMs}")
    }

    private suspend fun handleWaitForGone(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) return textResult("{\"found\":false}")
            val tree = uiTreeWalker.walkTree(rootNode)
            val found = resolveSelector(tree, text, resourceId, contentDesc)
            if (found == null) return textResult("{\"found\":false}")
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":true,\"note\":\"Element still present after ${timeoutMs}ms\"}")
    }

    private suspend fun handleWaitForIdle(args: JsonObject): McpToolCallResult {
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        var lastEventTime = System.currentTimeMillis()
        val idleThreshold = 500L

        val listener = object : AccessibilityEventListener {
            override fun onEvent(event: android.view.accessibility.AccessibilityEvent) {
                lastEventTime = System.currentTimeMillis()
            }
        }
        service.registerEventListener(listener)
        return try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(idleThreshold)
                    if (System.currentTimeMillis() - lastEventTime >= idleThreshold) break
                }
            }
            textResult("{\"idle\":true}")
        } finally {
            // Always unregister — covers normal exit, timeout, and coroutine cancellation
            service.unregisterEventListener(listener)
        }
    }

    private suspend fun handleScrollToElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val maxScrolls = args["max_scrolls"]?.jsonPrimitive?.intOrNull ?: 20

        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = if (direction == "down") dm.heightPixels * 0.7f else dm.heightPixels * 0.3f
        val endY = if (direction == "down") dm.heightPixels * 0.3f else dm.heightPixels * 0.7f

        repeat(maxScrolls) { scrollCount ->
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) return textResult("{\"found\":true,\"scrolls\":$scrollCount}")
            }
            withTimeoutOrNull(1000L) {
                executeGestureAndWait { cb -> gestureEngine.executeSwipe(centerX, startY, centerX, endY, 300L, cb) }
            }
            delay(200)
        }
        return textResult("{\"found\":false,\"scrolls\":$maxScrolls}")
    }

    // =====================================================================
    // SEQUENCE TOOL
    // =====================================================================

    /**
     * Execute an ordered batch of UI actions in one MCP call.
     *
     * Mutating steps run exactly once; failures never trigger retries. The gate
     * prevents two sequences from interleaving on the same device.
     */
    private suspend fun handleSequence(args: JsonObject): McpToolCallResult {
        val parsed = SequenceValidation.parse(args)
        if (parsed is SequenceParseResult.Invalid) {
            return sequenceErrorResult(parsed.code, parsed.message, parsed.step)
        }
        val plan = (parsed as SequenceParseResult.Valid).plan

        if (!sequenceGate.tryEnter()) {
            return sequenceErrorResult(
                SequenceErrors.SEQUENCE_BUSY,
                "another sequence is already running on this device"
            )
        }

        return try {
            val startedNs = System.nanoTime()
            if (plan.settleMs > 0) {
                withTimeoutOrNull(minOf(plan.settleMs, plan.timeoutMs)) {
                    waitForIdleInternal(plan.settleMs)
                }
            }

            // Settle time counts against the overall timeout.
            val settleElapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            val effectivePlan = plan.copy(
                timeoutMs = (plan.timeoutMs - settleElapsedMs).coerceAtLeast(0)
            )

            val runner = SequenceStepRunner { step, timeoutMs ->
                executeSequenceStep(step, timeoutMs)
            }
            val result = SequenceExecutor { buildUiTreeJson(effectivePlan.observeFilter) }
                .execute(effectivePlan, runner)

            var payload = result.toJson()
            var screenshot: String? = null

            if (plan.includeScreenshot) {
                try {
                    val jpegBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
                    screenshot = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                } catch (e: Exception) {
                    payload = JsonObject(
                        payload.toMutableMap().apply {
                            put(
                                "screenshot_error",
                                JsonPrimitive(e.message ?: "screenshot failed")
                            )
                        }
                    )
                }
            }

            val content = mutableListOf(
                McpContentBlock(type = "text", text = payload.toString())
            )
            if (screenshot != null) {
                content.add(
                    McpContentBlock(
                        type = "image",
                        data = screenshot,
                        mimeType = "image/jpeg"
                    )
                )
            }

            McpToolCallResult(content = content, isError = !result.success)
        } finally {
            sequenceGate.exit()
        }
    }

    private fun sequenceErrorResult(
        code: String,
        message: String,
        step: Int? = null
    ): McpToolCallResult = McpToolCallResult(
        content = listOf(
            McpContentBlock(type = "text", text = sequenceFailureJson(code, message, step).toString())
        ),
        isError = true
    )

    private suspend fun executeSequenceStep(
        step: SequenceStep,
        timeoutMs: Long
    ): SequenceStepResult = try {
        when (step.action) {
            "tap" -> executeTapStep(step)
            "input_text" -> executeInputTextStep(step)
            "wait_for_element" -> executeWaitForElementStep(step, timeoutMs)
            "wait_for_gone" -> executeWaitForGoneStep(step, timeoutMs)
            "wait_for_idle" ->
                if (waitForIdleInternal(timeoutMs)) stepOk()
                else stepFailure(
                    SequenceErrors.STEP_TIMEOUT,
                    "wait_for_idle timed out after ${timeoutMs}ms"
                )
            "press_key" -> executePressKeyStep(step)
            "swipe" -> executeSwipeStep(step)
            "scroll_to_element" -> executeScrollToElementStep(step, timeoutMs)
            "sleep" -> {
                val durationMs = (step.args["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
                delay(durationMs)
                stepOk()
            }
            "assert_text" -> executeAssertTextStep(step)
            else -> stepFailure(SequenceErrors.INVALID_STEP, "unknown action '${step.action}'")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Sequence step ${step.index} (${step.action}) failed", e)
        stepFailure(
            SequenceErrors.STEP_GESTURE_FAILED,
            "step failed while executing '${step.action}'"
        )
    }

    private fun stepOk(detail: JsonObject? = null) =
        SequenceStepResult(ok = true, detail = detail)

    private fun stepFailure(code: String, message: String, detail: JsonObject? = null) =
        SequenceStepResult(ok = false, detail = detail, error = SequenceStepError(code, message))

    private fun matchMode(args: JsonObject): SequenceMatchMode =
        SequenceMatchMode.fromWire(args.stringOrNull("match")) ?: SequenceMatchMode.AND

    private suspend fun executeTapStep(step: SequenceStep): SequenceStepResult {
        val args = step.args
        val x = (args["x"] as? JsonPrimitive)?.intOrNull
        val y = (args["y"] as? JsonPrimitive)?.intOrNull

        val point: Pair<Float, Float> = if (x != null && y != null) {
            x.toFloat() to y.toFloat()
        } else {
            val bounds = InputTargeting.parseBounds(args["bounds"])
            if (bounds != null) {
                ((bounds.left + bounds.right) / 2f) to ((bounds.top + bounds.bottom) / 2f)
            } else {
                val tree = currentTreeOrNull()
                    ?: return stepFailure(
                        SequenceErrors.STEP_TARGET_NOT_FOUND,
                        "No active window"
                    )
                val selector = SequenceSelector.from(args)
                when (val selection = selectSequenceElement(tree.elements, selector, matchMode(args))) {
                    is SequenceSelection.Found -> {
                        val b = selection.element.bounds
                            ?: return stepFailure(
                                SequenceErrors.STEP_TARGET_NOT_FOUND,
                                "tap target has no bounds: ${selector.describe()}"
                            )
                        ((b.left + b.right) / 2f) to ((b.top + b.bottom) / 2f)
                    }

                    SequenceSelection.NotFound -> return stepFailure(
                        SequenceErrors.STEP_TARGET_NOT_FOUND,
                        "tap target not found: ${selector.describe()}"
                    )

                    is SequenceSelection.Ambiguous -> return stepFailure(
                        SequenceErrors.STEP_TARGET_AMBIGUOUS,
                        "multiple elements match: ${selector.describe()}; add class_name or use element_id"
                    )
                }
            }
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb ->
                gestureEngine.executeTap(point.first, point.second, cb)
            }
        } ?: false

        return if (success) {
            stepOk(buildJsonObject {
                put("x", point.first.toInt())
                put("y", point.second.toInt())
            })
        } else {
            stepFailure(SequenceErrors.STEP_GESTURE_FAILED, "tap gesture was cancelled or timed out")
        }
    }

    private suspend fun executeInputTextStep(step: SequenceStep): SequenceStepResult =
        when (val outcome = performInputText(step.args)) {
            is InputOutcome.Success -> stepOk(buildJsonObject {
                put("method", outcome.method)
                putTarget(outcome.target)
                put("before", outcome.before)
                put("after", outcome.after)
            })

            is InputOutcome.Failure -> {
                val detail = buildJsonObject {
                    outcome.target?.let { putTarget(it) }
                    outcome.before?.let { put("before", it) }
                    outcome.after?.let { put("after", it) }
                }
                stepFailure(mapInputErrorCode(outcome.code), outcome.message, detail)
            }
        }

    private fun mapInputErrorCode(code: String): String = when (code) {
        "TARGET_NOT_FOUND", "NO_ACTIVE_WINDOW" -> SequenceErrors.STEP_TARGET_NOT_FOUND
        "TARGET_AMBIGUOUS" -> SequenceErrors.STEP_TARGET_AMBIGUOUS
        "INPUT_NOT_EDITABLE" -> SequenceErrors.STEP_NOT_EDITABLE
        "INPUT_VERIFY_FAILED" -> SequenceErrors.STEP_INPUT_VERIFY_FAILED
        "INVALID_BOUNDS", "INVALID_STEP" -> SequenceErrors.INVALID_STEP
        else -> SequenceErrors.STEP_INPUT_REJECTED
    }

    private suspend fun executeWaitForElementStep(
        step: SequenceStep,
        timeoutMs: Long
    ): SequenceStepResult {
        val selector = SequenceSelector.from(step.args)
        val match = matchMode(step.args)
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L

        while (true) {
            val tree = currentTreeOrNull()
            if (tree != null) {
                when (val selection = selectSequenceElement(tree.elements, selector, match)) {
                    is SequenceSelection.Found -> return stepOk(buildJsonObject {
                        put("found", true)
                        putJsonObject("element") {
                            put("elementId", selection.element.elementId)
                            selection.element.resourceId?.let { put("resourceId", it) }
                            selection.element.bounds?.let { put("bounds", formatBounds(it)) }
                        }
                    })

                    is SequenceSelection.Ambiguous -> return stepOk(buildJsonObject {
                        put("found", true)
                        put("ambiguous", true)
                        put("matches", selection.count)
                    })

                    SequenceSelection.NotFound -> Unit
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) break
            delay(minOf(POLL_INTERVAL_MS, remainingMs))
        }

        return stepFailure(
            SequenceErrors.STEP_TIMEOUT,
            "wait_for_element timed out after ${timeoutMs}ms: ${selector.describe()}"
        )
    }

    private suspend fun executeWaitForGoneStep(
        step: SequenceStep,
        timeoutMs: Long
    ): SequenceStepResult {
        val selector = SequenceSelector.from(step.args)
        val match = matchMode(step.args)
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L

        while (true) {
            val tree = currentTreeOrNull()
            if (tree != null) {
                val selection = selectSequenceElement(tree.elements, selector, match)
                if (selection is SequenceSelection.NotFound) {
                    return stepOk(buildJsonObject { put("found", false) })
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) break
            delay(minOf(POLL_INTERVAL_MS, remainingMs))
        }

        return stepFailure(
            SequenceErrors.STEP_TIMEOUT,
            "wait_for_gone timed out after ${timeoutMs}ms: ${selector.describe()}"
        )
    }

    private suspend fun executePressKeyStep(step: SequenceStep): SequenceStepResult {
        val key = step.args.stringOrNull("key")
            ?: return stepFailure(SequenceErrors.INVALID_STEP, "'key' is required")
        val error = pressKeyInternal(key)
        return if (error == null) {
            stepOk(buildJsonObject { put("key", key) })
        } else {
            stepFailure(SequenceErrors.STEP_INPUT_REJECTED, error)
        }
    }

    private suspend fun executeSwipeStep(step: SequenceStep): SequenceStepResult {
        val args = step.args
        val startX = (args["start_x"] as? JsonPrimitive)?.intOrNull
            ?: return stepFailure(SequenceErrors.INVALID_STEP, "'start_x' is required")
        val startY = (args["start_y"] as? JsonPrimitive)?.intOrNull
            ?: return stepFailure(SequenceErrors.INVALID_STEP, "'start_y' is required")
        val endX = (args["end_x"] as? JsonPrimitive)?.intOrNull
            ?: return stepFailure(SequenceErrors.INVALID_STEP, "'end_x' is required")
        val endY = (args["end_y"] as? JsonPrimitive)?.intOrNull
            ?: return stepFailure(SequenceErrors.INVALID_STEP, "'end_y' is required")
        val durationMs = (args["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb ->
                gestureEngine.executeSwipe(
                    startX.toFloat(),
                    startY.toFloat(),
                    endX.toFloat(),
                    endY.toFloat(),
                    durationMs,
                    cb
                )
            }
        } ?: false

        return if (success) stepOk()
        else stepFailure(SequenceErrors.STEP_GESTURE_FAILED, "swipe gesture was cancelled or timed out")
    }

    private suspend fun executeScrollToElementStep(
        step: SequenceStep,
        timeoutMs: Long
    ): SequenceStepResult {
        val args = step.args
        val selector = SequenceSelector.from(args)
        val match = matchMode(args)
        val direction = args.stringOrNull("direction") ?: "down"
        val maxScrolls = (args["max_scrolls"] as? JsonPrimitive)?.intOrNull ?: 20

        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction) {
            "up" -> {
                startX = centerX; startY = dm.heightPixels * 0.3f
                endX = centerX; endY = dm.heightPixels * 0.7f
            }
            "left" -> {
                startX = dm.widthPixels * 0.8f; startY = centerY
                endX = dm.widthPixels * 0.2f; endY = centerY
            }
            "right" -> {
                startX = dm.widthPixels * 0.2f; startY = centerY
                endX = dm.widthPixels * 0.8f; endY = centerY
            }
            else -> {
                startX = centerX; startY = dm.heightPixels * 0.7f
                endX = centerX; endY = dm.heightPixels * 0.3f
            }
        }

        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L

        repeat(maxScrolls) { scrollCount ->
            val tree = currentTreeOrNull()
            if (tree != null) {
                if (selectSequenceElement(tree.elements, selector, match) !is SequenceSelection.NotFound) {
                    return stepOk(buildJsonObject {
                        put("found", true)
                        put("scrolls", scrollCount)
                    })
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) {
                return stepFailure(
                    SequenceErrors.STEP_TIMEOUT,
                    "scroll_to_element timed out after ${timeoutMs}ms: ${selector.describe()}"
                )
            }

            withTimeoutOrNull(minOf(1000L, remainingMs)) {
                executeGestureAndWait { cb ->
                    gestureEngine.executeSwipe(startX, startY, endX, endY, 300L, cb)
                }
            }

            val afterScrollMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (afterScrollMs <= 0) {
                return stepFailure(
                    SequenceErrors.STEP_TIMEOUT,
                    "scroll_to_element timed out after ${timeoutMs}ms: ${selector.describe()}"
                )
            }
            delay(minOf(200L, afterScrollMs))
        }

        return stepFailure(
            SequenceErrors.STEP_TARGET_NOT_FOUND,
            "scroll_to_element did not find target after $maxScrolls scrolls: ${selector.describe()}"
        )
    }

    private suspend fun executeAssertTextStep(step: SequenceStep): SequenceStepResult {
        val args = step.args
        val selector = SequenceSelector.from(args)
        val match = matchMode(args)
        val contains = (args["contains"] as? JsonPrimitive)?.booleanOrNull ?: true

        val tree = currentTreeOrNull()
            ?: return stepFailure(SequenceErrors.STEP_ASSERT_FAILED, "No active window")

        val element = when (val selection = selectSequenceElement(
            tree.elements,
            selector,
            match,
            textContains = contains
        )) {
            is SequenceSelection.Found -> selection.element
            SequenceSelection.NotFound -> return stepFailure(
                SequenceErrors.STEP_ASSERT_FAILED,
                "assert_text target not found: ${selector.describe()}"
            )
            is SequenceSelection.Ambiguous -> return stepFailure(
                SequenceErrors.STEP_ASSERT_FAILED,
                "assert_text matched multiple elements: ${selector.describe()}"
            )
        }

        val actual = (element.text ?: element.contentDescription).orEmpty().trim()

        val forbidden = args.stringOrNull("text_not_equals")
        if (forbidden != null && actual == forbidden.trim()) {
            return stepFailure(
                SequenceErrors.STEP_ASSERT_FAILED,
                "assert_text: element text still equals the forbidden value"
            )
        }

        val expected = args.stringOrNull("equals") ?: args.stringOrNull("text_equals")
        if (expected != null && actual != expected.trim()) {
            return stepFailure(
                SequenceErrors.STEP_ASSERT_FAILED,
                "assert_text: element text did not match the expected value"
            )
        }

        return stepOk(buildJsonObject {
            putJsonObject("element") {
                put("elementId", element.elementId)
                element.resourceId?.let { put("resourceId", it) }
                element.bounds?.let { put("bounds", formatBounds(it)) }
            }
        })
    }

    private suspend fun waitForIdleInternal(timeoutMs: Long): Boolean {
        var lastEventTime = System.currentTimeMillis()
        val idleThreshold = 500L

        val listener = object : AccessibilityEventListener {
            override fun onEvent(event: android.view.accessibility.AccessibilityEvent) {
                lastEventTime = System.currentTimeMillis()
            }
        }
        service.registerEventListener(listener)
        return try {
            withTimeoutOrNull(timeoutMs) {
                while (System.currentTimeMillis() - lastEventTime < idleThreshold) {
                    delay(idleThreshold)
                }
                true
            } ?: false
        } finally {
            service.unregisterEventListener(listener)
        }
    }

    // =====================================================================
    // DEVICE / META / TEST TOOLS
    // =====================================================================

    private fun handleListDevices(): McpToolCallResult {
        val dm = appContext.resources.displayMetrics
        val result = buildJsonObject {
            putJsonArray("devices") {
                addJsonObject {
                    put("device_id", "local")
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("screen_width", dm.widthPixels)
                    put("screen_height", dm.heightPixels)
                    put("status", "connected")
                    put("all_permissions_ready", true)
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleSearchTools(args: JsonObject): McpToolCallResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return errorResult("query required")
        val category = args["category"]?.jsonPrimitive?.contentOrNull
        val allTools = McpToolRegistry.getAllTools()
        val matches = allTools.filter { tool ->
            (tool.name.contains(query, ignoreCase = true) || tool.description.contains(query, ignoreCase = true)) &&
            (category == null || tool.name.contains(category, ignoreCase = true))
        }
        val result = buildJsonObject {
            putJsonArray("tools") {
                matches.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                    }
                }
            }
            put("count", matches.size)
        }
        return textResult(result.toString())
    }

    private fun handleDescribeTools(args: JsonObject): McpToolCallResult {
        val toolNames = args["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("tools") {
                toolNames.forEach { name ->
                    val tool = McpToolRegistry.getTool(name)
                    if (tool != null) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        }
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleEnableEvents(args: JsonObject): McpToolCallResult {
        val enable = args["enable"]?.jsonPrimitive?.booleanOrNull ?: return errorResult("enable (boolean) required")
        service.setEventsEnabled(enable)
        return textResult("{\"events_enabled\":$enable}")
    }

    private fun handleGetDeviceInfo(): McpToolCallResult {
        val dm = appContext.resources.displayMetrics
        val result = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("screen_width", dm.widthPixels)
            put("screen_height", dm.heightPixels)
            put("density_dpi", dm.densityDpi)
            put("density", dm.density)
        }
        return textResult(result.toString())
    }

}
