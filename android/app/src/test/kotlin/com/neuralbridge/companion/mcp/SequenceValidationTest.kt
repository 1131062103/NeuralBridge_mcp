package com.neuralbridge.companion.mcp

import com.neuralbridge.companion.service.Bounds
import com.neuralbridge.companion.service.UiElement
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SequenceValidationTest {

    private fun parse(json: String): SequenceParseResult =
        SequenceValidation.parse(Json.parseToJsonElement(json).jsonObject)

    private fun valid(json: String): SequencePlan {
        val result = parse(json)
        assertTrue("expected valid plan, got $result", result is SequenceParseResult.Valid)
        return (result as SequenceParseResult.Valid).plan
    }

    // ── Top-level validation ────────────────────────────────────────────────

    @Test
    fun `missing steps is INVALID_SEQUENCE`() {
        val result = parse("""{"observe":"final"}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_SEQUENCE, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `empty steps is INVALID_SEQUENCE`() {
        val result = parse("""{"steps":[]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_SEQUENCE, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `more than 50 steps is INVALID_SEQUENCE`() {
        val steps = (0 until 51).joinToString(",") { """{"action":"wait_for_idle"}""" }
        val result = parse("""{"steps":[$steps]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_SEQUENCE, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `defaults match the documented contract`() {
        val plan = valid("""{"steps":[{"action":"wait_for_idle"}]}""")
        assertTrue(plan.stopOnError)
        assertEquals(SequenceObserveMode.FINAL, plan.observe)
        assertEquals("text", plan.observeFilter)
        assertFalse(plan.includeScreenshot)
        assertEquals(30_000L, plan.timeoutMs)
        assertEquals(0L, plan.settleMs)
    }

    // ── Step validation ─────────────────────────────────────────────────────

    @Test
    fun `unknown action is INVALID_STEP and reports index`() {
        val result = parse("""{"steps":[{"action":"wait_for_idle"},{"action":"teleport"}]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        val invalid = result as SequenceParseResult.Invalid
        assertEquals(SequenceErrors.INVALID_STEP, invalid.code)
        assertEquals(1, invalid.step)
    }

    @Test
    fun `input_text without text is INVALID_STEP`() {
        val result = parse("""{"steps":[{"action":"input_text","resource_id":"field"}]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_STEP, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `swipe without end coordinates is INVALID_STEP`() {
        val result = parse(
            """{"steps":[{"action":"swipe","start_x":1,"start_y":2,"end_x":3}]}"""
        )
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_STEP, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `tap without coordinates bounds or selector is INVALID_STEP`() {
        val result = parse("""{"steps":[{"action":"tap"}]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_STEP, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `malformed bounds is INVALID_STEP`() {
        val result = parse("""{"steps":[{"action":"tap","bounds":"[1,2][3"}]}""")
        assertTrue(result is SequenceParseResult.Invalid)
        assertEquals(SequenceErrors.INVALID_STEP, (result as SequenceParseResult.Invalid).code)
    }

    @Test
    fun `valid step receives its own timeout and selector`() {
        val plan = valid(
            """{"steps":[
                {"action":"tap","text":"买入","resource_id":"btn_buy","timeout_ms":600,
                 "description":"tap buy"},
                {"action":"input_text","text":"9.40","bounds":"[100,400][400,460]",
                 "class_name":"EditText","read_back":true}
            ]}"""
        )
        assertEquals(2, plan.steps.size)
        assertEquals(600L, plan.steps[0].timeoutMs)
        assertEquals("tap buy", plan.steps[0].description)
        val selector = SequenceSelector.from(plan.steps[0].args)
        assertEquals("买入", selector.text)
        assertEquals("btn_buy", selector.resourceId)
    }

    @Test
    fun `assert_text accepts text_equals and text_not_equals`() {
        valid(
            """{"steps":[
                {"action":"assert_text","resource_id":"stockname","text_not_equals":"股票名称"},
                {"action":"assert_text","text":"委托成功","contains":true}
            ]}"""
        )
    }

    // ── AND / OR selector semantics ────────────────────────────────────────

    private fun element(
        text: String?,
        resourceId: String?,
        contentDesc: String? = null
    ): UiElement = UiElement(
        elementId = "id-" + (resourceId ?: text),
        resourceId = resourceId,
        className = "android.widget.TextView",
        text = text,
        contentDescription = contentDesc,
        bounds = Bounds(0, 0, 100, 50),
        visible = true,
        enabled = true,
        clickable = true,
        scrollable = false,
        focusable = true,
        longClickable = false,
        checkable = false,
        checked = false,
        semanticType = "text",
        aiDescription = "Text"
    )

    @Test
    fun `multiple selector fields default to AND`() {
        val selector = SequenceSelector(text = "买入", resourceId = "btn_buy")
        val matching = element("买入股票", "com.app:id/btn_buy")
        val wrongId = element("买入股票", "com.app:id/btn_sell")

        assertTrue(selector.matches(matching, SequenceMatchMode.AND))
        assertFalse(selector.matches(wrongId, SequenceMatchMode.AND))
    }

    @Test
    fun `match or accepts either selector field`() {
        val selector = SequenceSelector(text = "买入", resourceId = "btn_buy")
        val wrongId = element("买入股票", "com.app:id/btn_sell")
        val wrongText = element("卖出", "com.app:id/btn_buy")

        assertTrue(selector.matches(wrongId, SequenceMatchMode.OR))
        assertTrue(selector.matches(wrongText, SequenceMatchMode.OR))
    }

    @Test
    fun `selection prefers the best match but flags true ties`() {
        val container = element("买入", "container").copy(
            clickable = false,
            focusable = false,
            visible = false,
            bounds = Bounds(0, 0, 500, 500)
        )
        val child = element("买入", "button")

        val selection = selectSequenceElement(
            listOf(container, child),
            SequenceSelector(text = "买入"),
            SequenceMatchMode.AND
        )
        assertTrue(selection is SequenceSelection.Found)
        assertEquals("button", (selection as SequenceSelection.Found).element.resourceId)

        val tie = element("买入", "other-button")
        assertTrue(
            selectSequenceElement(
                listOf(child, tie),
                SequenceSelector(text = "买入"),
                SequenceMatchMode.AND
            ) is SequenceSelection.Ambiguous
        )
    }

    @Test
    fun `duplicate nodes with identical bounds are one physical target`() {
        // Huawei Settings exposes the dashboard container twice in the a11y
        // tree; identical bounds/type must not be reported as ambiguous.
        val first = element("关于手机", "android:id/title")
        val duplicate = first.copy()

        val selection = selectSequenceElement(
            listOf(first, duplicate),
            SequenceSelector(text = "关于手机"),
            SequenceMatchMode.AND
        )

        assertTrue(selection is SequenceSelection.Found)
        assertEquals(first, (selection as SequenceSelection.Found).element)
    }
}
