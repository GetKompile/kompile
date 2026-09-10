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
        return optimize(toolDefs, level, Map.of());
    }

    /**
     * Optimize with per-tool curated compact hints. At the COMPACT and AGGRESSIVE
     * levels — which strip parameter descriptions and hard-truncate the tool
     * description — a tool whose name appears in {@code compactHints} has its
     * description replaced by the curated hint instead of being blind-truncated,
     * so the compressed schema still tells an agent how to call the tool. Hints
     * are keyed by tool name and ignored at NONE/MODERATE (where the full
     * description and parameter docs are already present).
     *
     * @param toolDefs     array of tool definitions (OpenAI or MCP shape)
     * @param level        desired optimization level
     * @param compactHints tool-name → curated one-line hint; may be {@code null}/empty
     * @return             new array with compressed schemas; never {@code null}
     */
    public static ArrayNode optimize(ArrayNode toolDefs, OptimizationLevel level,
                                     Map<String, String> compactHints) {
        if (toolDefs == null) {
            return MAPPER.createArrayNode();
        }
        Map<String, String> hints = (compactHints == null) ? Map.of() : compactHints;
        if (level == OptimizationLevel.NONE) {
            return toolDefs.deepCopy();
        }

        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode toolDef : toolDefs) {
            result.add(optimizeSingleTool(toolDef, level, hints));
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
    private static JsonNode optimizeSingleTool(JsonNode toolDef, OptimizationLevel level,
                                               Map<String, String> compactHints) {
        ObjectNode copy = toolDef.deepCopy();

        // Try OpenAI function-calling format first
        JsonNode functionNode = copy.get("function");
        if (functionNode != null && functionNode.isObject()) {
            ObjectNode function = (ObjectNode) functionNode;
            optimizeDescriptionField(function, level,
                    compactHintFor(compactHints, level, function.path("name").asText(null)));
            JsonNode parametersNode = function.get("parameters");
            if (parametersNode != null && parametersNode.isObject()) {
                optimizeParametersSchema((ObjectNode) parametersNode,
                        parameterLevel(level, function.path("name").asText()));
            }
            return copy;
        }

        // MCP tool format: name/description/inputSchema at top level
        if (copy.has("name")) {
            optimizeDescriptionField(copy, level,
                    compactHintFor(compactHints, level, copy.path("name").asText(null)));
            JsonNode inputSchema = copy.get("inputSchema");
            if (inputSchema != null && inputSchema.isObject()) {
                optimizeParametersSchema((ObjectNode) inputSchema,
                        parameterLevel(level, copy.path("name").asText()));
            }
            return copy;
        }

        return copy;
    }

    /**
     * The curated compact hint to substitute for a tool's description, or {@code null} to
     * keep the default truncation behavior. Only applies at the aggressive levels
     * (COMPACT/AGGRESSIVE) that strip parameter descriptions; at NONE/MODERATE the full
     * docs remain, so no hint is used.
     */
    private static String compactHintFor(Map<String, String> compactHints,
                                         OptimizationLevel level, String toolName) {
        if (toolName == null
                || (level != OptimizationLevel.COMPACT && level != OptimizationLevel.AGGRESSIVE)) {
            return null;
        }
        String hint = compactHints.get(toolName);
        return (hint != null && !hint.isBlank()) ? hint : null;
    }

    /**
     * Truncate the {@code "description"} field on {@code node} according to
     * the given optimization level.  Operates in-place on the provided
     * {@link ObjectNode}.
     */
    private static void optimizeDescriptionField(ObjectNode node, OptimizationLevel level,
                                                 String compactHint) {
        JsonNode descNode = node.get("description");
        if (descNode == null || !descNode.isTextual()) {
            return;
        }
        // A curated compact hint (COMPACT/AGGRESSIVE only) replaces blind truncation, so the
        // compressed schema keeps the one line an agent needs to call the tool correctly.
        if (compactHint != null) {
            node.put("description", capHint(compactHint));
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
     * Upper bound on a curated compact hint, so a careless hint can't defeat compaction.
     * Curated hints are meant to be one dense line; anything longer is trimmed at a word
     * boundary. Larger than the blind COMPACT/AGGRESSIVE caps on purpose — the whole point
     * of a hint is that a curated line carries more usable signal than a blind truncation.
     */
    private static final int HINT_MAX = 200;

    private static String capHint(String hint) {
        String h = hint.strip();
        if (h.length() <= HINT_MAX) {
            return h;
        }
        int cut = h.lastIndexOf(' ', HINT_MAX);
        if (cut < HINT_MAX / 2) {
            cut = HINT_MAX;
        }
        return h.substring(0, cut) + ELLIPSIS;
    }

    private static OptimizationLevel parameterLevel(OptimizationLevel level, String toolName) {
        // Parallel dispatch needs typed subtask fields and required name/prompt keys, not an
        // opaque "object with keys" summary. Still strip prose/defaults; do not expand other tools.
        return level == OptimizationLevel.COMPACT && "multi_task".equals(toolName)
                ? OptimizationLevel.AGGRESSIVE : level;
    }

    private static boolean preserveLargeEnum(String propertyName) {
        return "action".equals(propertyName) || "fn".equals(propertyName) || "operation".equals(propertyName);
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
                    // MODERATE: just truncate the description (param descriptions get no hint).
                    optimizeDescriptionField(propObj, level, null);
                }

                if (level == OptimizationLevel.COMPACT) {
                    // COMPACT: additional reductions. Keep action/function selector enums even
                    // when large; without them action-heavy tools become guesswork after compacting.
                    JsonNode enumNode = propObj.get("enum");
                    if (enumNode != null && enumNode.isArray() && enumNode.size() > 5
                            && !preserveLargeEnum(entry.getKey())) {
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
