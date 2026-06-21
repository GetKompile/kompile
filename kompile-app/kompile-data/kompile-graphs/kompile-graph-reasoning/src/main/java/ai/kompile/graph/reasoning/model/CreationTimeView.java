/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A computed, lazy ReasoningGraph view that filters entities and relations by
 * CREATION time (transaction time) — when a node was physically created in the graph
 * via a crawl, channel message, or agent assert.
 *
 * Distinct from TemporalView which filters by valid-time (event-occurrence time).
 * Both views are composable: wrap one inside the other for dual-axis scoping.
 *
 * Creation time is read from GraphEntity.attributes():
 *   "_extractedAt" → Instant (ISO-8601, set by crawl producer)
 *   "_observedAt"  → fallback
 *   absent         → element predates provenance; treated as always included
 *
 * Crawl run scoping: filter by "_crawlRunId" attribute.
 * Changeset scoping: filter by "_changesetId" attribute.
 *
 * Per creation-time-gated-analysis-design.md §2.2.
 * Infra-free. No Spring, no JPA.
 */
public final class CreationTimeView implements ReasoningGraph {

    // Key constants (mirrors GraphProvenanceKeys without depending on kompile-knowledge-graph)
    public static final String EXTRACTED_AT_KEY  = "_extractedAt";
    public static final String OBSERVED_AT_KEY   = "_observedAt";
    public static final String CRAWL_RUN_ID_KEY  = "_crawlRunId";
    public static final String CHANGESET_ID_KEY  = "_changesetId";

    private final ReasoningGraph source;
    private final Instant createdFrom;    // null = open-left (no lower bound)
    private final Instant createdBefore;  // null = open-right (no upper bound)
    private final String crawlRunId;      // null = any
    private final String changesetId;     // null = any

    // Private constructor
    private CreationTimeView(ReasoningGraph source,
                              Instant createdFrom, Instant createdBefore,
                              String crawlRunId, String changesetId) {
        this.source        = Objects.requireNonNull(source, "source");
        this.createdFrom   = createdFrom;
        this.createdBefore = createdBefore;
        this.crawlRunId    = crawlRunId;
        this.changesetId   = changesetId;
    }

    // ─── Factories ───────────────────────────────────────────────────────────

    /**
     * Include only elements created before the given instant.
     * Equivalent to "what did the graph look like just before T?"
     */
    public static CreationTimeView asOf(ReasoningGraph graph, Instant createdBefore) {
        return new CreationTimeView(Objects.requireNonNull(graph), null, createdBefore, null, null);
    }

    /**
     * Include only elements created within [from, before) (half-open interval).
     * null bounds are open-left / open-right.
     */
    public static CreationTimeView between(ReasoningGraph graph, Instant from, Instant before) {
        return new CreationTimeView(Objects.requireNonNull(graph), from, before, null, null);
    }

    /**
     * Include only elements created by the given crawl run.
     */
    public static CreationTimeView byCrawlRun(ReasoningGraph graph, String crawlRunId) {
        return new CreationTimeView(Objects.requireNonNull(graph), null, null, crawlRunId, null);
    }

    /**
     * Include only elements belonging to the given changeset.
     */
    public static CreationTimeView byChangeset(ReasoningGraph graph, String changesetId) {
        return new CreationTimeView(Objects.requireNonNull(graph), null, null, null, changesetId);
    }

    // ─── ReasoningGraph ──────────────────────────────────────────────────────

    @Override
    public Collection<GraphEntity> entities() {
        return source.entities().stream()
                .filter(this::entityMatches)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<GraphRelation> relations() {
        Set<String> validIds = entities().stream().map(GraphEntity::id).collect(Collectors.toSet());
        return source.relations().stream()
                .filter(r -> relationMatches(r, validIds))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GraphEntity> entity(String id) {
        return source.entity(id).filter(this::entityMatches);
    }

    @Override
    public List<GraphRelation> outgoing(String entityId) {
        Set<String> validIds = entities().stream().map(GraphEntity::id).collect(Collectors.toSet());
        return source.outgoing(entityId).stream()
                .filter(r -> relationMatches(r, validIds))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphRelation> incoming(String entityId) {
        Set<String> validIds = entities().stream().map(GraphEntity::id).collect(Collectors.toSet());
        return source.incoming(entityId).stream()
                .filter(r -> relationMatches(r, validIds))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphRelation> relationsOf(String entityId) {
        Set<String> validIds = entities().stream().map(GraphEntity::id).collect(Collectors.toSet());
        return source.relationsOf(entityId).stream()
                .filter(r -> relationMatches(r, validIds))
                .collect(Collectors.toList());
    }

    // ─── Filtering ───────────────────────────────────────────────────────────

    private boolean entityMatches(GraphEntity e) {
        return matchesCreationTime(e.attributes()) && matchesIds(e.attributes());
    }

    private boolean relationMatches(GraphRelation r, Set<String> validEntityIds) {
        if (!validEntityIds.contains(r.sourceId()) || !validEntityIds.contains(r.targetId())) return false;
        return matchesCreationTime(r.attributes()) && matchesIds(r.attributes());
    }

    /**
     * Check creation-time window. Resolution order:
     *   1. _extractedAt (primary)
     *   2. _observedAt  (fallback)
     *   3. absent       → always included (open-world for pre-provenance elements)
     */
    private boolean matchesCreationTime(Map<String, ?> attrs) {
        if (createdFrom == null && createdBefore == null) return true; // no time filter active
        Instant creationTs = resolveCreationTime(attrs);
        if (creationTs == null) return true; // timeless — always included
        boolean afterFrom   = (createdFrom   == null) || !creationTs.isBefore(createdFrom);
        boolean beforeUpper = (createdBefore == null) || creationTs.isBefore(createdBefore);
        return afterFrom && beforeUpper;
    }

    private boolean matchesIds(Map<String, ?> attrs) {
        if (crawlRunId != null) {
            Object val = attrs.get(CRAWL_RUN_ID_KEY);
            if (!crawlRunId.equals(val != null ? val.toString() : null)) return false;
        }
        if (changesetId != null) {
            Object val = attrs.get(CHANGESET_ID_KEY);
            if (!changesetId.equals(val != null ? val.toString() : null)) return false;
        }
        return true;
    }

    private static Instant resolveCreationTime(Map<String, ?> attrs) {
        Object extractedAt = attrs.get(EXTRACTED_AT_KEY);
        if (extractedAt != null) return parseInstant(extractedAt.toString());
        Object observedAt = attrs.get(OBSERVED_AT_KEY);
        if (observedAt != null) return parseInstant(observedAt.toString());
        return null;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    @Override
    public String toString() {
        if (crawlRunId != null) return "CreationTimeView{crawlRunId=" + crawlRunId + "}";
        if (changesetId != null) return "CreationTimeView{changesetId=" + changesetId + "}";
        return "CreationTimeView{from=" + createdFrom + ", before=" + createdBefore + "}";
    }
}
