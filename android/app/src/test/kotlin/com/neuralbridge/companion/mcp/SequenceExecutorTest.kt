package com.neuralbridge.companion.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SequenceExecutorTest {

    private fun step(
        index: Int,
        action: String = "tap",
        timeoutMs: Long? = null,
        args: JsonObject = buildJsonObject { put("action", action) }
    ) = SequenceStep(index, action, args, timeoutMs, null)

    private fun plan(
        steps: List<SequenceStep>,
        stopOnError: Boolean = true,
        timeoutMs: Long = 10_000L,
        observe: SequenceObserveMode = SequenceObserveMode.NONE
    ) = SequencePlan(
        steps = steps,
        stopOnError = stopOnError,
        observe = observe,
        observeFilter = "text",
        includeScreenshot = false,
        timeoutMs = timeoutMs,
        settleMs = 0L
    )

    @Test
    fun `stops on first failure and does not execute later steps`() = runBlocking {
        val executed = CopyOnWriteArrayList<Int>()
        val runner = SequenceStepRunner { step, _ ->
            executed.add(step.index)
            if (step.index == 1) {
                SequenceStepResult(
                    ok = false,
                    error = SequenceStepError(SequenceErrors.STEP_TARGET_NOT_FOUND, "not found")
                )
            } else {
                SequenceStepResult(ok = true)
            }
        }

        val result = SequenceExecutor().execute(plan((0..2).map { step(it) }), runner)

        assertEquals(listOf(0, 1), executed.toList())
        assertFalse(result.success)
        assertEquals(1, result.failedStep)
        assertEquals(SequenceErrors.STEP_TARGET_NOT_FOUND, result.error?.code)
        assertEquals(1, result.completed)
        assertEquals(3, result.total)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `stop_on_error false continues and summarizes the first failure`() = runBlocking {
        val executed = CopyOnWriteArrayList<Int>()
        val runner = SequenceStepRunner { step, _ ->
            executed.add(step.index)
            if (step.index == 1) {
                SequenceStepResult(
                    ok = false,
                    error = SequenceStepError(SequenceErrors.STEP_ASSERT_FAILED, "assert failed")
                )
            } else {
                SequenceStepResult(ok = true)
            }
        }

        val result = SequenceExecutor().execute(
            plan((0..2).map { step(it) }, stopOnError = false),
            runner
        )

        assertEquals(listOf(0, 1, 2), executed.toList())
        assertFalse(result.success)
        assertEquals(1, result.failedStep)
        assertEquals(2, result.completed)
        assertEquals(3, result.steps.size)
        assertFalse(result.steps[1].ok)
        assertTrue(result.steps[2].ok)
    }

    @Test
    fun `step records carry index action ok and elapsed time`() = runBlocking {
        val runner = SequenceStepRunner { step, _ ->
            delay(5)
            SequenceStepResult(
                ok = true,
                detail = buildJsonObject { put("method", "action_set_text") }
            )
        }

        val result = SequenceExecutor().execute(
            plan(listOf(step(0, "tap"), step(1, "input_text"))),
            runner
        )

        assertTrue(result.success)
        assertEquals(2, result.completed)
        assertEquals(listOf(0, 1), result.steps.map { it.index })
        assertEquals(listOf("tap", "input_text"), result.steps.map { it.action })
        assertTrue(result.steps.all { it.ok })
        assertTrue(result.steps.all { it.elapsedMs >= 0 })
        assertEquals("action_set_text", result.steps[1].detail?.get("method")?.jsonPrimitive?.content)
    }

    @Test
    fun `step-level timeout maps to STEP_TIMEOUT`() = runBlocking {
        val runner = SequenceStepRunner { _, _ ->
            delay(500)
            SequenceStepResult(ok = true)
        }

        val result = SequenceExecutor().execute(
            plan(listOf(step(0, timeoutMs = 20)), timeoutMs = 5_000L),
            runner
        )

        assertFalse(result.success)
        assertEquals(SequenceErrors.STEP_TIMEOUT, result.error?.code)
        assertEquals(0, result.failedStep)
    }

    @Test
    fun `overall timeout maps to SEQUENCE_TIMEOUT`() = runBlocking {
        val runner = SequenceStepRunner { _, _ ->
            delay(500)
            SequenceStepResult(ok = true)
        }

        val result = SequenceExecutor().execute(
            plan((0..2).map { step(it) }, timeoutMs = 50L),
            runner
        )

        assertFalse(result.success)
        assertEquals(SequenceErrors.SEQUENCE_TIMEOUT, result.error?.code)
        assertEquals(0, result.failedStep)
        assertEquals(1, result.steps.size)
    }

    @Test
    fun `observe each attaches a tree to every executed step`() = runBlocking {
        val observations = AtomicInteger(0)
        val tree = buildJsonObject { put("format", "compact") }
        val runner = SequenceStepRunner { _, _ -> SequenceStepResult(ok = true) }

        val result = SequenceExecutor {
            observations.incrementAndGet()
            tree
        }.execute(
            plan((0..1).map { step(it) }, observe = SequenceObserveMode.EACH),
            runner
        )

        assertEquals(2, observations.get())
        assertEquals(tree, result.steps[0].tree)
        assertEquals(tree, result.steps[1].tree)
        assertNull(result.finalTree)
    }

    @Test
    fun `final tree is captured even when a step fails`() = runBlocking {
        val tree = buildJsonObject { put("format", "compact") }
        val runner = SequenceStepRunner { _, _ ->
            SequenceStepResult(
                ok = false,
                error = SequenceStepError(SequenceErrors.STEP_GESTURE_FAILED, "cancelled")
            )
        }

        val result = SequenceExecutor { tree }.execute(
            plan(listOf(step(0)), observe = SequenceObserveMode.FINAL),
            runner
        )

        assertFalse(result.success)
        assertEquals(tree, result.finalTree)
    }

    @Test
    fun `observe none never reads the tree`() = runBlocking {
        val observations = AtomicInteger(0)
        val runner = SequenceStepRunner { _, _ -> SequenceStepResult(ok = true) }

        val result = SequenceExecutor {
            observations.incrementAndGet()
            buildJsonObject { put("format", "compact") }
        }.execute(plan(listOf(step(0))), runner)

        assertEquals(0, observations.get())
        assertNull(result.finalTree)
    }

    @Test
    fun `result never contains input text`() = runBlocking {
        val secret = "hunter2-secret-password"
        val inputStep = step(
            0,
            "input_text",
            args = buildJsonObject {
                put("action", "input_text")
                put("text", secret)
                put("resource_id", "password")
            }
        )
        val runner = SequenceStepRunner { _, _ ->
            SequenceStepResult(
                ok = false,
                error = SequenceStepError(SequenceErrors.STEP_INPUT_REJECTED, "field rejected input")
            )
        }

        val result = SequenceExecutor().execute(plan(listOf(inputStep)), runner)
        val json = result.toJson().toString()

        assertFalse("result must not echo input text", json.contains(secret))
        assertTrue(json.contains(SequenceErrors.STEP_INPUT_REJECTED))
    }

    @Test
    fun `result json uses the documented success shape`() = runBlocking {
        val runner = SequenceStepRunner { _, _ ->
            SequenceStepResult(ok = true, detail = buildJsonObject { put("x", 1) })
        }

        val json = SequenceExecutor().execute(
            plan(listOf(step(0, "tap"))),
            runner
        ).toJson()

        assertEquals(true, json["success"]?.jsonPrimitive?.boolean)
        assertEquals(1, json["completed"]?.jsonPrimitive?.int)
        assertEquals(1, json["total"]?.jsonPrimitive?.int)
        assertTrue((json["elapsed_ms"]?.jsonPrimitive?.long ?: -1) >= 0)
        assertNull(json["failed_step"])
        assertNull(json["error"])

        val record = json["steps"]!!.jsonArray[0].jsonObject
        assertEquals(0, record["index"]?.jsonPrimitive?.int)
        assertEquals("tap", record["action"]?.jsonPrimitive?.content)
        assertEquals(true, record["ok"]?.jsonPrimitive?.boolean)
        assertTrue((record["elapsed_ms"]?.jsonPrimitive?.long ?: -1) >= 0)
        assertEquals(1, record["detail"]?.jsonObject?.get("x")?.jsonPrimitive?.int)
    }

    @Test
    fun `result json uses the documented failure shape`() = runBlocking {
        val runner = SequenceStepRunner { _, _ ->
            SequenceStepResult(
                ok = false,
                error = SequenceStepError(SequenceErrors.STEP_TIMEOUT, "timed out")
            )
        }

        val json = SequenceExecutor().execute(
            plan(listOf(step(0, "wait_for_element"))),
            runner
        ).toJson()

        assertEquals(false, json["success"]?.jsonPrimitive?.boolean)
        assertEquals(0, json["failed_step"]?.jsonPrimitive?.int)
        val error = json["error"]?.jsonObject
        assertEquals(SequenceErrors.STEP_TIMEOUT, error?.get("code")?.jsonPrimitive?.content)
        assertEquals(0, error?.get("step")?.jsonPrimitive?.int)
        val record = json["steps"]!!.jsonArray[0].jsonObject
        assertEquals(false, record["ok"]?.jsonPrimitive?.boolean)
        assertEquals(
            SequenceErrors.STEP_TIMEOUT,
            record["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `sequence gate admits only one sequence at a time`() {
        val gate = SequenceGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertTrue(gate.isBusy())
        gate.exit()
        assertFalse(gate.isBusy())
        assertTrue(gate.tryEnter())
    }
}
