package com.neuralbridge.companion.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes a validated [SequencePlan] against an arbitrary [SequenceStepRunner].
 *
 * The engine owns ordering, per-step and overall deadlines, stop-on-error,
 * observation and result assembly. It never retries a step and never inspects
 * step input values, so it is safe to unit-test with a fake runner.
 */
class SequenceExecutor(
    private val observe: (suspend () -> JsonObject?)? = null
) {
    suspend fun execute(
        plan: SequencePlan,
        runner: SequenceStepRunner
    ): SequenceRunResult {
        val startNs = System.nanoTime()
        val overallDeadlineNs = startNs + plan.timeoutMs * NS_PER_MS
        val records = mutableListOf<SequenceStepRecord>()
        var firstError: SequenceRunError? = null

        for (step in plan.steps) {
            val remainingMs = (overallDeadlineNs - System.nanoTime()) / NS_PER_MS
            if (remainingMs <= 0) {
                val error = SequenceStepError(
                    SequenceErrors.SEQUENCE_TIMEOUT,
                    "sequence timed out after ${plan.timeoutMs}ms"
                )
                records.add(
                    SequenceStepRecord(
                        index = step.index,
                        action = step.action,
                        description = step.description,
                        ok = false,
                        elapsedMs = 0,
                        error = error
                    )
                )
                if (firstError == null) {
                    firstError = SequenceRunError(error.code, error.message, step.index)
                }
                break
            }

            val stepBudgetMs = minOf(
                step.timeoutMs ?: SequenceLimits.DEFAULT_STEP_TIMEOUT_MS,
                remainingMs
            ).coerceAtLeast(1)
            val stepStartNs = System.nanoTime()

            val outcome = withTimeoutOrNull(stepBudgetMs) {
                runner.run(step, stepBudgetMs)
            }
            val elapsedMs = (System.nanoTime() - stepStartNs) / NS_PER_MS
            val effective = outcome ?: SequenceStepResult(
                ok = false,
                error = SequenceStepError(
                    SequenceErrors.STEP_TIMEOUT,
                    "step timed out after ${stepBudgetMs}ms"
                )
            )

            var record = SequenceStepRecord(
                index = step.index,
                action = step.action,
                description = step.description,
                ok = effective.ok,
                elapsedMs = elapsedMs,
                detail = effective.detail,
                error = effective.error
            )

            // A step that ran out of the overall budget is a sequence timeout,
            // not a step-owned timeout.
            val remainingAfterMs = (overallDeadlineNs - System.nanoTime()) / NS_PER_MS
            if (!effective.ok && remainingAfterMs <= 0) {
                val error = SequenceStepError(
                    SequenceErrors.SEQUENCE_TIMEOUT,
                    "sequence timed out after ${plan.timeoutMs}ms"
                )
                record = record.copy(error = error)
                records.add(record)
                if (firstError == null) {
                    firstError = SequenceRunError(error.code, error.message, step.index)
                }
                break
            }

            if (plan.observe == SequenceObserveMode.EACH) {
                val observation = observeOnce(
                    ((overallDeadlineNs - System.nanoTime()) / NS_PER_MS).coerceAtLeast(0)
                )
                record = record.copy(tree = observation.tree, treeError = observation.error)
            }
            records.add(record)

            if (!effective.ok) {
                if (firstError == null) {
                    val error = effective.error ?: SequenceStepError(
                        SequenceErrors.INVALID_STEP,
                        "step failed without an error"
                    )
                    firstError = SequenceRunError(error.code, error.message, step.index)
                }
                if (plan.stopOnError) break
            }
        }

        var finalTree: JsonObject? = null
        var finalError: String? = null
        if (plan.observe == SequenceObserveMode.FINAL) {
            val observation = observeOnce(
                ((overallDeadlineNs - System.nanoTime()) / NS_PER_MS).coerceAtLeast(0)
            )
            finalTree = observation.tree
            finalError = observation.error
        }

        return SequenceRunResult(
            success = firstError == null && records.all { it.ok },
            completed = records.count { it.ok },
            total = plan.steps.size,
            elapsedMs = (System.nanoTime() - startNs) / NS_PER_MS,
            failedStep = firstError?.step,
            error = firstError,
            steps = records,
            finalTree = finalTree,
            finalError = finalError
        )
    }

    /**
     * Observation is best-effort: a missing/timed-out tree must never replace a
     * step error. It is bounded by the overall deadline so `timeout_ms` remains
     * a hard limit.
     */
    private suspend fun observeOnce(timeoutMs: Long): Observation {
        if (timeoutMs <= 0) {
            return Observation(tree = null, error = "UI tree capture skipped: sequence deadline reached")
        }
        val observer = observe
            ?: return Observation(tree = null, error = "UI tree unavailable")
        return try {
            val captured = withTimeoutOrNull(timeoutMs) {
                Captured(observer.invoke())
            }
            if (captured == null) {
                Observation(tree = null, error = "UI tree capture timed out")
            } else {
                Observation(
                    tree = captured.tree,
                    error = if (captured.tree == null) "UI tree unavailable" else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Observation(tree = null, error = e.message ?: "failed to read UI tree")
        }
    }

    private data class Captured(val tree: JsonObject?)
    private data class Observation(val tree: JsonObject?, val error: String?)

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

fun interface SequenceStepRunner {
    suspend fun run(step: SequenceStep, timeoutMs: Long): SequenceStepResult
}

data class SequenceStepResult(
    val ok: Boolean,
    val detail: JsonObject? = null,
    val error: SequenceStepError? = null
)

data class SequenceStepError(val code: String, val message: String)

data class SequenceRunError(val code: String, val message: String, val step: Int? = null)

data class SequenceStepRecord(
    val index: Int,
    val action: String,
    val description: String?,
    val ok: Boolean,
    val elapsedMs: Long,
    val detail: JsonObject? = null,
    val error: SequenceStepError? = null,
    val tree: JsonObject? = null,
    val treeError: String? = null
)

data class SequenceRunResult(
    val success: Boolean,
    val completed: Int,
    val total: Int,
    val elapsedMs: Long,
    val failedStep: Int?,
    val error: SequenceRunError?,
    val steps: List<SequenceStepRecord>,
    val finalTree: JsonObject?,
    val finalError: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("success", success)
        put("completed", completed)
        put("total", total)
        put("elapsed_ms", elapsedMs)
        failedStep?.let { put("failed_step", it) }
        error?.let { err ->
            putJsonObject("error") {
                put("code", err.code)
                put("message", err.message)
                err.step?.let { put("step", it) }
            }
        }
        putJsonArray("steps") {
            steps.forEach { step ->
                addJsonObject {
                    put("index", step.index)
                    put("action", step.action)
                    step.description?.let { put("description", it) }
                    put("ok", step.ok)
                    put("elapsed_ms", step.elapsedMs)
                    step.detail?.let { put("detail", it) }
                    step.error?.let { err ->
                        putJsonObject("error") {
                            put("code", err.code)
                            put("message", err.message)
                        }
                    }
                    step.tree?.let { put("tree", it) }
                    step.treeError?.let { put("tree_error", it) }
                }
            }
        }
        finalTree?.let { put("final", it) }
        finalError?.let { put("final_error", it) }
    }
}

/**
 * Single-sequence gate per device. The handler holds one instance; a second
 * concurrent call receives SEQUENCE_BUSY instead of interleaving UI actions.
 */
class SequenceGate {
    private val busy = AtomicBoolean(false)

    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun exit() {
        busy.set(false)
    }

    fun isBusy(): Boolean = busy.get()
}

/** Top-level failure payload used for INVALID_SEQUENCE / SEQUENCE_BUSY. */
fun sequenceFailureJson(code: String, message: String, step: Int? = null): JsonObject =
    buildJsonObject {
        put("success", false)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            step?.let { put("step", it) }
        }
    }
