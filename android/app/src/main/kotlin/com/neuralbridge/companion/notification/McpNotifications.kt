package com.neuralbridge.companion.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.neuralbridge.companion.MainActivity
import com.neuralbridge.companion.R
import com.neuralbridge.companion.mcp.McpNetworkUtils
import com.neuralbridge.companion.mcp.McpHttpServer
import com.neuralbridge.companion.service.McpForegroundService
import com.neuralbridge.companion.service.McpServerState

/**
 * Persistent foreground-service notification for the MCP server.
 *
 * The notification never contains input text, tokens or account data — only
 * the listening URL and a short error message.
 */
object McpNotifications {
    const val CHANNEL_ID = "neuralbridge_mcp"
    const val NOTIFICATION_ID = 1001
    private const val START_DENIED_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mcp_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.mcp_notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun build(context: Context, state: McpServerState): Notification {
        val title: String
        val text: String
        when (state) {
            is McpServerState.Running -> {
                title = context.getString(R.string.mcp_notification_running_title)
                val ip = McpNetworkUtils.getWifiIpAddress(context) ?: "device-ip"
                text = context.getString(R.string.mcp_notification_running_message, ip, state.port)
            }
            McpServerState.Starting -> {
                title = context.getString(R.string.mcp_notification_starting_title)
                text = context.getString(
                    R.string.mcp_notification_starting_message,
                    McpHttpServer.MCP_PORT
                )
            }
            is McpServerState.Failed -> {
                title = context.getString(R.string.mcp_notification_failed_title)
                text = state.message
            }
            McpServerState.Stopped -> {
                title = context.getString(R.string.mcp_notification_stopped_title)
                text = context.getString(R.string.mcp_notification_stopped_message)
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                context.getString(R.string.mcp_notification_action_stop),
                serviceAction(context, McpForegroundService.ACTION_STOP, 1)
            )
            .addAction(
                0,
                context.getString(R.string.mcp_notification_action_restart),
                serviceAction(context, McpForegroundService.ACTION_RESTART, 2)
            )
            .build()
    }

    fun notify(context: Context, state: McpServerState) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, build(context, state))
    }

    /**
     * Android 12+ can reject a background startForegroundService(). Tell the
     * user instead of failing silently. Separate ID so it does not collide with
     * the persistent notification.
     */
    fun showStartDenied(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.mcp_notification_start_denied_title))
            .setContentText(context.getString(R.string.mcp_notification_start_denied_message))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(START_DENIED_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, McpForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
