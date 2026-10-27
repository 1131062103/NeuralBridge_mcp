package com.neuralbridge.companion.mcp

import com.neuralbridge.companion.input.InputTargeting
import com.neuralbridge.companion.input.InputTargeting.area
import com.neuralbridge.companion.service.UiElement
import kotlinx.serialization.json.*

/**
 * Pure (Android-free) model, validation and selector matching for
 * `android_sequence`. Kept free of android.* dependencies so the contract can be
 * covered by plain JVM unit tests.
 */
object SequenceLimits {
    const val MAX_STEPS = 50
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_STEP_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 600_000L
    const val MAX_SETTLE_MS = 10_000L
    const val MAX_SLEEP_MS = 60_000L
    const val MAX_DESCRIPTION_LENGTH = 200
}

/** Stable error codes returned to clients. */
object SequenceErrors {
    const val INVALID_SEQUENCE = "INVALID_SEQUENCE"
    const val INVALID_STEP = "INVALID_STEP"
    const val STEP_TARGET_NOT_FOUND = "STEP_TARGET_NOT_FOUND"
    const val STEP_TARGET_AMBIGUOUS = "STEP_TARGET_AMBIGUOUS"
    const val STEP_TIMEOUT = "STEP_TIMEOUT"
    const val STEP_ASSERT_FAILED = "STEP_ASSERT_FAILED"
    const val STEP_INPUT_REJECTED = "STEP_INPUT_REJECTED"
    const val STEP_NOT_EDITABLE = "STEP_NOT_EDITABLE"
    const val STEP_INPUT_VERIFY_FAILED = "STEP_INPUT_VERIFY_FAILED"
    const val STEP_GESTURE_FAILED = "STEP_GESTURE_FAILED"
    const val SEQUENCE_TIMEOUT = "SEQUENCE_TIMEOUT"
    const val SEQUENCE_BUSY = "SEQUENCE_BUSY"
    const val SEQUENCE_CANCELLED = "SEQUENCE_CANCELLED"
}

enum class SequenceObserveMode(val wire: String) {
    NONE("none"), FINAL("final"), EACH("each");

    companion object {
        fun fromWire(value: String?): SequenceObserveMode? =
            entries.firstOrNull { it.wire == value }
    }
}

enum class SequenceMatchMode(val wire: String) {
    AND("and"), OR("or");

    companion object {
        fun fromWire(value: String?): SequenceMatchMode? =
            entries.firstOrNull { it.wire == value }
    }
}

data class SequenceStep(
    val index: Int,
    val action: String,
    val args: JsonObject,
    val timeoutMs: Long?,
    val description: String?
)

data class SequencePlan(
    val steps: List<SequenceStep>,
    val stopOnError: Boolean,
    val observe: SequenceObserveMode,
    val observeFilter: String,
    val includeScreenshot: Boolean,
    val timeoutMs: Long,
    val settleMs: Long
)

sealed class SequenceParseResult {
    data class Valid(val plan: SequencePlan) : SequenceParseResult()
    data class Invalid(
        val code: String,
        val message: String,
        val step: Int? = null
    ) : SequenceParseResult()
}

/**
 * Selector used by sequence actions. Unlike the single-action tools, multiple
 * fields default to AND; callers can opt into OR with `"match": "or"`.
 */
data class SequenceSelector(
    val text: String? = null,
    val resourceId: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val elementId: String? = null
) {
    val isEmpty: Boolean
        get() = text == null && resourceId == null && contentDesc == null &&
            className == null && elementId == null

    fun matches(
        element: UiElement,
        mode: SequenceMatchMode,
        textContains: Boolean = true
    ): Boolean {
        val checks = mutableListOf<Boolean>()

        text?.let {
            checks.add(
                if (textContains) element.text?.contains(it, ignoreCase = true) == true
                else element.text?.trim() == it.trim()
            )
        }
        resourceId?.let { wanted ->
            checks.add(
                element.resourceId?.let { it == wanted || it.endsWith(wanted) } == true
            )
        }
        contentDesc?.let {
            checks.add(element.contentDescription?.contains(it, ignoreCase = true) == true)
        }
        className?.let {
            checks.add(element.className?.contains(it, ignoreCase = true) == true)
        }
        elementId?.let { checks.add(element.elementId == it) }

        if (checks.isEmpty()) return false
        return when (mode) {
            SequenceMatchMode.AND -> checks.all { it }
            SequenceMatchMode.OR -> checks.any { it }
        }
    }

    /** Human-readable selector for error messages (never input text). */
    fun describe(): String = buildList {
        text?.let { add("text=$it") }
        resourceId?.let { add("resource_id=$it") }
        contentDesc?.let { add("content_desc=$it") }
        className?.let { add("class_name=$it") }
        elementId?.let { add("element_id=$it") }
    }.joinToString(", ").ifEmpty { "no selector" }

    companion object {
        fun from(args: JsonObject): SequenceSelector = SequenceSelector(
            text = args.stringOrNull("text"),
            resourceId = args.stringOrNull("resource_id"),
            contentDesc = args.stringOrNull("content_desc"),
            className = args.stringOrNull("class_name"),
            elementId = args.stringOrNull("element_id")
        )
    }
}

sealed class SequenceSelection {
    data class Found(val element: UiElement) : SequenceSelection()
    data object NotFound : SequenceSelection()
    data class Ambiguous(val count: Int) : SequenceSelection()
}

/**
 * Pick the best match. Only genuinely equivalent candidates are treated as
 * ambiguous, so a clickable child beating its container is not an error.
 */
fun selectSequenceElement(
    elements: List<UiElement>,
    selector: SequenceSelector,
    mode: SequenceMatchMode,
    textContains: Boolean = true
): SequenceSelection {
    val matches = elements.filter { selector.matches(it, mode, textContains) }
    if (matches.isEmpty()) return SequenceSelection.NotFound

    val comparator = compareBy<UiElement>(
        { if (it.editable) 0 else 1 },
        { if (it.visible) 0 else 1 },
        { if (it.focusable) 0 else 1 },
        { it.bounds?.area() ?: Long.MAX_VALUE }
    )
    val sorted = matches.sortedWith(comparator)
    if (sorted.size > 1 && comparator.compare(sorted[0], sorted[1]) == 0) {
        // Accessibility trees sometimes expose the same node twice (duplicate
        // dashboard/window containers). Identical bounds + type means one
        // physical target, so tapping the first is safe. Only genuinely
        // different targets are ambiguous.
        val first = sorted[0]
        val second = sorted[1]
        val samePhysicalTarget = first.bounds == second.bounds &&
            first.resourceId == second.resourceId &&
            first.className == second.className
        if (!samePhysicalTarget) {
            return SequenceSelection.Ambiguous(sorted.size)
        }
    }
    return SequenceSelection.Found(sorted[0])
}

object SequenceValidation {

    private val ACTIONS = setOf(
        "tap", "input_text", "wait_for_element", "wait_for_gone", "wait_for_idle",
        "press_key", "swipe", "scroll_to_element", "sleep", "assert_text"
    )

    fun parse(args: JsonObject): SequenceParseResult {
        val stepsElement = args["steps"]
        if (stepsElement !is JsonArray) {
            return invalidSequence("'steps' is required and must be an array of steps")
        }
        if (stepsElement.size !in 1..SequenceLimits.MAX_STEPS) {
            return invalidSequence(
                "'steps' must contain between 1 and ${SequenceLimits.MAX_STEPS} steps " +
                    "(got ${stepsElement.size})"
            )
        }

        val stopOnError = when (val value = args["stop_on_error"]) {
            null, JsonNull -> true
            is JsonPrimitive -> value.booleanOrNull
                ?: return invalidSequence("'stop_on_error' must be a boolean")
            else -> return invalidSequence("'stop_on_error' must be a boolean")
        }

        val observeRaw = args["observe"]
        if (observeRaw != null && observeRaw !is JsonNull && args.stringOrNull("observe") == null) {
            return invalidSequence("'observe' must be one of: none, final, each")
        }
        val observe = when (val value = args.stringOrNull("observe")) {
            null -> SequenceObserveMode.FINAL
            else -> SequenceObserveMode.fromWire(value)
                ?: return invalidSequence("'observe' must be one of: none, final, each")
        }

        val observeFilterRaw = args["observe_filter"]
        if (observeFilterRaw != null && observeFilterRaw !is JsonNull &&
            args.stringOrNull("observe_filter") == null
        ) {
            return invalidSequence("'observe_filter' must be one of: all, interactive, text")
        }
        val observeFilter = args.stringOrNull("observe_filter") ?: "text"
        if (observeFilter !in setOf("all", "interactive", "text")) {
            return invalidSequence("'observe_filter' must be one of: all, interactive, text")
        }

        val includeScreenshot = when (val value = args["include_screenshot"]) {
            null, JsonNull -> false
            is JsonPrimitive -> value.booleanOrNull
                ?: return invalidSequence("'include_screenshot' must be a boolean")
            else -> return invalidSequence("'include_screenshot' must be a boolean")
        }

        val timeoutMs = optionalLong(args, "timeout_ms", SequenceLimits.DEFAULT_TIMEOUT_MS)
            ?: return invalidSequence("'timeout_ms' must be a positive integer")
        if (timeoutMs !in 1..SequenceLimits.MAX_TIMEOUT_MS) {
            return invalidSequence(
                "'timeout_ms' must be between 1 and ${SequenceLimits.MAX_TIMEOUT_MS}"
            )
        }

        val settleMs = optionalLong(args, "settle_ms", 0L)
            ?: return invalidSequence("'settle_ms' must be a non-negative integer")
        if (settleMs !in 0..SequenceLimits.MAX_SETTLE_MS) {
            return invalidSequence(
                "'settle_ms' must be between 0 and ${SequenceLimits.MAX_SETTLE_MS}"
            )
        }

        val steps = mutableListOf<SequenceStep>()
        stepsElement.forEachIndexed { index, raw ->
            if (raw !is JsonObject) {
                return invalidStep(index, "step must be a JSON object")
            }
            val error = validateStep(index, raw)
            if (error != null) return invalidStep(index, error)

            val stepTimeout = raw["timeout_ms"]?.let { it.jsonPrimitive.longOrNull }
            val description = raw.stringOrNull("description")
            steps.add(
                SequenceStep(
                    index = index,
                    action = raw.stringOrNull("action")!!,
                    args = raw,
                    timeoutMs = stepTimeout,
                    description = description
                )
            )
        }

        return SequenceParseResult.Valid(
            SequencePlan(
                steps = steps,
                stopOnError = stopOnError,
                observe = observe,
                observeFilter = observeFilter,
                includeScreenshot = includeScreenshot,
                timeoutMs = timeoutMs,
                settleMs = settleMs
            )
        )
    }

    /** Returns an error message, or null when the step is valid. */
    private fun validateStep(index: Int, step: JsonObject): String? {
        val action = step.stringOrNull("action")
            ?: return "'action' is required and must be a string"
        if (action !in ACTIONS) {
            return "unknown action '$action'. Supported: ${ACTIONS.sorted().joinToString(", ")}"
        }

        val timeoutRaw = step["timeout_ms"]
        if (timeoutRaw != null && timeoutRaw !is JsonNull) {
            val timeout = (timeoutRaw as? JsonPrimitive)?.longOrNull
                ?: return "'timeout_ms' must be a positive integer"
            if (timeout <= 0) return "'timeout_ms' must be a positive integer"
        }

        val description = step.stringOrNull("description")
        if (step["description"] != null && step["description"] !is JsonNull && description == null) {
            return "'description' must be a string"
        }
        if ((description?.length ?: 0) > SequenceLimits.MAX_DESCRIPTION_LENGTH) {
            return "'description' must be at most ${SequenceLimits.MAX_DESCRIPTION_LENGTH} characters"
        }

        val matchRaw = step["match"]
        if (matchRaw != null && matchRaw !is JsonNull) {
            val match = step.stringOrNull("match")
                ?: return "'match' must be one of: and, or"
            if (SequenceMatchMode.fromWire(match) == null) {
                return "'match' must be one of: and, or"
            }
        }

        checkStringFields(step, listOf("text", "resource_id", "content_desc", "class_name", "element_id"))
            ?.let { return it }
        checkLongFields(step, listOf("x", "y"))
            ?.let { return it }

        val selector = SequenceSelector.from(step)

        return when (action) {
            "tap" -> validateTap(step, selector)
            "input_text" -> validateInputText(step)
            "wait_for_element", "wait_for_gone" -> {
                if (selector.isEmpty) "at least one selector (text, resource_id, content_desc) is required"
                else null
            }
            "wait_for_idle" -> null
            "press_key" -> {
                when {
                    step.stringOrNull("key").isNullOrBlank() -> "'key' is required and must be a string"
                    else -> null
                }
            }
            "swipe" -> validateSwipe(step)
            "scroll_to_element" -> {
                if (selector.isEmpty) {
                    "at least one selector (text, resource_id, content_desc) is required"
                } else {
                    validateScrollOptions(step)
                }
            }
            "sleep" -> validateSleep(step)
            "assert_text" -> validateAssert(step, selector)
            else -> null
        }
    }

    private fun validateTap(step: JsonObject, selector: SequenceSelector): String? {
        val hasX = step["x"] != null && step["x"] !is JsonNull
        val hasY = step["y"] != null && step["y"] !is JsonNull
        if (hasX != hasY) return "'x' and 'y' must be provided together"

        if (hasX && hasY) return null

        checkBounds(step)?.let { return it }

        if (step["bounds"] != null && step["bounds"] !is JsonNull) return null

        if (selector.isEmpty) {
            return "tap requires coordinates (x,y), bounds, or at least one selector"
        }
        return null
    }

    private fun validateInputText(step: JsonObject): String? {
        val text = step.stringOrNull("text")
        if (step["text"] == null || step["text"] is JsonNull) {
            return "'text' is required for input_text (use an empty string to clear)"
        }
        if (text == null) return "'text' must be a string"

        checkBounds(step)?.let { return it }

        checkBooleanFields(step, listOf("append", "read_back", "allow_clipboard"))
            ?.let { return it }
        checkLongFields(step, listOf("bounds_tolerance"))
            ?.let { return it }

        step["bounds_tolerance"]?.let { raw ->
            val tolerance = (raw as? JsonPrimitive)?.intOrNull
            if (tolerance != null && tolerance < 0) {
                return "'bounds_tolerance' must be a non-negative integer"
            }
        }

        return null
    }

    private fun validateSwipe(step: JsonObject): String? {
        checkLongFields(step, listOf("start_x", "start_y", "end_x", "end_y", "duration_ms"))
            ?.let { return it }
        listOf("start_x", "start_y", "end_x", "end_y").forEach { name ->
            if (step[name] == null || step[name] is JsonNull) {
                return "'$name' is required for swipe"
            }
        }
        (step["duration_ms"] as? JsonPrimitive)?.longOrNull?.let {
            if (it < 0) return "'duration_ms' must be a non-negative integer"
        }
        return null
    }

    private fun validateScrollOptions(step: JsonObject): String? {
        checkLongFields(step, listOf("max_scrolls"))?.let { return it }
        (step["max_scrolls"] as? JsonPrimitive)?.intOrNull?.let {
            if (it !in 1..100) return "'max_scrolls' must be between 1 and 100"
        }
        val directionRaw = step["direction"]
        if (directionRaw != null && directionRaw !is JsonNull) {
            val direction = step.stringOrNull("direction")
                ?: return "'direction' must be one of: up, down, left, right"
            if (direction !in setOf("up", "down", "left", "right")) {
                return "'direction' must be one of: up, down, left, right"
            }
        }
        return null
    }

    private fun validateSleep(step: JsonObject): String? {
        checkLongFields(step, listOf("duration_ms"))?.let { return it }
        val duration = (step["duration_ms"] as? JsonPrimitive)?.longOrNull
            ?: return "'duration_ms' is required for sleep"
        if (duration !in 0..SequenceLimits.MAX_SLEEP_MS) {
            return "'duration_ms' must be between 0 and ${SequenceLimits.MAX_SLEEP_MS}"
        }
        return null
    }

    private fun validateAssert(step: JsonObject, selector: SequenceSelector): String? {
        if (selector.isEmpty) {
            return "assert_text requires at least one selector (text, resource_id, content_desc)"
        }
        checkStringFields(step, listOf("text_equals", "text_not_equals", "equals"))
            ?.let { return it }
        checkBooleanFields(step, listOf("contains"))
            ?.let { return it }
        return null
    }

    private fun checkBounds(step: JsonObject): String? {
        val raw = step["bounds"] ?: return null
        if (raw is JsonNull) return null
        if (raw !is JsonPrimitive && raw !is JsonObject) {
            return "'bounds' must be a string or {left,top,right,bottom} object"
        }
        if (InputTargeting.parseBounds(raw) == null) {
            return "'bounds' is malformed; expected \"[l,t][r,b]\", \"[l,t,r,b]\" or {left,top,right,bottom}"
        }
        return null
    }

    private fun checkStringFields(step: JsonObject, names: List<String>): String? {
        names.forEach { name ->
            val raw = step[name] ?: return@forEach
            if (raw is JsonNull) return@forEach
            if (raw !is JsonPrimitive || !raw.isString) {
                return "'$name' must be a string"
            }
        }
        return null
    }

    private fun checkLongFields(step: JsonObject, names: List<String>): String? {
        names.forEach { name ->
            val raw = step[name] ?: return@forEach
            if (raw is JsonNull) return@forEach
            val value = (raw as? JsonPrimitive)?.longOrNull
            if (value == null) return "'$name' must be an integer"
        }
        return null
    }

    private fun checkBooleanFields(step: JsonObject, names: List<String>): String? {
        names.forEach { name ->
            val raw = step[name] ?: return@forEach
            if (raw is JsonNull) return@forEach
            val value = (raw as? JsonPrimitive)?.booleanOrNull
            if (value == null) return "'$name' must be a boolean"
        }
        return null
    }

    private fun optionalLong(args: JsonObject, name: String, default: Long?): Long? {
        val raw = args[name] ?: return default
        if (raw is JsonNull) return default
        return (raw as? JsonPrimitive)?.longOrNull
    }

    private fun invalidSequence(message: String) =
        SequenceParseResult.Invalid(SequenceErrors.INVALID_SEQUENCE, message)

    private fun invalidStep(index: Int, message: String) =
        SequenceParseResult.Invalid(SequenceErrors.INVALID_STEP, "step $index: $message", index)
}

// ── Safe JSON accessors ──────────────────────────────────────────────────────

internal fun JsonObject.stringOrNull(name: String): String? {
    val raw = this[name] ?: return null
    if (raw is JsonNull) return null
    val primitive = raw as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}
