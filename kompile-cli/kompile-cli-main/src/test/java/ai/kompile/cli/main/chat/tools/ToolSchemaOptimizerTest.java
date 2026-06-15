/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolSchemaOptimizer}.
 * <p>
 * Tests all four optimization levels (NONE, MODERATE, AGGRESSIVE, COMPACT),
 * token estimation, adaptive optimization, description truncation,
 * parameter schema optimization, and both OpenAI and MCP schema formats.
 */
class ToolSchemaOptimizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===================================================================
    // Utility constructor guard
    // ===================================================================

    @Nested
    class UtilityClass {

        @Test
        void cannotInstantiate() {
            assertThrows(Exception.class, () -> {
                var ctor = ToolSchemaOptimizer.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                ctor.newInstance();
            });
        }
    }

    // ===================================================================
    // OptimizationLevel enum
    // ===================================================================

    @Nested
    class OptimizationLevelEnum {

        @Test
        void hasFourLevels() {
            assertEquals(4, ToolSchemaOptimizer.OptimizationLevel.values().length);
        }

        @Test
        void levelsInOrder() {
            ToolSchemaOptimizer.OptimizationLevel[] levels = ToolSchemaOptimizer.OptimizationLevel.values();
            assertEquals(ToolSchemaOptimizer.OptimizationLevel.NONE, levels[0]);
            assertEquals(ToolSchemaOptimizer.OptimizationLevel.MODERATE, levels[1]);
            assertEquals(ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE, levels[2]);
            assertEquals(ToolSchemaOptimizer.OptimizationLevel.COMPACT, levels[3]);
        }
    }

    // ===================================================================
    // optimize() — NONE level
    // ===================================================================

    @Nested
    class OptimizeNone {

        @Test
        void noneLevel_returnsDeepCopy() {
            ArrayNode tools = createOpenAiToolArray("Read",
                    "Read a file from the filesystem and return its contents.",
                    createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.NONE);

            assertEquals(tools.toString(), result.toString());
            assertNotSame(tools, result);
        }

        @Test
        void nullToolDefs_returnsEmptyArray() {
            ArrayNode result = ToolSchemaOptimizer.optimize(null,
                    ToolSchemaOptimizer.OptimizationLevel.NONE);

            assertNotNull(result);
            assertEquals(0, result.size());
        }
    }

    // ===================================================================
    // optimize() — MODERATE level
    // ===================================================================

    @Nested
    class OptimizeModerate {

        @Test
        void moderateLevel_truncatesLongDescription() {
            String longDesc = "A".repeat(300);
            ArrayNode tools = createOpenAiToolArray("Tool", longDesc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            String resultDesc = result.get(0).get("function").get("description").asText();
            assertTrue(resultDesc.length() <= 203,
                    "Description should be truncated (200 + ellipsis)");
        }

        @Test
        void moderateLevel_preservesShortDescription() {
            String shortDesc = "Read a file.";
            ArrayNode tools = createOpenAiToolArray("Read", shortDesc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            String resultDesc = result.get(0).get("function").get("description").asText();
            assertEquals(shortDesc, resultDesc);
        }

        @Test
        void moderateLevel_removesExamplesAndDefault() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            prop.put("description", "The path");
            prop.put("default", "/tmp");
            prop.set("examples", MAPPER.createArrayNode().add("file.txt"));
            properties.set("path", prop);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Read", "Read a file.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            JsonNode resultProp = result.get(0).get("function").get("parameters")
                    .get("properties").get("path");
            assertFalse(resultProp.has("default"));
            assertFalse(resultProp.has("examples"));
            assertTrue(resultProp.has("description"), "MODERATE keeps descriptions");
        }
    }

    // ===================================================================
    // optimize() — AGGRESSIVE level
    // ===================================================================

    @Nested
    class OptimizeAggressive {

        @Test
        void aggressiveLevel_stripsParameterDescriptions() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            prop.put("description", "The file path to read");
            properties.set("path", prop);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Read", "Read a file.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE);

            JsonNode resultProp = result.get(0).get("function").get("parameters")
                    .get("properties").get("path");
            assertFalse(resultProp.has("description"),
                    "AGGRESSIVE strips parameter descriptions");
        }

        @Test
        void aggressiveLevel_removesConstraintFields() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            params.put("additionalProperties", false);
            params.put("minItems", 1);
            params.put("maxItems", 100);
            params.put("pattern", "^[a-z]+$");
            params.put("format", "uri");

            ArrayNode tools = createOpenAiToolArray("Tool", "A tool.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE);

            JsonNode resultParams = result.get(0).get("function").get("parameters");
            assertFalse(resultParams.has("additionalProperties"));
            assertFalse(resultParams.has("minItems"));
            assertFalse(resultParams.has("maxItems"));
            assertFalse(resultParams.has("pattern"));
            assertFalse(resultParams.has("format"));
        }

        @Test
        void aggressiveLevel_truncatesDescriptionTo100() {
            String desc = "X".repeat(150);
            ArrayNode tools = createOpenAiToolArray("Tool", desc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE);

            String resultDesc = result.get(0).get("function").get("description").asText();
            assertTrue(resultDesc.length() <= 103,
                    "AGGRESSIVE truncates to 100 chars + ellipsis");
        }
    }

    // ===================================================================
    // optimize() — COMPACT level
    // ===================================================================

    @Nested
    class OptimizeCompact {

        @Test
        void compactLevel_truncatesDescriptionTo60() {
            String desc = "Y".repeat(100);
            ArrayNode tools = createOpenAiToolArray("Tool", desc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT);

            String resultDesc = result.get(0).get("function").get("description").asText();
            assertTrue(resultDesc.length() <= 63,
                    "COMPACT truncates to 60 chars + ellipsis");
        }

        @Test
        void compactLevel_stripsLargeEnums() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            ArrayNode enumValues = MAPPER.createArrayNode();
            for (int i = 0; i < 8; i++) enumValues.add("val_" + i);
            prop.set("enum", enumValues);
            properties.set("mode", prop);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Tool", "A tool.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT);

            JsonNode resultProp = result.get(0).get("function").get("parameters")
                    .get("properties").get("mode");
            assertFalse(resultProp.has("enum"),
                    "COMPACT strips enums with >5 entries");
        }

        @Test
        void compactLevel_preservesSmallEnums() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            ArrayNode enumValues = MAPPER.createArrayNode();
            enumValues.add("a").add("b").add("c");
            prop.set("enum", enumValues);
            properties.set("mode", prop);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Tool", "A tool.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.COMPACT);

            JsonNode resultProp = result.get(0).get("function").get("parameters")
                    .get("properties").get("mode");
            assertTrue(resultProp.has("enum"),
                    "COMPACT preserves enums with <=5 entries");
        }
    }

    // ===================================================================
    // MCP tool format
    // ===================================================================

    @Nested
    class McpToolFormat {

        @Test
        void mcpFormat_optimizesDescription() {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", "mcp__kompile__read");
            tool.put("description", "D".repeat(300));
            ObjectNode inputSchema = MAPPER.createObjectNode();
            inputSchema.put("type", "object");
            tool.set("inputSchema", inputSchema);

            ArrayNode tools = MAPPER.createArrayNode();
            tools.add(tool);

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            String desc = result.get(0).get("description").asText();
            assertTrue(desc.length() <= 203);
        }

        @Test
        void mcpFormat_optimizesInputSchema() {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", "mcp__kompile__bash");
            tool.put("description", "Run a bash command.");
            ObjectNode inputSchema = MAPPER.createObjectNode();
            inputSchema.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode cmdProp = MAPPER.createObjectNode();
            cmdProp.put("type", "string");
            cmdProp.put("description", "The command to run");
            cmdProp.put("default", "echo hello");
            properties.set("command", cmdProp);
            inputSchema.set("properties", properties);
            tool.set("inputSchema", inputSchema);

            ArrayNode tools = MAPPER.createArrayNode();
            tools.add(tool);

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            assertFalse(result.get(0).get("inputSchema").get("properties")
                    .get("command").has("default"));
        }
    }

    // ===================================================================
    // estimateTokens()
    // ===================================================================

    @Nested
    class EstimateTokens {

        @Test
        void nullArray_returnsZero() {
            assertEquals(0, ToolSchemaOptimizer.estimateTokens(null));
        }

        @Test
        void emptyArray_returnsSmallValue() {
            ArrayNode empty = MAPPER.createArrayNode();
            int tokens = ToolSchemaOptimizer.estimateTokens(empty);
            assertTrue(tokens >= 0);
        }

        @Test
        void estimatesAsCharsOverFour() {
            ArrayNode tools = createOpenAiToolArray("Read", "Read a file.", createSimpleParams());
            String serialized = tools.toString();
            int expected = serialized.length() / 4;

            assertEquals(expected, ToolSchemaOptimizer.estimateTokens(tools));
        }
    }

    // ===================================================================
    // optimizeAdaptive()
    // ===================================================================

    @Nested
    class OptimizeAdaptive {

        @Test
        void fitsWithinBudget_returnsNone() {
            ArrayNode tools = createOpenAiToolArray("Read", "Read a file.", createSimpleParams());
            int budget = ToolSchemaOptimizer.estimateTokens(tools) + 100;

            ArrayNode result = ToolSchemaOptimizer.optimizeAdaptive(tools, budget);
            // Should use NONE level since it fits
            assertEquals(tools.toString(), result.toString());
        }

        @Test
        void tightBudget_usesHigherLevel() {
            ObjectNode params = createParamsWithLongDescriptions();
            ArrayNode tools = createOpenAiToolArray("Tool",
                    "X".repeat(300), params);

            // Budget too small for NONE
            int noneTokens = ToolSchemaOptimizer.estimateTokens(tools);
            ArrayNode result = ToolSchemaOptimizer.optimizeAdaptive(tools, noneTokens / 2);

            // Result should be smaller than original
            int resultTokens = ToolSchemaOptimizer.estimateTokens(result);
            assertTrue(resultTokens < noneTokens,
                    "Adaptive should produce a smaller result");
        }

        @Test
        void impossiblySmallBudget_returnsAggressive() {
            ArrayNode tools = createOpenAiToolArray("Tool", "Desc.", createSimpleParams());
            ArrayNode result = ToolSchemaOptimizer.optimizeAdaptive(tools, 1);

            // Should return something (AGGRESSIVE fallback) without throwing
            assertNotNull(result);
        }
    }

    // ===================================================================
    // Description truncation at sentence boundary
    // ===================================================================

    @Nested
    class DescriptionTruncation {

        @Test
        void truncatesAtSentenceBoundary() {
            // 180-char first sentence + more text
            String firstSentence = "A".repeat(120) + ". ";
            String desc = firstSentence + "B".repeat(200);
            ArrayNode tools = createOpenAiToolArray("Tool", desc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            String resultDesc = result.get(0).get("function").get("description").asText();
            // Should end at the sentence boundary period
            assertTrue(resultDesc.endsWith("."),
                    "Should truncate at sentence boundary");
        }

        @Test
        void noSentenceBoundary_usesEllipsis() {
            String desc = "A".repeat(300); // No periods at all
            ArrayNode tools = createOpenAiToolArray("Tool", desc, createSimpleParams());

            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            String resultDesc = result.get(0).get("function").get("description").asText();
            assertTrue(resultDesc.endsWith("..."),
                    "Should use ellipsis when no sentence boundary");
        }
    }

    // ===================================================================
    // Recursive schema optimization
    // ===================================================================

    @Nested
    class RecursiveOptimization {

        @Test
        void nestedObjectProperties_optimized() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();

            // Nested object property
            ObjectNode nested = MAPPER.createObjectNode();
            nested.put("type", "object");
            ObjectNode nestedProps = MAPPER.createObjectNode();
            ObjectNode innerProp = MAPPER.createObjectNode();
            innerProp.put("type", "string");
            innerProp.put("description", "Inner property description");
            innerProp.put("default", "foo");
            nestedProps.set("inner", innerProp);
            nested.set("properties", nestedProps);
            properties.set("config", nested);

            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Tool", "A tool.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            JsonNode innerResult = result.get(0).get("function").get("parameters")
                    .get("properties").get("config").get("properties").get("inner");
            assertFalse(innerResult.has("default"), "Nested defaults should be removed");
        }

        @Test
        void arrayItemsSchema_optimized() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode arrayProp = MAPPER.createObjectNode();
            arrayProp.put("type", "array");
            ObjectNode items = MAPPER.createObjectNode();
            items.put("type", "string");
            items.put("default", "item");
            arrayProp.set("items", items);
            properties.set("list", arrayProp);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Tool", "A tool.", params);
            ArrayNode result = ToolSchemaOptimizer.optimize(tools,
                    ToolSchemaOptimizer.OptimizationLevel.MODERATE);

            JsonNode itemsResult = result.get(0).get("function").get("parameters")
                    .get("properties").get("list").get("items");
            assertFalse(itemsResult.has("default"));
        }
    }

    // ===================================================================
    // Does not modify original
    // ===================================================================

    @Nested
    class Immutability {

        @Test
        void originalNotModified() {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            prop.put("default", "val");
            prop.put("description", "D".repeat(300));
            properties.set("p", prop);
            params.set("properties", properties);

            ArrayNode tools = createOpenAiToolArray("Tool", "X".repeat(300), params);
            String originalStr = tools.toString();

            ToolSchemaOptimizer.optimize(tools, ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE);

            assertEquals(originalStr, tools.toString(),
                    "Original tool definitions should not be modified");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static ArrayNode createOpenAiToolArray(String name, String description, ObjectNode params) {
        ObjectNode function = MAPPER.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", params);

        ObjectNode toolDef = MAPPER.createObjectNode();
        toolDef.put("type", "function");
        toolDef.set("function", function);

        ArrayNode array = MAPPER.createArrayNode();
        array.add(toolDef);
        return array;
    }

    private static ObjectNode createSimpleParams() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode prop = MAPPER.createObjectNode();
        prop.put("type", "string");
        properties.set("input", prop);
        params.set("properties", properties);
        return params;
    }

    private static ObjectNode createParamsWithLongDescriptions() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        for (int i = 0; i < 5; i++) {
            ObjectNode prop = MAPPER.createObjectNode();
            prop.put("type", "string");
            prop.put("description", "Parameter " + i + " description. " + "Z".repeat(200));
            prop.put("default", "default_" + i);
            prop.set("examples", MAPPER.createArrayNode().add("example_" + i));
            properties.set("param_" + i, prop);
        }
        params.set("properties", properties);
        return params;
    }
}
