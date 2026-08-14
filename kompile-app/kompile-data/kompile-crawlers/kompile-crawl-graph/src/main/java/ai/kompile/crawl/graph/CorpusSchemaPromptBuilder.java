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

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

final class CorpusSchemaPromptBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Bound one semantic call. CorpusSchemaUnifier sends every ordered passage across batches.
    private static final int MAX_PASSAGES = 8;
    private static final int MAX_PASSAGE_CHARS = 1_024;

    private CorpusSchemaPromptBuilder() {
    }

    static String build(Map<String, String> passageTexts, GraphSchema establishedSchema) {
        return build(passageTexts, establishedSchema, true);
    }

    static String build(
            Map<String, String> passageTexts,
            GraphSchema establishedSchema,
            boolean structuredToolAvailable) {
        validatePassageInputs(passageTexts);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Read the corpus passages and infer their reusable graph ontology.\n");
        if (structuredToolAvailable) {
            prompt.append("Call submit_corpus_schema exactly once. Add no prose.\n");
        } else {
            prompt.append("Return only one JSON object with nodeTypes, relationshipTypes, and patterns arrays.\n");
        }
        prompt.append("Add each distinct type exactly once. Never repeat an array item.\n");
        prompt.append("A statement like 'X is a Y' makes Y a node-type candidate, never X.\n");
        prompt.append("A statement like 'X verb Y' makes the verb a directed relationship-type candidate.\n");
        prompt.append("Add a node type for each explicit reusable entity category, never for a particular name or value.\n");
        prompt.append("Add a relationship type for each explicit directed relation category.\n");
        prompt.append("For every relationship type, add one patterns object whose sourceType and targetType are declared node labels.\n");
        prompt.append("Type names must be UPPER_SNAKE_CASE and match [A-Z][A-Z0-9_]*.\n");
        prompt.append("Use empty arrays only when the passages contain no reusable entity or relation categories.\n\n");

        prompt.append("AUTHORITATIVE EXISTING SCHEMA\n");
        if (!hasSchemaContent(establishedSchema)) {
            prompt.append("None. Infer the initial ontology from the passages.\n\n");
        } else {
            prompt.append("Only add missing types. Do not delete, rename, redefine, or repeat these types:\n");
            prompt.append(serializeConfiguredSchema(establishedSchema)).append("\n\n");
        }

        prompt.append("CORPUS PASSAGES\n");
        int emitted = 0;
        for (Map.Entry<String, String> passage : passageTexts.entrySet()) {
            if (emitted++ >= MAX_PASSAGES) {
                break;
            }
            prompt.append("BEGIN CORPUS PASSAGE\n");
            prompt.append("chunkId: ").append(passage.getKey()).append('\n');
            String text = passage.getValue();
            prompt.append(text.length() > MAX_PASSAGE_CHARS
                    ? text.substring(0, MAX_PASSAGE_CHARS)
                    : text).append('\n');
            prompt.append("END CORPUS PASSAGE\n\n");
        }

        return prompt.toString();
    }

    private static boolean hasSchemaContent(GraphSchema schema) {
        return schema != null
                && ((schema.getNodeTypes() != null && !schema.getNodeTypes().isEmpty())
                || (schema.getRelationshipTypes() != null
                        && !schema.getRelationshipTypes().isEmpty())
                || (schema.getPatterns() != null && !schema.getPatterns().isEmpty()));
    }

    private static void validatePassageInputs(Map<String, String> passageTexts) {
        if (passageTexts == null) {
            throw new IllegalArgumentException("passageTexts must not be null");
        }
        if (passageTexts.isEmpty()) {
            throw new IllegalArgumentException("passageTexts must not be empty");
        }
        for (Map.Entry<String, String> passage : passageTexts.entrySet()) {
            if (passage.getKey() == null || passage.getKey().isBlank()) {
                throw new IllegalArgumentException("passageTexts keys must be non-null and non-blank");
            }
            if (passage.getValue() == null || passage.getValue().isBlank()) {
                throw new IllegalArgumentException("passageTexts values must be non-null and non-blank");
            }
        }
    }

    private static String serializeConfiguredSchema(GraphSchema configuredSchema) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(configuredSchema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize configured schema.", exception);
        }
    }
}
