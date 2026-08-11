/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.List;

final class CorpusSchemaUnificationPromptBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Keep the schema pre-pass prompt independent of corpus/document count. The model's
    // context window is finite, so long-tail observations must not consume the extraction budget.
    private static final int MAX_NODE_CANDIDATES = 12;
    private static final int MAX_RELATIONSHIP_CANDIDATES = 12;
    private static final int MAX_VALUES_PER_FIELD = 4;
    private static final int MAX_VALUE_CHARS = 96;

    private CorpusSchemaUnificationPromptBuilder() {
    }

    static String build(CorpusSchemaCandidates.Inventory inventory, GraphSchema configuredSchema) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are asked to produce a small, reusable graph schema overlay for downstream extraction.\n\n");
        prompt.append("Return JSON only.\n");
        prompt.append("Return exactly these top-level fields: nodeTypes, relationshipTypes, patterns.\n");
        prompt.append("Do not emit markdown fences or commentary.\n\n");

        prompt.append("Output contract:\n");
        prompt.append("1) candidate values are observations, not mandatory canonical schema definitions.\n");
        prompt.append("2) use existing configured schema as authoritative when provided; do not delete, rename, or redefine its canonical types.\n");
        prompt.append("3) infer the smallest reusable schema that best explains the candidate observations.\n");
        prompt.append("4) proper names, filenames, sheet names, dates, monetary values, and individual process runs are not schema types.\n");
        prompt.append("5) merge synonymous or near-duplicate candidates where practical.\n");
        prompt.append("6) preserve source-language predicates as relationship aliases.\n");
        prompt.append("7) node and relationship names must match [A-Z][A-Z0-9_]*.\n");
        prompt.append("8) node types may include stable scalar properties.\n");
        prompt.append("9) relationship types may include stable scalar properties and aliases.\n");
        prompt.append("10) each relationship type must include at least one directed pattern.\n");
        prompt.append("11) relationship patterns must use the canonical relationship type from relationshipTypes; aliases are not valid values inside [:...].\n");
        prompt.append("12) omit unsupported properties.\n");
        prompt.append("13) do not invent source facts, graph instances, or source entities.\n");
        prompt.append("14) never return placeholder or generic type names such as NODE_LABEL, REL_TYPE, ENTITY_TYPE, TYPE, UNKNOWN, or RELATIONSHIP; if evidence is insufficient, omit that type.\n\n");

        prompt.append("Canonical output shape:\n");
        prompt.append("{ \"nodeTypes\": [], \"relationshipTypes\": [], \"patterns\": [] }\n");
        prompt.append("Mandatory array element contract:\n");
        prompt.append("- Every nodeTypes item MUST be a JSON object with string fields label and description; it may also contain a properties array of objects with string fields name and type. Never put bare strings in nodeTypes.\n");
        prompt.append("- Every relationshipTypes item MUST be a JSON object with string fields type and description; it may also contain a properties array and an aliases array of strings. Never put bare strings in relationshipTypes.\n");
        prompt.append("- Every patterns item MUST be a string exactly shaped as (SOURCE_TYPE)-[:RELATIONSHIP_TYPE]->(TARGET_TYPE); candidate field names or isolated words are not patterns.\n");
        prompt.append("The empty JSON above shows only the top-level fields. Populate arrays only from the authoritative schema or candidate inventory, and obey the object contracts exactly.\n\n");

        prompt.append("Authorized property types: String, Integer, Decimal, Boolean, Date, Year, YearMonth, DateTime.\n");
        prompt.append("Use properties only when strongly supported by many observations.\n\n");

        prompt.append("AUTHORITATIVE EXISTING SCHEMA\n");
        if (configuredSchema == null) {
            prompt.append("None.\n\n");
        } else {
            prompt.append(serializeConfiguredSchema(configuredSchema)).append("\n\n");
        }

        prompt.append("CANDIDATE INVENTORY (observations; no raw corpus passages)\n");
        appendCandidateInventory(prompt, inventory);
        prompt.append("\n");

        return prompt.toString();
    }

    private static void appendCandidateInventory(StringBuilder prompt, CorpusSchemaCandidates.Inventory inventory) {
        CorpusSchemaCandidates.Inventory safeInventory = inventory == null
                ? new CorpusSchemaCandidates.Inventory(List.of(), List.of())
                : inventory;

        prompt.append("NODE_CANDIDATE_PAYLOAD\n");
        prompt.append("[\n");
        for (CorpusSchemaCandidates.NodeCandidate node : safeInventory.nodeCandidates().stream()
                .limit(MAX_NODE_CANDIDATES)
                .toList()) {
            prompt.append("  {\n");
            prompt.append("    \"candidateKey\": ").append(quoted(node.candidateKey())).append(",\n");
            prompt.append("    \"surfaceForms\": ").append(quotedList(node.surfaceForms())).append(",\n");
            prompt.append("    \"categories\": ").append(quotedList(node.categories())).append(",\n");
            prompt.append("    \"supportCount\": ").append(node.supportCount()).append(",\n");
            prompt.append("    \"maximumConfidence\": ").append(node.maximumConfidence()).append(",\n");
            prompt.append("    \"passageIds\": ").append(quotedList(node.passageIds()));
            prompt.append("\n  },\n");
        }
        if (!safeInventory.nodeCandidates().isEmpty()) {
            prompt.setLength(prompt.length() - 2);
            prompt.append("\n");
        }
        prompt.append("]\n\n");

        prompt.append("RELATIONSHIP_CANDIDATES\n");
        prompt.append("[\n");
        for (CorpusSchemaCandidates.RelationshipCandidate relation : safeInventory.relationshipCandidates().stream()
                .limit(MAX_RELATIONSHIP_CANDIDATES)
                .toList()) {
            prompt.append("  {\n");
            prompt.append("    \"candidateKey\": ").append(quoted(relation.candidateKey())).append(",\n");
            prompt.append("    \"surfaceForms\": ").append(quotedList(relation.surfaceForms())).append(",\n");
            prompt.append("    \"sourceConcepts\": ").append(quotedList(relation.sourceConcepts())).append(",\n");
            prompt.append("    \"targetConcepts\": ").append(quotedList(relation.targetConcepts())).append(",\n");
            prompt.append("    \"supportCount\": ").append(relation.supportCount()).append(",\n");
            prompt.append("    \"maximumStrength\": ").append(relation.maximumStrength()).append(",\n");
            prompt.append("    \"passageIds\": ").append(quotedList(relation.passageIds()));
            prompt.append("\n  },\n");
        }
        if (!safeInventory.relationshipCandidates().isEmpty()) {
            prompt.setLength(prompt.length() - 2);
            prompt.append("\n");
        }
        prompt.append("]\n\n");

        if (safeInventory.nodeCandidates().isEmpty() && safeInventory.relationshipCandidates().isEmpty()) {
            prompt.append("No observable candidate payload is available. Return an empty overlay schema.\n");
        }
    }

    private static String quoted(String value) {
        if (value == null) {
            return "\"\"";
        }
        return quotedMapValue(value);
    }

    private static String quotedMapValue(String value) {
        String bounded = value.length() > MAX_VALUE_CHARS
                ? value.substring(0, MAX_VALUE_CHARS)
                : value;
        String escaped = bounded
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return '"' + escaped + '"';
    }

    private static String quotedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder list = new StringBuilder("[");
        int emitted = 0;
        for (String value : values) {
            if (emitted++ >= MAX_VALUES_PER_FIELD) {
                break;
            }
            list.append(quoted(value)).append(", ");
        }
        if (list.length() >= 2) {
            list.setLength(list.length() - 2);
        }
        list.append("]");
        return list.toString();
    }

    private static String serializeConfiguredSchema(GraphSchema configuredSchema) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(configuredSchema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize configured schema.", exception);
        }
    }
}
