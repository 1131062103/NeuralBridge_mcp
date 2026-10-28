package com.neuralbridge.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.neuralbridge.companion.AppPrefs

/**
 * Restarts the MCP foreground service after a reboot or an app update, but
 * only when the user's master toggle is ON.
 *
 * LOCKED_BOOT_COMPLETED is deliberately not used: the preference lives in
 * credential-encrypted storage and is unreadable before the first unlock.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val enabled = AppPrefs.isEnabled(context)
        if (!McpServicePolicy.shouldAutoStart(enabled)) {
            Log.i(TAG, "Auto-start skipped on $action: master toggle is OFF")
            return
        }

        val started = McpForegroundService.ensureRunning(context)
        Log.i(TAG, "Auto-start on $action: ${if (started) "requested" else "denied"}")
    }
}
