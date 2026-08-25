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
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives and freezes one semantic schema overlay for a unified corpus snapshot.
 *
 * <p>Configured seeds and types emitted by deterministic extractors establish the initial ontology.
 * The configured small model reads every corpus passage in bounded batches and proposes types
 * supported by the text. Batch outputs remain untrusted until one corpus-wide consolidation call
 * selects a coherent vocabulary. Node types are committed once, then relationship types follow the
 * same proposal/consolidation lifecycle against the frozen node vocabulary.</p>
 */
@Component
final class CorpusSchemaUnifier {

    static final String NODE_TYPE_TOOL_NAME = "submit_node_types";
    static final String RELATIONSHIP_TYPE_TOOL_NAME = "submit_relationship_types";
    private static final String TASK_TYPE = "llm";
    private static final int MAX_SCHEMA_NAME_CHARS = 48;
    private static final String SCHEMA_NAME_PATTERN = "^[A-Z][A-Z0-9_]*$";
    private static final int MAX_MODEL_PASSAGES_PER_CALL = 8;
    private static final int MAX_MODEL_PASSAGE_CHARS = 1_024;
    private static final int MIN_MODEL_PASSAGE_BOUNDARY_CHARS = 512;
    private static final int MAX_MODEL_TEXT_CHARS_PER_CALL = 6_144;
    private static final int MAX_CONSOLIDATED_TYPES = 12;
    private static final int MAX_VALIDATION_FEEDBACK_CHARS = 1_000;
    private static final Logger log = LoggerFactory.getLogger(CorpusSchemaUnifier.class);
    private static final Map<String, Object> NODE_TYPE_TOOL_PARAMETERS = typeToolParameters(
            "nodeTypes", "One reusable node category in UPPER_SNAKE_CASE; never an instance name or value.");
    private static final Map<String, Object> RELATIONSHIP_TYPE_TOOL_PARAMETERS = typeToolParameters(
            "relationshipTypes", "One reusable directed relationship category in UPPER_SNAKE_CASE; never a relation instance or triple.");

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
        return unify(passageTexts, inventory, configuredSchema, deterministicGraphSchema,
                CorpusTopicEvidence.empty(), job, corpusSnapshotId, dispatcher);
    }

    GraphSchema unify(
            Map<String, String> passageTexts,
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema,
            GraphSchema deterministicGraphSchema,
            CorpusTopicEvidence topicEvidence,
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
        List<Map<String, String>> batches = modelPassageBatches(passageTexts);
        int maxValidationRetries = maxValidationRetries(job);

        runTypePass(
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES,
                batches, ontology, topicEvidence, job, corpusSnapshotId,
                dispatcher, maxValidationRetries);
        runTypePass(
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES,
                batches, ontology, topicEvidence, job, corpusSnapshotId,
                dispatcher, maxValidationRetries);

        // Preserve an explicit empty schema as a frozen result. Null means no authoritative schema
        // exists and would re-enable ontology mutation during entity extraction.
        return ontology.snapshot();
    }

    private static void runTypePass(
            CorpusSchemaPromptBuilder.TypePass pass,
            List<Map<String, String>> batches,
            CrawlOntology ontology,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        Map<String, Integer> proposalSupport = discoverTypeProposals(
                pass, batches, ontology, topicEvidence, job, corpusSnapshotId,
                dispatcher, maxValidationRetries);
        proposalSupport = evidenceGroundedProposals(
                proposalSupport, batches, topicEvidence);
        if (proposalSupport.isEmpty()) {
            return;
        }

        GraphSchema consolidated = consolidateTypeProposals(
                pass, proposalSupport, ontology, topicEvidence, job, corpusSnapshotId,
                dispatcher, maxValidationRetries);
        CrawlOntology.UpdateResult update = ontology.updateTypesOnly(consolidated);
        if (!update.valid()) {
            throw new IllegalStateException(
                    "Corpus " + passLabel(pass) + " consolidation could not be committed: "
                            + conciseValidationErrors(update.errors()));
        }
    }

    private static Map<String, Integer> discoverTypeProposals(
            CorpusSchemaPromptBuilder.TypePass pass,
            List<Map<String, String>> batches,
            CrawlOntology ontology,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        List<String> failures = new ArrayList<>();
        Map<String, Integer> proposalSupport = new LinkedHashMap<>();
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            try {
                String basePrompt = CorpusSchemaPromptBuilder.build(
                        batches.get(batchIndex), ontology.snapshot(), pass, topicEvidence);
                String validationErrors = null;
                boolean batchSucceeded = false;
                for (int attempt = 1; attempt <= maxValidationRetries + 1; attempt++) {
                    String prompt = attempt == 1
                            ? basePrompt
                            : schemaRepairPrompt(
                                    basePrompt, pass, validationErrors,
                                    attempt, maxValidationRetries + 1);
                    CrawlLlmDispatcher.LlmCallScope scope = schemaScope(
                            job, corpusSnapshotId, pass, batchIndex + 1, attempt);
                    CorpusSchemaResponseParser.ParseResult parsed = parseStructured(
                            dispatcher.promptStructuredWithCapacityFallback(
                                    structuredRequest(prompt, pass, false), TASK_TYPE, job, scope),
                            pass);
                    if (!parsed.valid()) {
                        validationErrors = conciseValidationErrors(parsed.errors());
                    } else {
                        List<String> errors = typeResponseErrors(
                                pass, parsed.schema(), ontology.snapshot(), false, Set.of());
                        if (errors.isEmpty()) {
                            addProposalSupport(
                                    pass, parsed.schema(), ontology.snapshot(), proposalSupport);
                            batchSucceeded = true;
                            break;
                        }
                        validationErrors = conciseValidationErrors(errors);
                    }

                    if (attempt <= maxValidationRetries) {
                        log.warn(
                                "[Job {}] Corpus {} validation failed for snapshot {} batch {}/{} "
                                        + "attempt {}/{}; retrying with validator feedback: {}",
                                job.getJobId(), passLabel(pass), corpusSnapshotId,
                                batchIndex + 1, batches.size(), attempt,
                                maxValidationRetries + 1, validationErrors);
                    }
                }
                if (!batchSucceeded) {
                    throw new IllegalStateException(validationErrors == null
                            ? "Corpus type validation failed without diagnostics"
                            : validationErrors);
                }
            } catch (RuntimeException modelFailure) {
                failures.add("batch " + (batchIndex + 1) + ": " + conciseMessage(modelFailure));
                log.warn(
                        "[Job {}] Corpus {} induction failed for snapshot {} batch {}/{}; "
                                + "the type pre-pass will fail after remaining batch diagnostics: {}",
                        job.getJobId(), passLabel(pass), corpusSnapshotId,
                        batchIndex + 1, batches.size(), conciseMessage(modelFailure));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Corpus " + passLabel(pass) + " induction failed for snapshot "
                            + corpusSnapshotId + ": " + String.join("; ", failures));
        }
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(proposalSupport));
    }

    private static GraphSchema consolidateTypeProposals(
            CorpusSchemaPromptBuilder.TypePass pass,
            Map<String, Integer> proposalSupport,
            CrawlOntology ontology,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        String basePrompt = CorpusSchemaPromptBuilder.buildConsolidation(
                ontology.snapshot(), pass, proposalSupport, topicEvidence);
        String validationErrors = null;
        for (int attempt = 1; attempt <= maxValidationRetries + 1; attempt++) {
            String prompt = attempt == 1
                    ? basePrompt
                    : schemaRepairPrompt(
                            basePrompt, pass, validationErrors,
                            attempt, maxValidationRetries + 1);
            CrawlLlmDispatcher.LlmCallScope scope = schemaConsolidationScope(
                    job, corpusSnapshotId, pass, attempt);
            CorpusSchemaResponseParser.ParseResult parsed = parseStructured(
                    dispatcher.promptStructuredWithCapacityFallback(
                            structuredRequest(prompt, pass, true), TASK_TYPE, job, scope),
                    pass);
            if (!parsed.valid()) {
                validationErrors = conciseValidationErrors(parsed.errors());
            } else {
                List<String> errors = typeResponseErrors(
                        pass, parsed.schema(), ontology.snapshot(), true,
                        proposalSupport.keySet());
                if (errors.isEmpty()) {
                    return parsed.schema();
                }
                validationErrors = conciseValidationErrors(errors);
            }

            if (attempt <= maxValidationRetries) {
                log.warn(
                        "[Job {}] Corpus {} consolidation failed for snapshot {} attempt {}/{}; "
                                + "retrying with validator feedback: {}",
                        job.getJobId(), passLabel(pass), corpusSnapshotId,
                        attempt, maxValidationRetries + 1, validationErrors);
            }
        }
        throw new IllegalStateException(
                "Corpus " + passLabel(pass) + " consolidation failed for snapshot "
                        + corpusSnapshotId + ": "
                        + (validationErrors == null
                                ? "Schema consolidation failed without diagnostics"
                                : validationErrors));
    }

    private static StructuredChatLanguageModel.Request structuredRequest(
            String prompt,
            CorpusSchemaPromptBuilder.TypePass pass,
            boolean consolidation) {
        String action = consolidation ? "Consolidate untrusted proposals into" : "Define";
        String systemPrompt = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? action + " reusable node schema types only. Do not extract entities or relations. "
                        + "Call the required tool exactly once without prose."
                : action + " reusable relationship schema types only. Do not extract entities or relations. "
                        + "Call the required tool exactly once without prose.";
        String toolName = toolName(pass);
        String description = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "Submit missing reusable node type labels only. Never submit entity instances, "
                        + "relationship types, relations, triples, ids, or endpoint patterns."
                : "Submit missing reusable relationship type labels only. Never submit relation "
                        + "instances, triples, entities, ids, source/target endpoints, or patterns.";
        Map<String, Object> parameters = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? NODE_TYPE_TOOL_PARAMETERS : RELATIONSHIP_TYPE_TOOL_PARAMETERS;
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message("system", systemPrompt),
                        new StructuredChatLanguageModel.Message("user", prompt)),
                List.of(new StructuredChatLanguageModel.Tool(
                        toolName, description, parameters)),
                true,
                StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                StructuredChatLanguageModel.ToolCallFormat.MODEL,
                StructuredChatLanguageModel.ToolChoice.REQUIRED);
    }

    private static CorpusSchemaResponseParser.ParseResult parseStructured(
            StructuredChatLanguageModel.Response response,
            CorpusSchemaPromptBuilder.TypePass pass) {
        if (response == null) {
            return new CorpusSchemaResponseParser.ParseResult(
                    null, List.of("[SCHEMA_RESPONSE] Structured model response was null"));
        }
        String expectedTool = toolName(pass);
        for (StructuredChatLanguageModel.ToolCall call : response.toolCalls()) {
            if (call != null && expectedTool.equals(call.name())) {
                return parseTypeArguments(call.arguments(), pass);
            }
        }
        List<String> errors = new java.util.ArrayList<>();
        List<String> returnedTools = response.toolCalls().stream()
                .filter(java.util.Objects::nonNull)
                .map(StructuredChatLanguageModel.ToolCall::name)
                .filter(CorpusSchemaUnifier::hasText)
                .toList();
        errors.add(returnedTools.isEmpty()
                ? "[SCHEMA_TOOL_CALL] Structured response did not call " + expectedTool
                : "[SCHEMA_TOOL_CALL] Structured response called the wrong tool(s): "
                        + returnedTools + "; expected " + expectedTool);
        response.parseErrors().stream()
                .filter(CorpusSchemaUnifier::hasText)
                .map(error -> "[SCHEMA_TOOL_CALL] " + error)
                .forEach(errors::add);
        return new CorpusSchemaResponseParser.ParseResult(null, errors);
    }

    private static CorpusSchemaResponseParser.ParseResult parseTypeArguments(
            Map<String, Object> arguments,
            CorpusSchemaPromptBuilder.TypePass pass) {
        String expectedField = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "nodeTypes" : "relationshipTypes";
        if (arguments == null || arguments.size() != 1
                || !arguments.containsKey(expectedField)) {
            return new CorpusSchemaResponseParser.ParseResult(
                    null,
                    List.of("[SCHEMA_TYPE_ONLY] " + toolName(pass)
                            + " arguments must contain only " + expectedField));
        }
        Object values = arguments.get(expectedField);
        if (!(values instanceof List<?> labels)) {
            return new CorpusSchemaResponseParser.ParseResult(
                    null,
                    List.of("[SCHEMA_TYPE_ONLY] " + expectedField
                            + " must be an array of plain label strings"));
        }
        for (Object label : labels) {
            if (!(label instanceof String)) {
                return new CorpusSchemaResponseParser.ParseResult(
                        null,
                        List.of("[SCHEMA_TYPE_ONLY] " + expectedField
                                + " entries must be plain label strings, never objects"));
            }
        }
        return CorpusSchemaResponseParser.parse(arguments);
    }

    private static CrawlLlmDispatcher.LlmCallScope schemaScope(
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CorpusSchemaPromptBuilder.TypePass pass,
            int batchIndex,
            int attempt) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        String passName = passId(pass);
        String taskId = jobId + ":corpus-schema:" + passName + ":" + batchIndex
                + (attempt > 1 ? ":attempt:" + attempt : "");
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS",
                passName + "-" + batchIndex,
                attempt,
                taskId,
                null,
                null,
                corpusSnapshotId,
                null,
                0,
                0);
    }

    private static CrawlLlmDispatcher.LlmCallScope schemaConsolidationScope(
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CorpusSchemaPromptBuilder.TypePass pass,
            int attempt) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        String passName = passId(pass) + "-consolidation";
        String taskId = jobId + ":corpus-schema:" + passId(pass) + ":consolidation"
                + (attempt > 1 ? ":attempt:" + attempt : "");
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS",
                passName,
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
            String basePrompt,
            CorpusSchemaPromptBuilder.TypePass pass,
            String validationErrors,
            int attempt,
            int totalAttempts) {
        String expectedTool = toolName(pass);
        return basePrompt
                + "\n\nTYPE-SCHEMA REPAIR REQUIRED (attempt " + attempt + " of " + totalAttempts + ")\n"
                + "The previous " + expectedTool + " call failed validation:\n"
                + (hasText(validationErrors) ? validationErrors : "Unknown type validation error")
                + "\nReturn one complete corrected " + expectedTool + " call. Do not return a patch, "
                + "instances, relations, triples, endpoints, or patterns.\n";
    }

    private static List<String> unexpectedPassContent(
            CorpusSchemaPromptBuilder.TypePass pass, GraphSchema schema) {
        List<String> errors = new ArrayList<>();
        if (schema == null) {
            return List.of("[SCHEMA_RESPONSE] Parsed type schema was null");
        }
        if (schema.getPatterns() != null && !schema.getPatterns().isEmpty()) {
            errors.add("[SCHEMA_TYPE_ONLY] Type discovery must not emit endpoint patterns");
        }
        if (pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                && schema.getRelationshipTypes() != null
                && !schema.getRelationshipTypes().isEmpty()) {
            errors.add("[SCHEMA_NODE_TYPES_ONLY] Node-type pass must not emit relationship types");
        }
        if (pass == CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES
                && schema.getNodeTypes() != null
                && !schema.getNodeTypes().isEmpty()) {
            errors.add("[SCHEMA_RELATIONSHIP_TYPES_ONLY] Relationship-type pass must not emit node types");
        }
        return List.copyOf(errors);
    }

    private static List<String> typeResponseErrors(
            CorpusSchemaPromptBuilder.TypePass pass,
            GraphSchema schema,
            GraphSchema establishedSchema,
            boolean consolidation,
            Set<String> allowedConsolidationLabels) {
        List<String> errors = new ArrayList<>(unexpectedPassContent(pass, schema));
        CorpusSchemaOverlayValidator.Result validation =
                CorpusSchemaOverlayValidator.validateTypesOnly(establishedSchema, schema);
        errors.addAll(validation.errors());
        if (!consolidation || schema == null) {
            return List.copyOf(errors);
        }

        List<String> labels = typeLabels(pass, schema);
        if (labels.size() > MAX_CONSOLIDATED_TYPES) {
            errors.add("[SCHEMA_TYPE_BUDGET] Consolidated " + passLabel(pass)
                    + " contains " + labels.size() + " labels; maximum is "
                    + MAX_CONSOLIDATED_TYPES);
        }

        Set<String> authoritativeSameType = typeNames(pass, establishedSchema);
        CorpusSchemaPromptBuilder.TypePass oppositePass =
                pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                        ? CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES
                        : CorpusSchemaPromptBuilder.TypePass.NODE_TYPES;
        Set<String> authoritativeOppositeTypes = typeNames(oppositePass, establishedSchema);
        for (String label : labels) {
            String canonical = canonicalName(label);
            if (allowedConsolidationLabels == null
                    || !allowedConsolidationLabels.contains(canonical)) {
                errors.add("[SCHEMA_UNPROPOSED_TYPE] Consolidation returned a label that no corpus batch proposed: "
                        + label);
            }
            if (authoritativeSameType.contains(canonical)) {
                errors.add("[SCHEMA_AUTHORITATIVE_TYPE] Consolidation repeated an existing "
                        + passLabel(pass) + ": " + label);
            }
            if (authoritativeOppositeTypes.contains(canonical)) {
                String oppositeLabel = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                        ? "relationship" : "node";
                errors.add("[SCHEMA_TYPE_CATEGORY] " + passLabel(pass)
                        + " duplicates a frozen " + oppositeLabel + " type: " + label);
            }
        }
        return List.copyOf(errors);
    }

    private static void addProposalSupport(
            CorpusSchemaPromptBuilder.TypePass pass,
            GraphSchema proposal,
            GraphSchema establishedSchema,
            Map<String, Integer> proposalSupport) {
        Set<String> authoritative = typeNames(pass, establishedSchema);
        for (String label : typeLabels(pass, proposal)) {
            String canonical = canonicalName(label);
            if (!authoritative.contains(canonical)) {
                proposalSupport.merge(canonical, 1, Integer::sum);
            }
        }
    }

    private static Map<String, Integer> evidenceGroundedProposals(
            Map<String, Integer> proposals,
            List<Map<String, String>> batches,
            CorpusTopicEvidence topicEvidence) {
        if (proposals == null || proposals.isEmpty()) {
            return proposals == null ? Map.of() : proposals;
        }
        List<Set<String>> passageTokens = batches.stream()
                .flatMap(batch -> batch.values().stream())
                .filter(CorpusSchemaUnifier::hasText)
                .map(CorpusSchemaUnifier::lexicalTokens)
                .toList();
        Set<String> topicTerms = (topicEvidence == null ? List.<CorpusTopicEvidence.Topic>of()
                : topicEvidence.topics()).stream()
                .flatMap(topic -> topic.termsByLanguage().values().stream())
                .flatMap(Collection::stream)
                .flatMap(value -> lexicalTokens(value).stream())
                .collect(Collectors.toSet());
        int requiredPassageSupport = passageTokens.size() >= 4 ? 2 : 1;
        Map<String, Integer> grounded = new LinkedHashMap<>();
        proposals.forEach((label, batchSupport) -> {
            List<String> tokens = Arrays.stream(label.toLowerCase(Locale.ROOT).split("_"))
                    .filter(token -> token.length() >= 3)
                    .toList();
            long passageSupport = passageTokens.stream()
                    .filter(passage -> tokens.stream().anyMatch(passage::contains))
                    .count();
            boolean topicSupport = tokens.stream().anyMatch(topicTerms::contains);
            if (batchSupport >= 2 || passageSupport >= requiredPassageSupport
                    || (passageSupport >= 1 && topicSupport)) {
                grounded.put(label, batchSupport);
            } else {
                log.warn("Dropping ungrounded corpus schema proposal '{}': batchSupport={}, passageSupport={}",
                        label, batchSupport, passageSupport);
            }
        });
        return Collections.unmodifiableMap(grounded);
    }

    private static Set<String> lexicalTokens(String value) {
        if (!hasText(value)) return Set.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 3)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> typeLabels(
            CorpusSchemaPromptBuilder.TypePass pass, GraphSchema schema) {
        if (schema == null) {
            return List.of();
        }
        if (pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES) {
            if (schema.getNodeTypes() == null) {
                return List.of();
            }
            return schema.getNodeTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(node -> node.getLabel())
                    .filter(CorpusSchemaUnifier::hasText)
                    .map(String::trim)
                    .toList();
        }
        if (schema.getRelationshipTypes() == null) {
            return List.of();
        }
        return schema.getRelationshipTypes().stream()
                .filter(java.util.Objects::nonNull)
                .map(relationship -> relationship.getType())
                .filter(CorpusSchemaUnifier::hasText)
                .map(String::trim)
                .toList();
    }

    private static Set<String> typeNames(
            CorpusSchemaPromptBuilder.TypePass pass, GraphSchema schema) {
        Set<String> names = new LinkedHashSet<>();
        for (String label : typeLabels(pass, schema)) {
            names.add(canonicalName(label));
        }
        return names;
    }

    private static String canonicalName(String label) {
        return label.trim().toUpperCase(Locale.ROOT);
    }

    private static String toolName(CorpusSchemaPromptBuilder.TypePass pass) {
        return pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? NODE_TYPE_TOOL_NAME : RELATIONSHIP_TYPE_TOOL_NAME;
    }

    private static String passId(CorpusSchemaPromptBuilder.TypePass pass) {
        return pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "node-types" : "relationship-types";
    }

    private static String passLabel(CorpusSchemaPromptBuilder.TypePass pass) {
        return pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "node-type schema" : "relationship-type schema";
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

    private static Map<String, Object> typeToolParameters(
            String fieldName, String description) {
        return objectSchema(
                Map.of(fieldName, uniqueTypeArraySchema(
                        schemaNameSchema(description))),
                List.of(fieldName));
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
