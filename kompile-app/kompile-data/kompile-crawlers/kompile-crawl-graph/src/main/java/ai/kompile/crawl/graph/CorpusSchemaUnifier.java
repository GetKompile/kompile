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
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * supported by the text. Topic evidence is first bound by the model to existing hierarchy parents
 * and typed relationship signatures. Remaining node proposals stay untrusted until one corpus-wide
 * consolidation call selects a coherent vocabulary; newly discovered relationships without a
 * signature receive one bounded endpoint-signature pass before the schema is frozen.</p>
 */
@Component
final class CorpusSchemaUnifier {

    static final String NODE_TYPE_TOOL_NAME = "submit_node_types";
    static final String RELATIONSHIP_TYPE_TOOL_NAME = "submit_relationship_types";
    static final String TOPIC_BINDING_TOOL_NAME = "bind_topics_to_schema";
    static final String ENDPOINT_SIGNATURE_TOOL_NAME = "bind_relationship_signatures";
    private static final String TASK_TYPE = "llm";
    private static final int MAX_SCHEMA_NAME_CHARS = 48;
    private static final String SCHEMA_NAME_PATTERN = "^[A-Z][A-Z0-9_]*$";
    private static final int MAX_MODEL_PASSAGES_PER_CALL = 8;
    private static final int MAX_MODEL_PASSAGE_CHARS = 1_024;
    private static final int MIN_MODEL_PASSAGE_BOUNDARY_CHARS = 512;
    private static final int MAX_MODEL_TEXT_CHARS_PER_CALL = 6_144;
    private static final int MAX_CONSOLIDATED_TYPES = 12;
    // Keep each constrained response small enough for slow local decoders. Every topic still receives
    // its own required LLM binding call, so coverage is complete rather than truncated.
    private static final int MAX_TOPICS_PER_BINDING_CALL = 1;
    private static final int MAX_TOPIC_PARENT_TYPES = 40;
    private static final int MAX_TOPIC_CANDIDATE_LABELS = 24;
    private static final int MAX_TOPIC_NODE_OPTIONS = 40;
    private static final int MAX_TOPIC_RELATIONSHIP_OPTIONS = 24;
    private static final int MAX_TOPIC_ENDPOINT_OPTIONS = 40;
    private static final int MAX_TOPIC_NODE_TYPES_PER_TOPIC = 2;
    private static final int MAX_TOPIC_RELATIONSHIPS_PER_TOPIC = 1;
    private static final int MAX_ENDPOINT_SIGNATURES = 4;
    private static final int MAX_ENDPOINT_OPTION_IDS = 40;
    private static final int MAX_ENDPOINT_EVIDENCE_OPTIONS = 24;
    private static final int MAX_ENDPOINT_SIGNATURE_BATCHES = 512;
    private static final int MAX_VALIDATION_FEEDBACK_CHARS = 1_000;
    private static final Set<String> TOPIC_BINDING_FIELDS = Set.of("b");
    private static final Set<String> STOPWORD_SCHEMA_LABELS = Set.of(
            "THE", "AND", "FOR", "WITH", "FROM", "INTO", "OVER", "UNDER",
            "THIS", "THAT", "THESE", "THOSE", "WHICH", "WHAT", "WHEN", "WHERE",
            "BETWEEN", "THROUGH", "ABOUT", "AFTER", "BEFORE", "NOT");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(CorpusSchemaUnifier.class);
    private static final Map<String, Object> RELATIONSHIP_TYPE_TOOL_PARAMETERS = classifiedTypeToolParameters(
            "relationshipTypes", "type", "connectionFamily",
            SchemaHierarchyVocabulary.CONNECTION_FAMILIES,
            "One reusable specific directed predicate in UPPER_SNAKE_CASE; never a family name, relation instance, or triple.");

    record TypeProposal(String label, String classification) {
        TypeProposal {
            label = canonicalName(label);
            classification = canonicalName(classification);
        }
    }

    record UnificationResult(GraphSchema schema, CorpusTopicEvidence topicEvidence) {}

    private record TopicBindingParseResult(
            GraphSchema overlay,
            List<CorpusTopicEvidence.TopicBinding> bindings,
            List<String> errors) {
        boolean valid() {
            return overlay != null && errors != null && errors.isEmpty();
        }
    }

    private record TopicBindingResult(
            CorpusTopicEvidence evidence,
            List<String> failures) {
        TopicBindingResult {
            evidence = evidence == null ? CorpusTopicEvidence.empty() : evidence;
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    private record TopicBindingOptions(
            Map<String, String> nodes,
            Map<String, String> parents,
            Map<String, String> relationships,
            Map<String, String> families,
            Map<String, String> endpoints,
            Map<String, String> evidence) {
        Map<String, Object> promptView() {
            return Map.of(
                    "nodeIds", List.copyOf(nodes.values()),
                    "parentIds", List.copyOf(parents.values()),
                    "relationshipIds", List.copyOf(relationships.values()),
                    "familyIds", List.copyOf(families.values()),
                    "endpointIds", List.copyOf(endpoints.values()),
                    "evidenceIds", List.copyOf(evidence.values()));
        }
    }

    private record EndpointSignatureOptions(
            Map<String, String> relationships,
            Map<String, String> endpoints,
            Map<String, String> evidence) {
        Map<String, List<String>> promptView() {
            return Map.of(
                    "relationshipIds", List.copyOf(relationships.values()),
                    "endpointIds", List.copyOf(endpoints.values()),
                    "evidenceIds", List.copyOf(evidence.values()));
        }
    }

    private record EndpointSignatureParseResult(
            List<String> patterns,
            List<String> errors) {
        boolean valid() {
            return errors != null && errors.isEmpty();
        }
    }

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
        return unifyWithTopicBindings(
                passageTexts, inventory, configuredSchema, deterministicGraphSchema,
                topicEvidence, job, corpusSnapshotId, dispatcher).schema();
    }

    UnificationResult unifyWithTopicBindings(
            Map<String, String> passageTexts,
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema,
            GraphSchema deterministicGraphSchema,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher) {
        GraphSchema configuredWithBaseline =
                SchemaHierarchyVocabulary.withBaseline(configuredSchema);
        GraphSchema deterministicConceptSchema =
                DeterministicCorpusSchemaInferencer.infer(inventory, configuredWithBaseline);
        GraphSchema establishedSchema = CrawlOntology.merge(
                configuredWithBaseline, deterministicGraphSchema);
        establishedSchema = CrawlOntology.merge(establishedSchema, deterministicConceptSchema);
        if (passageTexts == null || passageTexts.isEmpty()
                || dispatcher == null || job == null) {
            return new UnificationResult(establishedSchema,
                    topicEvidence == null ? CorpusTopicEvidence.empty() : topicEvidence);
        }

        if (!dispatcher.hasStructuredChatBackend()) {
            throw new IllegalStateException(
                    "Semantic corpus schema induction requires structured-chat tool support");
        }

        CrawlOntology ontology = new CrawlOntology(establishedSchema);
        int maxValidationRetries = maxValidationRetries(job);
        TopicBindingResult topicBinding = bindTopicsToHierarchy(
                passageTexts,
                topicEvidence == null ? CorpusTopicEvidence.empty() : topicEvidence,
                ontology, job, corpusSnapshotId, dispatcher, maxValidationRetries);
        CorpusTopicEvidence boundTopicEvidence = topicBinding.evidence();
        boolean hasTopics = topicEvidence != null && !topicEvidence.isEmpty();
        boolean allTopicsBound = hasTopics
                && boundTopicEvidence.bindings().stream()
                .map(CorpusTopicEvidence.TopicBinding::topicId)
                .distinct().count() == topicEvidence.topics().size();
        List<Map<String, String>> signatureBatches = modelPassageBatches(passageTexts);
        if (!allTopicsBound) {
            // Topic binding is a precision improvement, not a reason to drop text. When only some
            // topics bind, run the existing type passes over the unbound topic passages. A fully bound
            // corpus skips these passes so the binder remains authoritative and no duplicate work runs.
            Map<String, String> fallbackPassages = hasTopics
                    ? passagesForUnboundTopics(passageTexts, topicEvidence, boundTopicEvidence,
                    topicBinding.failures())
                    : passageTexts;
            List<Map<String, String>> batches = modelPassageBatches(fallbackPassages);
            if (fallbackPassages.values().stream().noneMatch(CorpusSchemaUnifier::containsLatinLetter)) {
                batches = batches.stream()
                        .flatMap(batch -> batch.entrySet().stream())
                        .map(entry -> Map.of(entry.getKey(), entry.getValue()))
                        .toList();
            }
            signatureBatches = batches;
            runTypePass(
                    CorpusSchemaPromptBuilder.TypePass.NODE_TYPES,
                    batches, ontology, boundTopicEvidence, job, corpusSnapshotId,
                    dispatcher, maxValidationRetries);
            runTypePass(
                    CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES,
                    batches, ontology, boundTopicEvidence, job, corpusSnapshotId,
                    dispatcher, maxValidationRetries);
        }

        addMissingRelationshipSignatures(
                ontology, establishedSchema, signatureBatches, job,
                corpusSnapshotId, dispatcher, maxValidationRetries);

        // Preserve an explicit empty relationship vocabulary as closed. Null means unspecified/open;
        // an empty list means the prepass found no allowed predicates and later extraction must abstain.
        GraphSchema frozen = withoutUnboundDiscoveredRelationships(
                ontology.snapshot(), establishedSchema);
        if (frozen.getRelationshipTypes() == null) {
            frozen.setRelationshipTypes(List.of());
        }
        return new UnificationResult(frozen, boundTopicEvidence);
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
        Map<TypeProposal, Integer> proposalSupport = discoverTypeProposals(
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

    private static TopicBindingResult bindTopicsToHierarchy(
            Map<String, String> passageTexts,
            CorpusTopicEvidence topicEvidence,
            CrawlOntology ontology,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        if (topicEvidence == null || topicEvidence.isEmpty()) {
            return new TopicBindingResult(CorpusTopicEvidence.empty(), List.of());
        }
        List<CorpusTopicEvidence.TopicBinding> accepted = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int remainingNodeTypes = MAX_CONSOLIDATED_TYPES;
        int remainingRelationshipTypes = MAX_CONSOLIDATED_TYPES;
        for (int start = 0; start < topicEvidence.topics().size(); start += MAX_TOPICS_PER_BINDING_CALL) {
            int end = Math.min(topicEvidence.topics().size(), start + MAX_TOPICS_PER_BINDING_CALL);
            CorpusTopicEvidence batch = topicEvidence.topicBatch(start, end);
            GraphSchema promptSchema = ontology.snapshot();
            int nodeTypesPerTopic = perTopicBudget(
                    remainingNodeTypes, batch.topics().size(), MAX_TOPIC_NODE_TYPES_PER_TOPIC);
            int relationshipsPerTopic = perTopicBudget(
                    remainingRelationshipTypes, batch.topics().size(),
                    MAX_TOPIC_RELATIONSHIPS_PER_TOPIC);
            String basePrompt = CorpusSchemaPromptBuilder.buildTopicBinding(
                    batch, remainingNodeTypes, remainingRelationshipTypes);
            String validationErrors = null;
            boolean succeeded = false;
            for (int attempt = 1; attempt <= maxValidationRetries + 1; attempt++) {
                String prompt = attempt == 1 ? basePrompt : schemaRepairPrompt(
                        basePrompt, null, validationErrors, attempt, maxValidationRetries + 1);
                CrawlLlmDispatcher.LlmCallScope scope = topicBindingScope(
                        job, corpusSnapshotId, start / MAX_TOPICS_PER_BINDING_CALL + 1, attempt);
                GraphSchema established = ontology.snapshot();
                StructuredChatLanguageModel.Response response;
                try {
                    response = dispatcher.promptStructuredWithCapacityFallback(
                            topicBindingRequest(
                                    prompt, batch, promptSchema, passageTexts,
                                    nodeTypesPerTopic, relationshipsPerTopic),
                            TASK_TYPE, job, scope);
                } catch (RuntimeException structuredFailure) {
                    response = recoverCompletedPackedBinding(structuredFailure);
                    if (response == null) throw structuredFailure;
                    log.warn("Recovered a complete numeric topic binding after tool-wrapper "
                            + "constraint closure failed: {}", conciseMessage(structuredFailure));
                }
                TopicBindingParseResult parsed = parseTopicBindings(
                        response, batch, passageTexts, established,
                        remainingNodeTypes, remainingRelationshipTypes);
                if (!parsed.valid()) {
                    validationErrors = conciseValidationErrors(parsed.errors());
                    continue;
                }
                CrawlOntology.UpdateResult update = ontology.update(parsed.overlay());
                if (!update.valid()) {
                    validationErrors = conciseValidationErrors(update.errors());
                    continue;
                }
                accepted.addAll(parsed.bindings());
                remainingNodeTypes -= parsed.overlay().getNodeTypes() == null
                        ? 0 : parsed.overlay().getNodeTypes().size();
                remainingRelationshipTypes -= parsed.overlay().getRelationshipTypes() == null
                        ? 0 : parsed.overlay().getRelationshipTypes().size();
                succeeded = true;
                break;
            }
            if (!succeeded) {
                // A single unbindable topic must not abort the whole corpus pre-pass:
                // skip it and continue so the remaining topics can still bind.
                String topicId = batch.topics().stream()
                        .map(CorpusTopicEvidence.Topic::topicId).findFirst().orElse("unknown");
                failures.add(topicId + ": " + (validationErrors == null
                        ? "unknown topic binding failure" : validationErrors));
                log.warn("Skipping unbindable topic batch after {} attempts: {}",
                        maxValidationRetries + 1, validationErrors);
                continue;
            }
        }
        return new TopicBindingResult(topicEvidence.withBindings(accepted), failures);
    }

    private static Map<String, String> passagesForUnboundTopics(
            Map<String, String> passageTexts,
            CorpusTopicEvidence topicEvidence,
            CorpusTopicEvidence boundTopicEvidence,
            List<String> failures) {
        Set<String> boundTopicIds = boundTopicEvidence.bindings().stream()
                .map(CorpusTopicEvidence.TopicBinding::topicId)
                .collect(Collectors.toSet());
        List<CorpusTopicEvidence.Topic> unboundTopics = topicEvidence.topics().stream()
                .filter(topic -> !boundTopicIds.contains(topic.topicId()))
                .toList();
        if (unboundTopics.isEmpty()) return passageTexts;

        LinkedHashMap<String, String> selected = new LinkedHashMap<>();
        for (CorpusTopicEvidence.Topic topic : unboundTopics) {
            for (String chunkId : topic.memberChunkIds()) {
                if (!hasText(chunkId)) continue;
                passageTexts.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(chunkId)
                                || entry.getKey().startsWith(chunkId + "#"))
                        .forEach(entry -> selected.putIfAbsent(entry.getKey(), entry.getValue()));
            }
        }
        if (!failures.isEmpty()) {
            log.warn("Unbound corpus topic diagnostics: {}", failures);
        }
        if (selected.isEmpty()) {
            // Topic metadata can be stale after source normalization. Do not silently lose its text:
            // preserve coverage with the same full passage input used by the legacy pre-pass.
            log.warn("Unbound topic passages could not be mapped to source chunks; "
                    + "falling back to all corpus passages for schema diagnostics: {}",
                    unboundTopics.stream().map(CorpusTopicEvidence.Topic::topicId).toList());
            return passageTexts;
        }
        return Collections.unmodifiableMap(selected);
    }

    private static void addMissingRelationshipSignatures(
            CrawlOntology ontology,
            GraphSchema authoritativeSchema,
            List<Map<String, String>> batches,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        GraphSchema frozen = ontology.snapshot();
        Set<String> authoritativeRelations = typeNames(
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES, authoritativeSchema);
        List<String> relationCandidates = typeLabels(
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES, frozen).stream()
                .map(CorpusSchemaUnifier::canonicalName)
                .filter(label -> !authoritativeRelations.contains(label))
                .filter(label -> !hasPatternFor(frozen, label))
                .distinct()
                .sorted()
                .toList();
        if (relationCandidates.isEmpty()) return;

        List<String> endpointValues = typeLabels(
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES, frozen).stream()
                .map(CorpusSchemaUnifier::canonicalName)
                .distinct()
                .sorted()
                .toList();
        List<String> evidenceValues = endpointEvidenceValues(batches);
        if (endpointValues.isEmpty() || evidenceValues.isEmpty()) {
            log.warn("Abstaining newly discovered relationships without grounded endpoint evidence: {}",
                    relationCandidates);
            return;
        }

        List<EndpointSignatureOptions> optionBatches = endpointSignatureOptionBatches(
                relationCandidates, endpointValues, evidenceValues);
        if (optionBatches.size() > MAX_ENDPOINT_SIGNATURE_BATCHES) {
            throw new IllegalStateException("Endpoint signature batch budget exceeded: "
                    + optionBatches.size() + " > " + MAX_ENDPOINT_SIGNATURE_BATCHES
                    + " (predicates=" + relationCandidates.size()
                    + ", endpointTypes=" + endpointValues.size()
                    + ", evidence=" + evidenceValues.size() + ")");
        }

        Set<String> acceptedPatterns = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < optionBatches.size(); batchIndex++) {
            EndpointSignatureOptions options = optionBatches.get(batchIndex);
            String basePrompt = CorpusSchemaPromptBuilder.buildEndpointSignatureBinding(
                    options.promptView());
            String validationErrors = null;
            boolean batchSucceeded = false;
            for (int attempt = 1; attempt <= maxValidationRetries + 1; attempt++) {
                String prompt = attempt == 1 ? basePrompt : endpointSignatureRepairPrompt(
                        basePrompt, validationErrors, attempt, maxValidationRetries + 1);
                CrawlLlmDispatcher.LlmCallScope scope = endpointSignatureScope(
                        job, corpusSnapshotId, batchIndex + 1, attempt);
                EndpointSignatureParseResult parsed;
                try {
                    parsed = parseEndpointSignatures(
                            dispatcher.promptStructuredWithCapacityFallback(
                                    endpointSignatureRequest(prompt, options), TASK_TYPE, job, scope),
                            options, batches, frozen);
                } catch (RuntimeException failure) {
                    parsed = new EndpointSignatureParseResult(
                            List.of(), List.of("[SCHEMA_ENDPOINT_SIGNATURE] "
                                    + conciseMessage(failure)));
                }
                if (parsed.valid()) {
                    acceptedPatterns.addAll(parsed.patterns());
                    batchSucceeded = true;
                    break;
                }
                validationErrors = conciseValidationErrors(parsed.errors());
                if (attempt <= maxValidationRetries) {
                    log.warn("Relationship endpoint signature validation failed for snapshot {} batch {}/{} "
                                    + "attempt {}/{}: {}", corpusSnapshotId, batchIndex + 1,
                            optionBatches.size(), attempt, maxValidationRetries + 1, validationErrors);
                }
            }
            if (!batchSucceeded) {
                failures.add("batch " + (batchIndex + 1) + ": "
                        + (validationErrors == null ? "unknown endpoint signature failure"
                        : validationErrors));
            }
        }

        if (!acceptedPatterns.isEmpty()) {
            CrawlOntology.UpdateResult update = ontology.update(
                    new GraphSchema(null, null, List.copyOf(acceptedPatterns)));
            if (!update.valid()) {
                failures.add("ontology update: " + conciseValidationErrors(update.errors()));
            }
        }
        Set<String> covered = acceptedPatterns.stream()
                .map(CorpusSchemaUnifier::relationTypeFromPattern)
                .filter(CorpusSchemaUnifier::hasText)
                .collect(Collectors.toSet());
        List<String> abstained = relationCandidates.stream()
                .filter(candidate -> !covered.contains(candidate)).toList();
        if (!abstained.isEmpty()) {
            log.warn("Abstaining newly discovered relationships with no grounded endpoint signature: {}",
                    abstained);
        }
        if (!failures.isEmpty()) {
            log.warn("Endpoint signature batches failed for snapshot {}: {}",
                    corpusSnapshotId, String.join("; ", failures));
        }
    }

    private static List<EndpointSignatureOptions> endpointSignatureOptionBatches(
            List<String> relationshipValues,
            List<String> endpointValues,
            List<String> evidenceValues) {
        List<List<String>> endpointPages = pairCoveredEndpointPages(endpointValues);
        List<List<String>> evidencePages = boundedPages(evidenceValues, MAX_ENDPOINT_EVIDENCE_OPTIONS);
        int pagesPerPredicate = Math.max(endpointPages.size(), evidencePages.size());
        long expectedBatches = (long) relationshipValues.size() * pagesPerPredicate;
        if (expectedBatches > MAX_ENDPOINT_SIGNATURE_BATCHES) {
            throw new IllegalStateException("Endpoint signature batch budget exceeded: "
                    + expectedBatches + " > " + MAX_ENDPOINT_SIGNATURE_BATCHES
                    + " (predicates=" + relationshipValues.size()
                    + ", endpointTypes=" + endpointValues.size()
                    + ", evidence=" + evidenceValues.size() + ")");
        }
        List<EndpointSignatureOptions> result = new ArrayList<>((int) expectedBatches);
        for (String relationship : relationshipValues) {
            for (int page = 0; page < pagesPerPredicate; page++) {
                // A predicate gets one bounded table per page. When one dimension has fewer pages,
                // repeat its complete selection instead of taking a predicate/type/evidence
                // cross-product. Endpoint pages are pair-covered: every source/target combination
                // occurs in at least one table without exposing an unbounded endpoint cross-product.
                List<String> endpoints = endpointPages.get(
                        Math.min(page, endpointPages.size() - 1));
                List<String> evidence = evidencePages.get(
                        Math.min(page, evidencePages.size() - 1));
                result.add(new EndpointSignatureOptions(
                        indexedOptions(List.of(relationship)),
                        indexedOptions(endpoints),
                        indexedEvidenceOptions(evidence)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Splits large endpoint vocabularies into bounded pair-covering tables. A single table cannot
     * represent every source/target pair once the vocabulary exceeds the option budget, so partition
     * values into half-sized blocks and emit one table for each block pair. Every pair is then present
     * together in one table, while the hard batch budget still bounds the total work.
     */
    private static List<List<String>> pairCoveredEndpointPages(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() <= MAX_ENDPOINT_OPTION_IDS) return List.of(List.copyOf(values));

        int blockSize = Math.max(1, MAX_ENDPOINT_OPTION_IDS / 2);
        List<List<String>> blocks = boundedPages(values, blockSize);
        long pageCount = (long) blocks.size() * (blocks.size() - 1) / 2;
        if (pageCount > MAX_ENDPOINT_SIGNATURE_BATCHES) {
            throw new IllegalStateException("Endpoint signature pair-page budget exceeded: "
                    + pageCount + " > " + MAX_ENDPOINT_SIGNATURE_BATCHES
                    + " (endpointTypes=" + values.size() + ")");
        }
        List<List<String>> pages = new ArrayList<>((int) pageCount);
        for (int left = 0; left < blocks.size(); left++) {
            for (int right = left + 1; right < blocks.size(); right++) {
                List<String> page = new ArrayList<>(
                        blocks.get(left).size() + blocks.get(right).size());
                page.addAll(blocks.get(left));
                page.addAll(blocks.get(right));
                pages.add(List.copyOf(page));
            }
        }
        return List.copyOf(pages);
    }

    private static List<List<String>> boundedPages(
            List<String> values, int pageSize) {
        if (values == null || values.isEmpty()) return List.of();
        List<List<String>> pages = new ArrayList<>();
        for (int start = 0; start < values.size(); start += pageSize) {
            pages.add(List.copyOf(values.subList(start,
                    Math.min(values.size(), start + pageSize))));
        }
        return List.copyOf(pages);
    }

    /**
     * Native CHAT_MODEL returns validated JSON content rather than a provider tool-call envelope.
     * Keep the genuine tool-call path unchanged, and expose JSON arguments only after the strict
     * provider schema plus this object-shape conversion have succeeded.
     */
    private static Map<String, Object> responseArguments(
            StructuredChatLanguageModel.Response response, String expectedTool) {
        if (response == null) {
            throw new IllegalArgumentException("Structured model response was null");
        }
        List<StructuredChatLanguageModel.ToolCall> calls = response.toolCalls().stream()
                .filter(java.util.Objects::nonNull).toList();
        if (!calls.isEmpty()) {
            if (calls.size() != 1 || !expectedTool.equals(calls.get(0).name())) {
                throw new IllegalArgumentException("Structured response must call "
                        + expectedTool + " exactly once");
            }
            Map<String, Object> arguments = calls.get(0).arguments();
            if (arguments == null) {
                throw new IllegalArgumentException(expectedTool + " arguments were null");
            }
            return arguments;
        }
        if (response.content() == null || response.content().isBlank()) {
            throw new IllegalArgumentException("Structured response must call "
                    + expectedTool + " exactly once or return its validated JSON object");
        }
        try {
            Map<?, ?> parsed = OBJECT_MAPPER.readValue(response.content(), Map.class);
            Map<String, Object> arguments = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(expectedTool + " JSON arguments must use string keys");
                }
                arguments.put(key, entry.getValue());
            }
            return arguments;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(expectedTool
                    + " native JSON response was not a JSON object", invalid);
        }
    }

    private static EndpointSignatureParseResult parseEndpointSignatures(
            StructuredChatLanguageModel.Response response,
            EndpointSignatureOptions options,
            List<Map<String, String>> batches,
            GraphSchema frozen) {
        try {
            if (response == null) {
                throw new IllegalArgumentException("Structured endpoint signature response was null");
            }
            Map<String, Object> arguments = responseArguments(
                    response, ENDPOINT_SIGNATURE_TOOL_NAME);
            if (!arguments.keySet().equals(Set.of("s"))) {
                throw new IllegalArgumentException(ENDPOINT_SIGNATURE_TOOL_NAME
                        + " arguments must contain exactly [s]");
            }
            String packed = requiredText(arguments, "s");
            if ("0".equals(canonicalName(packed))) {
                return new EndpointSignatureParseResult(List.of(), List.of());
            }
            String[] signatures = packed.split(";", -1);
            if (signatures.length > MAX_ENDPOINT_SIGNATURES) {
                throw new IllegalArgumentException("At most " + MAX_ENDPOINT_SIGNATURES
                        + " endpoint signatures may be returned");
            }
            LinkedHashSet<String> patterns = new LinkedHashSet<>();
            for (String signature : signatures) {
                String[] fields = signature.split("\\|", -1);
                if (fields.length != 4 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException(
                            "Each endpoint signature must contain RELATIONSHIP|SOURCE|TARGET|EVIDENCE ids");
                }
                String relation = resolveBindingOption(
                        options.relationships(), fields[0], "relationship", "endpoint-signature");
                String source = resolveBindingOption(
                        options.endpoints(), fields[1], "source", "endpoint-signature");
                String target = resolveBindingOption(
                        options.endpoints(), fields[2], "target", "endpoint-signature");
                String evidence = resolveGroundingEvidence(
                        options.evidence(), canonicalName(fields[3]),
                        "relationship", "endpoint-signature");
                if (!evidenceGroundedInBatches(evidence, batches)) {
                    throw new IllegalArgumentException(
                            "Endpoint signature evidence is not grounded in corpus passages: " + evidence);
                }
                String pattern = "(" + source + ")-[:" + relation + "]->(" + target + ")";
                if (ai.kompile.core.graphrag.format.GraphExtractionValidator
                        .parseRelationPattern(pattern).isEmpty()) {
                    throw new IllegalArgumentException("Malformed endpoint signature: " + pattern);
                }
                patterns.add(pattern);
            }
            CorpusSchemaOverlayValidator.Result validation =
                    CorpusSchemaOverlayValidator.validate(
                            frozen, new GraphSchema(null, null, List.copyOf(patterns)));
            if (!validation.valid()) {
                throw new IllegalArgumentException(conciseValidationErrors(validation.errors()));
            }
            return new EndpointSignatureParseResult(List.copyOf(patterns), List.of());
        } catch (RuntimeException failure) {
            return new EndpointSignatureParseResult(
                    List.of(), List.of("[SCHEMA_ENDPOINT_SIGNATURE] " + failure.getMessage()));
        }
    }

    private static StructuredChatLanguageModel.Request endpointSignatureRequest(
            String prompt, EndpointSignatureOptions options) {
        String entry = packedRequiredOptionIds(options.relationships()) + "\\|"
                + packedRequiredOptionIds(options.endpoints()) + "\\|"
                + packedRequiredOptionIds(options.endpoints()) + "\\|"
                + packedRequiredOptionIds(options.evidence());
        int entryMaxLength = maxOptionIdLength(options.relationships()) + 1
                + maxOptionIdLength(options.endpoints()) + 1
                + maxOptionIdLength(options.endpoints()) + 1
                + maxOptionIdLength(options.evidence());
        Map<String, Object> packed = Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", MAX_ENDPOINT_SIGNATURES * entryMaxLength
                        + MAX_ENDPOINT_SIGNATURES - 1,
                "pattern", "^(?:0|" + entry + "(?:;" + entry + "){0,"
                        + (MAX_ENDPOINT_SIGNATURES - 1) + "})$",
                "description", "Semicolon-delimited RELATIONSHIP_ID|SOURCE_ID|TARGET_ID|EVIDENCE_ID; "
                        + "use only BINDING_OPTION_IDS_JSON ids, or 0 for no grounded signatures.");
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message("system",
                                "Bind existing relationship predicates to grounded directed endpoint signatures. "
                                        + "Call the tool once without prose."),
                        new StructuredChatLanguageModel.Message("user", prompt)),
                List.of(new StructuredChatLanguageModel.Tool(
                        ENDPOINT_SIGNATURE_TOOL_NAME,
                        "Submit zero or more grounded endpoint signatures for existing predicate ids. "
                                + "Never invent labels or relation families.",
                        objectSchema(Map.of("s", packed), List.of("s")))),
                true,
                StructuredChatLanguageModel.ToolDefinitionFormat.STANDARD,
                StructuredChatLanguageModel.ToolCallFormat.MODEL,
                StructuredChatLanguageModel.ToolChoice.REQUIRED);
    }

    private static CrawlLlmDispatcher.LlmCallScope endpointSignatureScope(
            UnifiedCrawlJob job, String corpusSnapshotId, int batchIndex, int attempt) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS", "relationship-signatures-" + batchIndex, attempt,
                jobId + ":corpus-schema:relationship-signatures:" + batchIndex
                        + (attempt > 1 ? ":attempt:" + attempt : ""),
                null, null, corpusSnapshotId, null, 0, 0);
    }

    private static String endpointSignatureRepairPrompt(
            String basePrompt, String validationErrors, int attempt, int totalAttempts) {
        return basePrompt + "\n\nENDPOINT-SIGNATURE REPAIR REQUIRED (attempt " + attempt
                + " of " + totalAttempts + ")\nThe previous " + ENDPOINT_SIGNATURE_TOOL_NAME
                + " call failed validation:\n"
                + (hasText(validationErrors) ? validationErrors : "Unknown endpoint signature validation error")
                + "\nReturn one complete corrected call. Use only numeric option ids, preserve direction, "
                + "and abstain with s=0 when evidence is not grounded.\n";
    }

    private static List<String> endpointEvidenceValues(List<Map<String, String>> batches) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (batches != null) {
            for (Map<String, String> batch : batches) {
                if (batch == null) continue;
                for (String value : batch.values()) {
                    if (hasText(value)) {
                        // modelPassageBatches already bounds each value to one semantic fragment.
                        // Keep that complete fragment: a bare prefix or keyword cannot establish
                        // endpoint direction/type semantics and makes lexical grounding overbroad.
                        values.add(value);
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    private static boolean evidenceGroundedInBatches(
            String evidence, List<Map<String, String>> batches) {
        Set<String> evidenceTokens = lexicalTokens(evidence);
        if (evidenceTokens.isEmpty() || batches == null) return false;
        return batches.stream().flatMap(batch -> batch.values().stream())
                .filter(CorpusSchemaUnifier::hasText)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT)
                        .contains(evidence.toLowerCase(Locale.ROOT))
                        || lexicalTokens(value).containsAll(evidenceTokens));
    }

    private static boolean hasPatternFor(GraphSchema schema, String relationType) {
        if (schema == null || schema.getPatterns() == null) return false;
        return schema.getPatterns().stream()
                .filter(CorpusSchemaUnifier::hasText)
                .map(value -> ai.kompile.core.graphrag.format.GraphExtractionValidator
                        .parseRelationPattern(value))
                .flatMap(java.util.Optional::stream)
                .anyMatch(signature -> relationType.equals(canonicalName(signature.relationType())));
    }

    private static String relationTypeFromPattern(String pattern) {
        if (!hasText(pattern)) return null;
        return ai.kompile.core.graphrag.format.GraphExtractionValidator
                .parseRelationPattern(pattern)
                .map(signature -> canonicalName(signature.relationType()))
                .orElse(null);
    }

    private static GraphSchema withoutUnboundDiscoveredRelationships(
            GraphSchema schema, GraphSchema authoritativeSchema) {
        if (schema == null || schema.getRelationshipTypes() == null) return schema;
        Set<String> authoritative = typeNames(
                CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES, authoritativeSchema);
        Set<String> patterned = schema.getPatterns() == null ? Set.of() : schema.getPatterns().stream()
                .map(CorpusSchemaUnifier::relationTypeFromPattern)
                .filter(CorpusSchemaUnifier::hasText)
                .collect(Collectors.toSet());
        List<RelationshipType> retained = schema.getRelationshipTypes().stream()
                .filter(java.util.Objects::nonNull)
                .filter(relationship -> authoritative.contains(canonicalName(relationship.getType()))
                        || patterned.contains(canonicalName(relationship.getType())))
                .toList();
        return new GraphSchema(schema.getNodeTypes(), retained, schema.getPatterns());
    }

    static StructuredChatLanguageModel.Response recoverCompletedPackedBinding(
            RuntimeException failure) {
        String message = failure == null ? null : failure.getMessage();
        if (!hasText(message)
                || !message.contains("Constraint rejected every candidate token")
                || !message.contains("<parameter=b>")) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "<parameter=b>(?:\\\\n|\\s)*\\\\?\\\"?([0-9]+(?:\\|[0-9]+){7})(?!\\|[0-9])")
                .matcher(message);
        if (!matcher.find()) return null;
        return new StructuredChatLanguageModel.Response(
                "<recovered-topic-binding>", "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "recovered-topic-binding", TOPIC_BINDING_TOOL_NAME,
                        Map.of("b", matcher.group(1)))),
                List.of());
    }

    private static StructuredChatLanguageModel.Request topicBindingRequest(
            String prompt,
            CorpusTopicEvidence batch,
            GraphSchema establishedSchema,
            Map<String, String> passageTexts,
            int nodeTypesPerTopic,
            int relationshipsPerTopic) {
        TopicBindingOptions options = topicBindingOptions(
                batch, establishedSchema, passageTexts);
        String disabledNode = nodeTypesPerTopic > 0 ? ""
                : " No new node definition budget remains; reference an existing node type or use NONE.";
        String disabledRelationship = relationshipsPerTopic > 0
                ? "" : " No new relationship definition budget remains; reference an existing predicate or use NONE.";
        // A concrete node binding (NODE|PARENT|EVIDENCE) must select a real parent id:
        // accepting PARENT=0 there lets the model pair concrete nodes with parentType NONE,
        // which the validator then rejects after every retry — aborting the corpus pre-pass.
        String nodeGroup = "(?:0\\|0\\|0|"
                + packedRequiredOptionIds(options.nodes()) + "\\|"
                + packedRequiredOptionIds(options.parents()) + "\\|"
                + packedRequiredOptionIds(options.evidence()) + ")";
        String relationshipGroup = "(?:0\\|0\\|0\\|0\\|0|"
                + packedRequiredOptionIds(options.relationships()) + "\\|"
                + packedRequiredOptionIds(options.families()) + "\\|"
                + packedRequiredOptionIds(options.endpoints()) + "\\|"
                + packedRequiredOptionIds(options.endpoints()) + "\\|"
                + packedRequiredOptionIds(options.evidence()) + ")";
        Map<String, Object> packedBinding = Map.of(
                "type", "string",
                "minLength", 15,
                "maxLength", 128,
                "pattern", "^" + nodeGroup + "\\|" + relationshipGroup + "$",
                "description", "Exactly eight pipe-delimited numeric ids in this order: "
                        + "NODE_ID|PARENT_ID|NODE_EVIDENCE_ID|RELATIONSHIP_ID|FAMILY_ID|"
                        + "SOURCE_ID|TARGET_ID|RELATIONSHIP_EVIDENCE_ID. Select ids only from "
                        + "BINDING_OPTION_IDS_JSON; absent category fields are 0."
                        + disabledNode + disabledRelationship);
        Map<String, Object> parameters = objectSchema(Map.of("b", packedBinding), List.of("b"));
        String bindingPrompt = CorpusSchemaPromptBuilder.checkedPrompt(
                prompt + "\nBINDING_OPTION_IDS_JSON="
                        + serializeBindingOptions(options.promptView()) + "\n");
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message("system",
                                "Bind one topic by option id. Call the tool once without prose."),
                        new StructuredChatLanguageModel.Message("user", bindingPrompt)),
                List.of(new StructuredChatLanguageModel.Tool(
                        TOPIC_BINDING_TOOL_NAME,
                        "Bind one topic to inherited types and a typed predicate.",
                        parameters)),
                true,
                StructuredChatLanguageModel.ToolDefinitionFormat.STANDARD,
                StructuredChatLanguageModel.ToolCallFormat.MODEL,
                StructuredChatLanguageModel.ToolChoice.REQUIRED);
    }

    private static TopicBindingOptions topicBindingOptions(
            CorpusTopicEvidence batch,
            GraphSchema establishedSchema,
            Map<String, String> passageTexts) {
        List<String> corpusCandidates = topicSchemaCandidateLabels(
                batch.topics().get(0), passageTexts);
        List<String> establishedNodes = typeLabels(
                CorpusSchemaPromptBuilder.TypePass.NODE_TYPES, establishedSchema);
        List<String> existingCustomNodes = establishedNodes.stream()
                .map(CorpusSchemaUnifier::canonicalName)
                .filter(label -> !SchemaHierarchyVocabulary.isBaseEntityType(label))
                .toList();
        List<String> nodes = mergeBindingOptions(
                corpusCandidates, establishedNodes, MAX_TOPIC_NODE_OPTIONS);
        List<String> relationships = mergeBindingOptions(
                corpusCandidates.stream().limit(16).toList(),
                typeLabels(CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES, establishedSchema),
                MAX_TOPIC_RELATIONSHIP_OPTIONS);
        List<String> endpoints = mergeBindingOptions(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES,
                existingCustomNodes,
                MAX_TOPIC_ENDPOINT_OPTIONS);
        return new TopicBindingOptions(
                indexedOptions(nodes),
                indexedOptions(allowedParentTypes(establishedSchema, batch, passageTexts)),
                indexedOptions(relationships),
                indexedOptions(SchemaHierarchyVocabulary.CONNECTION_FAMILIES),
                indexedOptions(endpoints),
                indexedEvidenceOptions(CorpusTopicEvidence.promptGroundingTexts(
                        batch.topics().get(0), passageTexts)));
    }

    private static Map<String, String> indexedOptions(Collection<String> values) {
        LinkedHashMap<String, String> indexed = new LinkedHashMap<>();
        int index = 1;
        for (String value : values) {
            if (!hasText(value)) continue;
            String canonical = canonicalName(value);
            if (!canonical.matches(SCHEMA_NAME_PATTERN)
                    || canonical.length() > MAX_SCHEMA_NAME_CHARS
                    || indexed.containsValue(canonical)) continue;
            indexed.put(String.valueOf(index++), canonical);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Map<String, String> indexedEvidenceOptions(Collection<String> values) {
        LinkedHashMap<String, String> indexed = new LinkedHashMap<>();
        int index = 1;
        for (String value : values) {
            if (!hasText(value) || indexed.containsValue(value)) continue;
            indexed.put(String.valueOf(index++), value);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static String packedOptionIds(Map<String, String> options) {
        return options.isEmpty()
                ? "0" : "(?:0|" + compactPositiveOptionIdPattern(options.size()) + ")";
    }

    private static String packedRequiredOptionIds(Map<String, String> options) {
        return options.isEmpty()
                ? "(?!)" : compactPositiveOptionIdPattern(options.size());
    }

    static String compactPositiveOptionIdPattern(int maxId) {
        if (maxId <= 0) return "(?!)";
        // Local option pages are small; explicit ids avoid malformed decimal ranges at 100+
        // (for example [1-10][0-9]) while keeping the decoder contract exact.
        return "(?:" + java.util.stream.IntStream.rangeClosed(1, maxId)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining("|")) + ")";
    }

    private static int maxOptionIdLength(Map<String, String> options) {
        return Math.max(1, String.valueOf(options.size()).length());
    }

    private static String serializeBindingOptions(Map<String, Object> options) {
        try {
            return OBJECT_MAPPER.writeValueAsString(options);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize topic binding options", e);
        }
    }

    private static List<String> mergeBindingOptions(
            Collection<String> first, Collection<String> second, int limit) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) first.stream().filter(CorpusSchemaUnifier::hasText)
                .map(CorpusSchemaUnifier::canonicalName).forEach(merged::add);
        if (second != null) second.stream().filter(CorpusSchemaUnifier::hasText)
                .map(CorpusSchemaUnifier::canonicalName).forEach(merged::add);
        return merged.stream().limit(limit).toList();
    }

    private static List<String> topicSchemaCandidateLabels(
            CorpusTopicEvidence.Topic topic, Map<String, String> passageTexts) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<String> topicTerms = topic == null ? List.of() : topic.termsByLanguage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .filter(CorpusSchemaUnifier::hasText)
                .toList();
        for (String evidence : topicTerms) {
            List<String> tokens = new ArrayList<>(lexicalTokens(evidence));
            for (int width = Math.min(3, tokens.size()); width >= 2; width--) {
                for (int start = 0; start + width <= tokens.size(); start++) {
                    addSchemaCandidate(candidates,
                            String.join("_", tokens.subList(start, start + width)));
                }
            }
            for (String token : tokens) addSchemaCandidate(candidates, token);
            if (candidates.size() >= MAX_TOPIC_CANDIDATE_LABELS) break;
        }
        return candidates.stream().limit(MAX_TOPIC_CANDIDATE_LABELS).toList();
    }

    private static void addSchemaCandidate(Set<String> candidates, String value) {
        String candidate = canonicalName(value);
        if (isBindableSchemaLabel(candidate)
                && candidate.length() <= MAX_SCHEMA_NAME_CHARS) {
            candidates.add(candidate);
        }
    }

    /**
     * Function-word tokens (THE, AND, WITH…) satisfy SCHEMA_NAME_PATTERN after upper-casing
     * but are never meaningful schema labels; binding one forces a validator rejection that
     * aborts the whole topic pre-pass, so exclude them from candidate and option lists.
     */
    static boolean isBindableSchemaLabel(String canonicalLabel) {
        if (canonicalLabel == null || !canonicalLabel.matches(SCHEMA_NAME_PATTERN)) return false;
        return !STOPWORD_SCHEMA_LABELS.contains(canonicalLabel);
    }

    private static TopicBindingParseResult parseTopicBindings(
            StructuredChatLanguageModel.Response response,
            CorpusTopicEvidence batch,
            Map<String, String> passageTexts,
            GraphSchema establishedSchema,
            int remainingNodeTypes,
            int remainingRelationshipTypes) {
        try {
            if (response == null) throw new IllegalArgumentException("Structured model response was null");
            Map<String, Object> arguments = responseArguments(
                    response, TOPIC_BINDING_TOOL_NAME);
            if (!arguments.keySet().equals(TOPIC_BINDING_FIELDS)) {
                throw new IllegalArgumentException(
                        TOPIC_BINDING_TOOL_NAME + " arguments must contain exactly "
                                + TOPIC_BINDING_FIELDS);
            }
            TopicBindingOptions bindingOptions = topicBindingOptions(
                    batch, establishedSchema, passageTexts);
            List<?> definitions = List.of(normalizeFlatTopicBinding(
                    arguments, batch.topics().get(0), bindingOptions));
            Map<String, CorpusTopicEvidence.Topic> topics = batch.topics().stream()
                    .collect(Collectors.toMap(CorpusTopicEvidence.Topic::topicId,
                            java.util.function.Function.identity(), (left, ignored) -> left,
                            LinkedHashMap::new));
            Set<String> corpusCandidateLabels = new LinkedHashSet<>(
                    topicSchemaCandidateLabels(batch.topics().get(0), passageTexts));
            Map<String, NodeType> existingNodes = establishedSchema.getNodeTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toMap(node -> canonicalName(node.getLabel()),
                            java.util.function.Function.identity(), (left, ignored) -> left,
                            LinkedHashMap::new));
            Map<String, RelationshipType> existingRelations = establishedSchema.getRelationshipTypes() == null
                    ? Map.of() : establishedSchema.getRelationshipTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toMap(relation -> canonicalName(relation.getType()),
                            java.util.function.Function.identity(), (left, ignored) -> left,
                            LinkedHashMap::new));
            Map<String, NodeType> novelNodes = new LinkedHashMap<>();
            List<Map<?, ?>> rawBindings = new ArrayList<>();
            Set<String> returnedTopicIds = new LinkedHashSet<>();
            for (Object definition : definitions) {
                Map<?, ?> fields = exactMap(definition,
                        Set.of("topicId", "nodeTypes", "relationshipTypes"), "topic binding");
                String topicId = requiredText(fields, "topicId");
                CorpusTopicEvidence.Topic topic = topics.get(topicId);
                if (topic == null || !returnedTopicIds.add(topicId)) {
                    throw new IllegalArgumentException("Unknown or duplicate topicId: " + topicId);
                }
                for (Object nodeValue : requiredArray(fields, "nodeTypes")) {
                    Map<?, ?> node = exactMap(nodeValue,
                            Set.of("label", "parentType", "groundingPhrase"),
                            "node binding");
                    String label = canonicalName(requiredText(node, "label"));
                    String parent = canonicalName(requiredText(node, "parentType"));
                    NodeType existing = existingNodes.get(label);
                    if ("NONE".equals(parent) && existing == null) {
                        throw new IllegalArgumentException(
                                "New topic-bound node type requires an existing parentType: " + label);
                    }
                    if (!"NONE".equals(parent) && !existingNodes.containsKey(parent)) {
                        throw new IllegalArgumentException("Topic binding parentType is not in the established hierarchy: " + parent);
                    }
                    String groundingPhrase = optionalText(node, "groundingPhrase");
                    if (existingRelations.containsKey(label)) {
                        throw new IllegalArgumentException(
                                "Topic-bound node type collides with relationship type " + label);
                    }
                    if (existing == null && !corpusCandidateLabels.isEmpty()
                            && !corpusCandidateLabels.contains(label)) {
                        throw new IllegalArgumentException(
                                "Topic-bound node type is not a corpus label candidate: " + label);
                    }
                    if (existing != null && !"NONE".equals(parent)
                            && hasText(existing.getParentType())
                            && !parent.equals(canonicalName(existing.getParentType()))) {
                        throw new IllegalArgumentException("Topic binding changed parentType for existing node " + label);
                    }
                    NodeType previous = existing != null
                            ? null : novelNodes.putIfAbsent(label,
                            new NodeType(label, "Topic-bound node type " + label + ".", null, parent));
                    if (previous != null && !parent.equals(canonicalName(previous.getParentType()))) {
                        throw new IllegalArgumentException("Conflicting parents for topic-bound node type " + label);
                    }
                }
                rawBindings.add(fields);
            }
            if (!returnedTopicIds.equals(topics.keySet())) {
                throw new IllegalArgumentException("Every supplied topicId must be returned exactly once");
            }

            Set<String> availableNodeTypes = new LinkedHashSet<>(existingNodes.keySet());
            availableNodeTypes.addAll(novelNodes.keySet());
            Map<String, RelationshipType> novelRelations = new LinkedHashMap<>();
            Set<String> patterns = new LinkedHashSet<>();
            List<CorpusTopicEvidence.TopicBinding> bindings = new ArrayList<>();
            for (Map<?, ?> fields : rawBindings) {
                String topicId = requiredText(fields, "topicId");
                CorpusTopicEvidence.Topic topic = topics.get(topicId);
                List<CorpusTopicEvidence.NodeBinding> nodeBindings = new ArrayList<>();
                for (Object nodeValue : requiredArray(fields, "nodeTypes")) {
                    Map<?, ?> node = (Map<?, ?>) nodeValue;
                    nodeBindings.add(new CorpusTopicEvidence.NodeBinding(
                            canonicalName(requiredText(node, "label")),
                            canonicalName(requiredText(node, "parentType")),
                            optionalText(node, "groundingPhrase")));
                }
                List<CorpusTopicEvidence.RelationshipBinding> relationBindings = new ArrayList<>();
                for (Object relationValue : requiredArray(fields, "relationshipTypes")) {
                    Map<?, ?> relation = exactMap(relationValue,
                            Set.of("type", "connectionFamily", "sourceType", "targetType",
                                    "groundingPhrase"),
                            "relationship binding");
                    String type = canonicalName(requiredText(relation, "type"));
                    String family = canonicalName(requiredText(relation, "connectionFamily"));
                    String source = canonicalName(requiredText(relation, "sourceType"));
                    String target = canonicalName(requiredText(relation, "targetType"));
                    if (!SchemaHierarchyVocabulary.isConnectionFamily(family)) {
                        throw new IllegalArgumentException("Unknown connectionFamily " + family);
                    }
                    if (!availableNodeTypes.contains(source) || !availableNodeTypes.contains(target)) {
                        throw new IllegalArgumentException("Relationship " + type
                                + " uses unknown endpoint types " + source + " -> " + target);
                    }
                    String groundingPhrase = optionalText(relation, "groundingPhrase");
                    if (existingNodes.containsKey(type) || novelNodes.containsKey(type)) {
                        throw new IllegalArgumentException(
                                "Topic-bound relationship type collides with node type " + type);
                    }
                    RelationshipType existing = existingRelations.get(type);
                    if (existing == null && !corpusCandidateLabels.isEmpty()
                            && !corpusCandidateLabels.contains(type)) {
                        throw new IllegalArgumentException(
                                "Topic-bound relationship type is not a corpus label candidate: " + type);
                    }
                    if (existing != null && hasText(existing.getConnectionFamily())
                            && !family.equals(canonicalName(existing.getConnectionFamily()))) {
                        throw new IllegalArgumentException("Topic binding changed connectionFamily for existing relationship " + type);
                    }
                    RelationshipType previous = existing != null && hasText(existing.getConnectionFamily())
                            ? null : novelRelations.putIfAbsent(type,
                            new RelationshipType(type, "Topic-bound relationship type " + type + ".",
                                    null, List.of(), family));
                    if (previous != null && !family.equals(canonicalName(previous.getConnectionFamily()))) {
                        throw new IllegalArgumentException("Conflicting families for topic-bound relationship " + type);
                    }
                    patterns.add("(" + source + ")-[:" + type + "]->(" + target + ")");
                    relationBindings.add(new CorpusTopicEvidence.RelationshipBinding(
                            type, family, source, target, groundingPhrase));
                }
                bindings.add(new CorpusTopicEvidence.TopicBinding(
                        topicId, nodeBindings, relationBindings));
            }
            GraphSchema overlay = new GraphSchema(
                    List.copyOf(novelNodes.values()),
                    List.copyOf(novelRelations.values()),
                    List.copyOf(patterns));
            if (novelNodes.size() > remainingNodeTypes
                    || novelRelations.size() > remainingRelationshipTypes) {
                throw new IllegalArgumentException(
                        "Topic binding exceeded the remaining schema type budget");
            }
            CorpusSchemaOverlayValidator.Result validation =
                    CorpusSchemaOverlayValidator.validate(establishedSchema, overlay);
            if (!validation.valid()) {
                return new TopicBindingParseResult(null, List.of(), validation.errors());
            }
            return new TopicBindingParseResult(overlay, List.copyOf(bindings), List.of());
        } catch (IllegalArgumentException failure) {
            return new TopicBindingParseResult(
                    null, List.of(), List.of("[TOPIC_SCHEMA_BINDING] " + failure.getMessage()));
        }
    }

    private static Map<?, ?> exactMap(Object value, Set<String> fields, String kind) {
        if (!(value instanceof Map<?, ?> map)
                || !map.keySet().stream().map(String::valueOf).collect(Collectors.toSet()).equals(fields)) {
            throw new IllegalArgumentException(kind + " must contain exactly " + fields);
        }
        return map;
    }

    private static Map<String, Object> normalizeFlatTopicBinding(
            Map<String, Object> fields,
            CorpusTopicEvidence.Topic topic,
            TopicBindingOptions options) {
        String topicId = topic.topicId();
        String[] packed = requiredText(fields, "b").split("\\|", -1);
        if (packed.length != 8 || Arrays.stream(packed).anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "b must contain exactly eight nonblank pipe-delimited fields for " + topicId);
        }
        String nodeType = resolveBindingOption(options.nodes(), packed[0], "node", topicId);
        String parentType = resolveBindingOption(options.parents(), packed[1], "parent", topicId);
        String nodeEvidenceId = canonicalName(packed[2]);
        String nodeEvidence = resolveGroundingEvidence(
                options.evidence(), nodeEvidenceId, "node", topicId);
        String relationshipType = resolveBindingOption(
                options.relationships(), packed[3], "relationship", topicId);
        String family = resolveBindingOption(options.families(), packed[4], "family", topicId);
        String sourceType = resolveBindingOption(options.endpoints(), packed[5], "source", topicId);
        String targetType = resolveBindingOption(options.endpoints(), packed[6], "target", topicId);
        String relationshipEvidenceId = canonicalName(packed[7]);
        String relationshipEvidence = resolveGroundingEvidence(
                options.evidence(), relationshipEvidenceId, "relationship", topicId);
        validatePackedSchemaName("nodeType", nodeType);
        validatePackedSchemaName("parentType", parentType);
        validatePackedSchemaName("relationshipType", relationshipType);
        validatePackedSchemaName("connectionFamily", family);
        validatePackedSchemaName("sourceType", sourceType);
        validatePackedSchemaName("targetType", targetType);
        boolean hasNode = !"NONE".equals(nodeType);
        boolean hasRelationship = !"NONE".equals(relationshipType);
        boolean validNodeFields = hasNode
                ? !"NONE".equals(nodeEvidence)
                : "NONE".equals(parentType) && "NONE".equals(nodeEvidence);
        if (!validNodeFields) {
            throw new IllegalArgumentException(
                    "node fields must all be NONE or all be concrete for " + topicId);
        }
        boolean allRelationshipMetadataNone = "NONE".equals(family)
                && "NONE".equals(sourceType) && "NONE".equals(targetType)
                && "NONE".equals(relationshipEvidence);
        boolean allRelationshipMetadataConcrete = !"NONE".equals(family)
                && !"NONE".equals(sourceType) && !"NONE".equals(targetType)
                && !"NONE".equals(relationshipEvidence);
        if (!(hasRelationship && allRelationshipMetadataConcrete)
                && !(!hasRelationship && allRelationshipMetadataNone)) {
            throw new IllegalArgumentException(
                    "relationship fields must all be NONE or all be concrete for " + topicId);
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("topicId", topicId);
        binding.put("nodeTypes", hasNode
                ? List.of(Map.of("label", nodeType, "parentType", parentType,
                        "groundingPhrase", nodeEvidence)) : List.of());
        binding.put("relationshipTypes", hasRelationship
                ? List.of(Map.of(
                        "type", relationshipType,
                        "connectionFamily", family,
                        "sourceType", sourceType,
                        "targetType", targetType,
                        "groundingPhrase", relationshipEvidence)) : List.of());
        return Map.copyOf(binding);
    }

    private static String resolveBindingOption(
            Map<String, String> options,
            String optionId,
            String category,
            String topicId) {
        String canonicalId = canonicalName(optionId);
        if ("0".equals(canonicalId)) return "NONE";
        String value = options.get(canonicalId);
        if (!hasText(value)) {
            throw new IllegalArgumentException(
                    "Unknown " + category + " option id " + canonicalId + " for " + topicId);
        }
        return value;
    }

    private static String resolveGroundingEvidence(
            Map<String, String> visibleEvidence,
            String evidenceId,
            String category,
            String topicId) {
        if ("0".equals(evidenceId)) return "NONE";
        String evidence = visibleEvidence.get(evidenceId);
        if (!hasText(evidence)) {
            throw new IllegalArgumentException(
                    "Unknown " + category + " evidence id " + evidenceId + " for " + topicId);
        }
        return evidence;
    }

    private static void validatePackedSchemaName(String field, String value) {
        if ("NONE".equals(value)) return;
        if (value.length() > MAX_SCHEMA_NAME_CHARS || !value.matches(SCHEMA_NAME_PATTERN)) {
            throw new IllegalArgumentException(field + " must match " + SCHEMA_NAME_PATTERN
                    + " and be at most " + MAX_SCHEMA_NAME_CHARS + " characters");
        }
    }

    private static List<?> requiredArray(Map<?, ?> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return list;
    }

    private static String requiredText(Map<?, ?> values, String field) {
        Object value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank");
        }
        return value.toString().trim();
    }

    private static String optionalText(Map<?, ?> values, String field) {
        Object value = values.get(field);
        return value == null ? "" : value.toString().trim();
    }

    private static CrawlLlmDispatcher.LlmCallScope topicBindingScope(
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            int batchIndex,
            int attempt) {
        String jobId = hasText(job.getJobId()) ? job.getJobId() : "crawl";
        return new CrawlLlmDispatcher.LlmCallScope(
                "SCHEMA_PREPASS", "topic-binding-" + batchIndex, attempt,
                jobId + ":corpus-schema:topic-binding:" + batchIndex
                        + (attempt > 1 ? ":attempt:" + attempt : ""),
                null, null, corpusSnapshotId, null, 0, 0);
    }

    private static Map<TypeProposal, Integer> discoverTypeProposals(
            CorpusSchemaPromptBuilder.TypePass pass,
            List<Map<String, String>> batches,
            CrawlOntology ontology,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        List<String> failures = new ArrayList<>();
        Map<TypeProposal, Integer> proposalSupport = new LinkedHashMap<>();
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
                                    structuredRequest(prompt, pass, false, ontology.snapshot()), TASK_TYPE, job, scope),
                            pass);
                    if (!parsed.valid()) {
                        validationErrors = conciseValidationErrors(parsed.errors());
                    } else {
                        GraphSchema novelTypes = withoutAuthoritativeTypes(
                                pass, parsed.schema(), ontology.snapshot());
                        List<String> errors = typeResponseErrors(
                                pass, novelTypes, ontology.snapshot(), false, Set.of());
                        if (errors.isEmpty()) {
                            addProposalSupport(
                                    pass, novelTypes, ontology.snapshot(), proposalSupport);
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
            Map<TypeProposal, Integer> proposalSupport,
            CrawlOntology ontology,
            CorpusTopicEvidence topicEvidence,
            UnifiedCrawlJob job,
            String corpusSnapshotId,
            CrawlLlmDispatcher dispatcher,
            int maxValidationRetries) {
        String basePrompt = CorpusSchemaPromptBuilder.buildConsolidation(
                ontology.snapshot(), pass, proposalSupport, topicEvidence);
        String validationErrors = null;
        boolean allFailuresEligibleForGroundedFallback = true;
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
                            structuredRequest(prompt, pass, true, ontology.snapshot()), TASK_TYPE, job, scope),
                    pass);
            if (!parsed.valid()) {
                allFailuresEligibleForGroundedFallback = false;
                validationErrors = conciseValidationErrors(parsed.errors());
            } else {
                List<String> errors = typeResponseErrors(
                        pass, parsed.schema(), ontology.snapshot(), true,
                        proposalSupport.keySet());
                if (errors.isEmpty()) {
                    return parsed.schema();
                }
                allFailuresEligibleForGroundedFallback &= errors.stream()
                        .allMatch(error -> error.startsWith("[SCHEMA_UNPROPOSED_TYPE]"));
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

        if (allFailuresEligibleForGroundedFallback) {
            GraphSchema fallback = deterministicConsolidationFallback(
                    pass, proposalSupport, ontology.snapshot());
            log.warn(
                    "[Job {}] Corpus {} consolidation exhausted {} attempts for snapshot {}; "
                            + "using deterministic grounded proposal fallback: {}",
                    job.getJobId(), passLabel(pass), maxValidationRetries + 1,
                    corpusSnapshotId, typeLabels(pass, fallback));
            return fallback;
        }
        throw new IllegalStateException(
                "Corpus " + passLabel(pass) + " consolidation failed for snapshot "
                        + corpusSnapshotId + ": "
                        + (validationErrors == null
                                ? "Schema consolidation failed without diagnostics"
                                : validationErrors));
    }

    private static GraphSchema deterministicConsolidationFallback(
            CorpusSchemaPromptBuilder.TypePass pass,
            Map<TypeProposal, Integer> proposalSupport,
            GraphSchema establishedSchema) {
        Set<String> authoritativeSameType = typeNames(pass, establishedSchema);
        CorpusSchemaPromptBuilder.TypePass oppositePass =
                pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                        ? CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES
                        : CorpusSchemaPromptBuilder.TypePass.NODE_TYPES;
        Set<String> authoritativeOppositeTypes = typeNames(oppositePass, establishedSchema);

        Map<TypeProposal, Integer> unambiguous = unambiguousFallbackProposals(proposalSupport);
        List<TypeProposal> proposals = unambiguous.entrySet().stream()
                .sorted(java.util.Comparator
                        .<Map.Entry<TypeProposal, Integer>>comparingInt(entry ->
                                entry.getValue() == null ? 0 : entry.getValue())
                        .reversed()
                        .thenComparing(entry -> entry.getKey().label())
                        .thenComparing(entry -> entry.getKey().classification()))
                .map(Map.Entry::getKey)
                .filter(proposal -> !authoritativeSameType.contains(proposal.label()))
                .filter(proposal -> !authoritativeOppositeTypes.contains(proposal.label()))
                .limit(MAX_CONSOLIDATED_TYPES)
                .toList();

        String field = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "nodeTypes" : "relationshipTypes";
        List<Map<String, Object>> definitions = proposals.stream()
                .map(proposal -> Map.<String, Object>of(
                        pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                                ? "label" : "type", proposal.label(),
                        pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                                ? "parentType" : "connectionFamily", proposal.classification()))
                .toList();
        CorpusSchemaResponseParser.ParseResult parsed = parseTypeArguments(
                Map.of(field, definitions), pass);
        if (!parsed.valid()) {
            throw new IllegalStateException(
                    "Deterministic corpus " + passLabel(pass)
                            + " fallback could not be parsed: "
                            + conciseValidationErrors(parsed.errors()));
        }
        List<String> errors = typeResponseErrors(
                pass, parsed.schema(), establishedSchema, true, proposalSupport.keySet());
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Deterministic corpus " + passLabel(pass)
                            + " fallback failed validation: "
                            + conciseValidationErrors(errors));
        }
        return parsed.schema();
    }

    static Map<TypeProposal, Integer> unambiguousFallbackProposals(
            Map<TypeProposal, Integer> proposalSupport) {
        Map<String, List<Map.Entry<TypeProposal, Integer>>> byLabel = new LinkedHashMap<>();
        proposalSupport.entrySet().forEach(entry ->
                byLabel.computeIfAbsent(entry.getKey().label(), ignored -> new ArrayList<>())
                        .add(entry));
        Map<TypeProposal, Integer> resolved = new LinkedHashMap<>();
        byLabel.forEach((label, candidates) -> {
            int bestSupport = candidates.stream()
                    .map(Map.Entry::getValue)
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0);
            List<TypeProposal> best = candidates.stream()
                    .filter(entry -> java.util.Objects.equals(entry.getValue(), bestSupport))
                    .map(Map.Entry::getKey)
                    .toList();
            if (best.size() == 1) {
                resolved.put(best.get(0), bestSupport);
            } else {
                log.warn("Dropping ambiguously classified fallback schema type '{}': {}",
                        label, best);
            }
        });
        return Collections.unmodifiableMap(resolved);
    }

    private static StructuredChatLanguageModel.Request structuredRequest(
            String prompt,
            CorpusSchemaPromptBuilder.TypePass pass,
            boolean consolidation,
            GraphSchema establishedSchema) {
        String action = consolidation ? "Consolidate untrusted proposals into" : "Define";
        String systemPrompt = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? action + " reusable node schema types only. Do not extract entities or relations. "
                        + "Call the required tool exactly once without prose."
                : action + " reusable relationship schema types only. Do not extract entities or relations. "
                        + "Call the required tool exactly once without prose.";
        String toolName = toolName(pass);
        String description = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? "Submit missing reusable node types with one baseline parentType. Never submit entity instances, "
                        + "relationship types, relations, triples, ids, or endpoint patterns."
                : "Submit missing specific relationship predicates with one connectionFamily. Never submit relation "
                        + "instances, triples, entities, ids, source/target endpoints, or patterns.";
        Map<String, Object> parameters = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? classifiedTypeToolParameters(
                        "nodeTypes", "label", "parentType",
                        allowedParentTypes(establishedSchema, null, Map.of()),
                        "One reusable node category in UPPER_SNAKE_CASE; never an instance name or value.")
                : RELATIONSHIP_TYPE_TOOL_PARAMETERS;
        return new StructuredChatLanguageModel.Request(
                List.of(
                        new StructuredChatLanguageModel.Message("system", systemPrompt),
                        new StructuredChatLanguageModel.Message("user", prompt)),
                List.of(new StructuredChatLanguageModel.Tool(
                        toolName, description, parameters)),
                true,
                StructuredChatLanguageModel.ToolDefinitionFormat.STANDARD,
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
        List<StructuredChatLanguageModel.ToolCall> calls = response.toolCalls().stream()
                .filter(java.util.Objects::nonNull).toList();
        if (calls.size() == 1 && expectedTool.equals(calls.get(0).name())) {
            return parseTypeArguments(calls.get(0).arguments(), pass);
        }
        if (calls.isEmpty() && response.content() != null && !response.content().isBlank()) {
            try {
                Map<?, ?> parsed = OBJECT_MAPPER.readValue(response.content(), Map.class);
                Map<String, Object> arguments = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("schema JSON keys must be strings");
                    }
                    arguments.put(key, entry.getValue());
                }
                return parseTypeArguments(arguments, pass);
            } catch (Exception invalidJson) {
                return new CorpusSchemaResponseParser.ParseResult(
                        null, List.of("[SCHEMA_JSON] Native schema response was not a valid "
                                + expectedTool + " object: " + invalidJson.getMessage()));
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
        if (!(values instanceof List<?> definitions)) {
            return new CorpusSchemaResponseParser.ParseResult(
                    null,
                    List.of("[SCHEMA_TYPE_ONLY] " + expectedField
                            + " must be an array of classified type objects"));
        }
        Set<String> requiredFields = pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                ? Set.of("label", "parentType")
                : Set.of("type", "connectionFamily");
        for (Object definition : definitions) {
            if (!(definition instanceof Map<?, ?> fields)
                    || !fields.keySet().stream().map(String::valueOf)
                            .collect(Collectors.toSet()).equals(requiredFields)) {
                return new CorpusSchemaResponseParser.ParseResult(
                        null,
                        List.of("[SCHEMA_TYPE_ONLY] " + expectedField
                                + " entries must contain exactly " + requiredFields));
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
        String expectedTool = pass == null ? TOPIC_BINDING_TOOL_NAME : toolName(pass);
        return basePrompt
                + "\n\nTYPE-SCHEMA REPAIR REQUIRED (attempt " + attempt + " of " + totalAttempts + ")\n"
                + "The previous " + expectedTool + " call failed validation:\n"
                + (hasText(validationErrors) ? validationErrors : "Unknown type validation error")
                + "\nReturn one complete corrected " + expectedTool + " call. "
                + (pass == null
                ? "Return every supplied topic id with complete inherited type bindings and typed signatures; do not return a patch.\n"
                : "Do not return a patch, instances, relations, triples, endpoints, or patterns.\n");
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
            Set<TypeProposal> allowedConsolidationLabels) {
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
        for (TypeProposal proposal : typeProposals(pass, schema)) {
            String label = proposal.label();
            String canonical = canonicalName(label);
            if (allowedConsolidationLabels == null
                    || !allowedConsolidationLabels.contains(proposal)) {
                errors.add("[SCHEMA_UNPROPOSED_TYPE] Consolidation returned a type/classification pair that no corpus batch proposed: "
                        + proposal);
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
            Map<TypeProposal, Integer> proposalSupport) {
        Set<String> authoritative = typeNames(pass, establishedSchema);
        for (TypeProposal typeProposal : typeProposals(pass, proposal)) {
            if (!authoritative.contains(typeProposal.label())) {
                proposalSupport.merge(typeProposal, 1, Integer::sum);
            }
        }
    }

    /**
     * Model discovery output is an overlay, not a restatement of the frozen ontology. Ignore
     * repeated authoritative labels before validating parent/family metadata; their model-supplied
     * classifications cannot alter the established schema and therefore are not proposals.
     */
    private static GraphSchema withoutAuthoritativeTypes(
            CorpusSchemaPromptBuilder.TypePass pass,
            GraphSchema proposal,
            GraphSchema establishedSchema) {
        if (proposal == null) return null;
        Set<String> authoritative = typeNames(pass, establishedSchema);
        CorpusSchemaPromptBuilder.TypePass oppositePass =
                pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES
                        ? CorpusSchemaPromptBuilder.TypePass.RELATIONSHIP_TYPES
                        : CorpusSchemaPromptBuilder.TypePass.NODE_TYPES;
        Set<String> opposite = typeNames(oppositePass, establishedSchema);
        if (pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES) {
            List<NodeType> novel = (proposal.getNodeTypes() == null
                    ? List.<NodeType>of() : proposal.getNodeTypes()).stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> hasText(type.getLabel()))
                    .filter(type -> !authoritative.contains(canonicalName(type.getLabel())))
                    .filter(type -> !opposite.contains(canonicalName(type.getLabel())))
                    .toList();
            return new GraphSchema(novel, null, null);
        }
        List<RelationshipType> novel = (proposal.getRelationshipTypes() == null
                ? List.<RelationshipType>of() : proposal.getRelationshipTypes()).stream()
                .filter(java.util.Objects::nonNull)
                .filter(type -> hasText(type.getType()))
                .filter(type -> !authoritative.contains(canonicalName(type.getType())))
                .filter(type -> !opposite.contains(canonicalName(type.getType())))
                .toList();
        return new GraphSchema(null, novel, null);
    }

    private static Map<TypeProposal, Integer> evidenceGroundedProposals(
            Map<TypeProposal, Integer> proposals,
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
        Map<TypeProposal, Integer> grounded = new LinkedHashMap<>();
        proposals.forEach((proposal, batchSupport) -> {
            List<String> tokens = Arrays.stream(
                            proposal.label().toLowerCase(Locale.ROOT).split("_"))
                    .filter(token -> token.length() >= 3)
                    .toList();
            long passageSupport = passageTokens.stream()
                    .filter(passage -> tokens.stream().allMatch(passage::contains))
                    .count();
            boolean topicSupport = tokens.stream().allMatch(topicTerms::contains);
            if (!tokens.isEmpty() && (passageSupport >= requiredPassageSupport
                    || (passageSupport >= 1 && topicSupport))) {
                grounded.put(proposal, batchSupport);
            } else {
                log.warn("Dropping ungrounded corpus schema proposal '{}': batchSupport={}, passageSupport={}",
                        proposal, batchSupport, passageSupport);
            }
        });
        return Collections.unmodifiableMap(grounded);
    }

    private static boolean containsLatinLetter(String token) {
        return token != null && token.codePoints().anyMatch(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= 'a' && codePoint <= 'z'));
    }

    private static Set<String> lexicalTokens(String value) {
        if (!hasText(value)) return Set.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> containsLatinLetter(token)
                        ? token.codePointCount(0, token.length()) >= 3
                        : token.codePointCount(0, token.length()) >= 2)
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

    private static List<TypeProposal> typeProposals(
            CorpusSchemaPromptBuilder.TypePass pass, GraphSchema schema) {
        if (schema == null) return List.of();
        if (pass == CorpusSchemaPromptBuilder.TypePass.NODE_TYPES) {
            if (schema.getNodeTypes() == null) return List.of();
            return schema.getNodeTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> hasText(node.getLabel()) && hasText(node.getParentType()))
                    .map(node -> new TypeProposal(node.getLabel(), node.getParentType()))
                    .toList();
        }
        if (schema.getRelationshipTypes() == null) return List.of();
        return schema.getRelationshipTypes().stream()
                .filter(java.util.Objects::nonNull)
                .filter(type -> hasText(type.getType()) && hasText(type.getConnectionFamily()))
                .map(type -> new TypeProposal(type.getType(), type.getConnectionFamily()))
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

    private static Map<String, Object> classifiedTypeToolParameters(
            String fieldName, String nameField, String classificationField,
            List<String> classifications, String description) {
        Map<String, Object> item = objectSchema(
                Map.of(
                        nameField, schemaNameSchema(description),
                        classificationField, Map.of(
                                "type", "string",
                                "enum", classifications,
                                "description", "Required stable baseline classification.")),
                List.of(nameField, classificationField));
        return objectSchema(
                Map.of(fieldName, uniqueTypeArraySchema(
                        item)),
                List.of(fieldName));
    }

    private static int perTopicBudget(int remaining, int topicCount, int maximumPerTopic) {
        if (remaining <= 0 || topicCount <= 0) return 0;
        return Math.min(maximumPerTopic, (remaining + topicCount - 1) / topicCount);
    }

    private static List<String> allowedParentTypes(
            GraphSchema establishedSchema,
            CorpusTopicEvidence topicEvidence,
            Map<String, String> passageTexts) {
        LinkedHashSet<String> parents = new LinkedHashSet<>(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES);
        if (establishedSchema != null && establishedSchema.getNodeTypes() != null) {
            Set<String> evidence = new LinkedHashSet<>();
            Map<String, String> texts = passageTexts == null ? Map.of() : passageTexts;
            if (topicEvidence != null) {
                topicEvidence.topics().stream()
                        .flatMap(topic -> topic.termsByLanguage().values().stream())
                        .flatMap(Collection::stream)
                        .flatMap(value -> lexicalTokens(value).stream())
                        .forEach(evidence::add);
                topicEvidence.topics().stream()
                        .flatMap(topic -> topic.memberChunkIds().stream())
                        .map(texts::get)
                        .filter(CorpusSchemaUnifier::hasText)
                        .flatMap(value -> lexicalTokens(value).stream())
                        .forEach(evidence::add);
            }
            establishedSchema.getNodeTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(node -> hasText(node.getLabel()))
                    .filter(node -> !SchemaHierarchyVocabulary.isBaseEntityType(node.getLabel()))
                    .sorted(java.util.Comparator
                            .<NodeType>comparingInt(node -> parentRelevance(node, evidence))
                            .reversed()
                            .thenComparing(node -> canonicalName(node.getLabel())))
                    .limit(Math.max(0,
                            MAX_TOPIC_PARENT_TYPES - SchemaHierarchyVocabulary.BASE_ENTITY_TYPES.size()))
                    .map(NodeType::getLabel)
                    .map(CorpusSchemaUnifier::canonicalName)
                    .forEach(parents::add);
        }
        return List.copyOf(parents);
    }

    private static int parentRelevance(NodeType node, Set<String> evidence) {
        Set<String> tokens = new LinkedHashSet<>(lexicalTokens(node.getLabel()));
        tokens.addAll(lexicalTokens(node.getDescription()));
        return (int) tokens.stream().filter(evidence::contains).count();
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
