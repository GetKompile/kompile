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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Utility class that compresses tool definitions (JSON schemas) to reduce
 * the token footprint when sending tool lists to LLMs.
 *
 * <p>Three optimization levels are supported:
 * <ul>
 *   <li>{@link OptimizationLevel#NONE} – pass-through, no changes</li>
 *   <li>{@link OptimizationLevel#MODERATE} – trims long descriptions,
 *       removes {@code examples} and {@code default} fields</li>
 *   <li>{@link OptimizationLevel#AGGRESSIVE} – all of MODERATE plus
 *       strips parameter descriptions entirely and removes several
 *       rarely-needed schema constraint fields</li>
 * </ul>
 *
 * <p>This class is thread-safe and stateless; all methods are static.
 */
public final class ToolSchemaOptimizer {

    /** Maximum description length for {@link OptimizationLevel#MODERATE}. */
    private static final int MODERATE_DESC_MAX = 200;

    /** Maximum description length for {@link OptimizationLevel#AGGRESSIVE}. */
    private static final int AGGRESSIVE_DESC_MAX = 100;

    private static final String ELLIPSIS = "...";

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /**
     * Schema fields that are stripped at the AGGRESSIVE level because they
     * add bytes without materially helping the model choose the right tool.
     */
    private static final String[] AGGRESSIVE_REMOVE_FIELDS = {
        "additionalProperties",
        "minItems",
        "maxItems",
        "pattern",
        "format"
    };

    /** Maximum description length for {@link OptimizationLevel#COMPACT}. */
    private static final int COMPACT_DESC_MAX = 60;

    /** Optimization levels, ordered from least to most aggressive. */
    public enum OptimizationLevel {
        /** No compression — schemas are returned unchanged. */
        NONE,
        /**
         * Moderate compression: truncate descriptions to 200 chars, remove
         * {@code examples} and {@code default} fields from parameter schemas.
         */
        MODERATE,
        /**
         * Aggressive compression: truncate descriptions to 100 chars, strip
         * all parameter descriptions, and remove constraint-only schema fields
         * ({@code additionalProperties}, {@code minItems}, {@code maxItems},
         * {@code pattern}, {@code format}).
         */
        AGGRESSIVE,
        /**
         * Compact compression for MCP tool listings: truncate top-level
         * description to 60 chars, strip ALL parameter descriptions,
         * remove all constraint fields, collapse nested object schemas
         * to just their required fields, and strip enum values from
         * parameters where the type alone is sufficient.
         */
        COMPACT
    }

    /** Private constructor — this is a pure static utility class. */
    private ToolSchemaOptimizer() {
        throw new UnsupportedOperationException("ToolSchemaOptimizer is a utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Return a new {@link ArrayNode} containing optimized copies of each tool
     * definition in {@code toolDefs}.  The original array is never modified.
     *
     * @param toolDefs  array of OpenAI-style function tool definitions
     * @param level     desired optimization level
     * @return          new array with compressed schemas; never {@code null}
     */
    public static ArrayNode optimize(ArrayNode toolDefs, OptimizationLevel level) {
        if (toolDefs == null) {
            return MAPPER.createArrayNode();
        }
        if (level == OptimizationLevel.NONE) {
            return toolDefs.deepCopy();
        }

        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode toolDef : toolDefs) {
            result.add(optimizeSingleTool(toolDef, level));
        }
        return result;
    }

    /**
     * Estimate the token count for the given tool definitions array using the
     * rough heuristic of {@code chars / 4}.
     *
     * @param toolDefs  array of tool definitions (may be {@code null})
     * @return          estimated token count; 0 if {@code toolDefs} is null
     */
    public static int estimateTokens(ArrayNode toolDefs) {
        if (toolDefs == null) {
            return 0;
        }
        String serialized = toolDefs.toString();
        return serialized.length() / 4;
    }

    /**
     * Adaptively optimize tool definitions to fit within a token budget.
     * Tries {@link OptimizationLevel#NONE} first, then
     * {@link OptimizationLevel#MODERATE}, then
     * {@link OptimizationLevel#AGGRESSIVE}, returning the first result whose
     * estimated token count is within {@code tokenBudget}.
     *
     * <p>If even the AGGRESSIVE version exceeds the budget, that version is
     * returned anyway — the caller is responsible for deciding whether to
     * proceed or prune tools further.
     *
     * @param toolDefs    array of tool definitions
     * @param tokenBudget maximum acceptable estimated token count
     * @return            optimized array that ideally fits within the budget
     */
    public static ArrayNode optimizeAdaptive(ArrayNode toolDefs, int tokenBudget) {
        for (OptimizationLevel level : OptimizationLevel.values()) {
            ArrayNode candidate = optimize(toolDefs, level);
            if (estimateTokens(candidate) <= tokenBudget) {
                return candidate;
            }
        }
        // Return AGGRESSIVE as the best-effort result even if still over budget.
        return optimize(toolDefs, OptimizationLevel.AGGRESSIVE);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Produce an optimized deep copy of a single tool definition node.
     * Handles two shapes:
     * <pre>
     * // OpenAI function-calling format:
     * { "type": "function", "function": { "name": "...", "description": "...", "parameters": { ... } } }
     *
     * // MCP tool format:
     * { "name": "...", "description": "...", "inputSchema": { ... } }
     * </pre>
     */
    private static JsonNode optimizeSingleTool(JsonNode toolDef, OptimizationLevel level) {
        ObjectNode copy = toolDef.deepCopy();

        // Try OpenAI function-calling format first
        JsonNode functionNode = copy.get("function");
        if (functionNode != null && functionNode.isObject()) {
            ObjectNode function = (ObjectNode) functionNode;
            optimizeDescriptionField(function, level);
            JsonNode parametersNode = function.get("parameters");
            if (parametersNode != null && parametersNode.isObject()) {
                optimizeParametersSchema((ObjectNode) parametersNode, level);
            }
            return copy;
        }

        // MCP tool format: name/description/inputSchema at top level
        if (copy.has("name")) {
            optimizeDescriptionField(copy, level);
            JsonNode inputSchema = copy.get("inputSchema");
            if (inputSchema != null && inputSchema.isObject()) {
                optimizeParametersSchema((ObjectNode) inputSchema, level);
            }
            return copy;
        }

        return copy;
    }

    /**
     * Truncate the {@code "description"} field on {@code node} according to
     * the given optimization level.  Operates in-place on the provided
     * {@link ObjectNode}.
     */
    private static void optimizeDescriptionField(ObjectNode node, OptimizationLevel level) {
        JsonNode descNode = node.get("description");
        if (descNode == null || !descNode.isTextual()) {
            return;
        }
        int maxLen = switch (level) {
            case COMPACT -> COMPACT_DESC_MAX;
            case AGGRESSIVE -> AGGRESSIVE_DESC_MAX;
            default -> MODERATE_DESC_MAX;
        };
        String desc = descNode.asText();
        if (desc.length() > maxLen) {
            // Try to cut at sentence boundary for readability
            int cutoff = desc.lastIndexOf(". ", maxLen);
            if (cutoff > maxLen / 2) {
                node.put("description", desc.substring(0, cutoff + 1));
            } else {
                node.put("description", desc.substring(0, maxLen) + ELLIPSIS);
            }
        }
    }

    /**
     * Recursively optimize a JSON Schema object that describes tool parameters.
     * Handles both the top-level schema and nested property sub-schemas.
     */
    private static void optimizeParametersSchema(ObjectNode schema, OptimizationLevel level) {
        // Remove examples and default at every level (MODERATE and above).
        schema.remove("examples");
        schema.remove("default");

        boolean isAggressive = level == OptimizationLevel.AGGRESSIVE || level == OptimizationLevel.COMPACT;

        if (isAggressive) {
            // Strip constraint-only fields.
            for (String field : AGGRESSIVE_REMOVE_FIELDS) {
                schema.remove(field);
            }
        }

        // Descend into "properties" sub-schemas.
        JsonNode propertiesNode = schema.get("properties");
        if (propertiesNode != null && propertiesNode.isObject()) {
            ObjectNode properties = (ObjectNode) propertiesNode;
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode propSchema = entry.getValue();
                if (!propSchema.isObject()) {
                    continue;
                }
                ObjectNode propObj = (ObjectNode) propSchema;

                if (isAggressive) {
                    // Remove parameter descriptions entirely.
                    propObj.remove("description");
                } else {
                    // MODERATE: just truncate the description.
                    optimizeDescriptionField(propObj, level);
                }

                if (level == OptimizationLevel.COMPACT) {
                    // COMPACT: additional reductions
                    // Strip enum values with more than 5 entries (name is enough)
                    JsonNode enumNode = propObj.get("enum");
                    if (enumNode != null && enumNode.isArray() && enumNode.size() > 5) {
                        propObj.remove("enum");
                    }
                    // Collapse nested items schemas to just type
                    JsonNode itemsNode = propObj.get("items");
                    if (itemsNode != null && itemsNode.isObject()) {
                        ObjectNode itemsObj = (ObjectNode) itemsNode;
                        if (itemsObj.has("properties")) {
                            // Nested object items — collapse to just list property names
                            JsonNode nestedProps = itemsObj.get("properties");
                            if (nestedProps.isObject()) {
                                StringBuilder keySummary = new StringBuilder("object with keys: ");
                                Iterator<String> keyIter = nestedProps.fieldNames();
                                int keyCount = 0;
                                while (keyIter.hasNext()) {
                                    if (keyCount > 0) keySummary.append(", ");
                                    keySummary.append(keyIter.next());
                                    keyCount++;
                                }
                                ObjectNode collapsed = MAPPER.createObjectNode();
                                collapsed.put("type", "object");
                                collapsed.put("description", keySummary.toString());
                                propObj.set("items", collapsed);
                            }
                        }
                    }
                }

                // Recurse for nested schemas (e.g., array items, nested objects).
                optimizeParametersSchema(propObj, level);
            }
        }

        // Recurse into "items" for array schemas.
        JsonNode itemsNode = schema.get("items");
        if (itemsNode != null && itemsNode.isObject()) {
            optimizeParametersSchema((ObjectNode) itemsNode, level);
        }

        // Recurse into "oneOf", "anyOf", "allOf" sub-schemas.
        for (String compositeKeyword : new String[]{"oneOf", "anyOf", "allOf"}) {
            JsonNode compositeNode = schema.get(compositeKeyword);
            if (compositeNode != null && compositeNode.isArray()) {
                for (JsonNode subSchema : compositeNode) {
                    if (subSchema.isObject()) {
                        optimizeParametersSchema((ObjectNode) subSchema, level);
                    }
                }
            }
        }
    }
}
