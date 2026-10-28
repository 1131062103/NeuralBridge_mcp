package com.neuralbridge.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.AppPrefs
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.uitree.UiTreeWalker
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NeuralBridge AccessibilityService
 *
 * Core automation service that provides:
 * - UI tree observation across all apps
 * - Gesture injection via dispatchGesture()
 * - Screenshot capture
 * - Event streaming to connected clients
 *
 * The MCP HTTP server lives in its own foreground service
 * (McpForegroundService), so this service can be unbound or marked crashed
 * without taking port 7474 down. Tools that need accessibility read
 * [services] per call and return ACCESSIBILITY_NOT_CONNECTED when it is null.
 */
class NeuralBridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NeuralBridge"

        // Event streaming throttling: max 10 events/sec = 1 event per 100ms
        private const val MIN_EVENT_INTERVAL_MS = 100L

        /**
         * Static reference to the running service instance.
         * Used by command handlers to access service capabilities.
         */
        @Volatile
        var instance: NeuralBridgeAccessibilityService? = null
            private set

        /**
         * Accessible bundle (service + engines) for MCP tool calls. Set on
         * connect, cleared on unbind/destroy. Null means "accessibility not
         * connected" — callers must not crash.
         */
        @Volatile
        var services: CompanionServices? = null
            private set

        /**
         * Static toast buffer for McpToolHandler.handleGetRecentToasts().
         * CopyOnWriteArrayList is thread-safe for concurrent reads from MCP HTTP handler.
         */
        val recentToasts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
    }

    // Coroutine scope for service lifecycle
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Core components
    private lateinit var gestureEngine: GestureEngine
    private lateinit var inputEngine: InputEngine
    private lateinit var uiTreeWalker: UiTreeWalker
    private lateinit var screenshotPipeline: ScreenshotPipeline

    // Event listener for UI changes (CopyOnWriteArrayList for thread-safe iteration from onAccessibilityEvent)
    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<AccessibilityEventListener>()

    // Event streaming state
    private val eventsEnabled = AtomicBoolean(false)
    private val lastEventTime = AtomicLong(0)

    // Track service start time
    private var startTime: Long = 0L

    /**
     * Service connected - called when AccessibilityService is bound
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")

        startTime = System.currentTimeMillis()

        // Configure accessibility service
        configureAccessibilityService()

        // Initialize components (sets gestureEngine, uiTreeWalker, screenshotPipeline)
        initializeComponents()

        // Publish instance before conditional startup so MainActivity can query it
        instance = this
        services = CompanionServices(
            service = this,
            gestureEngine = gestureEngine,
            uiTreeWalker = uiTreeWalker,
            inputEngine = inputEngine,
            screenshotPipeline = screenshotPipeline
        )

        // The MCP server has its own foreground service. ensureRunning() is a
        // no-op when it is already listening, so accessibility reconnects never
        // create a second server or race for port 7474.
        //
        // MediaProjection consent is requested explicitly from the Setup tab,
        // never here: this callback also fires after a crash/reboot and a system
        // dialog would otherwise interrupt whatever automation is in progress.
        if (AppPrefs.isEnabled(this)) {
            McpForegroundService.ensureRunning(this)
        }

        Log.i(TAG, "NeuralBridge service fully initialized")
    }

    /**
     * Configure AccessibilityService flags
     */
    private fun configureAccessibilityService() {
        // Note: Most configuration is in accessibility_service_config.xml
        // Additional runtime configuration can be done here if needed

        Log.d(TAG, "AccessibilityService configured")
    }

    /**
     * Initialize core components
     */
    private fun initializeComponents() {
        gestureEngine = GestureEngine(this)
        inputEngine = InputEngine(this)
        uiTreeWalker = UiTreeWalker(this)
        screenshotPipeline = ScreenshotPipeline(this)

        // Update state when the MediaProjection session is lost by the system
        screenshotPipeline.onMediaProjectionLost = {
            Log.w(TAG, "MediaProjection session lost - falling back to AccessibilityService screenshots")
        }

        // MediaProjection requires the hosting app to be running a foreground
        // service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. The MCP
        // foreground service carries that type; ask it before and after the
        // projection session.
        screenshotPipeline.ensureProjectionForeground = {
            McpForegroundService.updateProjectionForegroundType(includeProjection = true)
        }
        screenshotPipeline.onProjectionEnded = {
            McpForegroundService.updateProjectionForegroundType(includeProjection = false)
        }

        Log.d(TAG, "Core components initialized")
    }

    /**
     * Try to consume any pending MediaProjection consent result. Called from
     * MainActivity.onResume() after the user grants consent in the Setup tab.
     * It only consumes the stored result; it never launches a new consent
     * dialog.
     */
    fun tryConsumeMediaProjectionConsent() {
        if (screenshotPipeline.hasMediaProjectionPermission()) return
        if (!com.neuralbridge.companion.screenshot.ScreenshotConsentActivity.hasConsentResult()) return
        serviceScope.launch {
            val granted = screenshotPipeline.tryConsumePendingConsent()
            if (granted) {
                Log.i(TAG, "MediaProjection permission granted from UI - fast screenshots enabled")
            }
        }
    }

    /**
     * Handle accessibility events
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Always notify local listeners first (for wait_for_idle, etc.)
        eventListeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Event listener error", e)
            }
        }

        // Check if event streaming is enabled
        if (!eventsEnabled.get()) {
            return
        }

        // Throttle events to prevent flooding
        if (shouldThrottleEvent()) {
            return
        }

        // Stream relevant events to MCP server
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                sendUIChangeEvent(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Check if it's a Toast
                if (event.className == "android.widget.Toast") {
                    sendToastEvent(event)
                }
            }
        }
    }

    /**
     * Handle service interruption
     */
    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    /**
     * Service unbound. The MCP foreground service and HTTP server keep running;
     * only accessibility-related state is cleared.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "AccessibilityService unbound")
        cleanupAccessibilityState()
        return super.onUnbind(intent)
    }

    /**
     * Service destroyed
     */
    override fun onDestroy() {
        Log.i(TAG, "Accessibility service shutting down")
        cleanupAccessibilityState()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Release accessibility-only resources. Deliberately does NOT touch the
     * MCP foreground service: an accessibility crash must not drop port 7474.
     */
    private fun cleanupAccessibilityState() {
        // Releases VirtualDisplay, ImageReader and MediaProjection synchronously.
        if (::screenshotPipeline.isInitialized) {
            screenshotPipeline.cleanup()
        }
        if (services?.service === this) {
            services = null
        }
        if (instance === this) {
            instance = null
        }
    }

    // ========================================================================
    // Public API for command handlers
    // ========================================================================

    /**
     * Get root UI node
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * Execute a gesture
     */
    fun executeGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback? = null
    ) {
        val internalCallback = if (callback != null) {
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback.onCompleted(gestureDescription ?: return)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback.onCancelled(gestureDescription ?: return)
                }
            }
        } else null

        dispatchGesture(gesture, internalCallback, null)
    }

    /**
     * Get UI tree
     */
    suspend fun getUiTree(
        includeInvisible: Boolean = false,
        maxDepth: Int = 0
    ): UiTree {
        return withContext(Dispatchers.Default) {
            uiTreeWalker.walkTree(getRootNode(), includeInvisible, maxDepth)
        }
    }

    /**
     * Capture screenshot
     */
    suspend fun captureScreenshot(quality: ScreenshotQuality): ByteArray {
        return screenshotPipeline.capture(quality)
    }

    /**
     * Perform global action (wrapper)
     */
    fun executeGlobalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    /**
     * Register event listener
     */
    fun registerEventListener(listener: AccessibilityEventListener) {
        eventListeners.add(listener)
    }

    /**
     * Unregister event listener
     */
    fun unregisterEventListener(listener: AccessibilityEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Called when the user turns off the master toggle. Releases the
     * MediaProjection session; the MCP foreground service is stopped by
     * MainActivity so the two controls cannot drift apart.
     */
    fun disable() {
        // Releases VirtualDisplay, ImageReader and MediaProjection synchronously.
        if (::screenshotPipeline.isInitialized) {
            screenshotPipeline.cleanup()
        }
    }

    /**
     * Enable or disable event streaming
     */
    fun setEventsEnabled(enabled: Boolean) {
        eventsEnabled.set(enabled)
        Log.i(TAG, "Event streaming ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if an MCP HTTP client has been active recently (within 30s window).
     * The server is owned by McpServerController, not by this service.
     */
    fun isHttpClientActive(): Boolean = McpServerController.isClientActive()

    /**
     * Get service uptime in milliseconds
     */
    fun getUptime(): Long {
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0
    }

    /**
     * Get MCP HTTP server port (for MainActivity display)
     */
    fun getMcpHttpPort(): Int = McpServerController.port()

    /**
     * Check if MCP HTTP server is running (for MainActivity status display)
     */
    fun getMcpHttpActive(): Boolean = McpServerController.isRunning

    /**
     * Check if MediaProjection permission is granted
     */
    fun hasMediaProjectionPermission(): Boolean {
        return if (::screenshotPipeline.isInitialized) screenshotPipeline.hasMediaProjectionPermission() else false
    }

    /**
     * Check if enough time has passed since last event (throttling)
     */
    private fun shouldThrottleEvent(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEventTime.get()
        if (now - last < MIN_EVENT_INTERVAL_MS) {
            return true // Too soon, throttle
        }
        lastEventTime.set(now)
        return false
    }

    /**
     * Handle UI change event (logged only; no broadcast since TCP was removed)
     */
    private fun sendUIChangeEvent(event: AccessibilityEvent) {
        Log.d(TAG, "UI change event: ${event.source?.hashCode() ?: "unknown"}")
    }

    /**
     * Cache toast event for MCP HTTP handler polling
     */
    private fun sendToastEvent(event: AccessibilityEvent) {
        try {
            val text = event.text.joinToString(" ")

            // Add to static toast buffer for MCP HTTP handler (max 50 entries)
            if (recentToasts.size >= 50) recentToasts.removeAt(0)
            recentToasts.add(text to System.currentTimeMillis())

            Log.d(TAG, "Toast event cached: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching toast event", e)
        }
    }
}

/**
 * Interface for accessibility event listeners
 */
interface AccessibilityEventListener {
    fun onEvent(event: AccessibilityEvent)
}

/**
 * Gesture execution result callback
 */
interface GestureResultCallback {
    fun onCompleted(gesture: GestureDescription)
    fun onCancelled(gesture: GestureDescription)
}

/**
 * Screenshot quality levels
 */
enum class ScreenshotQuality(val jpegQuality: Int) {
    FULL(80),
    THUMBNAIL(40)
}

/**
 * UI tree representation
 */
data class UiTree(
    val elements: List<UiElement>,
    val foregroundApp: String,
    val totalNodes: Int,
    val captureTimestamp: Long
)

/**
 * UI element representation
 */
data class UiElement(
    val elementId: String,
    val resourceId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Bounds?,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val longClickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val editable: Boolean = false,
    val semanticType: String,
    val aiDescription: String
)

/**
 * Element bounds
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
