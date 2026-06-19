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
package ai.kompile.event.observation.service;

import ai.kompile.event.observation.config.EventObservationConfig;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.config.OpportunityModel;
import ai.kompile.event.observation.domain.EventChannel;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans a fact sheet's subgraph and emits observed events: one {@code ENTITY_OCCURRENCE} per entity
 * node (from its mention/reference count) and one {@code CONNECTION_OCCURRENCE} per real edge (from
 * its weight·confidence strength). The Binomial denominators follow the configured
 * {@link OpportunityModel}. Invoked on each crawl/graph-build completion.
 */
@Service
public class GraphEventScanner {

    private static final Logger log = LoggerFactory.getLogger(GraphEventScanner.class);
    private static final int MAX_NODES = 50_000;

    /** Computed/structural edge types that do not represent real observed connections. */
    private static final Set<EdgeType> SKIP_EDGES = EnumSet.of(
            EdgeType.EMBEDDING_SIMILARITY, EdgeType.HIERARCHICAL);

    /** Node levels treated as "entities". */
    private static final Set<NodeLevel> ENTITY_LEVELS = EnumSet.of(NodeLevel.ENTITY, NodeLevel.TABLE);

    private final KnowledgeGraphService graph;
    private final EventObservationService observationService;
    private final EventObservationConfigService configService;

    public GraphEventScanner(KnowledgeGraphService graph,
                             EventObservationService observationService,
                             EventObservationConfigService configService) {
        this.graph = graph;
        this.observationService = observationService;
        this.configService = configService;
    }

    public ScanResult scan(Long factSheetId, EventSource source, String crawlJobId) {
        EventObservationConfig cfg = configService.getConfig();
        if (!cfg.enabled()) {
            return ScanResult.disabled();
        }
        List<GraphNode> nodes = factSheetId != null ? graph.getNodesInFactSheet(factSheetId) : graph.getAllNodes(MAX_NODES);
        if (nodes == null || nodes.isEmpty()) {
            return ScanResult.empty();
        }
        OpportunityModel model = cfg.opportunityModel();

        long docsScanned = nodes.stream()
                .filter(n -> n.getNodeType() == NodeLevel.DOCUMENT || n.getNodeType() == NodeLevel.SOURCE)
                .count();
        if (docsScanned == 0) {
            docsScanned = nodes.size();
        }

        int entitiesObserved = scanEntities(nodes, cfg, model, docsScanned, factSheetId, source, crawlJobId);
        int connectionsObserved = scanConnections(nodes, cfg, model, factSheetId, source, crawlJobId);

        log.info("Event scan factSheet={} source={} -> {} entity events, {} connection events",
                factSheetId, source, entitiesObserved, connectionsObserved);
        return new ScanResult(entitiesObserved, connectionsObserved, true);
    }

    private int scanEntities(List<GraphNode> nodes, EventObservationConfig cfg, OpportunityModel model,
                             long docsScanned, Long factSheetId, EventSource source, String crawlJobId) {
        if (!cfg.entityEventsEnabled()) {
            return 0;
        }
        Map<String, Long> occurrenceByNode = new LinkedHashMap<>();
        long totalOccur = 0;
        for (GraphNode n : nodes) {
            if (n.getNodeType() == null || !ENTITY_LEVELS.contains(n.getNodeType())) {
                continue;
            }
            long occ = entityOccurrence(n);
            occurrenceByNode.put(n.getNodeId(), occ);
            totalOccur += occ;
        }
        if (occurrenceByNode.isEmpty()) {
            return 0;
        }
        long baseline = Math.max(1, totalOccur / occurrenceByNode.size());

        int observed = 0;
        for (Map.Entry<String, Long> e : occurrenceByNode.entrySet()) {
            Observation obs = OpportunityCalculator.forEntity(model, e.getValue(), docsScanned, totalOccur, baseline);
            if (obs.isEmpty()) {
                continue;
            }
            observationService.observeEntity(e.getKey(), obs.occurrences(), obs.opportunities(), source, factSheetId, crawlJobId);
            observed++;
        }
        return observed;
    }

    private int scanConnections(List<GraphNode> nodes, EventObservationConfig cfg, OpportunityModel model,
                                Long factSheetId, EventSource source, String crawlJobId) {
        if (!cfg.connectionEventsEnabled()) {
            return 0;
        }
        Map<String, EdgeInfo> edges = collectEdges(nodes);
        if (edges.isEmpty()) {
            return 0;
        }
        long totalConnections = edges.size();

        int observed = 0;
        for (EdgeInfo info : edges.values()) {
            double strength = clamp01(info.weight * info.confidence);
            Observation obs = OpportunityCalculator.forConnection(model, strength, totalConnections);
            if (obs.isEmpty()) {
                continue;
            }
            observationService.observeConnection(
                    new EventChannel(info.src, info.type.name(), info.tgt),
                    obs.occurrences(), obs.opportunities(), source, factSheetId, crawlJobId);
            observed++;
        }
        return observed;
    }

    /** Entity occurrence count: total entity mentions on the node, its edge count, or at least 1. */
    private long entityOccurrence(GraphNode n) {
        long mentions = 0;
        try {
            List<EntityMention> ms = graph.getEntityMentionsForNode(n.getNodeId());
            if (ms != null) {
                for (EntityMention m : ms) {
                    if (m.getMentionCount() != null) {
                        mentions += m.getMentionCount();
                    }
                }
            }
        } catch (Exception ignore) {
            // mentions are best-effort
        }
        long edgeCount = n.getEdgeCount() != null ? n.getEdgeCount() : 0L;
        return Math.max(1L, Math.max(mentions, edgeCount));
    }

    private Map<String, EdgeInfo> collectEdges(List<GraphNode> nodes) {
        Map<String, EdgeInfo> edges = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            List<GraphEdge> nodeEdges;
            try {
                nodeEdges = graph.getEdgesForNode(n.getNodeId());
            } catch (Exception ex) {
                continue;
            }
            if (nodeEdges == null) {
                continue;
            }
            for (GraphEdge edge : nodeEdges) {
                if (edge.getEdgeType() == null || SKIP_EDGES.contains(edge.getEdgeType())
                        || edge.getSourceNode() == null || edge.getTargetNode() == null) {
                    continue;
                }
                String src = edge.getSourceNode().getNodeId();
                String tgt = edge.getTargetNode().getNodeId();
                if (src == null || tgt == null) {
                    continue;
                }
                String key = src + "|" + edge.getEdgeType().name() + "|" + tgt;
                edges.computeIfAbsent(key, k -> new EdgeInfo(
                        src, tgt, edge.getEdgeType(),
                        edge.getWeight() != null ? edge.getWeight() : 0.5,
                        edge.getConfidence() != null ? edge.getConfidence() : 0.5));
            }
        }
        return edges;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private record EdgeInfo(String src, String tgt, EdgeType type, double weight, double confidence) {
    }
}
