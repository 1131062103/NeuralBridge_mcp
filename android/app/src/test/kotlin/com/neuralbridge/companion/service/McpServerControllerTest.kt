package com.neuralbridge.companion.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.BindException

class McpServerControllerTest {

    private class FakeServer(
        override val port: Int = 7474,
        private val failFirstAttempts: Int = 0,
        private val failAlways: Boolean = false
    ) : McpServerHandle {
        var startCalls = 0
        var stopCalls = 0
        var listening = false

        override suspend fun start() {
            startCalls++
            if (failAlways || startCalls <= failFirstAttempts) {
                throw BindException("Address already in use")
            }
            listening = true
        }

        override suspend fun stop() {
            stopCalls++
            listening = false
        }

        override suspend fun isListening(): Boolean = listening

        override fun isClientActive(windowMs: Long): Boolean = false

        override fun getLastRequestTimestamp(): Long = 0L
    }

    @Before
    fun setUp() {
        McpServerController.resetForTest()
        McpServerController.retryDelaysMs = listOf(0L)
    }

    @After
    fun tearDown() {
        McpServerController.resetForTest()
    }

    @Test
    fun `ensureRunning starts the server once and reports Running`() = runBlocking {
        val server = FakeServer()
        McpServerController.serverFactory = { server }

        val state = McpServerController.ensureRunningInternal()

        assertEquals(McpServerState.Running(7474), state)
        assertEquals(1, server.startCalls)
        assertTrue(McpServerController.isRunning)
    }

    @Test
    fun `ensureRunning is a no-op while the server is listening`() = runBlocking {
        val server = FakeServer()
        McpServerController.serverFactory = { server }

        McpServerController.ensureRunningInternal()
        McpServerController.ensureRunningInternal()
        McpServerController.ensureRunningInternal()

        assertEquals(1, server.startCalls)
    }

    @Test
    fun `bind failure does not throw and reports the port`() = runBlocking {
        val server = FakeServer(failAlways = true)
        McpServerController.serverFactory = { server }

        val state = McpServerController.ensureRunningInternal()

        assertTrue(state is McpServerState.Failed)
        assertEquals("port 7474 already in use", (state as McpServerState.Failed).message)
        assertEquals("port 7474 already in use", McpServerController.lastError())
        assertFalse(McpServerController.isRunning)
        assertEquals(1, server.stopCalls)
    }

    @Test
    fun `a transient bind failure is retried within the bounded budget`() = runBlocking {
        val failing = FakeServer(failFirstAttempts = 1)
        val healthy = FakeServer()
        val queue = ArrayDeque(listOf(failing, healthy))
        McpServerController.retryDelaysMs = listOf(0L, 0L, 0L)
        McpServerController.serverFactory = { queue.removeFirst() }

        val state = McpServerController.ensureRunningInternal()

        assertEquals(McpServerState.Running(7474), state)
        assertEquals(1, failing.startCalls)
        assertEquals(1, healthy.startCalls)
        assertEquals(1, failing.stopCalls)
    }

    @Test
    fun `a stale handle is stopped and replaced`() = runBlocking {
        val stale = FakeServer()
        val healthy = FakeServer()
        val queue = ArrayDeque(listOf(stale, healthy))
        McpServerController.serverFactory = { queue.removeFirst() }

        assertEquals(McpServerState.Running(7474), McpServerController.ensureRunningInternal())

        // Engine dies silently without updating the controller.
        stale.listening = false

        assertEquals(McpServerState.Running(7474), McpServerController.ensureRunningInternal())
        assertEquals(1, stale.stopCalls)
        assertEquals(1, healthy.startCalls)
    }

    @Test
    fun `concurrent callers do not create a second server`() = runBlocking {
        val slow = object : McpServerHandle {
            override val port: Int = 7474
            var startCalls = 0
            var listening = false

            override suspend fun start() {
                startCalls++
                delay(50)
                listening = true
            }

            override suspend fun stop() {
                listening = false
            }

            override suspend fun isListening(): Boolean = listening
            override fun isClientActive(windowMs: Long): Boolean = false
            override fun getLastRequestTimestamp(): Long = 0L
        }
        McpServerController.serverFactory = { slow }

        val results = listOf(
            async { McpServerController.ensureRunningInternal() },
            async { McpServerController.ensureRunningInternal() }
        ).map { it.await() }

        assertTrue(results.all { it is McpServerState.Running })
        assertEquals(1, slow.startCalls)
    }

    @Test
    fun `stop releases state and reports Stopped`() = runBlocking {
        val server = FakeServer()
        McpServerController.serverFactory = { server }
        McpServerController.ensureRunningInternal()

        McpServerController.stop()

        assertEquals(McpServerState.Stopped, McpServerController.state.value)
        assertFalse(McpServerController.isRunning)
        assertEquals(1, server.stopCalls)
    }

    @Test
    fun `watchdog path restart brings a stopped server back`() = runBlocking {
        val first = FakeServer()
        val second = FakeServer()
        val queue = ArrayDeque(listOf(first, second))
        McpServerController.serverFactory = { queue.removeFirst() }

        McpServerController.ensureRunningInternal()
        McpServerController.stop()
        val state = McpServerController.ensureRunningInternal()

        assertEquals(McpServerState.Running(7474), state)
        assertEquals(1, second.startCalls)
    }
}
