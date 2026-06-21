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
package ai.kompile.graph.reasoning.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A computed, lazy {@link ReasoningGraph} view that filters entities and relations by a temporal
 * window — without copying any data from the underlying graph.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #asOf(ReasoningGraph, Instant)} — includes only elements for which
 *       {@link GraphEntity#isValidAt(Instant)} / {@link GraphRelation#isValidAt(Instant)} returns
 *       {@code true} at the given instant.</li>
 *   <li>{@link #between(ReasoningGraph, Instant, Instant)} — includes only elements whose
 *       {@link TemporalInterval} (or point-timestamp-derived interval) overlaps
 *       {@code [from, to)}.</li>
 * </ul>
 *
 * <h2>Relation endpoint rule</h2>
 * <p>A relation is included in the view only when <em>both</em> of its endpoint entities are also
 * included in the view at the queried time. This prevents dangling edges from appearing in the
 * filtered graph.</p>
 *
 * <h2>Computed-view contract</h2>
 * <p>This view holds a reference to the live underlying graph. If the graph is a
 * {@link MutableReasoningGraph} and mutates after the view is created, the view reflects the
 * mutation — mirroring the {@code TypeHierarchy.fromGraph} snapshot caveat. All filtering is
 * performed on-demand (lazy); no pre-computed index is maintained. For tight reasoning loops
 * over large graphs, consider materializing the filtered collections into a fresh
 * {@link MutableReasoningGraph}.</p>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, JPA, or external dependencies. The view is callable from any layer that has a
 * {@link ReasoningGraph}.</p>
 */
public final class TemporalView implements ReasoningGraph {

    private final ReasoningGraph source;

    /**
     * The single point-in-time for {@code asOf} queries. When non-null, an element must satisfy
     * {@code isValidAt(asOfInstant)}.
     */
    private final Instant asOfInstant;

    /**
     * For {@code between} queries, the inclusive start of the window. May be {@code null}
     * (open-left — no lower bound).
     */
    private final Instant windowFrom;

    /**
     * For {@code between} queries, the exclusive end of the window. May be {@code null}
     * (open-right — no upper bound).
     */
    private final Instant windowTo;

    /**
     * Whether this view was created via {@link #asOf} ({@code true}) or
     * {@link #between} ({@code false}). Determines which filtering logic is applied.
     */
    private final boolean asOfMode;

    // ─── Private constructor ─────────────────────────────────────────────────────

    private TemporalView(ReasoningGraph source, Instant asOfInstant,
                         Instant windowFrom, Instant windowTo, boolean asOfMode) {
        this.source       = Objects.requireNonNull(source, "source");
        this.asOfInstant  = asOfInstant;
        this.windowFrom   = windowFrom;
        this.windowTo     = windowTo;
        this.asOfMode     = asOfMode;
    }

    // ─── Factories ───────────────────────────────────────────────────────────────

    /**
     * Create a view that includes only elements valid at the single instant {@code t}.
     *
     * <p>Each element is tested with {@link GraphEntity#isValidAt(Instant)} (respectively
     * {@link GraphRelation#isValidAt(Instant)}), which first checks any declared
     * {@link TemporalInterval} and falls back to the point {@link GraphEntity#timestamp()} when
     * no interval is present.</p>
     *
     * @param graph the underlying graph (never {@code null})
     * @param t     the query instant (never {@code null})
     * @return a lazy temporal view anchored at {@code t}
     */
    public static TemporalView asOf(ReasoningGraph graph, Instant t) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(t, "t");
        return new TemporalView(graph, t, null, null, true);
    }

    /**
     * Create a view that includes only elements whose temporal extent overlaps {@code [from, to)}.
     *
     * <p>If an element has a declared {@link TemporalInterval}, the overlap check uses
     * {@link TemporalInterval#overlaps(TemporalInterval)}. If an element carries only a point
     * {@link GraphEntity#timestamp()}, it is treated as a single-nanosecond interval via
     * {@link TemporalInterval#point(Instant)}, which overlaps {@code [from, to)} iff
     * {@code from <= timestamp < to}. Timeless elements (no timestamp, no interval) are always
     * included.</p>
     *
     * @param graph the underlying graph (never {@code null})
     * @param from  the inclusive start of the window (may be {@code null} for open-left)
     * @param to    the exclusive end of the window (may be {@code null} for open-right)
     * @return a lazy temporal view covering the window {@code [from, to)}
     */
    public static TemporalView between(ReasoningGraph graph, Instant from, Instant to) {
        Objects.requireNonNull(graph, "graph");
        return new TemporalView(graph, null, from, to, false);
    }

    // ─── ReasoningGraph implementation ──────────────────────────────────────────

    /**
     * Filtered collection of entities valid within this view's temporal window.
     *
     * <p>Iteration order matches the underlying graph's iteration order.</p>
     */
    @Override
    public Collection<GraphEntity> entities() {
        return source.entities().stream()
                .filter(this::entityMatches)
                .collect(Collectors.toList());
    }

    /**
     * Filtered collection of relations valid within this view's temporal window.
     *
     * <p>A relation is included only when the relation itself matches the window AND both its
     * source and target entities are valid at the same window (i.e. both are present in
     * {@link #entities()}).</p>
     */
    @Override
    public Collection<GraphRelation> relations() {
        // Materialise the valid entity id set once for endpoint checking
        Set<String> validEntityIds = entities().stream()
                .map(GraphEntity::id)
                .collect(Collectors.toSet());
        return source.relations().stream()
                .filter(r -> relationMatches(r, validEntityIds))
                .collect(Collectors.toList());
    }

    /**
     * Look up an entity by id within the temporal window. Returns {@link Optional#empty()} if
     * the entity exists in the source graph but is not valid at the queried time.
     */
    @Override
    public Optional<GraphEntity> entity(String id) {
        return source.entity(id).filter(this::entityMatches);
    }

    /**
     * Outgoing relations from {@code entityId} filtered by the temporal window. Both the
     * relation and the target endpoint must be valid.
     */
    @Override
    public List<GraphRelation> outgoing(String entityId) {
        Set<String> validEntityIds = entities().stream()
                .map(GraphEntity::id)
                .collect(Collectors.toSet());
        return source.outgoing(entityId).stream()
                .filter(r -> relationMatches(r, validEntityIds))
                .collect(Collectors.toList());
    }

    /**
     * Incoming relations to {@code entityId} filtered by the temporal window. Both the relation
     * and the source endpoint must be valid.
     */
    @Override
    public List<GraphRelation> incoming(String entityId) {
        Set<String> validEntityIds = entities().stream()
                .map(GraphEntity::id)
                .collect(Collectors.toSet());
        return source.incoming(entityId).stream()
                .filter(r -> relationMatches(r, validEntityIds))
                .collect(Collectors.toList());
    }

    /**
     * All relations incident to {@code entityId} (both directions) filtered by the temporal
     * window. Both the relation and the opposite endpoint must be valid.
     */
    @Override
    public List<GraphRelation> relationsOf(String entityId) {
        Set<String> validEntityIds = entities().stream()
                .map(GraphEntity::id)
                .collect(Collectors.toSet());
        return source.relationsOf(entityId).stream()
                .filter(r -> relationMatches(r, validEntityIds))
                .collect(Collectors.toList());
    }

    // ─── Internal filtering logic ────────────────────────────────────────────────

    private boolean entityMatches(GraphEntity e) {
        if (asOfMode) {
            return e.isValidAt(asOfInstant);
        }
        return matchesWindow(e.validTime(), e.timestamp());
    }

    private boolean relationMatches(GraphRelation r, Set<String> validEntityIds) {
        // Both endpoints must be valid in this view
        if (!validEntityIds.contains(r.sourceId()) || !validEntityIds.contains(r.targetId())) {
            return false;
        }
        if (asOfMode) {
            return r.isValidAt(asOfInstant);
        }
        return matchesWindow(r.validTime(), r.timestamp());
    }

    /**
     * Check whether an element with the given {@link TemporalInterval} (may be {@code null}) and
     * point timestamp (may be {@code null}) overlaps the {@code [windowFrom, windowTo)} window.
     *
     * <p>Logic:
     * <ul>
     *   <li>If a declared interval is present: use {@link TemporalInterval#overlaps}.</li>
     *   <li>If only a point timestamp is present: wrap it as a 1-ns interval and overlap-test.</li>
     *   <li>If neither is present (timeless element): always include.</li>
     * </ul>
     */
    private boolean matchesWindow(TemporalInterval elementInterval, Instant pointTs) {
        TemporalInterval window = TemporalInterval.of(windowFrom, windowTo);
        if (elementInterval != null) {
            return elementInterval.overlaps(window);
        }
        if (pointTs != null) {
            // A point timestamp t is in the window [from, to): inclusive start, exclusive end —
            // test from <= t < to directly. (The half-open overlap on a zero-width point would
            // wrongly exclude t exactly at the inclusive 'from' boundary.)
            boolean afterFrom = (windowFrom == null) || !pointTs.isBefore(windowFrom);
            boolean beforeTo  = (windowTo == null) || pointTs.isBefore(windowTo);
            return afterFrom && beforeTo;
        }
        // Timeless element — always included
        return true;
    }

    // ─── Diagnostics ─────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        if (asOfMode) {
            return "TemporalView{asOf=" + asOfInstant + ", source=" + source + '}';
        }
        return "TemporalView{from=" + windowFrom + ", to=" + windowTo + ", source=" + source + '}';
    }
}
