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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.mining.entail.PrecedenceMaterializer;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a knowledge (sub)graph into an {@link EventLog} — the bridge from "data" to "process mining."
 *
 * <p>This is the Event-Knowledge-Graph construction (Esser &amp; Fahland) specialised to kompile's
 * graph: a {@link CaseCorrelation} strategy groups nodes into cases, each node becomes an
 * {@link Event} (activity via {@link ActivityClassifier}, time via {@link GraphNode#getOccurredAt()},
 * provenance via the node id), and opt-in relation-event projection can add event-like edges as
 * activities via {@link RelationActivityClassifier}. The events of a case are ordered by timestamp
 * into a directly-follows {@link Trace}. Purely structural — no LLM.
 *
 * <p>Structural plumbing nodes ({@code SOURCE} crawl roots and {@code SNIPPET} chunks) are excluded by
 * default so the log reflects real entities/documents/events rather than ingestion artefacts.
 */
public final class EventLogExtractor {

    /** Levels that are ingestion scaffolding rather than process events. */
    public static final Set<NodeLevel> DEFAULT_EXCLUDED_LEVELS = EnumSet.of(NodeLevel.SOURCE, NodeLevel.SNIPPET);

    private final ActivityClassifier classifier;
    private final CaseCorrelation correlation;
    private final Set<NodeLevel> excludedLevels;
    private final RelationActivityClassifier relationClassifier;

    public EventLogExtractor() {
        this(ActivityClassifier.byEntityType(), new ConnectedComponentCorrelation(), DEFAULT_EXCLUDED_LEVELS,
                RelationActivityClassifier.none());
    }

    public static EventLogExtractor withRelationEvents() {
        return new EventLogExtractor(ActivityClassifier.byEntityType(), new ConnectedComponentCorrelation(),
                DEFAULT_EXCLUDED_LEVELS, RelationActivityClassifier.byResolvedTypes());
    }

    public EventLogExtractor(ActivityClassifier classifier, CaseCorrelation correlation, Set<NodeLevel> excludedLevels) {
        this(classifier, correlation, excludedLevels, RelationActivityClassifier.none());
    }

    public EventLogExtractor(ActivityClassifier classifier,
                             CaseCorrelation correlation,
                             Set<NodeLevel> excludedLevels,
                             RelationActivityClassifier relationClassifier) {
        this.classifier = (classifier != null) ? classifier : ActivityClassifier.byEntityType();
        this.correlation = (correlation != null) ? correlation : new ConnectedComponentCorrelation();
        this.excludedLevels = (excludedLevels != null) ? EnumSet.copyOf(excludedLevels) : EnumSet.noneOf(NodeLevel.class);
        this.relationClassifier = (relationClassifier != null) ? relationClassifier : RelationActivityClassifier.none();
    }

    /** Convenience: pull a fact sheet's nodes and edges from the graph service and extract a log. */
    public EventLog extractForFactSheet(KnowledgeGraphService graph, Long factSheetId) {
        List<GraphNode> nodes = graph.getNodesInFactSheet(factSheetId);
        List<GraphEdge> edges = graph.getEdgesInFactSheet(factSheetId);
        return extract(nodes, edges);
    }

    /**
     * Extracts an event log from an explicit node/edge set. Pure (no graph-service calls), which keeps
     * the whole mining pipeline unit-testable on hand-built graphs.
     */
    public EventLog extract(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        if (nodes != null) {
            for (GraphNode n : nodes) {
                if (n.getNodeId() == null) {
                    continue;
                }
                if (n.getNodeType() != null && excludedLevels.contains(n.getNodeType())) {
                    continue;
                }
                byId.put(n.getNodeId(), n);
            }
        }

        // Case correlation must see only OBSERVED, CASE-CARRYING structure:
        //  - Derived edges (the miner's own materialized DIRECTLY_FOLLOWS/PRECEDES, any
        //    INFERRED-provenance edge) would let each mining run reshape the next run's cases —
        //    a mine → materialize → re-mine feedback loop.
        //  - Actor/resource-incident edges (SENT_BY/SENT_TO to PERSON, BELONGS_TO to
        //    ORGANIZATION…) are resource assignments, not case structure: on real email crawls a
        //    shared approver or the company org node would union EVERY thread into one mega-case.
        //    They are NOT lost — ActorResourceObservations tallies them into per-activity
        //    performers for role binding (the OCPM resource perspective).
        List<GraphEdge> nonDerivedEdges = new ArrayList<>();
        List<GraphEdge> observedEdges = new ArrayList<>();
        if (edges != null) {
            for (GraphEdge e : edges) {
                if (e == null || isDerivedEdge(e)) {
                    continue;
                }
                nonDerivedEdges.add(e);
                if (touchesActorResource(e, byId)) {
                    continue;
                }
                observedEdges.add(e);
            }
        }

        Map<String, List<String>> cases = correlation.correlate(
                new ArrayList<>(byId.values()), observedEdges);
        Map<String, String> caseByNode = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : cases.entrySet()) {
            for (String nodeId : entry.getValue()) {
                caseByNode.put(nodeId, entry.getKey());
            }
        }

        // Event-time fallback from RELATION metadata: the email lane stamps occurredAt on every
        // relation (SENT_BY/SENT_TO carry the mail date), so a node the persist path never lifted
        // a time onto can still be dated by its earliest incident OBSERVED edge. Actor-incident
        // edges deliberately count here — they carry time even though they never carry cases.
        Map<String, LocalDateTime> earliestEdgeTime = new LinkedHashMap<>();
        for (GraphEdge e : nonDerivedEdges) {
            if (e.getOccurredAt() == null) {
                continue;
            }
            mergeEarliest(earliestEdgeTime, e.getSourceNodeId(), e.getOccurredAt());
            mergeEarliest(earliestEdgeTime, e.getTargetNodeId(), e.getOccurredAt());
        }
        Map<String, List<Event>> relationEventsByCase = relationEventsByCase(
                nonDerivedEdges, byId, caseByNode, earliestEdgeTime);

        List<Trace> traces = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : cases.entrySet()) {
            String caseId = entry.getKey();
            List<Event> events = new ArrayList<>();
            for (String nodeId : entry.getValue()) {
                GraphNode node = byId.get(nodeId);
                if (node == null) {
                    continue; // filtered out as an excluded level
                }
                String activity = classifier.activityOf(node);
                if (activity == null) {
                    continue; // classifier excluded this node (e.g. a spreadsheet structural type)
                }
                LocalDateTime when = node.getOccurredAt() != null
                        ? node.getOccurredAt()
                        : earliestEdgeTime.get(nodeId);
                events.add(new Event(caseId, activity, when, nodeId, eventAttributes(node)));
            }
            events.addAll(relationEventsByCase.getOrDefault(caseId, List.of()));
            if (!events.isEmpty()) {
                traces.add(new Trace(caseId, events));
            }
        }
        return new EventLog(traces);
    }

    private Map<String, List<Event>> relationEventsByCase(List<GraphEdge> edges,
                                                          Map<String, GraphNode> byId,
                                                          Map<String, String> caseByNode,
                                                          Map<String, LocalDateTime> earliestEdgeTime) {
        Map<String, List<Event>> byCase = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            GraphNode source = edge.resolvedSourceNode(byId);
            GraphNode target = edge.resolvedTargetNode(byId);
            String activity = relationClassifier.activityOf(edge, source, target);
            if (activity == null) {
                continue;
            }
            String caseId = relationCase(edge, source, target, caseByNode);
            if (caseId == null) {
                continue;
            }
            byCase.computeIfAbsent(caseId, ignored -> new ArrayList<>()).add(new Event(
                    caseId, activity, relationTime(edge, source, target, earliestEdgeTime),
                    relationEventId(edge), relationAttributes(edge, source, target)));
        }
        return byCase;
    }

    private static String relationCase(GraphEdge edge,
                                       GraphNode source,
                                       GraphNode target,
                                       Map<String, String> caseByNode) {
        String sourceCase = caseByNode.get(edge.getSourceNodeId());
        String targetCase = caseByNode.get(edge.getTargetNodeId());
        boolean sourceActor = ActivityClassifier.isActorResource(source);
        boolean targetActor = ActivityClassifier.isActorResource(target);
        if (sourceActor && targetActor) {
            return null;
        }
        if (sourceCase != null && sourceCase.equals(targetCase)) {
            return sourceCase;
        }
        if (sourceActor && targetCase != null) {
            return targetCase;
        }
        if (targetActor && sourceCase != null) {
            return sourceCase;
        }
        if (sourceCase != null && targetCase == null) {
            return sourceCase;
        }
        if (targetCase != null && sourceCase == null) {
            return targetCase;
        }
        return null;
    }

    private static LocalDateTime relationTime(GraphEdge edge,
                                              GraphNode source,
                                              GraphNode target,
                                              Map<String, LocalDateTime> earliestEdgeTime) {
        if (edge.getOccurredAt() != null) {
            return edge.getOccurredAt();
        }
        LocalDateTime endpointTime = earliest(source != null ? source.getOccurredAt() : null,
                target != null ? target.getOccurredAt() : null);
        if (endpointTime != null) {
            return endpointTime;
        }
        return earliest(earliestEdgeTime.get(edge.getSourceNodeId()), earliestEdgeTime.get(edge.getTargetNodeId()));
    }

    private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static String relationEventId(GraphEdge edge) {
        if (edge.getEdgeId() != null && !edge.getEdgeId().isBlank()) {
            return edge.getEdgeId();
        }
        return edge.getSourceNodeId() + ":" + RelationActivityClassifier.relationType(edge) + ":"
                + edge.getTargetNodeId();
    }

    private static Map<String, Object> relationAttributes(GraphEdge edge, GraphNode source, GraphNode target) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "eventProjection", "RELATION");
        putIfPresent(attributes, "relationType", RelationActivityClassifier.relationType(edge));
        putIfPresent(attributes, "sourceNodeId", edge.getSourceNodeId());
        putIfPresent(attributes, "targetNodeId", edge.getTargetNodeId());
        putIfPresent(attributes, "sourceType", RelationActivityClassifier.resolvedType(source));
        putIfPresent(attributes, "targetType", RelationActivityClassifier.resolvedType(target));
        return attributes;
    }

    private static void putIfPresent(Map<String, Object> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static void mergeEarliest(Map<String, LocalDateTime> earliest,
                                      String nodeId, LocalDateTime when) {
        if (nodeId == null || when == null) {
            return;
        }
        earliest.merge(nodeId, when, (a, b) -> a.isBefore(b) ? a : b);
    }

    /** Metadata keys that identify rather than describe — useless (or harmful) as decision data. */
    private static final Set<String> NON_DECISION_KEYS = Set.of(
            "entity_type", "source", "sourceId", "messageId", "nodeId", "externalId", "id");
    /** Cap on lifted attributes per event — decision data, not a metadata dump. */
    private static final int MAX_EVENT_ATTRIBUTES = 24;

    /**
     * Lift the node's scalar metadata onto the event as decision-mining attributes: the crawl
     * extractors put the business facets there (amounts, statuses, categories, dates), and
     * XOR-guard mining ({@code ProcessTreeToSuggestion.annotateChoice}) learns branch predicates
     * over exactly these values. Identifier-ish keys and non-scalar values are skipped —
     * attributes are decision data, not a metadata dump.
     */
    private static Map<String, Object> eventAttributes(GraphNode node) {
        Map<String, Object> metadata = node.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (attributes.size() >= MAX_EVENT_ATTRIBUTES) {
                break;
            }
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || NON_DECISION_KEYS.contains(key)
                    || !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                continue;
            }
            if (value instanceof String s && s.isBlank()) {
                continue;
            }
            attributes.put(key, value);
        }
        return attributes;
    }

    /**
     * True when either endpoint is an actor/resource-type node (person/org — see ActivityClassifier).
     *
     * <p>byId (the fact sheet's own nodes) is consulted FIRST: {@code GraphEdge.getSourceNode()}
     * never returns null once an id is set — it synthesizes a hollow id-only node — so preferring
     * the embedded node made this filter blind to actors on store-loaded edges (matrix-store edges
     * don't embed nodes), silently re-enabling the shared-approver mega-case collapse in production
     * while fixture-built edges (which DO embed real nodes) kept the tests green.
     */
    private static boolean touchesActorResource(GraphEdge e, Map<String, GraphNode> byId) {
        return ActivityClassifier.isActorResource(e.resolvedSourceNode(byId))
                || ActivityClassifier.isActorResource(e.resolvedTargetNode(byId));
    }

    /**
     * True for edges that are reasoning products rather than observations: INFERRED provenance
     * (OWL closure, cascade materialization, entailed precedence) or the miner's own
     * relation types regardless of provenance flags.
     */
    private static boolean isDerivedEdge(GraphEdge e) {
        if (e.getProvenanceType() == ai.kompile.knowledgegraph.domain.EdgeProvenance.INFERRED) {
            return true;
        }
        String relationType = e.getRelationType();
        return relationType != null
                && (relationType.equalsIgnoreCase(PrecedenceMaterializer.DIRECTLY_FOLLOWS)
                        || relationType.equalsIgnoreCase(PrecedenceMaterializer.PRECEDES)
                        || relationType.equalsIgnoreCase(PrecedenceMaterializer.PERFORMED_BY));
    }
}
