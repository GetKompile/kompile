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
    private static final int MIN_MODEL_PASSAGE_BOUNDARY_CHARS = 512;
    private static final int MAX_MODEL_TEXT_CHARS_PER_CALL = 6_144;
    private static final int MAX_VALIDATION_FEEDBACK_CHARS = 1_000;
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

        if (!dispatcher.hasStructuredChatBackend()) {
            throw new IllegalStateException(
                    "Semantic corpus schema induction requires structured-chat tool support");
        }

        CrawlOntology ontology = new CrawlOntology(establishedSchema);
        List<String> failures = new ArrayList<>();
        List<Map<String, String>> batches = modelPassageBatches(passageTexts);
        int maxValidationRetries = maxValidationRetries(job);
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            try {
                String basePrompt = CorpusSchemaPromptBuilder.build(
                        batches.get(batchIndex), ontology.snapshot(), true);
                String validationErrors = null;
                boolean batchSucceeded = false;
                for (int attempt = 1; attempt <= maxValidationRetries + 1; attempt++) {
                    String prompt = attempt == 1
                            ? basePrompt
                            : schemaRepairPrompt(
                                    basePrompt, validationErrors, attempt, maxValidationRetries + 1);
                    CrawlLlmDispatcher.LlmCallScope scope = schemaScope(
                            job, corpusSnapshotId, batchIndex + 1, attempt);
                    CorpusSchemaResponseParser.ParseResult parsed = parseStructured(
                            dispatcher.promptStructuredWithCapacityFallback(
                                    structuredRequest(prompt), TASK_TYPE, job, scope));
                    if (!parsed.valid()) {
                        validationErrors = conciseValidationErrors(parsed.errors());
                    } else {
                        CrawlOntology.UpdateResult update = ontology.update(parsed.schema());
                        if (update.valid()) {
                            batchSucceeded = true;
                            break;
                        }
                        validationErrors = conciseValidationErrors(update.errors());
                    }

                    if (attempt <= maxValidationRetries) {
                        log.warn(
                                "[Job {}] Corpus schema validation failed for snapshot {} batch {}/{} "
                                        + "attempt {}/{}; retrying with validator feedback: {}",
                                job.getJobId(), corpusSnapshotId, batchIndex + 1, batches.size(),
                                attempt, maxValidationRetries + 1, validationErrors);
                    }
                }
                if (!batchSucceeded) {
                    throw new IllegalStateException(validationErrors == null
                            ? "Corpus schema validation failed without diagnostics"
                            : validationErrors);
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
        // Preserve an explicit empty schema as a frozen result. Null means no authoritative schema
        // exists and would re-enable ontology mutation during entity extraction.
        return ontology.snapshot();
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
        List<String> errors = new java.util.ArrayList<>();
        List<String> returnedTools = response.toolCalls().stream()
                .filter(java.util.Objects::nonNull)
                .map(StructuredChatLanguageModel.ToolCall::name)
                .filter(CorpusSchemaUnifier::hasText)
                .toList();
        errors.add(returnedTools.isEmpty()
                ? "[SCHEMA_TOOL_CALL] Structured response did not call submit_corpus_schema"
                : "[SCHEMA_TOOL_CALL] Structured response called the wrong tool(s): "
                        + returnedTools);
        response.parseErrors().stream()
                .filter(CorpusSchemaUnifier::hasText)
                .map(error -> "[SCHEMA_TOOL_CALL] " + error)
                .forEach(errors::add);
        return new CorpusSchemaResponseParser.ParseResult(null, errors);
    }

    private static CrawlLlmDispatcher.LlmCallScope schemaScope(
            UnifiedCrawlJob job, String corpusSnapshotId, int batchIndex, int attempt) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        String taskId = jobId + ":corpus-schema:" + batchIndex
                + (attempt > 1 ? ":attempt:" + attempt : "");
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS",
                "corpus-schema-" + batchIndex,
                attempt,
                taskId,
                null,
                null,
                corpusSnapshotId,
                null,
                0,
                0);
    }

    private static int maxValidationRetries(UnifiedCrawlJob job) {
        return job == null || job.getRequest() == null
                ? 0
                : Math.max(0, job.getRequest().getMaxValidationRetries());
    }

    private static String schemaRepairPrompt(
            String basePrompt, String validationErrors, int attempt, int totalAttempts) {
        return basePrompt
                + "\n\nSCHEMA REPAIR REQUIRED (attempt " + attempt + " of " + totalAttempts + ")\n"
                + "The previous submit_corpus_schema call failed validation:\n"
                + (hasText(validationErrors) ? validationErrors : "Unknown schema validation error")
                + "\nReturn one complete corrected submit_corpus_schema call. Do not return a patch. "
                + "Every pattern endpoint must appear in nodeTypes or the existing schema, and every "
                + "pattern relationship must appear in relationshipTypes or the existing schema.\n";
    }

    private static String conciseValidationErrors(List<String> errors) {
        String feedback = errors == null ? "" : String.join("; ", errors);
        String compact = feedback.replace('\n', ' ').trim();
        if (compact.isEmpty()) {
            return "Schema validation failed without diagnostics";
        }
        return compact.length() > MAX_VALIDATION_FEEDBACK_CHARS
                ? compact.substring(0, MAX_VALIDATION_FEEDBACK_CHARS)
                : compact;
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
            int offset = 0;
            while (offset < text.length()) {
                int end = semanticFragmentEnd(text, offset);
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
                offset = end;
            }
        }
        if (!current.isEmpty()) {
            batches.add(java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(current)));
        }
        return List.copyOf(batches);
    }

    private static int semanticFragmentEnd(String text, int offset) {
        int hardEnd = Math.min(text.length(), offset + MAX_MODEL_PASSAGE_CHARS);
        if (hardEnd >= text.length()) {
            return text.length();
        }

        int minimumBoundary = Math.min(hardEnd, offset + MIN_MODEL_PASSAGE_BOUNDARY_CHARS);
        for (int index = hardEnd - 1; index >= minimumBoundary; index--) {
            char current = text.charAt(index);
            if (current == '\n' || current == '.' || current == '!' || current == '?'
                    || current == ';' || current == ':') {
                return index + 1;
            }
        }

        int end = hardEnd;
        if (Character.isHighSurrogate(text.charAt(end - 1))
                && end < text.length()
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end;
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
