/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessUnifiedGraphArtifacts;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.entail.ProcessEntailment;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import ai.kompile.process.discovery.mining.entail.ProcessHybridActivation;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;
import ai.kompile.process.discovery.mining.extract.ReasoningGraphEventLogExtractor;
import ai.kompile.process.discovery.mining.extract.RelationActivityClassifier;
import ai.kompile.process.discovery.mining.extract.TraceClusterer;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceBuilder;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.discovery.mining.tree.ProcessTree;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Store-agnostic business-process generation over a {@link ReasoningGraph}.
 *
 * <p>The persisted-graph discovery service starts from {@code GraphNode}/{@code GraphEdge}. This
 * facade exposes the same mining and reasoning stack directly to any reasoning graph, including a
 * loaded {@code .kgraph}. It deliberately creates more than one evidence view:</p>
 *
 * <ul>
 *   <li>activity-flow views interpret precedence-like relations as ordering between endpoint
 *       activities and enumerate bounded root-to-leaf traces from each graph component;</li>
 *   <li>relation-event views preserve the crawler convention that an observed relation is itself an
 *       event, then cluster cases by activity overlap before mining;</li>
 *   <li>semantic-fusion views connect independent cases through compatible graph embeddings while
 *       retaining only relations that touch the corresponding semantic anchors;</li>
 *   <li>every candidate is mined, entailed, activated through {@code HybridReasoner}, ranked, and
 *       accompanied by a canonical {@link ReasoningTrace}.</li>
 * </ul>
 *
 * <p>No process names, entity ids, or domain vocabulary are required. Optional process names and
 * case ids are consumed only when they already exist as graph metadata.</p>
 */
public final class ReasoningGraphProcessGenerator {

    private static final List<String> PROCESS_NAME_KEYS = List.of(
            "processName", "process_name", "workflowName", "workflow_name", "businessProcessName");
    private static final List<String> CASE_KEYS = List.of(
            "caseId", "case_id", "traceId", "trace_id", "processInstanceId", "process_instance_id",
            "workflowId", "workflow_id", "threadId", "thread_id");
    private static final Set<String> STRUCTURAL_TYPES = ActivityClassifier.STRUCTURAL_NON_ACTIVITY_TYPES;
    private static final int MAX_EVENT_ATTRIBUTES = 32;

    private ReasoningGraphProcessGenerator() {
    }

    public enum Projection {
        ACTIVITY_FLOW,
        RELATION_EVENTS
    }

    /**
     * Generator controls. Relation families are intentionally generic and replaceable; callers can
     * add ontology-specific relation aliases without changing the generator.
     */
    public record Options(int maxCandidates,
                          int maxPathsPerComponent,
                          int minActivities,
                          double noiseThreshold,
                          double clusterJaccardThreshold,
                          double semanticWeight,
                          double semanticFusionThreshold,
                          Map<String, Set<String>> flowRelationFamilies,
                          Map<String, Set<String>> eventRelationFamilies) {

        public Options {
            maxCandidates = Math.max(1, maxCandidates);
            maxPathsPerComponent = Math.max(1, maxPathsPerComponent);
            minActivities = Math.max(2, minActivities);
            noiseThreshold = Math.max(0.0, Math.min(1.0, noiseThreshold));
            clusterJaccardThreshold = Math.max(0.01, Math.min(1.0, clusterJaccardThreshold));
            semanticWeight = Math.max(0.0, semanticWeight);
            semanticFusionThreshold = Math.max(0.0, Math.min(1.0, semanticFusionThreshold));
            flowRelationFamilies = normalizedFamilies(flowRelationFamilies);
            eventRelationFamilies = normalizedFamilies(eventRelationFamilies);
        }

        public static Options defaults() {
            Map<String, Set<String>> flows = new LinkedHashMap<>();
            flows.put("flow", Set.of(
                    "FEEDS_INTO", "DIRECTLY_FOLLOWS", "PRECEDES", "NEXT", "FOLLOWED_BY",
                    "LEADS_TO", "THEN"));
            flows.put("control", Set.of(
                    "VALIDATES", "CHECKS", "VERIFIES", "RECONCILES", "GOVERNS"));
            flows.put("exception", Set.of(
                    "TRIGGERS", "APPLIES_ADJUSTMENT", "REMEDIATES", "ROUTES_TO"));

            Map<String, Set<String>> events = new LinkedHashMap<>();
            events.put("communication", Set.of(
                    "SENT_BY", "SENT_TO", "CC_TO", "BCC_TO", "REPLIED_TO", "HAS_ATTACHMENT",
                    "SUBMITTED_BY", "SUBMITS", "SUBMITS_FORECAST", "PERSON_SENT_EMAIL",
                    "SENT_EMAIL_WITH_ATTACHMENT", "RECEIVED_BY"));
            events.put("governance", Set.of(
                    "VALIDATES", "CHECKS", "VERIFIES", "RECONCILES", "APPROVED_BY", "APPROVES",
                    "SIGNED_OFF_BY", "ASSIGNED_TO", "PERFORMED_BY"));
            events.put("exception", Set.of(
                    "TRIGGERS", "ESCALATED_TO", "APPLIES_ADJUSTMENT", "REMEDIATES",
                    "ROUTES_TO", "ASSIGNED_TO"));
            events.put("delivery", Set.of(
                    "SOURCE_OF", "PRODUCES", "PUBLISHES", "ARCHIVES", "IMPORTS", "EXPORTS",
                    "LOADS", "TRANSFORMS"));
            events.put("retention", Set.of(
                    "CONTAINS", "ARCHIVES", "RETAINS", "REDACTS", "ENFORCES", "GOVERNS",
                    "OBJECT_LOCKS"));
            return new Options(48, 64, 2, 0.0, 0.30, 0.25, 0.86, flows, events);
        }

        private static Map<String, Set<String>> normalizedFamilies(Map<String, Set<String>> source) {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            if (source == null) {
                return Map.of();
            }
            for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                Set<String> types = new LinkedHashSet<>();
                if (entry.getValue() != null) {
                    for (String type : entry.getValue()) {
                        if (type != null && !type.isBlank()) {
                            types.add(normalize(type));
                        }
                    }
                }
                if (!types.isEmpty()) {
                    out.put(entry.getKey().trim().toLowerCase(Locale.ROOT), Set.copyOf(types));
                }
            }
            return Collections.unmodifiableMap(out);
        }
    }

    public record Candidate(String id,
                            int rank,
                            Projection projection,
                            String family,
                            double score,
                            EventLog eventLog,
                            DirectlyFollowsGraph dfg,
                            ProcessEntailmentResult entailment,
                            ProcessHybridActivation.Result hybridActivation,
                            ProcessTree processTree,
                            ProcessSuggestion suggestion,
                            ReasoningTrace reasoningTrace,
                            List<String> evidenceEntityIds,
                            List<String> evidenceRelationIds) {

        public int activityCount() {
            return eventLog.activityNames().size();
        }

        public int traceCount() {
            return eventLog.size();
        }

        public int entailedOnlyCount() {
            return entailment.entailedOnly().size();
        }
    }

    public record Result(List<Candidate> candidates,
                         int projectionCount,
                         int rejectedProjectionCount,
                         int graphEntityCount,
                         int graphRelationCount) {

        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public List<ProcessSuggestion> suggestions() {
            return candidates.stream().map(Candidate::suggestion).toList();
        }

        /**
         * Store ranked suggestions and one trace model per suggestion in a portable graph archive.
         */
        public void putArtifacts(UnifiedGraph graph) {
            if (graph == null || candidates.isEmpty()) {
                return;
            }
            ProcessUnifiedGraphArtifacts.putSuggestions(graph, suggestions());
            for (Candidate candidate : candidates) {
                ProcessUnifiedGraphArtifacts.putTrace(
                        graph, candidate.suggestion().getId(), candidate.reasoningTrace());
            }
        }
    }

    public static Result generate(ReasoningGraph graph) {
        return generate(graph, graph, Options.defaults());
    }

    public static Result generate(ReasoningGraph graph, Options options) {
        return generate(graph, graph, options);
    }

    /**
     * Generate projections from {@code graph} while resolving activity embeddings from a separate
     * scoring view of the same topology. This lets callers use a learned vector layer for hybrid
     * scoring without allowing that layer to redefine which crawl-observed semantic projections
     * exist. Entity and relation ids in the scoring graph should match the source graph.
     */
    public static Result generate(ReasoningGraph graph, ReasoningGraph semanticScoringGraph) {
        return generate(graph, semanticScoringGraph, Options.defaults());
    }

    public static Result generate(ReasoningGraph graph,
                                  ReasoningGraph semanticScoringGraph,
                                  Options options) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(semanticScoringGraph, "semanticScoringGraph");
        Options effective = options == null ? Options.defaults() : options;
        if (graph.isEmpty()) {
            return new Result(List.of(), 0, 0, 0, graph.relationCount());
        }

        Map<String, GraphEntity> entities = entitiesById(graph);
        Map<String, GraphRelation> relations = relationsById(graph);
        List<ProjectionInput> inputs = new ArrayList<>();
        inputs.addAll(activityFlowInputs(graph, entities, effective));
        inputs.addAll(relationEventInputs(graph, entities, effective));
        inputs.addAll(caseCorrelatedInputs(graph, entities, effective));
        inputs.addAll(semanticFusionInputs(inputs, entities, relations, effective));

        List<CandidateDraft> drafts = new ArrayList<>();
        int rejected = 0;
        for (ProjectionInput input : inputs) {
            CandidateDraft draft = mine(input, semanticScoringGraph,
                    entities, relations, effective);
            if (draft == null) {
                rejected++;
            } else {
                drafts.add(draft);
            }
        }

        List<CandidateDraft> deduplicated = suppressSubsumed(deduplicate(drafts));
        deduplicated.sort(Comparator.comparingDouble(CandidateDraft::score).reversed()
                .thenComparing(Comparator.comparingInt(CandidateDraft::activityCount).reversed())
                .thenComparing(d -> d.suggestion().getName())
                .thenComparing(CandidateDraft::id));
        if (deduplicated.size() > effective.maxCandidates()) {
            deduplicated = new ArrayList<>(deduplicated.subList(0, effective.maxCandidates()));
        }

        List<Candidate> ranked = new ArrayList<>(deduplicated.size());
        for (int i = 0; i < deduplicated.size(); i++) {
            CandidateDraft draft = deduplicated.get(i);
            draft.suggestion().setReasoningRank(i + 1);
            ranked.add(new Candidate(
                    draft.id(), i + 1, draft.projection(), draft.family(), draft.score(),
                    draft.eventLog(), draft.dfg(), draft.entailment(), draft.hybridActivation(),
                    draft.processTree(), draft.suggestion(), draft.reasoningTrace(),
                    draft.evidenceEntityIds(), draft.evidenceRelationIds()));
        }
        return new Result(ranked, inputs.size(), rejected, graph.entityCount(), graph.relationCount());
    }

    private static CandidateDraft mine(ProjectionInput input,
                                       ReasoningGraph graph,
                                       Map<String, GraphEntity> entities,
                                       Map<String, GraphRelation> relations,
                                       Options options) {
        EventLog eventLog = input.eventLog();
        if (eventLog == null || eventLog.isEmpty()
                || eventLog.activityNames().size() < options.minActivities()) {
            return null;
        }

        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        if (dfg.activities().size() < options.minActivities() || dfg.arcs().isEmpty()) {
            return null;
        }

        ProcessEntailmentResult entailment = ProcessEntailment.entail(
                eventLog, dfg, List.<DeclareConstraint>of());
        ActivityEmbeddingResolution embeddings = activityEmbeddings(eventLog, graph);
        ProcessHybridActivation.Result hybrid = ProcessHybridActivation.activate(
                eventLog, dfg, entailment, embeddings.vectors(), options.semanticWeight());
        ProcessTree tree = new InductiveMiner(options.noiseThreshold()).mine(eventLog);
        String id = "graph-process-" + sanitize(input.key());
        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, eventLog, input.processName());
        suggestion.setId(id);
        suggestion.setProcessKey(id);
        suggestion.setDiscoverySource("REASONING_GRAPH_PROCESS_MINING");
        suggestion.setDiscoveredAt(Instant.now());
        suggestion.setReasoningProjection(input.projection().name());
        suggestion.setReasoningFamily(input.family());
        suggestion.setHybridScore(hybrid.isEmpty() ? null : hybrid.meanHybrid());
        ProcessSuggestion.HybridReasoningDetails hybridDetails =
                ProcessHybridActivation.toSuggestionDetails(hybrid);
        if (hybridDetails != null) {
            hybridDetails.setEmbeddingSource(embeddings.source());
            hybridDetails.setDirectlyEmbeddedActivityCount(embeddings.directCount());
            hybridDetails.setInferredEmbeddingActivityCount(embeddings.inferredCount());
        }
        suggestion.setHybridReasoning(hybridDetails);
        suggestion.setEntailmentScore(entailment.isEmpty() ? null : entailment.fusedOpinion().expectation());
        suggestion.setProcessCaseCount(eventLog.size());
        suggestion.setProcessActivityCount(eventLog.activityNames().size());
        suggestion.setDirectlyFollowsCount(dfg.arcs().size());
        suggestion.setAcceptedPrecedenceCount(entailment.accepted().size());
        suggestion.setEntailedOnlyPrecedenceCount(entailment.entailedOnly().size());
        suggestion.setSourceGraphNodeIds(new ArrayList<>(input.entityIds()));
        suggestion.setSourceGraphRelationIds(new ArrayList<>(input.relationIds()));

        List<String> evidence = new ArrayList<>();
        evidence.add("Graph-only " + input.projection().name().toLowerCase(Locale.ROOT)
                + " projection over relation family " + input.family());
        evidence.add(eventLog.size() + " trace(s), " + eventLog.activityNames().size()
                + " activity label(s), " + dfg.arcs().size() + " directly-follows arc(s)");
        evidence.add(entailment.accepted().size() + " accepted precedence pair(s), "
                + entailment.entailedOnly().size() + " entailed-only pair(s)");
        evidence.add(embeddings.vectors().size() + " activity embedding(s): "
                + embeddings.directCount() + " direct, " + embeddings.inferredCount()
                + " graph-resolved");
        evidence.add("Evidence relations: " + String.join(", ", input.relationIds()));
        suggestion.setEvidence(evidence);

        double relationConfidence = input.relationIds().stream()
                .map(relations::get)
                .filter(Objects::nonNull)
                .mapToDouble(GraphRelation::confidence)
                .average()
                .orElse(0.5);
        double score = rankScore(suggestion, eventLog, dfg, entailment, hybrid, relationConfidence);
        suggestion.setConfidence(score);
        suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(id));
        suggestion.setReasoningTraceArtifactName(ProcessUnifiedGraphArtifacts.traceArtifactName(id));

        ProcessCausalAnalyzer.ProcessCausalModel causal = ProcessCausalAnalyzer.analyze(dfg);
        ReasoningTrace trace = ProcessReasoningTraceBuilder.build(
                suggestion, eventLog, dfg, entailment, causal, hybrid, null, tree);
        return new CandidateDraft(id, input.projection(), input.family(), score, eventLog, dfg,
                entailment, hybrid, tree, suggestion, trace,
                List.copyOf(input.entityIds()), List.copyOf(input.relationIds()));
    }

    private static double rankScore(ProcessSuggestion suggestion,
                                    EventLog log,
                                    DirectlyFollowsGraph dfg,
                                    ProcessEntailmentResult entailment,
                                    ProcessHybridActivation.Result hybrid,
                                    double relationConfidence) {
        double conformance = suggestion.getRawConformanceScore() == null
                ? suggestion.getConfidence() : suggestion.getRawConformanceScore();
        double entailmentScore = entailment.isEmpty()
                ? 0.0 : entailment.fusedOpinion().expectation();
        double activitySupport = Math.min(1.0, dfg.activities().size() / 8.0);
        double traceSupport = Math.min(1.0, Math.log1p(log.size()) / Math.log(8.0));
        double score = 0.30 * clamp(conformance)
                + 0.24 * clamp(entailmentScore)
                + 0.20 * clamp(hybrid.meanHybrid())
                + 0.14 * clamp(relationConfidence)
                + 0.07 * activitySupport
                + 0.05 * traceSupport;
        return clamp(score);
    }

    private static List<ProjectionInput> activityFlowInputs(ReasoningGraph graph,
                                                            Map<String, GraphEntity> entities,
                                                            Options options) {
        List<ProjectionInput> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> family : options.flowRelationFamilies().entrySet()) {
            List<GraphRelation> eligible = graph.relations().stream()
                    .filter(relation -> family.getValue().contains(effectiveType(relation)))
                    .filter(relation -> isActivityEntity(entities.get(relation.sourceId())))
                    .filter(relation -> isActivityEntity(entities.get(relation.targetId())))
                    .sorted(Comparator.comparing(GraphRelation::id))
                    .toList();
            if (eligible.isEmpty()) {
                continue;
            }

            Map<String, List<GraphRelation>> components = relationComponents(eligible);
            List<ProjectionInput> componentInputs = new ArrayList<>();
            Map<String, List<ProjectionInput>> aggregateGroups = new TreeMap<>();
            int componentOrdinal = 0;
            for (List<GraphRelation> componentRelations : components.values()) {
                componentOrdinal++;
                ProjectionInput input = flowInput(
                        family.getKey() + "-component-" + componentOrdinal,
                        family.getKey(), componentRelations, entities, options.maxPathsPerComponent());
                if (input != null) {
                    componentInputs.add(input);
                    String signature = aggregationSignature(componentRelations, entities);
                    aggregateGroups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(input);
                }
            }
            out.addAll(componentInputs);

            // Parallel suites are useful, but unrelated controls from different cases or source
            // types must not collapse into one catch-all candidate. Aggregate only compatible
            // disconnected components; retain each component for drill-down.
            if (!"flow".equals(family.getKey())) {
                for (Map.Entry<String, List<ProjectionInput>> group : aggregateGroups.entrySet()) {
                    List<ProjectionInput> compatible = group.getValue();
                    if (compatible.size() < 2) {
                        continue;
                    }
                    List<Trace> traces = compatible.stream()
                            .flatMap(input -> input.eventLog().traces().stream())
                            .toList();
                    Set<String> entityIds = new LinkedHashSet<>();
                    Set<String> relationIds = new LinkedHashSet<>();
                    compatible.forEach(input -> {
                        entityIds.addAll(input.entityIds());
                        relationIds.addAll(input.relationIds());
                    });
                    EventLog aggregate = new EventLog(traces);
                    if (aggregate.activityNames().size() >= options.minActivities()) {
                        String processName = commonProcessName(entityIds, entities);
                        if (processName == null) {
                            processName = genericProcessName(family.getKey());
                        }
                        out.add(new ProjectionInput(
                                family.getKey() + "-aggregate-" + sanitize(group.getKey()),
                                Projection.ACTIVITY_FLOW, family.getKey(), aggregate, processName,
                                List.copyOf(entityIds), List.copyOf(relationIds)));
                    }
                }
            }
        }
        return out;
    }

    private static String aggregationSignature(List<GraphRelation> relations,
                                               Map<String, GraphEntity> entities) {
        Set<String> cases = new TreeSet<>();
        Set<String> sourceTypes = new TreeSet<>();
        for (GraphRelation relation : relations) {
            String caseId = firstString(relation.attributes(), CASE_KEYS);
            if (caseId != null) {
                cases.add(normalize(caseId));
            }
            GraphEntity source = entities.get(relation.sourceId());
            String sourceType = bestType(source);
            sourceTypes.add(normalize(sourceType == null ? "UNKNOWN" : sourceType));
        }
        String caseSignature = cases.isEmpty() ? "UNCASED" : String.join("+", cases);
        return "CASE=" + caseSignature + ";SOURCE=" + String.join("+", sourceTypes);
    }

    private static ProjectionInput flowInput(String key,
                                             String family,
                                             List<GraphRelation> relations,
                                             Map<String, GraphEntity> entities,
                                             int maxPaths) {
        Set<String> nodeIds = new TreeSet<>();
        relations.forEach(relation -> {
            nodeIds.add(relation.sourceId());
            nodeIds.add(relation.targetId());
        });
        Map<String, List<String>> outgoing = new TreeMap<>();
        Map<String, Integer> indegree = new TreeMap<>();
        for (String nodeId : nodeIds) {
            outgoing.put(nodeId, new ArrayList<>());
            indegree.put(nodeId, 0);
        }
        for (GraphRelation relation : relations) {
            outgoing.get(relation.sourceId()).add(relation.targetId());
            indegree.merge(relation.targetId(), 1, Integer::sum);
        }
        outgoing.values().forEach(list -> list.sort(String::compareTo));

        List<String> roots = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (roots.isEmpty()) {
            roots = nodeIds.stream().sorted().toList();
        }

        List<List<String>> paths = new ArrayList<>();
        for (String root : roots) {
            enumeratePaths(root, outgoing, new ArrayList<>(), new LinkedHashSet<>(), paths, maxPaths);
            if (paths.size() >= maxPaths) {
                break;
            }
        }
        if (paths.isEmpty()) {
            return null;
        }

        List<Trace> traces = new ArrayList<>();
        Set<String> usedEntityIds = new LinkedHashSet<>();
        int pathOrdinal = 0;
        for (List<String> path : paths) {
            if (path.size() < 2) {
                continue;
            }
            pathOrdinal++;
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < path.size(); i++) {
                GraphEntity entity = entities.get(path.get(i));
                if (entity == null) {
                    continue;
                }
                usedEntityIds.add(entity.id());
                Map<String, Object> attributes = entityAttributes(entity);
                attributes.put("eventProjection", "ACTIVITY_FLOW");
                attributes.put("pathPosition", i);
                attributes.put("relationFamily", family);
                events.add(new Event(
                        "graph-flow:" + sanitize(key) + ":" + pathOrdinal,
                        activityLabel(entity),
                        timestamp(entity),
                        entity.id(),
                        attributes));
            }
            if (events.size() >= 2) {
                traces.add(new Trace("graph-flow:" + sanitize(key) + ":" + pathOrdinal, events));
            }
        }
        if (traces.isEmpty()) {
            return null;
        }

        String processName = "flow".equals(family)
                ? commonProcessName(usedEntityIds, entities)
                : null;
        if (processName == null) {
            processName = genericProcessName(family);
        }
        return new ProjectionInput(
                key, Projection.ACTIVITY_FLOW, family, new EventLog(traces), processName,
                List.copyOf(usedEntityIds), relations.stream().map(GraphRelation::id).toList());
    }

    private static void enumeratePaths(String current,
                                       Map<String, List<String>> outgoing,
                                       List<String> path,
                                       Set<String> visiting,
                                       List<List<String>> paths,
                                       int maxPaths) {
        if (paths.size() >= maxPaths || !visiting.add(current)) {
            return;
        }
        path.add(current);
        boolean advanced = false;
        for (String next : outgoing.getOrDefault(current, List.of())) {
            if (visiting.contains(next)) {
                continue;
            }
            advanced = true;
            enumeratePaths(next, outgoing, path, visiting, paths, maxPaths);
            if (paths.size() >= maxPaths) {
                break;
            }
        }
        if (!advanced && path.size() >= 2) {
            paths.add(List.copyOf(path));
        }
        path.remove(path.size() - 1);
        visiting.remove(current);
    }

    private static List<ProjectionInput> relationEventInputs(ReasoningGraph graph,
                                                             Map<String, GraphEntity> entities,
                                                             Options options) {
        List<ProjectionInput> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> family : options.eventRelationFamilies().entrySet()) {
            ReasoningGraphEventLogExtractor extractor = new ReasoningGraphEventLogExtractor(
                    family.getValue(), Set.of());
            EventLog log = extractor.extract(graph);
            if (log.isEmpty()) {
                continue;
            }
            List<EventLog> clusters = TraceClusterer.cluster(
                    log, options.clusterJaccardThreshold(), options.maxCandidates());
            int ordinal = 0;
            for (EventLog cluster : clusters) {
                ordinal++;
                if (cluster.activityNames().size() < options.minActivities()) {
                    continue;
                }
                Set<String> eventIds = cluster.traces().stream()
                        .flatMap(trace -> trace.events().stream())
                        .map(Event::graphNodeId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                Set<String> relationIds = graph.relations().stream()
                        .filter(relation -> family.getValue().contains(effectiveType(relation)))
                        .map(GraphRelation::id)
                        .filter(eventIds::contains)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (relationIds.isEmpty()) {
                    relationIds.addAll(eventIds);
                }
                Set<String> entityIds = graph.relations().stream()
                        .filter(relation -> relationIds.contains(relation.id()))
                        .flatMap(relation -> Stream.of(
                                relation.sourceId(), relation.targetId()))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                String processName = commonProcessName(entityIds, entities);
                if (processName == null) {
                    processName = genericProcessName(family.getKey());
                }
                out.add(new ProjectionInput(
                        family.getKey() + "-events-" + ordinal,
                        Projection.RELATION_EVENTS,
                        family.getKey(), cluster, processName,
                        List.copyOf(entityIds), List.copyOf(relationIds)));
            }
        }
        return out;
    }

    private static List<ProjectionInput> caseCorrelatedInputs(
            ReasoningGraph graph,
            Map<String, GraphEntity> entities,
            Options options) {
        Set<String> allowedTypes = new LinkedHashSet<>();
        options.flowRelationFamilies().values().forEach(allowedTypes::addAll);
        options.eventRelationFamilies().values().forEach(allowedTypes::addAll);
        if (allowedTypes.isEmpty()) {
            return List.of();
        }

        Map<String, GraphRelation> explicit = new LinkedHashMap<>();
        for (GraphRelation relation : graph.relations()) {
            if (allowedTypes.contains(effectiveType(relation))
                    && explicitCaseId(relation, entities) != null) {
                explicit.put(relation.id(), relation);
            }
        }
        if (explicit.isEmpty()) {
            return List.of();
        }

        EventLog extracted = new ReasoningGraphEventLogExtractor(allowedTypes, Set.of()).extract(graph);
        List<ProjectionInput> out = new ArrayList<>();
        int ordinal = 0;
        for (Trace trace : extracted.traces()) {
            List<Event> events = trace.events().stream()
                    .filter(event -> event.graphNodeId() != null)
                    .filter(event -> explicit.containsKey(event.graphNodeId()))
                    .toList();
            Set<String> activities = events.stream()
                    .map(Event::activity)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (events.size() < 2 || activities.size() < options.minActivities()) {
                continue;
            }

            ordinal++;
            List<String> relationIds = events.stream()
                    .map(Event::graphNodeId)
                    .distinct()
                    .toList();
            Set<String> entityIds = new LinkedHashSet<>();
            for (String relationId : relationIds) {
                GraphRelation relation = explicit.get(relationId);
                if (relation != null) {
                    entityIds.add(relation.sourceId());
                    entityIds.add(relation.targetId());
                }
            }
            String processName = commonProcessName(entityIds, entities);
            if (processName == null) {
                processName = caseProcessName(trace.caseId());
            }
            out.add(new ProjectionInput(
                    "case-events-" + sanitize(trace.caseId()) + "-" + ordinal,
                    Projection.RELATION_EVENTS,
                    "case",
                    new EventLog(List.of(new Trace(trace.caseId(), events))),
                    processName,
                    List.copyOf(entityIds),
                    relationIds));
        }
        return out;
    }

    private static String explicitCaseId(GraphRelation relation,
                                         Map<String, GraphEntity> entities) {
        String caseId = firstString(relation.attributes(), CASE_KEYS);
        if (caseId != null) {
            return caseId;
        }
        GraphEntity source = entities.get(relation.sourceId());
        caseId = source == null ? null : firstString(source.attributes(), CASE_KEYS);
        if (caseId != null) {
            return caseId;
        }
        GraphEntity target = entities.get(relation.targetId());
        return target == null ? null : firstString(target.attributes(), CASE_KEYS);
    }

    private static String caseProcessName(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return genericProcessName("case-correlated");
        }
        String display = caseId.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim()
                .replaceAll(" +", " ");
        if (display.isBlank() || display.length() > 72) {
            return genericProcessName("case-correlated");
        }
        return display + " process";
    }

    private static Map<String, List<GraphRelation>> relationComponents(List<GraphRelation> relations) {
        UnionFind components = new UnionFind();
        for (GraphRelation relation : relations) {
            components.union(relation.sourceId(), relation.targetId());
        }
        Map<String, List<GraphRelation>> grouped = new TreeMap<>();
        for (GraphRelation relation : relations) {
            String root = components.find(relation.sourceId());
            grouped.computeIfAbsent(root, ignored -> new ArrayList<>()).add(relation);
        }
        return grouped;
    }

    private static boolean isActivityEntity(GraphEntity entity) {
        if (entity == null || isActorResource(entity)) {
            return false;
        }
        String type = bestType(entity);
        return type == null || !STRUCTURAL_TYPES.contains(normalize(type));
    }

    private static boolean isActorResource(GraphEntity entity) {
        if (entity == null) {
            return false;
        }
        for (String type : entity.typeMemberships()) {
            if (ActivityClassifier.isActorResourceType(type)) {
                return true;
            }
        }
        return false;
    }

    private static String bestType(GraphEntity entity) {
        if (entity == null) {
            return null;
        }
        for (String type : entity.typeMemberships()) {
            if (type != null && !type.isBlank() && !isGenericType(type)) {
                return type;
            }
        }
        return entity.type();
    }

    private static boolean isGenericType(String type) {
        String normalized = normalize(type);
        return Set.of("ENTITY", "NODE", "GRAPH_NODE", "OBJECT", "RESOURCE", "UNKNOWN")
                .contains(normalized);
    }

    private static String activityLabel(GraphEntity entity) {
        if (entity.label() != null && !entity.label().isBlank()) {
            return entity.label().trim();
        }
        String type = bestType(entity);
        return ActivityClassifier.displayLabel(type == null ? "UNKNOWN" : type);
    }

    private static LocalDateTime timestamp(GraphEntity entity) {
        return entity.timestamp() == null
                ? null : LocalDateTime.ofInstant(entity.timestamp(), ZoneOffset.UTC);
    }

    private static Map<String, Object> entityAttributes(GraphEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityType", bestType(entity));
        int count = 0;
        for (Map.Entry<String, Object> entry : entity.attributes().entrySet()) {
            if (count >= MAX_EVENT_ATTRIBUTES) {
                break;
            }
            Object value = entry.getValue();
            if (entry.getKey() != null && !entry.getKey().isBlank() && value != null
                    && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                out.put(entry.getKey(), value);
                count++;
            }
        }
        return out;
    }

    private static List<ProjectionInput> semanticFusionInputs(
            List<ProjectionInput> source,
            Map<String, GraphEntity> entities,
            Map<String, GraphRelation> relations,
            Options options) {
        List<EmbeddedProjection> embedded = new ArrayList<>();
        for (ProjectionInput input : List.copyOf(source)) {
            if (input.relationIds().size() < 2) {
                continue;
            }
            embedded.addAll(embeddedProjections(
                    input, entities, relations, options.semanticFusionThreshold()));
        }
        embedded = pruneSemanticProjectionSubsets(
                embedded, options.semanticFusionThreshold());
        embedded.sort(Comparator.comparing(projection -> projection.input().key()));
        if (embedded.size() < 2) {
            return List.of();
        }

        // Complete-link clustering prevents a weak semantic bridge from joining otherwise
        // incompatible projections. Overlapping evidence is excluded because fusion should connect
        // independent observations, not duplicate another projection over the same relations.
        List<List<EmbeddedProjection>> clusters = new ArrayList<>();
        for (EmbeddedProjection projection : embedded) {
            boolean assigned = false;
            for (List<EmbeddedProjection> cluster : clusters) {
                if (cluster.size() < 8 && cluster.stream().allMatch(member ->
                        semanticallyCompatible(
                                projection, member, options.semanticFusionThreshold()))) {
                    cluster.add(projection);
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                List<EmbeddedProjection> cluster = new ArrayList<>();
                cluster.add(projection);
                clusters.add(cluster);
            }
        }

        List<ProjectionInput> out = new ArrayList<>();
        int ordinal = 0;
        for (List<EmbeddedProjection> cluster : clusters) {
            if (cluster.size() < 2) {
                continue;
            }
            ordinal++;
            List<Trace> traces = new ArrayList<>();
            Set<String> entityIds = new LinkedHashSet<>();
            Set<String> relationIds = new LinkedHashSet<>();
            int traceOrdinal = 0;
            for (EmbeddedProjection member : cluster) {
                ProjectionInput input = member.input();
                entityIds.addAll(input.entityIds());
                relationIds.addAll(input.relationIds());
                for (Trace trace : input.eventLog().traces()) {
                    traceOrdinal++;
                    traces.add(new Trace(
                            "semantic-fusion-" + ordinal + "-" + traceOrdinal + "-" + trace.caseId(),
                            trace.events()));
                }
            }
            EventLog aggregate = new EventLog(traces);
            if (aggregate.activityNames().size() < options.minActivities()) {
                continue;
            }
            String processName = commonProcessName(entityIds, entities);
            if (processName == null) {
                processName = genericProcessName("semantic fusion");
            }
            out.add(new ProjectionInput(
                    "semantic-fusion-" + ordinal,
                    Projection.RELATION_EVENTS,
                    "semantic-fusion",
                    aggregate,
                    processName,
                    List.copyOf(entityIds),
                    List.copyOf(relationIds)));
        }
        return out;
    }

    private static List<EmbeddedProjection> embeddedProjections(
            ProjectionInput input,
            Map<String, GraphEntity> entities,
            Map<String, GraphRelation> relations,
            double threshold) {
        VectorCentroid inputCentroid =
                semanticCentroid(input.relationIds(), entities, relations);
        if (inputCentroid == null || inputCentroid.count() < 2) {
            return List.of();
        }

        Map<String, List<String>> relationGroups = new TreeMap<>();
        for (String relationId : input.relationIds()) {
            GraphRelation relation = relations.get(relationId);
            if (relation == null || !relationHasCompatibleEmbedding(
                    relation, inputCentroid.vector(), entities, threshold)) {
                continue;
            }
            String caseId = explicitCaseId(relation, entities);
            String groupKey = caseId == null ? "projection:" + input.key() : "case:" + caseId;
            relationGroups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(relationId);
        }

        List<EmbeddedProjection> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : relationGroups.entrySet()) {
            if (group.getValue().size() < 2) {
                continue;
            }
            VectorCentroid groupCentroid =
                    semanticCentroid(group.getValue(), entities, relations);
            if (groupCentroid == null || groupCentroid.count() < 2) {
                continue;
            }
            ProjectionInput anchored = anchoredRelationInput(
                    input, group.getKey(), group.getValue(), entities, relations);
            if (anchored != null) {
                out.add(new EmbeddedProjection(
                        anchored, groupCentroid.vector(), groupCentroid.count()));
            }
        }
        return out;
    }

    private static ProjectionInput anchoredRelationInput(
            ProjectionInput source,
            String caseKey,
            List<String> relationIds,
            Map<String, GraphEntity> entities,
            Map<String, GraphRelation> relations) {
        List<Event> events = new ArrayList<>();
        Set<String> entityIds = new LinkedHashSet<>();
        for (String relationId : relationIds) {
            GraphRelation relation = relations.get(relationId);
            if (relation == null) {
                continue;
            }
            GraphEntity sourceEntity = entities.get(relation.sourceId());
            GraphEntity targetEntity = entities.get(relation.targetId());
            String sourceType = bestType(sourceEntity);
            String targetType = bestType(targetEntity);
            String activity = RelationActivityClassifier.activityLabel(
                    sourceType == null ? "UNKNOWN" : sourceType,
                    effectiveType(relation),
                    targetType == null ? "UNKNOWN" : targetType);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("relationType", effectiveType(relation));
            attributes.put("sourceEntityId", relation.sourceId());
            attributes.put("targetEntityId", relation.targetId());
            if (sourceEntity != null && sourceEntity.label() != null) {
                attributes.put("sourceLabel", sourceEntity.label());
            }
            if (targetEntity != null && targetEntity.label() != null) {
                attributes.put("targetLabel", targetEntity.label());
            }
            LocalDateTime occurredAt = relation.timestamp() == null
                    ? null : LocalDateTime.ofInstant(relation.timestamp(), ZoneOffset.UTC);
            events.add(new Event(caseKey, activity, occurredAt, relation.id(), attributes));
            entityIds.add(relation.sourceId());
            entityIds.add(relation.targetId());
        }
        if (events.size() < 2) {
            return null;
        }
        String processName = commonProcessName(entityIds, entities);
        if (processName == null) {
            processName = source.processName();
        }
        return new ProjectionInput(
                source.key() + "-semantic-" + sanitize(caseKey),
                Projection.RELATION_EVENTS,
                source.family(),
                new EventLog(List.of(new Trace(caseKey, events))),
                processName,
                List.copyOf(entityIds),
                List.copyOf(relationIds));
    }

    private static VectorCentroid semanticCentroid(
            Collection<String> relationIds,
            Map<String, GraphEntity> entities,
            Map<String, GraphRelation> relations) {
        Map<String, double[]> vectors = new LinkedHashMap<>();
        for (String relationId : new LinkedHashSet<>(relationIds)) {
            GraphRelation relation = relations.get(relationId);
            if (relation == null) {
                continue;
            }
            if (relation.hasEmbedding()) {
                vectors.put("relation:" + relation.id(), relation.embedding());
            }
            GraphEntity source = entities.get(relation.sourceId());
            if (source != null && source.hasEmbedding()) {
                vectors.put("entity:" + source.id(), source.embedding());
            }
            GraphEntity target = entities.get(relation.targetId());
            if (target != null && target.hasEmbedding()) {
                vectors.put("entity:" + target.id(), target.embedding());
            }
        }
        double[] sum = null;
        int count = 0;
        for (double[] vector : vectors.values()) {
            if (vector == null || vector.length == 0) {
                continue;
            }
            if (sum == null) {
                sum = vector.clone();
                count = 1;
            } else if (sum.length == vector.length) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += vector[i];
                }
                count++;
            }
        }
        if (sum == null || count == 0) {
            return null;
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= count;
        }
        return new VectorCentroid(sum, count);
    }

    private static boolean relationHasCompatibleEmbedding(
            GraphRelation relation,
            double[] centroid,
            Map<String, GraphEntity> entities,
            double threshold) {
        if (relation.hasEmbedding()
                && cosineSimilarity(relation.embedding(), centroid) >= threshold) {
            return true;
        }
        GraphEntity source = entities.get(relation.sourceId());
        if (source != null && source.hasEmbedding()
                && cosineSimilarity(source.embedding(), centroid) >= threshold) {
            return true;
        }
        GraphEntity target = entities.get(relation.targetId());
        return target != null && target.hasEmbedding()
                && cosineSimilarity(target.embedding(), centroid) >= threshold;
    }

    private static List<EmbeddedProjection> pruneSemanticProjectionSubsets(
            List<EmbeddedProjection> projections,
            double threshold) {
        List<EmbeddedProjection> sorted = new ArrayList<>(projections);
        sorted.sort(Comparator
                .comparingInt((EmbeddedProjection projection) ->
                        projection.input().relationIds().size())
                .reversed()
                .thenComparing(projection -> projection.input().key()));
        List<EmbeddedProjection> retained = new ArrayList<>();
        for (EmbeddedProjection candidate : sorted) {
            Set<String> candidateRelations =
                    new LinkedHashSet<>(candidate.input().relationIds());
            boolean covered = retained.stream().anyMatch(existing ->
                    existing.input().relationIds().size() >= candidateRelations.size()
                            && existing.input().relationIds().containsAll(candidateRelations)
                            && cosineSimilarity(existing.centroid(), candidate.centroid()) >= threshold);
            if (!covered) {
                retained.add(candidate);
            }
        }
        return retained;
    }

    private static boolean semanticallyCompatible(
            EmbeddedProjection left,
            EmbeddedProjection right,
            double threshold) {
        Set<String> leftRelations = new LinkedHashSet<>(left.input().relationIds());
        if (right.input().relationIds().stream().anyMatch(leftRelations::contains)) {
            return false;
        }
        return cosineSimilarity(left.centroid(), right.centroid()) >= threshold;
    }

    private static double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return -1.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return -1.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static ActivityEmbeddingResolution activityEmbeddings(
            EventLog eventLog,
            ReasoningGraph graph) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Boolean> directByActivity = new LinkedHashMap<>();
        for (Trace trace : eventLog.traces()) {
            for (Event event : trace.events()) {
                GraphEmbeddingResolver.Resolved resolved = resolveEventEmbedding(graph, event);
                if (!resolved.present()) {
                    continue;
                }
                double[] vector = resolved.vector();
                boolean direct = resolved.origin() == GraphEmbeddingResolver.Origin.DIRECT_ENTITY
                        || resolved.origin() == GraphEmbeddingResolver.Origin.DIRECT_RELATION;
                directByActivity.merge(event.activity(), direct, Boolean::logicalOr);
                double[] sum = sums.get(event.activity());
                if (sum == null) {
                    sums.put(event.activity(), vector.clone());
                    counts.put(event.activity(), 1);
                } else if (sum.length == vector.length) {
                    for (int i = 0; i < sum.length; i++) {
                        sum[i] += vector[i];
                    }
                    counts.merge(event.activity(), 1, Integer::sum);
                }
            }
        }
        Map<String, double[]> averaged = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            int count = counts.getOrDefault(entry.getKey(), 1);
            double[] vector = entry.getValue();
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= count;
            }
            averaged.put(entry.getKey(), vector);
        }
        int direct = 0;
        int inferred = 0;
        for (String activity : averaged.keySet()) {
            if (directByActivity.getOrDefault(activity, false)) {
                direct++;
            } else {
                inferred++;
            }
        }
        String source = averaged.isEmpty() ? null
                : inferred == 0 ? "GRAPH_VECTOR_DIRECT"
                : direct == 0 ? "GRAPH_VECTOR_RESOLVED"
                : "GRAPH_VECTOR_MIXED";
        return new ActivityEmbeddingResolution(averaged, direct, inferred, source);
    }

    private static GraphEmbeddingResolver.Resolved resolveEventEmbedding(
            ReasoningGraph graph, Event event) {
        GraphEmbeddingResolver.Resolved direct =
                GraphEmbeddingResolver.resolve(graph, event.graphNodeId());
        if (direct.present()) {
            return direct;
        }

        List<GraphEmbeddingResolver.Resolved> endpoints = new ArrayList<>(2);
        for (String key : List.of("sourceNodeId", "targetNodeId")) {
            Object id = event.attributes().get(key);
            if (id != null) {
                GraphEmbeddingResolver.Resolved resolved =
                        GraphEmbeddingResolver.resolve(graph, String.valueOf(id));
                if (resolved.present()) {
                    endpoints.add(resolved);
                }
            }
        }
        if (endpoints.isEmpty()) {
            return GraphEmbeddingResolver.Resolved.empty();
        }
        if (endpoints.size() == 1) {
            GraphEmbeddingResolver.Resolved endpoint = endpoints.get(0);
            return new GraphEmbeddingResolver.Resolved(endpoint.vector(),
                    GraphEmbeddingResolver.Origin.RELATION_ENDPOINTS,
                    endpoint.supportCount(), endpoint.hops());
        }
        double[] left = endpoints.get(0).vector();
        double[] right = endpoints.get(1).vector();
        if (left.length != right.length) {
            return endpoints.get(0);
        }
        double[] centroid = new double[left.length];
        for (int i = 0; i < centroid.length; i++) {
            centroid[i] = (left[i] + right[i]) / 2.0;
        }
        return new GraphEmbeddingResolver.Resolved(Embeddings.normalize(centroid),
                GraphEmbeddingResolver.Origin.RELATION_ENDPOINTS,
                endpoints.get(0).supportCount() + endpoints.get(1).supportCount(),
                Math.max(endpoints.get(0).hops(), endpoints.get(1).hops()));
    }

    private static String commonProcessName(Collection<String> entityIds,
                                            Map<String, GraphEntity> entities) {
        Map<String, Integer> counts = new TreeMap<>();
        int eligible = 0;
        for (String entityId : entityIds) {
            GraphEntity entity = entities.get(entityId);
            if (entity == null) {
                continue;
            }
            eligible++;
            String name = firstString(entity.attributes(), PROCESS_NAME_KEYS);
            if (name != null) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        Map.Entry<String, Integer> best = counts.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue)
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow();
        return best.getValue() >= Math.max(2, (int) Math.ceil(eligible * 0.50))
                ? best.getKey() : null;
    }

    private static String firstString(Map<String, Object> attributes, List<String> keys) {
        if (attributes == null) {
            return null;
        }
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String genericProcessName(String family) {
        return ActivityClassifier.displayLabel(family) + " process";
    }

    private static String effectiveType(GraphRelation relation) {
        String canonical = firstString(relation.attributes(), List.of(
                "canonicalRelationType", "canonical_relation_type",
                "normalizedRelationType", "normalized_relation_type"));
        return normalize(canonical == null ? relation.type() : canonical);
    }

    private static Map<String, GraphEntity> entitiesById(ReasoningGraph graph) {
        Map<String, GraphEntity> out = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            out.put(entity.id(), entity);
        }
        return out;
    }

    private static Map<String, GraphRelation> relationsById(ReasoningGraph graph) {
        Map<String, GraphRelation> out = new LinkedHashMap<>();
        for (GraphRelation relation : graph.relations()) {
            out.put(relation.id(), relation);
        }
        return out;
    }

    private static List<CandidateDraft> deduplicate(List<CandidateDraft> drafts) {
        Map<String, CandidateDraft> byFingerprint = new LinkedHashMap<>();
        for (CandidateDraft draft : drafts) {
            String fingerprint = fingerprint(draft);
            CandidateDraft previous = byFingerprint.get(fingerprint);
            if (previous == null || draft.score() > previous.score()) {
                byFingerprint.put(fingerprint, draft);
            }
        }
        return new ArrayList<>(byFingerprint.values());
    }

    private static List<CandidateDraft> suppressSubsumed(List<CandidateDraft> drafts) {
        List<CandidateDraft> retained = new ArrayList<>();
        for (CandidateDraft candidate : drafts) {
            Set<String> candidateRelations = new LinkedHashSet<>(candidate.evidenceRelationIds());
            boolean subsumed = false;
            for (CandidateDraft possibleAggregate : drafts) {
                if (candidate == possibleAggregate
                        || candidate.projection() != possibleAggregate.projection()
                        || !candidate.family().equals(possibleAggregate.family())
                        || possibleAggregate.evidenceRelationIds().size() <= candidateRelations.size()
                        || possibleAggregate.score() < candidate.score() - 0.10) {
                    continue;
                }
                Set<String> aggregateRelations =
                        new LinkedHashSet<>(possibleAggregate.evidenceRelationIds());
                int reasonableScope = Math.max(12, candidate.activityCount() * 4);
                if (possibleAggregate.activityCount() <= reasonableScope
                        && aggregateRelations.containsAll(candidateRelations)) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                retained.add(candidate);
            }
        }
        return retained;
    }

    private static String fingerprint(CandidateDraft draft) {
        Set<String> activities = new TreeSet<>();
        for (String activity : draft.eventLog().activityNames()) {
            activities.add(normalize(activity));
        }
        Set<String> arcs = new TreeSet<>();
        for (DirectlyFollowsGraph.Arc arc : draft.dfg().arcs().keySet()) {
            arcs.add(normalize(arc.from()) + ">" + normalize(arc.to()));
        }
        return activities + "|" + arcs;
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "candidate"
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "candidate" : sanitized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record ProjectionInput(String key,
                                   Projection projection,
                                   String family,
                                   EventLog eventLog,
                                   String processName,
                                   List<String> entityIds,
                                   List<String> relationIds) {
    }

    private record EmbeddedProjection(ProjectionInput input,
                                      double[] centroid,
                                      int semanticAnchorCount) {
    }

    private record VectorCentroid(double[] vector, int count) {
    }

    private record ActivityEmbeddingResolution(Map<String, double[]> vectors,
                                               int directCount,
                                               int inferredCount,
                                               String source) {
    }

    private record CandidateDraft(String id,
                                  Projection projection,
                                  String family,
                                  double score,
                                  EventLog eventLog,
                                  DirectlyFollowsGraph dfg,
                                  ProcessEntailmentResult entailment,
                                  ProcessHybridActivation.Result hybridActivation,
                                  ProcessTree processTree,
                                  ProcessSuggestion suggestion,
                                  ReasoningTrace reasoningTrace,
                                  List<String> evidenceEntityIds,
                                  List<String> evidenceRelationIds) {

        int activityCount() {
            return eventLog.activityNames().size();
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        void union(String left, String right) {
            parent.putIfAbsent(left, left);
            parent.putIfAbsent(right, right);
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (!leftRoot.equals(rightRoot)) {
                String root = leftRoot.compareTo(rightRoot) <= 0 ? leftRoot : rightRoot;
                parent.put(root.equals(leftRoot) ? rightRoot : leftRoot, root);
            }
        }

        String find(String value) {
            parent.putIfAbsent(value, value);
            String current = value;
            while (!current.equals(parent.get(current))) {
                current = parent.get(current);
            }
            String root = current;
            current = value;
            while (!current.equals(parent.get(current))) {
                String next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }
    }
}
