/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the bounded, stateless JSON evidence contract used by production compact GraphRAG.
 *
 * <p>The output deliberately omits embeddings, raw document bodies, prompts/responses, generated
 * summaries, and opaque metadata blobs. It preserves graph facts, induced relations, explicit source
 * identifiers, and canonical reasoning steps. Ordering and fallback identifiers are deterministic so
 * the exact context can be retained as an audit artifact and compared across answer models.</p>
 */
@Service
public class CompactGraphContextService {

    static final String CONTRACT = "kompile.compact-graph.v1";
    static final String TRACE_DTO_ARTIFACT = "reasoning/traces.json";
    static final String PROCESS_SUGGESTIONS_ARTIFACT = "process/suggestions.json";

    private static final int MAX_NODES = 32;
    private static final int MAX_RELATIONS = 96;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_TRACES = 4;
    private static final int MAX_TRACE_STEPS = 16;
    private static final int MAX_TRACE_STEPS_PER_TRACE = 6;
    private static final int MAX_ATTRIBUTES = 12;
    private static final int MAX_COLLECTION_VALUES = 8;
    private static final int MAX_TEXT = 240;

    private static final TypeReference<List<Map<String, Object>>> TRACE_DTO_LIST =
            new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() { };

    private final UnifiedGraphBridge unifiedGraphBridge;
    private final ObjectMapper objectMapper;

    public CompactGraphContextService(UnifiedGraphBridge unifiedGraphBridge, ObjectMapper objectMapper) {
        this.unifiedGraphBridge = unifiedGraphBridge;
        this.objectMapper = objectMapper;
    }

    /** Build compact JSON for the requested fact-sheet scope and retrieved node identifiers. */
    public CompactContext build(Long factSheetId, Collection<String> retrievedNodeIds) {
        UnifiedGraph graph = unifiedGraphBridge.export(factSheetId);
        LinkedHashSet<String> selectedIds = selectedIds(graph, retrievedNodeIds);

        List<Map<String, Object>> traces = compactTraces(graph, selectedIds);
        Map<String, Map<String, Object>> sources = new TreeMap<>();
        List<Map<String, Object>> nodes = compactNodes(graph, selectedIds, traces, sources);
        List<Map<String, Object>> relations = compactRelations(graph, selectedIds, sources);
        addTraceSources(traces, sources);

        List<Map<String, Object>> boundedSources = sources.values().stream()
                .limit(MAX_SOURCES)
                .toList();

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("graphId", graph.graphId());
        scope.put("factSheetId", graph.factSheetId());
        scope.put("retrievedNodeIds", List.copyOf(selectedIds));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contract", CONTRACT);
        root.put("scope", scope);
        root.put("sources", boundedSources);
        root.put("nodes", nodes);
        root.put("relations", relations);
        root.put("reasoningTraces", traces);

        try {
            return new CompactContext(
                    objectMapper.writeValueAsString(root),
                    List.copyOf(selectedIds),
                    boundedSources.size(),
                    traces.size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize compact graph context", e);
        }
    }

    private static LinkedHashSet<String> selectedIds(
            UnifiedGraph graph, Collection<String> retrievedNodeIds) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (retrievedNodeIds != null) {
            retrievedNodeIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .filter(id -> graph.entity(id).isPresent())
                    .distinct()
                    .limit(MAX_NODES)
                    .forEach(result::add);
        }
        if (result.isEmpty()) {
            graph.entities().stream()
                    .sorted(Comparator.comparing(GraphEntity::id))
                    .limit(MAX_NODES)
                    .map(GraphEntity::id)
                    .forEach(result::add);
        }
        return result;
    }

    private List<Map<String, Object>> compactNodes(
            UnifiedGraph graph,
            Set<String> selectedIds,
            List<Map<String, Object>> traces,
            Map<String, Map<String, Object>> sources) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        graph.entities().stream()
                .filter(entity -> selectedIds.contains(entity.id()))
                .sorted(Comparator.comparing(GraphEntity::id))
                .limit(MAX_NODES)
                .forEach(entity -> {
                    Map<String, Object> nodeAttributes = expandedAttributes(entity.attributes());
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", entity.id());
                    node.put("type", bounded(entity.type()));
                    node.put("label", bounded(entity.label()));
                    node.put("confidence", finite(entity.confidence()));
                    if (entity.timestamp() != null) {
                        node.put("timestamp", entity.timestamp().toString());
                    }
                    Object description = nodeAttributes.get("description");
                    if (description != null) {
                        node.put("description", bounded(String.valueOf(description)));
                    }
                    List<String> sourceIds = sourceIds(nodeAttributes, entity.id(), entity.label(), sources);
                    if (!sourceIds.isEmpty()) {
                        node.put("sourceIds", sourceIds);
                    }
                    List<String> traceIds = traces.stream()
                            .filter(trace -> traceReferences(trace, entity.id(), entity.label()))
                            .map(trace -> String.valueOf(trace.get("id")))
                            .toList();
                    if (!traceIds.isEmpty()) {
                        node.put("reasoningTraceIds", traceIds);
                    }
                    Map<String, Object> attributes = new LinkedHashMap<>(safeAttributes(nodeAttributes));
                    // Description is promoted above; do not spend compact-context budget repeating it.
                    attributes.remove("description");
                    if (!attributes.isEmpty()) {
                        node.put("attributes", attributes);
                    }
                    nodes.add(node);
                });
        return List.copyOf(nodes);
    }

    private List<Map<String, Object>> compactRelations(
            UnifiedGraph graph,
            Set<String> selectedIds,
            Map<String, Map<String, Object>> sources) {
        List<Map<String, Object>> relations = new ArrayList<>();
        graph.relations().stream()
                .filter(relation -> selectedIds.contains(relation.sourceId())
                        && selectedIds.contains(relation.targetId()))
                .sorted(Comparator.comparing(GraphRelation::id))
                .limit(MAX_RELATIONS)
                .forEach(relation -> {
                    Map<String, Object> relationAttributes = expandedAttributes(relation.attributes());
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("id", relation.id());
                    edge.put("source", relation.sourceId());
                    edge.put("target", relation.targetId());
                    edge.put("type", bounded(relation.type()));
                    edge.put("weight", finite(relation.weight()));
                    edge.put("confidence", finite(relation.confidence()));
                    edge.put("directed", relation.directed());
                    List<String> sourceIds = sourceIds(
                            relationAttributes, relation.id(), relation.type(), sources);
                    if (!sourceIds.isEmpty()) {
                        edge.put("sourceIds", sourceIds);
                    }
                    Map<String, Object> attributes = safeAttributes(relationAttributes);
                    if (!attributes.isEmpty()) {
                        edge.put("attributes", attributes);
                    }
                    relations.add(edge);
                });
        return List.copyOf(relations);
    }

    /** Expand the bridge's edge metadata JSON into typed attributes, while never exposing raw JSON. */
    private Map<String, Object> expandedAttributes(Map<String, Object> attributes) {
        Map<String, Object> expanded = new LinkedHashMap<>();
        if (attributes != null) {
            expanded.putAll(attributes);
        }
        Object rawMetadata = expanded.remove("metadataJson");
        if (rawMetadata instanceof String json && !json.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(json, STRING_OBJECT_MAP);
                parsed.forEach((key, value) -> {
                    if (key != null && value != null) {
                        expanded.putIfAbsent(key, value);
                    }
                });
            } catch (Exception ignored) {
                // Opaque or malformed metadata is omitted rather than becoming model input.
            }
        }
        return expanded;
    }

    private List<Map<String, Object>> compactTraces(UnifiedGraph graph, Set<String> selectedIds) {
        List<Map<String, Object>> traces = new ArrayList<>();
        int[] remainingSteps = {MAX_TRACE_STEPS};
        Set<String> referenceTerms = selectedReferenceTerms(graph, selectedIds);
        Map<String, ProcessTraceScope> processScopes = processTraceScopes(graph, selectedIds);
        List<TraceCandidate> traceCandidates = new ArrayList<>();
        for (String name : graph.artifacts().keySet().stream()
                .filter(value -> value.startsWith("trace:"))
                .sorted().toList()) {
            try {
                Object model = graph.model(name);
                if (model instanceof ReasoningTrace trace) {
                    ProcessTraceScope scope = processScopes.getOrDefault(name, ProcessTraceScope.EMPTY);
                    int relevance = scope.relevance()
                            + traceRelevance(trace, referenceTerms);
                    traceCandidates.add(new TraceCandidate(name, trace, relevance,
                            scope.sourceNodeIds(), scope.sourceRelationIds()));
                }
            } catch (RuntimeException ignored) {
                // A trace-prefixed artifact with incompatible bytes is omitted, never exposed raw.
            }
        }
        boolean hasScopedTrace = traceCandidates.stream().anyMatch(candidate -> candidate.relevance() > 0);
        traceCandidates.sort(Comparator.<TraceCandidate>comparingInt(TraceCandidate::relevance)
                .reversed().thenComparing(TraceCandidate::name));
        for (TraceCandidate candidate : traceCandidates) {
            if (traces.size() >= MAX_TRACES || remainingSteps[0] <= 0) {
                break;
            }
            if (!hasScopedTrace || candidate.relevance() > 0) {
                traces.add(compactTrace(candidate, remainingSteps));
            }
        }

        byte[] traceDtos = graph.artifact(TRACE_DTO_ARTIFACT);
        if (traceDtos != null && traceDtos.length > 0
                && traces.size() < MAX_TRACES && remainingSteps[0] > 0) {
            try {
                List<Map<String, Object>> dtos = objectMapper.readValue(traceDtos, TRACE_DTO_LIST);
                List<TraceDtoCandidate> dtoCandidates = dtos.stream()
                        .filter(dto -> dto != null && !dto.isEmpty())
                        .map(dto -> new TraceDtoCandidate(dto,
                                referenceRelevance(canonical(dto), referenceTerms)))
                        .sorted(Comparator.<TraceDtoCandidate>comparingInt(TraceDtoCandidate::relevance)
                                .reversed().thenComparing(candidate -> canonical(candidate.dto())))
                        .toList();
                boolean hasScopedDto = dtoCandidates.stream().anyMatch(candidate -> candidate.relevance() > 0);
                for (TraceDtoCandidate candidate : dtoCandidates) {
                    if (traces.size() >= MAX_TRACES || remainingSteps[0] <= 0) {
                        break;
                    }
                    if (!hasScopedDto || candidate.relevance() > 0) {
                        traces.add(compactTraceDto(candidate.dto(), remainingSteps));
                    }
                }
            } catch (Exception ignored) {
                // Malformed optional trace DTOs do not prevent graph-only compact retrieval.
            }
        }
        traces.sort(Comparator.comparing(trace -> String.valueOf(trace.get("id"))));
        return List.copyOf(traces.stream().limit(MAX_TRACES).toList());
    }

    private Map<String, ProcessTraceScope> processTraceScopes(UnifiedGraph graph, Set<String> selectedIds) {
        byte[] suggestionsJson = graph.artifact(PROCESS_SUGGESTIONS_ARTIFACT);
        if (suggestionsJson == null || suggestionsJson.length == 0 || selectedIds.isEmpty()) {
            return Map.of();
        }
        Set<String> selectedRelationIds = graph.relations().stream()
                .filter(relation -> selectedIds.contains(relation.sourceId())
                        && selectedIds.contains(relation.targetId()))
                .map(GraphRelation::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        try {
            List<Map<String, Object>> suggestions = objectMapper.readValue(suggestionsJson, TRACE_DTO_LIST);
            Map<String, ProcessTraceScope> scopes = new LinkedHashMap<>();
            for (Map<String, Object> suggestion : suggestions) {
                String artifactName = firstString(suggestion, "reasoningTraceArtifactName");
                if (artifactName == null) {
                    String suggestionId = firstString(suggestion, "id");
                    if (suggestionId != null) {
                        artifactName = "trace:process:" + suggestionId;
                    }
                }
                if (artifactName == null) {
                    continue;
                }
                Set<String> nodeIds = intersection(
                        stringValues(suggestion.get("sourceGraphNodeIds")), selectedIds);
                Set<String> relationIds = intersection(
                        stringValues(suggestion.get("sourceGraphRelationIds")), selectedRelationIds);
                ProcessTraceScope scope = new ProcessTraceScope(nodeIds, relationIds);
                if (scope.relevance() > 0) {
                    scopes.merge(artifactName, scope, ProcessTraceScope::merge);
                }
            }
            return Map.copyOf(scopes);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Set<String> selectedReferenceTerms(UnifiedGraph graph, Set<String> selectedIds) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String id : selectedIds) {
            addReferenceTerm(terms, id);
            graph.entity(id).ifPresent(entity -> addReferenceTerm(terms, entity.label()));
        }
        return Set.copyOf(terms);
    }

    private static void addReferenceTerm(Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT).strip();
        if (normalized.length() >= 3) {
            terms.add(normalized);
        }
    }

    private static int traceRelevance(ReasoningTrace trace, Set<String> referenceTerms) {
        return traceStepRelevance(trace.conclusion(), referenceTerms,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static int traceStepRelevance(ReasoningTrace.Step step, Set<String> referenceTerms,
                                          Set<ReasoningTrace.Step> visited) {
        if (step == null || !visited.add(step)) {
            return 0;
        }
        String text = step.conclusion() + ' ' + step.operation() + ' '
                + String.valueOf(step.source()) + ' ' + canonical(step.meta());
        int score = referenceRelevance(text, referenceTerms);
        for (ReasoningTrace.Step premise : step.premises()) {
            score += traceStepRelevance(premise, referenceTerms, visited);
        }
        return score;
    }

    private static int referenceRelevance(String value, Set<String> referenceTerms) {
        String haystack = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return (int) referenceTerms.stream().filter(haystack::contains).count();
    }

    private static Set<String> stringValues(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, Object> compactTrace(
            TraceCandidate candidate, int[] remainingSteps) {
        String traceId = candidate.name();
        ReasoningTrace trace = candidate.trace();
        int traceBudget = Math.min(MAX_TRACE_STEPS_PER_TRACE, remainingSteps[0]);
        int[] traceSteps = {traceBudget};
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<ReasoningTrace.Step, String> ids = new java.util.IdentityHashMap<>();
        assignStepIds(traceId, trace.conclusion(), ids, new int[]{0}, traceSteps);
        appendSteps(trace.conclusion(), ids, steps, traceSteps);
        remainingSteps[0] -= traceBudget - traceSteps[0];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", bounded(traceId));
        result.put("rootStepId", ids.get(trace.conclusion()));
        if (!candidate.sourceNodeIds().isEmpty()) {
            result.put("sourceNodeIds", candidate.sourceNodeIds());
        }
        if (!candidate.sourceRelationIds().isEmpty()) {
            result.put("sourceRelationIds", candidate.sourceRelationIds());
        }
        result.put("steps", steps);
        return result;
    }

    private static void assignStepIds(
            String traceId,
            ReasoningTrace.Step step,
            Map<ReasoningTrace.Step, String> ids,
            int[] sequence,
            int[] remainingSteps) {
        if (step == null || ids.containsKey(step) || sequence[0] >= remainingSteps[0]) {
            return;
        }
        ids.put(step, bounded(traceId) + ":s" + sequence[0]++);
        for (ReasoningTrace.Step premise : step.premises()) {
            assignStepIds(traceId, premise, ids, sequence, remainingSteps);
        }
    }

    private static void appendSteps(
            ReasoningTrace.Step step,
            Map<ReasoningTrace.Step, String> ids,
            List<Map<String, Object>> steps,
            int[] remainingSteps) {
        if (step == null || !ids.containsKey(step) || remainingSteps[0] <= 0) {
            return;
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", ids.get(step));
        compact.put("kind", step.kind().name());
        compact.put("conclusion", bounded(step.conclusion()));
        if (!step.operation().isBlank()) {
            compact.put("operation", bounded(step.operation()));
        }
        compact.put("confidence", finite(step.confidence()));
        if (step.source() != null && !step.source().isBlank()) {
            compact.put("sourceId", bounded(step.source()));
        }
        List<String> premiseIds = step.premises().stream()
                .map(ids::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!premiseIds.isEmpty()) {
            compact.put("premiseIds", premiseIds);
        }
        if (step.opinion() != null) {
            compact.put("opinion", Map.of(
                    "belief", finite(step.opinion().belief()),
                    "disbelief", finite(step.opinion().disbelief()),
                    "uncertainty", finite(step.opinion().uncertainty()),
                    "baseRate", finite(step.opinion().baseRate())));
        }
        Map<String, Object> meta = safeAttributes(new LinkedHashMap<>(step.meta()));
        if (!meta.isEmpty()) {
            compact.put("meta", meta);
        }
        steps.add(compact);
        remainingSteps[0]--;
        for (ReasoningTrace.Step premise : step.premises()) {
            appendSteps(premise, ids, steps, remainingSteps);
        }
    }

    private Map<String, Object> compactTraceDto(Map<String, Object> dto, int[] remainingSteps) {
        int traceBudget = Math.min(MAX_TRACE_STEPS_PER_TRACE, remainingSteps[0]);
        int[] traceSteps = {traceBudget};
        Map<String, Object> seed = new TreeMap<>();
        copyIfSafe(dto, seed, "targetId", "confidence", "inferenceMode", "evidence", "activatedRules",
                "breakdown", "attributionIndex", "traceGaps", "computedAt");
        String traceId = firstString(dto, "traceId", "id");
        if (traceId == null) {
            String target = firstString(dto, "targetId");
            traceId = "trace:dto:" + hash(target + ':' + canonical(seed)).substring(0, 16);
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        Object derivation = dto.get("derivationTree");
        String rootStepId = null;
        if (derivation instanceof Map<?, ?> root && traceSteps[0] > 0) {
            rootStepId = appendDtoStep(traceId, root, steps, traceSteps, new int[]{0});
        }
        remainingSteps[0] -= traceBudget - traceSteps[0];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", bounded(traceId));
        if (rootStepId != null) {
            result.put("rootStepId", rootStepId);
        }
        Object target = safeValue(dto.get("targetId"), 0);
        if (target != null) {
            result.put("target", target);
        }
        Object confidence = safeValue(dto.get("confidence"), 0);
        if (confidence != null) {
            result.put("confidence", confidence);
        }
        Object mode = safeValue(dto.get("inferenceMode"), 0);
        if (mode != null) {
            result.put("inferenceMode", mode);
        }
        for (String key : List.of("evidence", "activatedRules", "breakdown", "attributionIndex", "traceGaps")) {
            Object value = safeValue(dto.get(key), 0);
            if (value != null) {
                result.put(key, value);
            }
        }
        if (!steps.isEmpty()) {
            result.put("steps", steps);
        }
        return result;
    }

    private static String appendDtoStep(
            String traceId,
            Map<?, ?> node,
            List<Map<String, Object>> steps,
            int[] remainingSteps,
            int[] sequence) {
        if (remainingSteps[0] <= 0) {
            return null;
        }
        remainingSteps[0]--;
        String explicit = valueString(node.get("stepId"));
        String id = bounded(traceId) + ':' + (explicit == null ? "s" + sequence[0] : bounded(explicit));
        sequence[0]++;

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        putSafe(step, "conclusion", node.get("atom"));
        putSafe(step, "operation", node.get("rule"));
        putSafe(step, "confidence", node.get("confidence"));
        Object source = safeValue(node.get("source"), 0);
        if (source != null) {
            step.put("sourceId", source);
        }
        steps.add(step);

        List<String> premiseIds = new ArrayList<>();
        Object children = node.get("children");
        if (children instanceof Collection<?> collection) {
            for (Object child : collection) {
                if (child instanceof Map<?, ?> childMap && remainingSteps[0] > 0) {
                    String premiseId = appendDtoStep(traceId, childMap, steps, remainingSteps, sequence);
                    if (premiseId != null) {
                        premiseIds.add(premiseId);
                    }
                }
            }
        }

        if (!premiseIds.isEmpty()) {
            step.put("premiseIds", premiseIds);
        }
        return id;
    }

    private static List<String> sourceIds(
            Map<String, Object> attributes,
            String fallbackId,
            String fallbackName,
            Map<String, Map<String, Object>> sources) {
        if (attributes == null) {
            attributes = Map.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addSource(attributes, fallbackId, fallbackName, sources, result);

        Object supportingEvidence = attributes.get("supportingEvidence");
        if (supportingEvidence instanceof Collection<?> records) {
            for (Object value : records) {
                if (value instanceof Map<?, ?> record) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    record.forEach((key, item) -> normalized.put(String.valueOf(key), item));
                    addSource(normalized, fallbackId, fallbackName, sources, result);
                }
            }
        }
        Object sourceChunkIds = attributes.get("sourceChunkIds");
        if (sourceChunkIds instanceof Collection<?> chunks) {
            for (Object value : chunks) {
                if (value != null) {
                    addSource(Map.of("sourceChunkId", value), fallbackId, fallbackName, sources, result);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addSource(Map<String, ?> attributes, String fallbackId, String fallbackName,
                                  Map<String, Map<String, Object>> sources,
                                  Set<String> result) {
        String documentId = firstString(attributes, GraphProvenanceKeys.SOURCE_DOCUMENT_ID,
                "sourceDocumentId", "source_document_id", "source_id", "sourceId");
        String chunkId = firstString(attributes, GraphProvenanceKeys.SOURCE_CHUNK_ID,
                "sourceChunkId", "source_chunk_id", "chunk_id", "chunkId");
        String coarseSource = firstString(attributes, GraphProvenanceKeys.SOURCE, "source");
        if (documentId == null && chunkId == null && coarseSource == null) {
            return;
        }
        String sourceId = documentId != null ? documentId
                : (coarseSource != null ? coarseSource : (chunkId != null ? chunkId : fallbackId));
        String compactId = chunkId == null || chunkId.equals(sourceId)
                ? bounded(sourceId) : bounded(sourceId + "#" + chunkId);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", compactId);
        source.put("documentId", bounded(documentId));
        source.put("chunkId", bounded(chunkId));
        source.put("kind", bounded(coarseSource));
        source.put("name", bounded(firstString(attributes, "source_filename", "title", "name")));
        if (source.get("name") == null) {
            source.put("name", bounded(fallbackName));
        }
        source.put("crawlRunId", bounded(firstString(attributes, GraphProvenanceKeys.CRAWL_RUN_ID)));
        source.put("extractionModel", bounded(firstString(attributes, GraphProvenanceKeys.EXTRACTION_MODEL)));
        source.put("extractionLogId", bounded(firstString(attributes, GraphProvenanceKeys.EXTRACTION_LOG_ID)));
        source.put("url", bounded(firstString(attributes, "source_url", "source_path", "url", "path")));
        String evidenceQuote = bounded(firstString(attributes, "evidenceQuote", "evidence_quote", "quote"));
        if (evidenceQuote != null) {
            source.put("evidenceQuotes", List.of(evidenceQuote));
        }
        source.values().removeIf(java.util.Objects::isNull);
        sources.merge(compactId, source, CompactGraphContextService::mergeSource);
        result.add(compactId);
    }

    private static Map<String, Object> mergeSource(Map<String, Object> existing,
                                                   Map<String, Object> incoming) {
        incoming.forEach(existing::putIfAbsent);
        LinkedHashSet<String> quotes = new LinkedHashSet<>(stringValues(existing.get("evidenceQuotes")));
        quotes.addAll(stringValues(incoming.get("evidenceQuotes")));
        if (!quotes.isEmpty()) {
            existing.put("evidenceQuotes", List.copyOf(quotes.stream()
                    .limit(MAX_COLLECTION_VALUES).toList()));
        }
        return existing;
    }

    private static void addTraceSources(
            List<Map<String, Object>> traces, Map<String, Map<String, Object>> sources) {
        for (Map<String, Object> trace : traces) {
            Object steps = trace.get("steps");
            if (!(steps instanceof Collection<?> collection)) {
                continue;
            }
            for (Object value : collection) {
                if (!(value instanceof Map<?, ?> step)) {
                    continue;
                }
                String sourceId = valueString(step.get("sourceId"));
                if (sourceId != null && !sourceId.isBlank()) {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("id", bounded(sourceId));
                    source.put("kind", "reasoning-step");
                    sources.putIfAbsent(bounded(sourceId), source);
                }
            }
        }
    }

    private static boolean traceReferences(Map<String, Object> trace, String nodeId, String label) {
        String haystack = canonical(trace).toLowerCase(Locale.ROOT);
        return (nodeId != null && haystack.contains(nodeId.toLowerCase(Locale.ROOT)))
                || (label != null && !label.isBlank()
                && haystack.contains(label.toLowerCase(Locale.ROOT)));
    }

    private static Map<String, Object> safeAttributes(Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        attributes.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && !dangerousKey(entry.getKey())
                        && !sourceProjectionKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_ATTRIBUTES)
                .forEach(entry -> {
                    Object value = safeValue(entry.getValue(), 0);
                    if (value != null) {
                        safe.put(entry.getKey(), value);
                    }
                });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(safe));
    }

    private static Object safeValue(Object value, int depth) {
        if (value == null || depth > 2) {
            return null;
        }
        if (value instanceof String text) {
            return bounded(text);
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) ? number : null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> safe = new ArrayList<>();
            for (Object item : collection) {
                Object compact = safeValue(item, depth + 1);
                if (compact != null) {
                    safe.add(compact);
                }
                if (safe.size() >= MAX_COLLECTION_VALUES) {
                    break;
                }
            }
            return safe.isEmpty() ? null : List.copyOf(safe);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null
                            && !dangerousKey(String.valueOf(entry.getKey())))
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .limit(MAX_COLLECTION_VALUES)
                    .forEach(entry -> {
                        Object compact = safeValue(entry.getValue(), depth + 1);
                        if (compact != null) {
                            safe.put(String.valueOf(entry.getKey()), compact);
                        }
                    });
            return safe.isEmpty() ? null
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(safe));
        }
        return null;
    }

    private static boolean dangerousKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("embedding")
                || normalized.contains("vector")
                || normalized.contains("content")
                || normalized.contains("preview")
                || normalized.contains("prompt")
                || normalized.contains("response")
                || normalized.contains("llmcontext")
                || normalized.contains("naturallanguagesummary")
                || normalized.contains("metadatajson")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("authorization")
                || normalized.contains("token");
    }

    /** Source lineage is represented once in {@code sources} and referenced by {@code sourceIds}. */
    private static boolean sourceProjectionKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (normalized) {
            case "source", "sourceid", "sourcedocumentid", "sourcechunkid", "sourcechunkids",
                    "supportingevidence", "supportingcount", "evidencequote", "evidencequotes",
                    "crawlrunid", "extractionmodel", "extractionlogid", "sourcefilename",
                    "sourceurl", "sourcepath" -> true;
            default -> false;
        };
    }

    private static void copyIfSafe(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = safeValue(source.get(key), 0);
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static void putSafe(Map<String, Object> target, String key, Object value) {
        Object safe = safeValue(value, 0);
        if (safe != null) {
            target.put(key, safe);
        }
    }

    private static String firstString(Map<String, ?> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            String value = valueString(source.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String valueString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= MAX_TEXT ? normalized : normalized.substring(0, MAX_TEXT);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> result.append(entry.getKey()).append('=')
                            .append(canonical(entry.getValue())).append(';'));
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : collection) {
                result.append(canonical(item)).append(';');
            }
            return result.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    public record CompactContext(
            String json,
            List<String> retrievedNodeIds,
            int sourceCount,
            int traceCount) {
    }

    private record TraceCandidate(String name, ReasoningTrace trace, int relevance,
                                  Set<String> sourceNodeIds, Set<String> sourceRelationIds) {
    }

    private record TraceDtoCandidate(Map<String, Object> dto, int relevance) {
    }

    private record ProcessTraceScope(Set<String> sourceNodeIds, Set<String> sourceRelationIds) {
        private static final ProcessTraceScope EMPTY = new ProcessTraceScope(Set.of(), Set.of());

        private ProcessTraceScope {
            sourceNodeIds = immutableSortedSet(sourceNodeIds);
            sourceRelationIds = immutableSortedSet(sourceRelationIds);
        }

        int relevance() {
            return sourceNodeIds.size() * 100 + sourceRelationIds.size() * 50;
        }

        ProcessTraceScope merge(ProcessTraceScope other) {
            LinkedHashSet<String> nodes = new LinkedHashSet<>(sourceNodeIds);
            nodes.addAll(other.sourceNodeIds);
            LinkedHashSet<String> relations = new LinkedHashSet<>(sourceRelationIds);
            relations.addAll(other.sourceRelationIds);
            return new ProcessTraceScope(nodes, relations);
        }

        private static Set<String> immutableSortedSet(Set<String> values) {
            LinkedHashSet<String> sorted = values.stream().sorted()
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return java.util.Collections.unmodifiableSet(sorted);
        }
    }
}
