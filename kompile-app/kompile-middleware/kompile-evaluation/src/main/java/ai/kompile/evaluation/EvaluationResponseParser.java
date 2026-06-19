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

package ai.kompile.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared utility for parsing LLM evaluation JSON responses.
 * Handles markdown fences, malformed JSON, and missing fields.
 */
@Slf4j
public final class EvaluationResponseParser {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private EvaluationResponseParser() {}

    /**
     * Parse a raw LLM response into a JsonNode.
     * Strips markdown code fences and handles common malformations.
     *
     * @param raw the raw LLM response text
     * @return parsed JsonNode, or null if parsing fails entirely
     */
    public static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("Evaluation response is null or blank");
            return null;
        }

        String cleaned = stripMarkdownFences(raw).trim();

        // Try to extract JSON object from the response
        int braceStart = cleaned.indexOf('{');
        int braceEnd = cleaned.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            cleaned = cleaned.substring(braceStart, braceEnd + 1);
        }

        try {
            return MAPPER.readTree(cleaned);
        } catch (Exception e) {
            log.warn("Failed to parse evaluation response as JSON: {}", e.getMessage());
            log.debug("Raw response was: {}", raw);
            return null;
        }
    }

    /**
     * Extract a double value from a JsonNode.
     *
     * @param node         the parsed JSON
     * @param field        the field name
     * @param defaultValue value if field is missing or not numeric
     * @return the extracted double
     */
    public static double getDouble(JsonNode node, String field, double defaultValue) {
        if (node == null || !node.has(field)) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        // Try parsing string representation
        try {
            return Double.parseDouble(value.asText().trim());
        } catch (Exception e) {
            log.warn("Field '{}' is not numeric: {}", field, value.asText());
            return defaultValue;
        }
    }

    /**
     * Extract an int value from a JsonNode.
     */
    public static int getInt(JsonNode node, String field, int defaultValue) {
        if (node == null || !node.has(field)) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (Exception e) {
            log.warn("Field '{}' is not an integer: {}", field, value.asText());
            return defaultValue;
        }
    }

    /**
     * Extract a string value from a JsonNode.
     */
    public static String getString(JsonNode node, String field, String defaultValue) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        return node.get(field).asText(defaultValue);
    }

    /**
     * Extract a string array from a JsonNode array field.
     */
    public static List<String> getStringArray(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : node.get(field)) {
            String text = element.asText();
            if (text != null && !text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private static String stripMarkdownFences(String raw) {
        String result = raw;
        // Strip ```json ... ``` or ``` ... ```
        if (result.contains("```")) {
            result = result.replaceAll("```json\\s*", "");
            result = result.replaceAll("```\\s*", "");
        }
        return result;
    }
}
