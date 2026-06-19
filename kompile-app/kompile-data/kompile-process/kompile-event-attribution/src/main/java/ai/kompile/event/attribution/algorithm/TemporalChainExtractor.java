/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm;

import ai.kompile.event.attribution.domain.AttributionEvidence;
import ai.kompile.event.attribution.domain.EvidenceType;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts temporally-ordered event chains from the knowledge graph.
 *
 * <p>Temporal chain extraction identifies sequences of events connected by
 * time-based relationships and ranks predecessor events by temporal proximity
 * to the target. Events that occur closer in time and share entities or context
 * with the target receive higher attribution scores.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Find all nodes connected to the target via TEMPORAL edges</li>
 *   <li>Extract timestamps from node metadata (createdAt, indexed_at, event_time)</li>
 *   <li>Filter to events that precede the target event</li>
 *   <li>Score by temporal proximity: closer events get higher scores</li>
 *   <li>Boost score if nodes share entities (SHARED_ENTITY edges)</li>
 *   <li>Return sorted chain of temporal predecessors with evidence</li>
 * </ol>
 */
public class TemporalChainExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemporalChainExtractor.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final String[] TIMESTAMP_KEYS = {
            "event_time", "timestamp", "indexed_at", "created_at",
            "date", "occurred_at", "published_at"
    };

    private TemporalChainExtractor() {}

    /**
     * Extract a temporally-ordered chain of predecessor events for the given target.
     *
     * @param graphService the knowledge graph
     * @param targetNodeId the target event to explain
     * @param maxPredecessors maximum number of predecessor events to return
     * @param maxHops maximum hops from the target to search
     * @return ordered list of temporal predecessors with scores
     */
    public static List<TemporalPredecessor> extractPredecessors(KnowledgeGraphService graphService,
                                                                 String targetNodeId,
                                                                 int maxPredecessors,
                                                                 int maxHops) {
        return extractPredecessors(graphService, targetNodeId, maxPredecessors, maxHops,
                null, null);
    }

    /**
     * Extract temporal predecessors with optional temporal window filtering.
     *
     * @param graphService     the knowledge graph
     * @param targetNodeId     the target event to explain
     * @param maxPredecessors  maximum number of predecessor events to return
     * @param maxHops          maximum hops from the target to search
     * @param temporalStart    if non-null, exclude predecessors before this time
     * @param temporalEnd      if non-null, exclude predecessors after this time
     * @return ordered list of temporal predecessors with scores
     */
    public static List<TemporalPredecessor> extractPredecessors(KnowledgeGraphService graphService,
                                                                 String targetNodeId,
                                                                 int maxPredecessors,
                                                                 int maxHops,
                                                                 Instant temporalStart,
                                                                 Instant temporalEnd) {
        Optional<GraphNode> targetOpt = graphService.getNode(targetNodeId);
        if (targetOpt.isEmpty()) return List.of();

        GraphNode targetNode = targetOpt.get();
        Instant targetTime = extractTimestamp(targetNode);

        // BFS to find temporally-connected nodes
        Set<String> visited = new HashSet<>();
        visited.add(targetNodeId);
        Queue<String> frontier = new ArrayDeque<>();
        frontier.add(targetNodeId);

        Map<String, TimestampedNode> candidates = new LinkedHashMap<>();
        int depth = 0;

        while (!frontier.isEmpty() && depth < maxHops) {
            int levelSize = frontier.size();
            int currentDepth = depth;
            for (int i = 0; i < levelSize; i++) {
                String current = frontier.poll();
                List<GraphEdge> edges = graphService.getEdgesForNode(current);

                for (GraphEdge edge : edges) {
                    String neighborId = getNeighborId(edge, current);
                    if (neighborId == null || visited.contains(neighborId)) continue;
                    visited.add(neighborId);

                    Optional<GraphNode> neighborOpt = graphService.getNode(neighborId);
                    if (neighborOpt.isEmpty()) continue;
                    GraphNode neighbor = neighborOpt.get();

                    Instant neighborTime = extractTimestamp(neighbor);
                    boolean isTemporal = edge.getEdgeType() == EdgeType.TEMPORAL;
                    boolean isSharedEntity = edge.getEdgeType() == EdgeType.SHARED_ENTITY;

                    // Only consider as temporal predecessor if it has a timestamp before the target
                    // and within the optional temporal window
                    if (neighborTime != null && (targetTime == null || neighborTime.isBefore(targetTime))) {
                        if (temporalStart != null && neighborTime.isBefore(temporalStart)) continue;
                        if (temporalEnd != null && neighborTime.isAfter(temporalEnd)) continue;

                        candidates.computeIfAbsent(neighborId,
                                id -> new TimestampedNode(neighbor, neighborTime, currentDepth + 1,
                                        isTemporal, isSharedEntity));
                    }

                    // Continue BFS through temporal and shared-entity edges
                    if (isTemporal || isSharedEntity) {
                        frontier.add(neighborId);
                    }
                }
            }
            depth++;
        }

        // Score and sort
        List<TemporalPredecessor> predecessors = candidates.values().stream()
                .map(tn -> scorePredecessor(tn, targetTime))
                .sorted(Comparator.comparingDouble(TemporalPredecessor::score).reversed())
                .limit(maxPredecessors)
                .toList();

        return predecessors;
    }

    /**
     * Build attribution evidence from temporal predecessors.
     */
    public static List<AttributionEvidence> buildTemporalEvidence(List<TemporalPredecessor> predecessors) {
        return predecessors.stream()
                .map(p -> AttributionEvidence.builder()
                        .evidenceType(EvidenceType.TEMPORAL_PROXIMITY)
                        .strength(p.score)
                        .sourceNodeId(p.nodeId)
                        .summary("Temporal predecessor: " + p.title +
                                " (occurred at " + p.timestamp + ", " + p.hopsFromTarget + " hops away)")
                        .collectedAt(Instant.now())
                        .build())
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING
    // ═══════════════════════════════════════════════════════════════════════════

    private static TemporalPredecessor scorePredecessor(TimestampedNode tn, Instant targetTime) {
        double score = 0.5; // Base score

        // Temporal proximity boost
        if (targetTime != null && tn.timestamp != null) {
            long diffMs = Math.abs(targetTime.toEpochMilli() - tn.timestamp.toEpochMilli());
            // Exponential decay: closer in time = higher score
            // Half-life of 1 hour (3600000 ms)
            double decay = Math.exp(-diffMs / 3_600_000.0);
            score = 0.3 + 0.5 * decay; // Range [0.3, 0.8] from temporal proximity alone
        }

        // Boost for direct temporal edge
        if (tn.hasTemporalEdge) {
            score = Math.min(1.0, score + 0.15);
        }

        // Boost for shared entities
        if (tn.hasSharedEntityEdge) {
            score = Math.min(1.0, score + 0.1);
        }

        // Decay by hop distance
        score *= Math.pow(0.85, tn.hops);

        return new TemporalPredecessor(
                tn.node.getNodeId(),
                tn.node.getTitle(),
                tn.timestamp,
                score,
                tn.hops,
                tn.hasTemporalEdge,
                tn.hasSharedEntityEdge
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMESTAMP EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    static Instant extractTimestamp(GraphNode node) {
        // Try createdAt field first
        if (node.getCreatedAt() != null) {
            return node.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant();
        }

        // Try metadata JSON
        if (node.getMetadataJson() != null && !node.getMetadataJson().isBlank()) {
            try {
                Map<String, Object> meta = MAPPER.readValue(node.getMetadataJson(),
                        new TypeReference<>() {});
                for (String key : TIMESTAMP_KEYS) {
                    Object val = meta.get(key);
                    if (val instanceof String s) {
                        try {
                            return Instant.parse(s);
                        } catch (DateTimeParseException e) {
                            log.trace("Could not parse timestamp '{}' for key '{}' in node {}: {}", s, key, node.getNodeId(), e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.trace("Could not parse metadata JSON for node {}: {}", node.getNodeId(), e.getMessage());
            }
        }

        return null;
    }

    private static String getNeighborId(GraphEdge edge, String currentId) {
        if (edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(currentId)
                && edge.getTargetNode() != null) {
            return edge.getTargetNode().getNodeId();
        }
        if (edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(currentId)
                && edge.getSourceNode() != null) {
            return edge.getSourceNode().getNodeId();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    private record TimestampedNode(GraphNode node, Instant timestamp, int hops,
                                    boolean hasTemporalEdge, boolean hasSharedEntityEdge) {}

    /**
     * A temporally-ordered predecessor of the target event.
     */
    public record TemporalPredecessor(String nodeId, String title, Instant timestamp,
                                       double score, int hopsFromTarget,
                                       boolean hasTemporalEdge, boolean hasSharedEntityEdge) {}
}
