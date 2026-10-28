package com.neuralbridge.companion.service

import android.content.Context
import android.util.Log
import com.neuralbridge.companion.mcp.McpHttpServer
import com.neuralbridge.companion.mcp.McpToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Observable state of the MCP HTTP server. */
sealed class McpServerState {
    data object Stopped : McpServerState()
    data object Starting : McpServerState()
    data class Running(val port: Int) : McpServerState()
    data class Failed(val message: String) : McpServerState()
}

/**
 * Abstraction over the HTTP server so the controller can be unit tested with a
 * fake that simulates bind failures and silent deaths.
 */
interface McpServerHandle {
    val port: Int
    suspend fun start()
    suspend fun stop()
    suspend fun isListening(): Boolean
    fun isClientActive(windowMs: Long = 30_000L): Boolean
    fun getLastRequestTimestamp(): Long
}

/**
 * Single owner of the MCP HTTP server.
 *
 * The foreground service, the accessibility service and MainActivity all go
 * through this object so there is never more than one server instance bound to
 * port 7474. Start failures (port in use) are retried with bounded exponential
 * backoff and surfaced through [state] instead of crashing.
 */
object McpServerController {
    private const val TAG = "McpServerController"

    /** Hard bound for one bind attempt; a hung engine must not hold the mutex. */
    private const val START_ATTEMPT_TIMEOUT_MS = 15_000L

    private val mutex = Mutex()
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Stopped)
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    @Volatile
    private var server: McpServerHandle? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var appContext: Context? = null

    /** Delays before each start attempt; bounded so a stuck port cannot loop forever. */
    internal var retryDelaysMs: List<Long> = listOf(0L, 1_000L, 2_000L, 4_000L)

    internal var serverFactory: () -> McpServerHandle = ::defaultServerFactory

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** True when the controller believes the server is listening. */
    val isRunning: Boolean get() = _state.value is McpServerState.Running

    fun lastError(): String? = lastError

    fun isClientActive(windowMs: Long = 30_000L): Boolean =
        server?.isClientActive(windowMs) ?: false

    fun port(): Int = server?.port ?: McpHttpServer.MCP_PORT

    /**
     * Start the server if it is not already listening. Concurrency-safe; the
     * second caller waits and observes the first caller's result.
     */
    suspend fun ensureRunning(context: Context): McpServerState {
        initialize(context)
        return ensureRunningInternal()
    }

    internal suspend fun ensureRunningInternal(): McpServerState = mutex.withLock {
        val existing = server
        if (existing != null && existing.isListening()) {
            if (_state.value !is McpServerState.Running) {
                _state.value = McpServerState.Running(existing.port)
            }
            return@withLock _state.value
        }
        if (existing != null) {
            Log.w(TAG, "MCP server handle was stale; replacing it")
            stopHandle(existing)
            server = null
        }

        _state.value = McpServerState.Starting
        var lastFailure: String? = null

        for ((attempt, delayMs) in retryDelaysMs.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            val handle = try {
                serverFactory()
            } catch (e: Exception) {
                lastFailure = describeStartFailure(e)
                break
            }
            try {
                val started = withTimeoutOrNull(START_ATTEMPT_TIMEOUT_MS) {
                    handle.start()
                    true
                }
                if (started == null) {
                    throw java.util.concurrent.TimeoutException(
                        "server did not finish starting within ${START_ATTEMPT_TIMEOUT_MS}ms"
                    )
                }
                server = handle
                lastError = null
                _state.value = McpServerState.Running(handle.port)
                Log.i(TAG, "MCP HTTP server listening on port ${handle.port} (attempt ${attempt + 1})")
                return@withLock _state.value
            } catch (e: CancellationException) {
                // Parent cancelled (service stopping): stop the engine without
                // suspending, then propagate so the mutex is released.
                withContext(NonCancellable) {
                    runCatching { handle.stop() }
                        .onFailure { Log.w(TAG, "Cleanup after cancellation failed", it) }
                }
                throw e
            } catch (e: Exception) {
                lastFailure = describeStartFailure(e)
                Log.e(TAG, "MCP server start attempt ${attempt + 1} failed: $lastFailure")
                withContext(NonCancellable) {
                    runCatching { handle.stop() }
                        .onFailure { Log.w(TAG, "Cleanup after failed start also failed", it) }
                }
            }
        }

        server = null
        lastError = lastFailure
        _state.value = McpServerState.Failed(lastFailure ?: "MCP server did not start")
        _state.value
    }

    suspend fun stop() = mutex.withLock {
        server?.let { stopHandle(it) }
        server = null
        lastError = null
        _state.value = McpServerState.Stopped
    }

    suspend fun restart(context: Context): McpServerState {
        stop()
        return ensureRunning(context)
    }

    private suspend fun stopHandle(handle: McpServerHandle) {
        try {
            handle.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MCP HTTP server", e)
        }
    }

    private fun describeStartFailure(e: Exception): String {
        val messages = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .toList()
        val combined = messages.joinToString(" ")
        val portInUse = e is java.net.BindException ||
            combined.contains("Address already in use", ignoreCase = true) ||
            combined.contains("bind failed", ignoreCase = true) ||
            (combined.contains("bind", ignoreCase = true) &&
                combined.contains("in use", ignoreCase = true))
        return if (portInUse) {
            "port ${McpHttpServer.MCP_PORT} already in use"
        } else {
            "MCP server failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun defaultServerFactory(): McpServerHandle {
        val context = appContext
            ?: error("McpServerController.initialize() must be called before starting")
        return KtorMcpServerHandle(context)
    }

    /** Test hook: clears all state and restores the production factory. */
    internal fun resetForTest() {
        server = null
        lastError = null
        appContext = null
        _state.value = McpServerState.Stopped
        retryDelaysMs = listOf(0L, 1_000L, 2_000L, 4_000L)
        serverFactory = ::defaultServerFactory
    }
}

/** Production handle backed by Ktor CIO through [McpHttpServer]. */
internal class KtorMcpServerHandle(context: Context) : McpServerHandle {
    private val appContext = context.applicationContext
    private val server = McpHttpServer(
        context = appContext,
        toolHandler = McpToolHandler(appContext) { NeuralBridgeAccessibilityService.services }
    )

    override val port: Int get() = server.getPort()

    override suspend fun start() {
        server.start()
    }

    override suspend fun stop() {
        server.stop()
    }

    override suspend fun isListening(): Boolean =
        server.isRunning() && TcpProbe.isListening(server.getPort())

    override fun isClientActive(windowMs: Long): Boolean = server.isClientActive(windowMs)

    override fun getLastRequestTimestamp(): Long = server.getLastRequestTimestamp()
}

/** One-shot localhost TCP probe used to detect a silently dead HTTP engine. */
internal object TcpProbe {
    suspend fun isListening(port: Int, timeoutMs: Int = 300): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                }
                true
            } catch (_: IOException) {
                false
            }
        }
}
