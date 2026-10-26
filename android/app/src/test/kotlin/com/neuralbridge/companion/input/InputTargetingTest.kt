package com.neuralbridge.companion.input

import com.neuralbridge.companion.input.InputTargeting.area
import com.neuralbridge.companion.input.InputTargeting.formatBounds
import com.neuralbridge.companion.input.InputTargeting.matches
import com.neuralbridge.companion.input.InputTargeting.maskedText
import com.neuralbridge.companion.input.InputTargeting.normalizeText
import com.neuralbridge.companion.input.InputTargeting.parseBounds
import com.neuralbridge.companion.input.InputTargeting.textMatches
import com.neuralbridge.companion.service.Bounds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTargetingTest {

    // ── parseBounds: strings ────────────────────────────────────────────

    @Test
    fun `parseBounds accepts two-bracket form`() {
        assertEquals(Bounds(10, 20, 30, 40), parseBounds("[10,20][30,40]"))
        assertEquals(Bounds(10, 20, 30, 40), parseBounds(" [ 10 , 20 ] [ 30 , 40 ] "))
    }

    @Test
    fun `parseBounds accepts four-number form`() {
        assertEquals(Bounds(10, 20, 30, 40), parseBounds("[10,20,30,40]"))
        assertEquals(Bounds(-1, -2, 30, 40), parseBounds("[-1,-2,30,40]"))
    }

    @Test
    fun `parseBounds rejects malformed values`() {
        assertNull(parseBounds(null as String?))
        assertNull(parseBounds(""))
        assertNull(parseBounds("   "))
        assertNull(parseBounds("10,20,30,40"))
        assertNull(parseBounds("[10,20][30]"))
        assertNull(parseBounds("[10,20,30]"))
        assertNull(parseBounds("[a,20][30,40]"))
    }

    @Test
    fun `parseBounds rejects degenerate rectangles`() {
        assertNull(parseBounds("[10,20][10,40]"))
        assertNull(parseBounds("[10,20][30,20]"))
        assertNull(parseBounds("[10,20][5,40]"))
    }

    // ── parseBounds: JSON ───────────────────────────────────────────────

    @Test
    fun `parseBounds accepts JsonObject with numeric fields`() {
        val value = buildJsonObject {
            put("left", 10)
            put("top", 20)
            put("right", 30)
            put("bottom", 40)
        }
        assertEquals(Bounds(10, 20, 30, 40), parseBounds(value))
    }

    @Test
    fun `parseBounds accepts JsonObject with string fields`() {
        val value = buildJsonObject {
            put("left", "10")
            put("top", "20")
            put("right", "30")
            put("bottom", "40")
        }
        assertEquals(Bounds(10, 20, 30, 40), parseBounds(value))
    }

    @Test
    fun `parseBounds rejects incomplete or invalid Json`() {
        assertNull(parseBounds(buildJsonObject { put("left", 10); put("top", 20) }))
        assertNull(
            parseBounds(
                buildJsonObject {
                    put("left", 10); put("top", 20); put("right", 10); put("bottom", 40)
                }
            )
        )
        assertNull(parseBounds(JsonPrimitive(42)))
        assertNull(parseBounds(JsonPrimitive("not bounds")))
    }

    // ── Rect matching ───────────────────────────────────────────────────

    @Test
    fun `matches allows tolerance on every edge`() {
        val target = Bounds(100, 200, 300, 400)
        assertTrue(Bounds(100, 200, 300, 400).matches(target, 2))
        assertTrue(Bounds(102, 198, 298, 402).matches(target, 2))
        assertTrue(Bounds(98, 202, 302, 398).matches(target, 2))
    }

    @Test
    fun `matches rejects drift beyond tolerance`() {
        val target = Bounds(100, 200, 300, 400)
        assertFalse(Bounds(103, 200, 300, 400).matches(target, 2))
        assertFalse(Bounds(100, 200, 300, 403).matches(target, 2))
        // Negative tolerance clamps to an exact match.
        assertTrue(Bounds(100, 200, 300, 400).matches(target, -1))
        assertFalse(Bounds(101, 200, 300, 400).matches(target, -1))
    }

    @Test
    fun `area returns width times height`() {
        assertEquals(200L, Bounds(0, 0, 20, 10).area())
        assertEquals(0L, Bounds(10, 10, 10, 10).area())
    }

    // ── Text normalization / read-back ─────────────────────────────────

    @Test
    fun `normalizeText strips separators and insignificant zeroes`() {
        assertEquals("1000", normalizeText("1,000"))
        assertEquals("100", normalizeText("100.00"))
        assertEquals("9.63", normalizeText("9.630"))
        assertEquals("9.63", normalizeText("9.63"))
        assertEquals("100", normalizeText("100"))
        assertEquals("9.63", normalizeText("9.63"))
        assertEquals("-0.5", normalizeText("-0.50"))
        assertEquals("9.63", normalizeText(" 9.63 "))
        assertEquals("1000", normalizeText("1 000"))
        assertEquals("1000", normalizeText("1　000"))
    }

    @Test
    fun `normalizeText does not turn integer 100 into 1`() {
        assertEquals("100", normalizeText("100"))
        assertEquals("0", normalizeText("0"))
    }

    @Test
    fun `textMatches supports replace and append`() {
        assertTrue(textMatches("100", "100", append = false, before = ""))
        assertTrue(textMatches("100", "100", append = false, before = "50"))
        assertFalse(textMatches("50", "100", append = false, before = "50"))

        assertTrue(textMatches("50100", "100", append = true, before = "50"))
        assertFalse(textMatches("100", "100", append = true, before = "50"))
    }

    @Test
    fun `textMatches allows formatted read-back`() {
        assertTrue(textMatches("1,000.00", "1000", append = false, before = ""))
        assertTrue(textMatches("9.630", "9.63", append = false, before = ""))
        assertTrue(textMatches("1,000.00", "000", append = true, before = "1,000."))
    }

    @Test
    fun `textMatches handles empty requested text`() {
        assertTrue(textMatches("", "", append = false, before = "123"))
        assertTrue(textMatches("123", "", append = true, before = "123"))
        assertFalse(textMatches("123", "", append = false, before = "123"))
    }

    // ── Password masking / formatting ──────────────────────────────────

    @Test
    fun `maskedText hides password contents`() {
        assertEquals("***", maskedText("hunter2", isPassword = true))
        assertEquals("***", maskedText(null, isPassword = true))
        assertEquals("100", maskedText("100", isPassword = false))
        assertNull(maskedText(null, isPassword = false))
    }

    @Test
    fun `formatBounds emits two-bracket form`() {
        assertEquals("[10,20][30,40]", formatBounds(Bounds(10, 20, 30, 40)))
    }
}
