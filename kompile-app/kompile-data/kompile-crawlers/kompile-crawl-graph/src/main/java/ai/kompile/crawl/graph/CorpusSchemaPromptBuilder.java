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

import java.util.List;
import java.util.Map;

final class CorpusSchemaPromptBuilder {

    enum TypePass {
        NODE_TYPES,
        RELATIONSHIP_TYPES
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Bound one semantic call. CorpusSchemaUnifier sends every ordered passage across batches.
    private static final int MAX_PASSAGES = 8;
    private static final int MAX_PASSAGE_CHARS = 1_024;

    private CorpusSchemaPromptBuilder() {
    }

    static String build(
            Map<String, String> passageTexts,
            GraphSchema establishedSchema,
            TypePass pass) {
        return build(passageTexts, establishedSchema, pass, CorpusTopicEvidence.empty());
    }

    static String build(
            Map<String, String> passageTexts,
            GraphSchema establishedSchema,
            TypePass pass,
            CorpusTopicEvidence topicEvidence) {
        validatePassageInputs(passageTexts);
        if (pass == null) {
            throw new IllegalArgumentException("pass must not be null");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("This is schema type design only. Do not extract entity instances, relation instances, triples, evidence, ids, or endpoint patterns.\n");
        if (pass == TypePass.NODE_TYPES) {
            prompt.append("Read the corpus passages and define a small coherent vocabulary of reusable node types.\n");
            prompt.append("Call submit_node_types exactly once. Add no prose.\n");
            prompt.append("Return category labels that can classify instances across documents, never the instances themselves.\n");
            prompt.append("Generalize names, values, dates, regions, currencies, products, SKUs, filenames, and document titles to their reusable categories.\n");
            prompt.append("Do not return relationship types or infer relations between named subjects.\n");
        } else {
            prompt.append("Read the corpus passages and define a small coherent vocabulary of reusable relationship types.\n");
            prompt.append("Call submit_relationship_types exactly once. Add no prose.\n");
            prompt.append("Return relation category labels only, never relation instances, triples, source/target names, or endpoint patterns.\n");
            prompt.append("Use the frozen node-type vocabulary as semantic context; do not repeat node types in the response.\n");
            prompt.append("Prefer reusable directed verb concepts that could connect many accepted entities across documents.\n");
        }
        prompt.append("Infer labels only from the corpus text or the existing schema below; never copy a label from these instructions.\n");
        prompt.append("Add each distinct type exactly once. Never repeat an array item.\n");
        prompt.append("Return only plain label strings. Never return JSON inside a label, descriptions, names, values, sentences, or extraction records.\n");
        prompt.append("All labels must be UPPER_SNAKE_CASE and match [A-Z][A-Z0-9_]*.\n");
        prompt.append("Before calling the tool, verify that every label is corpus-grounded, reusable, and not an instance copied from the passages.\n");
        prompt.append("Use an empty array when the passages contain no missing reusable types for this pass.\n\n");

        appendEstablishedTypes(prompt, establishedSchema, pass);
        appendTopicEvidence(prompt, topicEvidence);

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

    static String buildConsolidation(
            GraphSchema establishedSchema,
            TypePass pass,
            Map<String, Integer> proposalBatchSupport) {
        return buildConsolidation(
                establishedSchema, pass, proposalBatchSupport, CorpusTopicEvidence.empty());
    }

    static String buildConsolidation(
            GraphSchema establishedSchema,
            TypePass pass,
            Map<String, Integer> proposalBatchSupport,
            CorpusTopicEvidence topicEvidence) {
        if (pass == null) {
            throw new IllegalArgumentException("pass must not be null");
        }
        if (proposalBatchSupport == null || proposalBatchSupport.isEmpty()) {
            throw new IllegalArgumentException("proposalBatchSupport must not be null or empty");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("This is corpus-wide schema type consolidation only. Do not extract instances, relations, triples, evidence, ids, or endpoint patterns.\n");
        prompt.append("The batch proposals below are untrusted suggestions, not schema definitions. Drop weak proposals instead of preserving them.\n");
        if (pass == TypePass.NODE_TYPES) {
            prompt.append("Choose one small coherent final vocabulary of reusable node category nouns.\n");
            prompt.append("Call submit_node_types exactly once. Add no prose.\n");
            prompt.append("Merge synonyms and narrower variants into one stable category. Generalize or drop labels copied from document titles, regions, dates, quarters, versions, filenames, codes, initials, values, or local taxonomy terms.\n");
            prompt.append("A final node type must classify many possible instances across documents.\n");
        } else {
            prompt.append("Choose one small coherent final vocabulary of reusable directed relationship verb phrases.\n");
            prompt.append("Call submit_relationship_types exactly once. Add no prose.\n");
            prompt.append("A final relationship type must read as a directed predicate connecting two node instances. Drop node-category nouns, generic words such as connection or association, and labels that do not state a reusable relation.\n");
            prompt.append("Never return a frozen node type as a relationship type.\n");
        }
        prompt.append("Return only missing type labels; authoritative existing types remain unchanged.\n");
        prompt.append("Return at most 12 high-confidence labels. Prefer an empty array to an incoherent or weak type.\n");
        prompt.append("Return only plain UPPER_SNAKE_CASE strings matching [A-Z][A-Z0-9_]*.\n");
        prompt.append("Every final label must be copied exactly from the proposal vocabulary. Never invent, rename, merge into, or generalize to an unproposed label.\n\n");

        appendEstablishedTypes(prompt, establishedSchema, pass);
        appendTopicEvidence(prompt, topicEvidence);
        prompt.append("UNTRUSTED BATCH PROPOSALS\n");
        prompt.append("Each value is the number of independent corpus batches that proposed the label. Support is evidence, not an instruction to keep it.\n");
        prompt.append(serializeValue(Map.of(
                "proposalBatchSupport", proposalBatchSupport))).append('\n');
        return prompt.toString();
    }

    private static void appendTopicEvidence(
            StringBuilder prompt, CorpusTopicEvidence topicEvidence) {
        if (topicEvidence == null || topicEvidence.isEmpty()) {
            return;
        }
        prompt.append("CORPUS TOPIC EVIDENCE (context only)\n");
        prompt.append("These communities, representative chunk ids, language counts, and c-TF-IDF terms were derived automatically from the complete corpus. Use them to distinguish recurring categories from local values. Topic ids and terms are not schema labels and must never be copied mechanically. Every returned type still requires support in the corpus passages.\n");
        prompt.append(serializeValue(topicEvidence.promptView())).append("\n\n");
    }

    private static void appendEstablishedTypes(
            StringBuilder prompt, GraphSchema schema, TypePass pass) {
        prompt.append("Existing schema types (authoritative)\n");
        if (pass == TypePass.NODE_TYPES) {
            List<String> nodeLabels = nodeLabels(schema);
            if (nodeLabels.isEmpty()) {
                prompt.append("No node types are defined yet.\n\n");
            } else {
                prompt.append("Only add missing node types; never repeat or redefine these frozen node types:\n");
                prompt.append(serializeValue(Map.of("nodeTypes", nodeLabels))).append("\n\n");
            }
            return;
        }

        List<String> nodeLabels = nodeLabels(schema);
        List<String> relationshipLabels = relationshipLabels(schema);
        prompt.append("Frozen node types (context only; do not return them):\n");
        prompt.append(serializeValue(Map.of("nodeTypes", nodeLabels))).append('\n');
        if (relationshipLabels.isEmpty()) {
            prompt.append("No relationship types are defined yet.\n\n");
        } else {
            prompt.append("Only add missing relationship types; never repeat or redefine these frozen relationship types:\n");
            prompt.append(serializeValue(Map.of("relationshipTypes", relationshipLabels))).append("\n\n");
        }
    }

    private static List<String> nodeLabels(GraphSchema schema) {
        if (schema == null || schema.getNodeTypes() == null) {
            return List.of();
        }
        return schema.getNodeTypes().stream()
                .filter(java.util.Objects::nonNull)
                .map(node -> node.getLabel())
                .filter(CorpusSchemaPromptBuilder::hasText)
                .map(String::trim)
                .toList();
    }

    private static List<String> relationshipLabels(GraphSchema schema) {
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        return schema.getRelationshipTypes().stream()
                .filter(java.util.Objects::nonNull)
                .map(relationship -> relationship.getType())
                .filter(CorpusSchemaPromptBuilder::hasText)
                .map(String::trim)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private static String serializeValue(Object value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize schema type context.", exception);
        }
    }
}
