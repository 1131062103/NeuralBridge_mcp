package com.neuralbridge.companion.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpToolRegistryTest {

    private val tools = McpToolRegistry.getAllTools()

    @Test
    fun `registry returns at least 30 tools`() {
        assertTrue("Expected ≥30 tools, got ${tools.size}", tools.size >= 30)
    }

    @Test
    fun `all tool names have android_ prefix`() {
        tools.forEach { tool ->
            assertTrue(
                "Tool '${tool.name}' missing android_ prefix",
                tool.name.startsWith("android_")
            )
        }
    }

    @Test
    fun `no duplicate tool names`() {
        val names = tools.map { it.name }
        val unique = names.toSet()
        assertEquals("Duplicate tool names found: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}",
            unique.size, names.size)
    }

    @Test
    fun `all tools have non-empty descriptions`() {
        tools.forEach { tool ->
            assertTrue("Tool '${tool.name}' has empty description", tool.description.isNotBlank())
        }
    }

    @Test
    fun `all tools have valid inputSchema`() {
        tools.forEach { tool ->
            val schema = tool.inputSchema
            assertEquals("Tool '${tool.name}' inputSchema missing type:object",
                "object", schema["type"]?.jsonPrimitive?.content)
            assertNotNull("Tool '${tool.name}' inputSchema missing properties",
                schema["properties"])
        }
    }

    @Test
    fun `getTool returns correct tool by name`() {
        val tap = McpToolRegistry.getTool("android_tap")
        assertNotNull(tap)
        assertEquals("android_tap", tap!!.name)
    }

    @Test
    fun `getTool returns null for unknown name`() {
        assertNull(McpToolRegistry.getTool("android_nonexistent_tool"))
    }

    @Test
    fun `core gesture tools are present`() {
        val coreTools = listOf(
            "android_tap", "android_swipe", "android_long_press", "android_double_tap",
            "android_pinch", "android_drag"
        )
        coreTools.forEach { name ->
            assertNotNull("Missing core tool: $name", McpToolRegistry.getTool(name))
        }
    }

    @Test
    fun `core observe tools are present`() {
        val observeTools = listOf(
            "android_get_ui_tree", "android_screenshot", "android_find_elements",
            "android_get_screen_context", "android_get_notifications"
        )
        observeTools.forEach { name ->
            assertNotNull("Missing observe tool: $name", McpToolRegistry.getTool(name))
        }
    }

    @Test
    fun `core wait tools are present`() {
        val waitTools = listOf(
            "android_wait_for_element", "android_wait_for_gone", "android_wait_for_idle"
        )
        waitTools.forEach { name ->
            assertNotNull("Missing wait tool: $name", McpToolRegistry.getTool(name))
        }
    }

    @Test
    fun `tools with required params have required array`() {
        // android_swipe requires start_x, start_y, end_x, end_y
        val swipe = McpToolRegistry.getTool("android_swipe")
        assertNotNull(swipe)
        val required = swipe!!.inputSchema["required"]?.jsonArray
        assertNotNull("android_swipe should have required array", required)
        val requiredNames = required!!.map { it.jsonPrimitive.content }
        assertTrue("start_x required", "start_x" in requiredNames)
        assertTrue("start_y required", "start_y" in requiredNames)
        assertTrue("end_x required", "end_x" in requiredNames)
        assertTrue("end_y required", "end_y" in requiredNames)
    }

    @Test
    fun `android_input_text has required text field`() {
        val inputText = McpToolRegistry.getTool("android_input_text")
        assertNotNull(inputText)
        val required = inputText!!.inputSchema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()
        assertTrue("text should be required for android_input_text", "text" in required)
    }

    @Test
    fun `android_input_text schema exposes NB-6 targeting params`() {
        val inputText = McpToolRegistry.getTool("android_input_text")
        assertNotNull(inputText)
        val properties = inputText!!.inputSchema["properties"]?.jsonObject
        assertNotNull("android_input_text missing properties", properties)

        listOf(
            "element_id", "bounds", "class_name",
            "read_back", "allow_clipboard", "bounds_tolerance"
        ).forEach { name ->
            assertNotNull(
                "android_input_text schema missing '$name'",
                properties!![name]
            )
        }

        // New targeting params must all be optional for backward compatibility.
        val required = inputText.inputSchema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()
        assertEquals(listOf("text"), required)
    }

    @Test
    fun `android_find_elements schema exposes editable and visible filters`() {
        val find = McpToolRegistry.getTool("android_find_elements")
        assertNotNull(find)
        val properties = find!!.inputSchema["properties"]?.jsonObject
        assertNotNull("android_find_elements missing properties", properties)

        listOf("editable", "visible", "prefer_editable").forEach { name ->
            assertNotNull(
                "android_find_elements schema missing '$name'",
                properties!![name]
            )
        }
    }

    @Test
    fun `android_sequence exists with steps required and 1 to 50 items`() {
        val tool = McpToolRegistry.getTool("android_sequence")
        assertNotNull("Missing android_sequence", tool)

        val schema = tool!!.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val required = schema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()
        assertEquals(listOf("steps"), required)

        val properties = schema["properties"]?.jsonObject
        assertNotNull("android_sequence missing properties", properties)
        val steps = properties!!["steps"]?.jsonObject
        assertNotNull("android_sequence missing steps schema", steps)
        assertEquals("array", steps!!["type"]?.jsonPrimitive?.content)
        assertEquals(1, steps["minItems"]?.jsonPrimitive?.int)
        assertEquals(50, steps["maxItems"]?.jsonPrimitive?.int)

        listOf(
            "stop_on_error", "observe", "observe_filter",
            "include_screenshot", "timeout_ms", "settle_ms"
        ).forEach { name ->
            assertNotNull("android_sequence schema missing '$name'", properties[name])
        }

        // Every documented action must be offered by the step schema.
        val actions = steps["items"]?.jsonObject
            ?.get("properties")?.jsonObject
            ?.get("action")?.jsonObject
            ?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()
        listOf(
            "tap", "input_text", "wait_for_element", "wait_for_gone", "wait_for_idle",
            "press_key", "swipe", "scroll_to_element", "sleep", "assert_text"
        ).forEach { action ->
            assertTrue("android_sequence missing action '$action'", action in actions)
        }
    }

    @Test
    fun `android_get_focused_element exists with no required params`() {
        val focused = McpToolRegistry.getTool("android_get_focused_element")
        assertNotNull("Missing android_get_focused_element", focused)

        val schema = focused!!.inputSchema
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertNotNull(schema["properties"])
        val required = schema["required"]?.jsonArray
        assertTrue(
            "android_get_focused_element must not require params",
            required == null || required.isEmpty()
        )
    }
}
