package com.neuralbridge.companion.service

import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import com.neuralbridge.companion.uitree.UiTreeWalker

/**
 * Bundle of objects owned by the running AccessibilityService.
 *
 * The MCP HTTP server outlives any single accessibility connection, so tool
 * handlers look this bundle up per call instead of capturing the instances at
 * server start time. When the accessibility service is not connected the
 * provider returns null and accessibility-dependent tools answer with
 * ACCESSIBILITY_NOT_CONNECTED instead of crashing.
 */
data class CompanionServices(
    val service: NeuralBridgeAccessibilityService,
    val gestureEngine: GestureEngine,
    val uiTreeWalker: UiTreeWalker,
    val inputEngine: InputEngine,
    val screenshotPipeline: ScreenshotPipeline
)
