package com.neuralbridge.companion.input

import com.neuralbridge.companion.service.Bounds
import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * Pure targeting helpers for android_input_text.
 *
 * Kept free of android.* dependencies so the parsing / verification rules can be
 * covered by JVM unit tests without an emulator or instrumentation.
 */
object InputTargeting {

    // "[left,top][right,bottom]"
    private val TWO_BRACKET_BOUNDS = Regex(
        """^\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*$"""
    )

    // "[left,top,right,bottom]"
    private val FOUR_NUMBER_BOUNDS = Regex(
        """^\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*$"""
    )

    /**
     * Parse "[l,t][r,b]" or "[l,t,r,b]". Returns null for malformed or degenerate bounds.
     */
    fun parseBounds(raw: String?): Bounds? {
        if (raw.isNullOrBlank()) return null

        val twoBracket = TWO_BRACKET_BOUNDS.matchEntire(raw)
        if (twoBracket != null) {
            val (left, top, right, bottom) = twoBracket.destructured
            return validatedBounds(left, top, right, bottom)
        }

        val fourNumber = FOUR_NUMBER_BOUNDS.matchEntire(raw)
        if (fourNumber != null) {
            val (left, top, right, bottom) = fourNumber.destructured
            return validatedBounds(left, top, right, bottom)
        }

        return null
    }

    /**
     * Parse either a bounds string or an object {"left":..,"top":..,"right":..,"bottom":..}.
     */
    fun parseBounds(value: JsonElement?): Bounds? {
        return when (value) {
            null, JsonNull -> null
            is JsonPrimitive -> if (value.isString) parseBounds(value.content) else null
            is JsonObject -> {
                val left = value["left"].asIntOrNull() ?: return null
                val top = value["top"].asIntOrNull() ?: return null
                val right = value["right"].asIntOrNull() ?: return null
                val bottom = value["bottom"].asIntOrNull() ?: return null
                if (right > left && bottom > top) Bounds(left, top, right, bottom) else null
            }
            else -> null
        }
    }

    private fun JsonElement?.asIntOrNull(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun validatedBounds(left: String, top: String, right: String, bottom: String): Bounds? {
        val l = left.toIntOrNull() ?: return null
        val t = top.toIntOrNull() ?: return null
        val r = right.toIntOrNull() ?: return null
        val b = bottom.toIntOrNull() ?: return null
        return if (r > l && b > t) Bounds(l, t, r, b) else null
    }

    /**
     * Compare screen bounds, allowing every edge to drift by [tolerance] pixels.
     */
    fun Bounds.matches(other: Bounds, tolerance: Int = 2): Boolean {
        val t = tolerance.coerceAtLeast(0)
        return abs(left - other.left) <= t &&
            abs(top - other.top) <= t &&
            abs(right - other.right) <= t &&
            abs(bottom - other.bottom) <= t
    }

    fun Bounds.area(): Long {
        val width = (right - left).coerceAtLeast(0).toLong()
        val height = (bottom - top).coerceAtLeast(0).toLong()
        return width * height
    }

    /**
     * Canonical form used only for verification: strips grouping separators/spaces
     * and insignificant trailing zeroes ("1,000.00" -> "1000", "9.630" -> "9.63").
     */
    fun normalizeText(value: String): String {
        var normalized = value
            .replace(",", "")
            .replace("，", "")
            .replace(" ", "")
            .replace("　", "")
            .replace(Regex("\\s+"), "")

        if (normalized.contains('.')) {
            normalized = normalized.trimEnd('0')
            if (normalized.endsWith('.')) normalized = normalized.dropLast(1)
        }
        return normalized
    }

    /**
     * Verify a read-back value against what the caller asked for.
     * Appends are checked against before + requested; formatted values are compared
     * using the normalized form.
     */
    fun textMatches(
        after: String,
        requested: String,
        append: Boolean,
        before: String
    ): Boolean {
        val expected = if (append) before + requested else requested
        if (after == expected) return true
        return normalizeText(after) == normalizeText(expected)
    }

    /**
     * Never expose password contents through get_focused_element.
     */
    fun maskedText(text: String?, isPassword: Boolean): String? =
        if (isPassword) "***" else text

    /**
     * Format used in new target/focused payloads.
     */
    fun formatBounds(bounds: Bounds): String =
        "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
}
