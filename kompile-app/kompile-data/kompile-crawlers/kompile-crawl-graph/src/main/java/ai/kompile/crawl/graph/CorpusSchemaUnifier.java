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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives and freezes one semantic schema overlay for a unified corpus snapshot.
 *
 * <p>Configured seeds and types emitted by deterministic extractors establish the initial ontology.
 * The configured small model then reads every corpus passage in bounded batches and may add types
 * supported by the text. Each batch sees the accumulated ontology, and the final merged schema is
 * frozen before extraction begins.</p>
 */
@Component
final class CorpusSchemaUnifier {

    static final String SCHEMA_TOOL_NAME = "submit_corpus_schema";
    private static final String TASK_TYPE = "llm";
    private static final int MAX_SCHEMA_NAME_CHARS = 48;
    private static final String SCHEMA_NAME_PATTERN = "^[A-Z][A-Z0-9_]*$";
    private static final int MAX_MODEL_PASSAGES_PER_CALL = 8;
    private static final int MAX_MODEL_PASSAGE_CHARS = 1_024;
    private static final int MAX_MODEL_TEXT_CHARS_PER_CALL = 6_144;
    private static final Logger log = LoggerFactory.getLogger(CorpusSchemaUnifier.class);
    private static final Map<String, Object> SCHEMA_TOOL_PARAMETERS = schemaToolParameters();

    GraphSchema unify(
            Map<String, String> passageTexts,
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher) {
        return unify(passageTexts, inventory, configuredSchema, null, job,
                corpusSnapshotId, dispatcher);
    }

    GraphSchema unify(
            Map<String, String> passageTexts,
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema,
            GraphSchema deterministicGraphSchema,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher) {
        GraphSchema deterministicConceptSchema =
                DeterministicCorpusSchemaInferencer.infer(inventory, configuredSchema);
        GraphSchema establishedSchema = CrawlOntology.merge(configuredSchema, deterministicGraphSchema);
        establishedSchema = CrawlOntology.merge(establishedSchema, deterministicConceptSchema);
        if (passageTexts == null || passageTexts.isEmpty()
                || dispatcher == null || job == null) {
            return establishedSchema;
        }

        boolean structured = dispatcher.hasStructuredChatBackend();
        if (!structured && !dispatcher.hasLlmChat()) {
            return establishedSchema;
        }

        CrawlOntology ontology = new CrawlOntology(establishedSchema);
        List<String> failures = new ArrayList<>();
        List<Map<String, String>> batches = modelPassageBatches(passageTexts);
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            try {
                String prompt = CorpusSchemaPromptBuilder.build(
                        batches.get(batchIndex), ontology.snapshot(), structured);
                CrawlLlmDispatcher.LlmCallScope scope =
                        schemaScope(job, corpusSnapshotId, batchIndex + 1);
                CorpusSchemaResponseParser.ParseResult parsed = structured
                        ? parseStructured(dispatcher.promptStructuredWithCapacityFallback(
                                structuredRequest(prompt), TASK_TYPE, job, scope))
                        : CorpusSchemaResponseParser.parse(
                                dispatcher.promptWithCapacityFallback(prompt, TASK_TYPE, job, scope));
                if (!parsed.valid()) {
                    throw new IllegalStateException(String.join("; ", parsed.errors()));
                }

                CrawlOntology.UpdateResult update = ontology.update(parsed.schema());
                if (!update.valid()) {
                    throw new IllegalStateException(String.join("; ", update.errors()));
                }
            } catch (RuntimeException modelFailure) {
                failures.add("batch " + (batchIndex + 1) + ": " + conciseMessage(modelFailure));
                log.warn(
                        "[Job {}] Semantic corpus schema induction failed for snapshot {} batch {}/{}; "
                                + "the ontology pre-pass will fail after remaining batch diagnostics: {}",
                        job.getJobId(), corpusSnapshotId, batchIndex + 1, batches.size(),
                        conciseMessage(modelFailure));
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Semantic corpus schema induction failed for snapshot "
                            + corpusSnapshotId + ": " + String.join("; ", failures));
        }
        GraphSchema unified = ontology.snapshot();
        if (hasSchemaContent(unified)) {
            return unified;
        }
        // An empty overlay is a valid semantic result: the corpus batch may support no reusable
        // additions beyond the established ontology. Preserve the pre-existing null=no-schema
        // contract instead of turning that answer into a crawl error.
        return null;
    }

    private static StructuredChatLanguageModel.Request structuredRequest(String prompt) {
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message(
                                "system",
                                "Read the corpus passages, infer their reusable graph ontology, "
                                        + "and call the required tool exactly once without prose."),
                        new StructuredChatLanguageModel.Message("user", prompt)),
                List.of(new StructuredChatLanguageModel.Tool(
                        SCHEMA_TOOL_NAME,
                        "Submit missing reusable graph types only. nodeTypes and relationshipTypes contain "
                                + "plain UPPER_SNAKE_CASE label strings only; never put JSON or descriptions "
                                + "inside either label array. Put sourceType, relationshipType, and targetType "
                                + "objects only in patterns. Never use a source instance as a schema name.",
                        SCHEMA_TOOL_PARAMETERS)),
                true,
                StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                StructuredChatLanguageModel.ToolCallFormat.MODEL,
                StructuredChatLanguageModel.ToolChoice.REQUIRED);
    }

    private static CorpusSchemaResponseParser.ParseResult parseStructured(
            StructuredChatLanguageModel.Response response) {
        if (response == null) {
            return new CorpusSchemaResponseParser.ParseResult(
                    null, List.of("[SCHEMA_RESPONSE] Structured model response was null"));
        }
        for (StructuredChatLanguageModel.ToolCall call : response.toolCalls()) {
            if (call != null && SCHEMA_TOOL_NAME.equals(call.name())) {
                return CorpusSchemaResponseParser.parse(call.arguments());
            }
        }

        String raw = hasText(response.content()) ? response.content() : response.rawText();
        CorpusSchemaResponseParser.ParseResult parsed = CorpusSchemaResponseParser.parse(raw);
        if (parsed.valid() || response.parseErrors().isEmpty()) {
            return parsed;
        }

        List<String> errors = new java.util.ArrayList<>(parsed.errors());
        response.parseErrors().stream()
                .filter(CorpusSchemaUnifier::hasText)
                .map(error -> "[SCHEMA_TOOL_CALL] " + error)
                .forEach(errors::add);
        return new CorpusSchemaResponseParser.ParseResult(null, errors);
    }

    private static CrawlLlmDispatcher.LlmCallScope schemaScope(
            UnifiedCrawlJob job, String corpusSnapshotId, int batchIndex) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS",
                "corpus-schema-" + batchIndex,
                batchIndex,
                jobId + ":corpus-schema:" + batchIndex,
                null,
                null,
                corpusSnapshotId,
                null,
                0,
                0);
    }

    static List<Map<String, String>> modelPassageBatches(Map<String, String> passageTexts) {
        if (passageTexts == null || passageTexts.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> batches = new ArrayList<>();
        LinkedHashMap<String, String> current = new LinkedHashMap<>();
        int currentChars = 0;
        for (Map.Entry<String, String> passage : passageTexts.entrySet()) {
            if (!hasText(passage.getKey()) || !hasText(passage.getValue())) {
                continue;
            }
            String text = passage.getValue();
            int fragment = 0;
            for (int offset = 0; offset < text.length(); offset += MAX_MODEL_PASSAGE_CHARS) {
                int end = Math.min(text.length(), offset + MAX_MODEL_PASSAGE_CHARS);
                String value = text.substring(offset, end);
                if (!current.isEmpty()
                        && (current.size() >= MAX_MODEL_PASSAGES_PER_CALL
                        || currentChars + value.length() > MAX_MODEL_TEXT_CHARS_PER_CALL)) {
                    batches.add(java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(current)));
                    current.clear();
                    currentChars = 0;
                }
                String key = passage.getKey() + "#schema-" + (++fragment);
                current.put(key, value);
                currentChars += value.length();
            }
        }
        if (!current.isEmpty()) {
            batches.add(java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(current)));
        }
        return List.copyOf(batches);
    }

    private static Map<String, Object> schemaToolParameters() {
        Map<String, Object> nodeType = schemaNameSchema(
                "One reusable entity category in UPPER_SNAKE_CASE; never an instance name.");
        Map<String, Object> relationshipType = schemaNameSchema(
                "One reusable directed relation category in UPPER_SNAKE_CASE.");

        Map<String, Object> endpointPattern = objectSchema(
                Map.of(
                        "sourceType", schemaNameSchema(
                                "Source node label declared in nodeTypes or the existing schema."),
                        "relationshipType", schemaNameSchema(
                                "Relationship label declared in relationshipTypes."),
                        "targetType", schemaNameSchema(
                                "Target node label declared in nodeTypes or the existing schema.")),
                List.of("sourceType", "relationshipType", "targetType"));

        return objectSchema(
                Map.of(
                        "nodeTypes", uniqueTypeArraySchema(nodeType),
                        "relationshipTypes", uniqueTypeArraySchema(relationshipType),
                        "patterns",
                                Map.of("type", "array", "items", endpointPattern)),
                List.of("nodeTypes", "relationshipTypes", "patterns"));
    }

    private static Map<String, Object> uniqueTypeArraySchema(Map<String, Object> items) {
        return Map.of(
                "type", "array",
                "items", items,
                "uniqueItems", true,
                "maxItems", 32);
    }

    private static Map<String, Object> schemaNameSchema(String description) {
        return Map.of(
                "type", "string",
                "pattern", SCHEMA_NAME_PATTERN,
                "maxLength", MAX_SCHEMA_NAME_CHARS,
                "description", description);
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static boolean hasSchemaContent(GraphSchema schema) {
        return schema != null
                && ((schema.getNodeTypes() != null && !schema.getNodeTypes().isEmpty())
                || (schema.getRelationshipTypes() != null
                        && !schema.getRelationshipTypes().isEmpty())
                || (schema.getPatterns() != null && !schema.getPatterns().isEmpty()));
    }

    private static String conciseMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (!hasText(message)) {
            return failure == null ? "unknown failure" : failure.getClass().getSimpleName();
        }
        String compact = message.replace('\n', ' ').trim();
        return compact.length() > 500 ? compact.substring(0, 500) : compact;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
