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

    // Legacy callers may still use this passage-based builder. Keep its prompt bounded
    // so schema inference never grows with the number or size of corpus documents.
    private static final int MAX_PASSAGES = 12;
    private static final int MAX_PASSAGE_CHARS = 512;

    private CorpusSchemaPromptBuilder() {
    }

    static String build(Map<String, String> passageTexts, GraphSchema configuredSchema) {
        validatePassageInputs(passageTexts);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are inferring a reusable graph schema overlay for this corpus.\n\n");
        prompt.append("Return exactly one JSON object.\n");
        prompt.append("Return JSON only.\n");
        prompt.append("Do not use markdown fences.\n\n");

        prompt.append("Output requirements:\n");
        prompt.append("1) Generate reusable type definitions, not source instances.\n");
        prompt.append("2) Person names, organizations, filenames, sheet names, dates, monetary values, and particular process runs must not become schema type names.\n");
        prompt.append("3) Node and relationship names must match [A-Z][A-Z0-9_]*.\n");
        prompt.append("4) Every node and relationship definition must include a concise description.\n");
        prompt.append("5) Properties describe reusable scalar fields on a type.\n");
        prompt.append("6) Independently queryable concepts should be nodes rather than properties.\n");
        prompt.append("7) Use only these canonical property types: String, Integer, Decimal, Boolean, Date, Year, YearMonth, DateTime.\n");
        prompt.append("8) Omit the properties field when no stable property contract is supported.\n");
        prompt.append("9) An explicitly empty properties array means the type allows no properties.\n");
        prompt.append("10) Every newly proposed relationship type must have at least one directed pattern.\n");
        prompt.append("11) Patterns must be exactly (SOURCE_TYPE)-[:RELATION_TYPE]->(TARGET_TYPE).\n");
        prompt.append("12) Relationship aliases are source-language phrases/synonyms and are not additional canonical relationship types.\n");
        prompt.append("13) Schema definitions are vocabulary and constraints, not source evidence.\n");
        prompt.append("14) The output is a schema overlay, not extracted graph entities or facts.\n\n");

        prompt.append("When configured schema exists, treat it as authoritative and do not rename or delete existing canonical names.\n");
        prompt.append("You may add missing definitions, fill a missing description, fill null property contracts, add relationship aliases, and add missing directed patterns.\n");
        prompt.append("Return only the overlay additions, not a duplicate of the full configured schema.\n\n");

        prompt.append("Authoritative schema JSON contract to follow:\n");
        prompt.append("{\n");
        prompt.append("  \"nodeTypes\": [\n");
        prompt.append("    {\"label\": \"NODE_LABEL\", \"description\": \"...\", \"properties\": [{\"name\": \"property_name\", \"type\": \"String\"}] }\n");
        prompt.append("  ],\n");
        prompt.append("  \"relationshipTypes\": [\n");
        prompt.append("    {\"type\": \"RELATION_TYPE\", \"description\": \"...\", \"aliases\": [\"from\", \"sent by\"] }\n");
        prompt.append("  ],\n");
        prompt.append("  \"patterns\": [\"(SOURCE_TYPE)-[:RELATION_TYPE]->(TARGET_TYPE)\"]\n");
        prompt.append("}\n\n");

        prompt.append("Example output:\n");
        prompt.append("{\n");
        prompt.append("  \"nodeTypes\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"label\": \"EMAIL_MESSAGE\",\n");
        prompt.append("      \"description\": \"An email message.\",\n");
        prompt.append("      \"properties\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"name\": \"subject\",\n");
        prompt.append("          \"type\": \"String\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    },\n");
        prompt.append("    {\n");
        prompt.append("      \"label\": \"PERSON\",\n");
        prompt.append("      \"description\": \"A human actor.\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"relationshipTypes\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"type\": \"SENT_BY\",\n");
        prompt.append("      \"description\": \"An email message was sent by a person.\",\n");
        prompt.append("      \"aliases\": [\"sent by\", \"from\"]\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"patterns\": [\"(EMAIL_MESSAGE)-[:SENT_BY]->(PERSON)\"]\n");
        prompt.append("}\n\n");

        prompt.append("AUTHORITATIVE EXISTING SCHEMA\n");
        if (configuredSchema == null) {
            prompt.append("None.\n\n");
        } else {
            prompt.append(serializeConfiguredSchema(configuredSchema)).append("\n\n");
        }

        prompt.append("CANDIDATE SCHEMA OVERLAY\n");
        prompt.append("Infer the schema overlay JSON now.\n\n");

        prompt.append("CORPUS PASSAGES (bounded observations)\n");
        int emittedPassages = 0;
        for (Map.Entry<String, String> passage : passageTexts.entrySet()) {
            if (emittedPassages++ >= MAX_PASSAGES) {
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
