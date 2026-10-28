package com.neuralbridge.companion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServicePolicyTest {

    @Test
    fun `enabled service is sticky so the system recreates it after a kill`() {
        assertEquals(McpServicePolicy.START_STICKY, McpServicePolicy.startCommandResult(true))
    }

    @Test
    fun `disabled service is not sticky`() {
        assertEquals(McpServicePolicy.START_NOT_STICKY, McpServicePolicy.startCommandResult(false))
    }

    @Test
    fun `boot receiver only auto-starts when the master toggle is on`() {
        assertTrue(McpServicePolicy.shouldAutoStart(true))
        assertFalse(McpServicePolicy.shouldAutoStart(false))
    }
}
