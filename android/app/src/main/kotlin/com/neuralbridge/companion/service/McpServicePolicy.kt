package com.neuralbridge.companion.service

/**
 * Pure service-start policy, kept free of android.app.Service so it can be
 * unit tested on the JVM.
 */
object McpServicePolicy {
    /** Mirrors android.app.Service.START_STICKY. */
    const val START_STICKY = 1

    /** Mirrors android.app.Service.START_NOT_STICKY. */
    const val START_NOT_STICKY = 2

    /** Service should only auto-start (boot / reconnect) when the switch is ON. */
    fun shouldAutoStart(masterEnabled: Boolean): Boolean = masterEnabled

    /** Sticky while enabled so the system recreates the service after a kill. */
    fun startCommandResult(masterEnabled: Boolean): Int =
        if (masterEnabled) START_STICKY else START_NOT_STICKY
}
