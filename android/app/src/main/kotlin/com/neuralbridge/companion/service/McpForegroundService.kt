package com.neuralbridge.companion.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.neuralbridge.companion.AppPrefs
import com.neuralbridge.companion.notification.McpNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the MCP HTTP server lifecycle.
 *
 * It is independent from the AccessibilityService: the accessibility binding
 * can drop (or the component can be marked crashed) while the server keeps
 * listening, and SWIPE-ing the task away does not stop it. START_STICKY lets
 * the system recreate the service after a low-memory kill.
 *
 * The service does not replay or retry MCP tool calls — it only restarts the
 * HTTP server.
 */
class McpForegroundService : Service() {

    companion object {
        private const val TAG = "McpForegroundService"

        const val ACTION_START = "com.neuralbridge.companion.action.MCP_START"
        const val ACTION_STOP = "com.neuralbridge.companion.action.MCP_STOP"
        const val ACTION_RESTART = "com.neuralbridge.companion.action.MCP_RESTART"

        private const val WATCHDOG_INTERVAL_MS = 20_000L
        private const val MAX_WATCHDOG_FAILURES = 5

        @Volatile
        private var instance: McpForegroundService? = null

        /**
         * Start the service if the master switch allows it. Never throws: an
         * Android 12+ background-start denial surfaces as a user-visible
         * notification instead of a crash.
         */
        fun ensureRunning(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!AppPrefs.isEnabled(appContext)) return false
            McpServerController.initialize(appContext)
            if (McpServerController.isRunning) return true
            val intent = Intent(appContext, McpForegroundService::class.java)
                .setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    handleStartDenied(appContext, e)
                } else {
                    Log.e(TAG, "Failed to start MCP foreground service", e)
                    McpNotifications.showStartDenied(appContext)
                }
                false
            }
        }

        /** Stop the foreground service and release the HTTP port. */
        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, McpForegroundService::class.java)
            )
        }

        /**
         * Upgrade/downgrade the foreground service type when a MediaProjection
         * session starts or ends. Must be applied before getMediaProjection().
         */
        fun updateProjectionForegroundType(includeProjection: Boolean) {
            instance?.let { service ->
                service.mainHandler.post {
                    service.applyForegroundType(includeProjection)
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun handleStartDenied(
            context: Context,
            e: ForegroundServiceStartNotAllowedException
        ) {
            Log.w(TAG, "Background foreground-service start denied; asking user to open the app", e)
            McpNotifications.showStartDenied(context)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogJob: Job? = null

    @Volatile
    private var lastNotifiedState: McpServerState = McpServerState.Starting

    // Coalesces rapid state changes (Starting → Running) into one update so an
    // older state can never overwrite a newer one.
    private val notificationRunnable = Runnable {
        McpNotifications.notify(this, lastNotifiedState)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        McpNotifications.ensureChannel(this)
        // startForegroundService() requires startForeground() within 5 seconds.
        applyForegroundType(includeProjection = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The notification action is a user-visible "off" switch; keep
                // the app's master toggle in sync so boot/onResume do not
                // immediately bring the service back.
                Log.i(TAG, "Stop requested from notification")
                AppPrefs.setEnabled(this, false)
                stopServerAndSelf()
                return McpServicePolicy.START_NOT_STICKY
            }

            ACTION_RESTART -> {
                Log.i(TAG, "Restart requested from notification")
                if (!AppPrefs.isEnabled(this)) {
                    stopServerAndSelf()
                    return McpServicePolicy.START_NOT_STICKY
                }
                startWatchdog()
                scope.launch {
                    publish(McpServerController.restart(applicationContext))
                }
            }

            else -> {
                if (!AppPrefs.isEnabled(this)) {
                    Log.i(TAG, "Master toggle is OFF; not starting the MCP server")
                    stopServerAndSelf()
                    return McpServicePolicy.START_NOT_STICKY
                }
                startWatchdog()
                publish(McpServerState.Starting)
                scope.launch {
                    publish(McpServerController.ensureRunning(applicationContext))
                }
            }
        }
        return McpServicePolicy.startCommandResult(AppPrefs.isEnabled(this))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the task away must not drop MCP clients; START_STICKY keeps
        // the service alive. Do not call stopSelf() here.
        Log.i(TAG, "Task removed; MCP foreground service keeps running")
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(notificationRunnable)
        watchdogJob?.cancel()
        watchdogJob = null
        // Cancel the in-flight start first: it holds the controller mutex and
        // must unwind (stopping its engine) before stop() can take over.
        scope.cancel()
        // Release port 7474 synchronously before the process can go away.
        runBlocking {
            withTimeoutOrNull(3_000L) { McpServerController.stop() }
        }
        Log.i(TAG, "MCP foreground service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground type / notification
    // ------------------------------------------------------------------

    private fun applyForegroundType(includeProjection: Boolean, allowFallback: Boolean = true) {
        val notification = McpNotifications.build(this, lastNotifiedState)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (includeProjection) {
                        types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    startForeground(McpNotifications.NOTIFICATION_ID, notification, types)
                }

                // API 29-33: SPECIAL_USE does not exist yet, so only request the
                // mediaProjection type when a projection session needs it.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeProjection -> {
                    startForeground(
                        McpNotifications.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                }

                else -> startForeground(McpNotifications.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (includeProjection && allowFallback) {
                // Never let a projection-type rejection kill the MCP server.
                Log.e(TAG, "mediaProjection foreground type rejected; continuing without it", e)
                applyForegroundType(includeProjection = false, allowFallback = false)
                McpNotifications.notify(
                    this,
                    McpServerState.Failed("MediaProjection foreground type unavailable")
                )
            } else {
                Log.e(TAG, "startForeground failed", e)
            }
        }
    }

    private fun publish(state: McpServerState) {
        lastNotifiedState = state
        mainHandler.removeCallbacks(notificationRunnable)
        mainHandler.post(notificationRunnable)
    }

    private fun stopServerAndSelf() {
        watchdogJob?.cancel()
        watchdogJob = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Watchdog
    // ------------------------------------------------------------------

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!AppPrefs.isEnabled(this@McpForegroundService)) return@launch

                val state = McpServerController.ensureRunning(applicationContext)
                publish(state)
                if (state is McpServerState.Running) {
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures++
                val message = (state as? McpServerState.Failed)?.message
                    ?: "MCP server is not listening"
                Log.w(
                    TAG,
                    "Watchdog failure $consecutiveFailures/$MAX_WATCHDOG_FAILURES: $message"
                )
                if (consecutiveFailures >= MAX_WATCHDOG_FAILURES) {
                    publish(
                        McpServerState.Failed(
                            "$message (auto-retry paused; tap Restart)"
                        )
                    )
                    return@launch
                }
            }
        }
    }
}
