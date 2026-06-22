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
package ai.kompile.graph.reasoning.subgraph;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Specification for a focused subgraph view extracted from a larger {@link
 * ai.kompile.graph.reasoning.model.ReasoningGraph}.
 *
 * <p>A {@code SubgraphSpec} is the sole input to {@link SubgraphMaterializer#materialize}.
 * It encodes <em>what to select</em> — seeds (by id or entity-type), expansion depth, predicate
 * allow-list, confidence floor, and a node cap — so that callers can construct diverse views
 * without touching the materializer implementation.</p>
 *
 * <h3>Defaults (documented for {@code KbConfig} integration)</h3>
 * <ul>
 *   <li>{@code radius} — {@value #DEFAULT_RADIUS} hop(s). Increase for broader neighbourhood
 *       views; KbConfig key {@code subgraph.default.radius}, range [0, ∞), integer.</li>
 *   <li>{@code maxNodes} — {@value #DEFAULT_MAX_NODES} nodes. Hard cap after BFS; KbConfig key
 *       {@code subgraph.default.max.nodes}, range [1, ∞), integer. 0 = unlimited (not
 *       recommended for large graphs).</li>
 *   <li>{@code minEdgeConfidence} — {@value #DEFAULT_MIN_EDGE_CONFIDENCE}. Edges below this
 *       threshold are pruned before traversal; KbConfig key
 *       {@code subgraph.default.min.edge.confidence}, range [0.0, 1.0], double.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SubgraphSpec spec = SubgraphSpec.builder()
 *     .seedId("node-A")
 *     .seedId("node-B")
 *     .radius(2)
 *     .allowedRelationType("CAUSES")
 *     .allowedRelationType("SUPPORTS")
 *     .maxNodes(50)
 *     .minEdgeConfidence(0.5)
 *     .build();
 * MutableReasoningGraph view = materializer.materialize(fullGraph, spec);
 * }</pre>
 */
public final class SubgraphSpec {

    /** Default BFS expansion radius (1 = seeds + direct neighbours). */
    public static final int DEFAULT_RADIUS = 1;

    /** Default maximum number of nodes in the materialized view (0 = unlimited). */
    public static final int DEFAULT_MAX_NODES = 500;

    /**
     * Default minimum edge confidence. Edges whose {@link
     * ai.kompile.graph.reasoning.model.GraphRelation#confidence()} is below this value are
     * excluded from traversal and from the result.
     */
    public static final double DEFAULT_MIN_EDGE_CONFIDENCE = 0.0;

    /** Explicit seed entity ids. BFS begins from every id present here. */
    private final Set<String> seedIds;

    /**
     * Entity types used as implicit seeds. Every entity in the source graph whose
     * {@link ai.kompile.graph.reasoning.model.GraphEntity#type()} is in this set is treated as a
     * seed node.
     */
    private final Set<String> seedEntityTypes;

    /**
     * Allowed relation types. When non-empty, only edges whose
     * {@link ai.kompile.graph.reasoning.model.GraphRelation#type()} is present in this set are
     * traversed. An empty set means <em>all relation types are allowed</em>.
     */
    private final Set<String> allowedRelationTypes;

    /** BFS expansion depth from each seed. {@code 0} = seeds only (no traversal). */
    private final int radius;

    /**
     * Maximum number of entities in the result. When the BFS queue would exceed this cap the
     * expansion stops (frontier nodes already visited are included; the rest are dropped). A value
     * of {@code 0} means unlimited.
     */
    private final int maxNodes;

    /**
     * Minimum relation confidence threshold. Relations whose {@code confidence()} is strictly
     * below this value are excluded from traversal and from the returned subgraph.
     */
    private final double minEdgeConfidence;

    private SubgraphSpec(Builder b) {
        this.seedIds             = Set.copyOf(b.seedIds);
        this.seedEntityTypes     = Set.copyOf(b.seedEntityTypes);
        this.allowedRelationTypes = Set.copyOf(b.allowedRelationTypes);
        this.radius              = b.radius;
        this.maxNodes            = b.maxNodes;
        this.minEdgeConfidence   = b.minEdgeConfidence;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Explicit seed node ids. Never {@code null}; may be empty. */
    public Set<String> seedIds() { return seedIds; }

    /** Entity types whose members are implicit seeds. Never {@code null}; may be empty. */
    public Set<String> seedEntityTypes() { return seedEntityTypes; }

    /**
     * Allow-listed relation types for traversal. An empty set means all types are allowed.
     * Never {@code null}.
     */
    public Set<String> allowedRelationTypes() { return allowedRelationTypes; }

    /** BFS hop radius. {@code 0} returns seeds only. Default: {@value #DEFAULT_RADIUS}. */
    public int radius() { return radius; }

    /**
     * Hard cap on result entities. {@code 0} = unlimited.
     * Default: {@value #DEFAULT_MAX_NODES}.
     */
    public int maxNodes() { return maxNodes; }

    /**
     * Minimum {@link ai.kompile.graph.reasoning.model.GraphRelation#confidence()} for an edge
     * to be included in traversal. Default: {@value #DEFAULT_MIN_EDGE_CONFIDENCE}.
     */
    public double minEdgeConfidence() { return minEdgeConfidence; }

    /** Whether a relation type is allowed given the current allow-list. */
    public boolean isRelationTypeAllowed(String relationType) {
        return allowedRelationTypes.isEmpty() || allowedRelationTypes.contains(relationType);
    }

    /** Whether a relation's confidence meets the minimum threshold. */
    public boolean isConfidenceSufficient(double confidence) {
        return confidence >= minEdgeConfidence;
    }

    // -----------------------------------------------------------------------
    // Factory helpers
    // -----------------------------------------------------------------------

    /** Start building a {@code SubgraphSpec} with all defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience: a spec seeded by a single node id with all defaults applied.
     * Equivalent to {@code builder().seedId(id).build()}.
     */
    public static SubgraphSpec ofSeedId(String id) {
        return builder().seedId(id).build();
    }

    /**
     * Convenience: a spec seeded by one or more node ids with the given radius and no other
     * filters.
     */
    public static SubgraphSpec ofSeedIds(int radius, String... ids) {
        Builder b = builder().radius(radius);
        for (String id : ids) b.seedId(id);
        return b.build();
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /** Fluent builder for {@link SubgraphSpec}. */
    public static final class Builder {

        private final Set<String> seedIds             = new LinkedHashSet<>();
        private final Set<String> seedEntityTypes     = new LinkedHashSet<>();
        private final Set<String> allowedRelationTypes = new LinkedHashSet<>();
        private int    radius           = DEFAULT_RADIUS;
        private int    maxNodes         = DEFAULT_MAX_NODES;
        private double minEdgeConfidence = DEFAULT_MIN_EDGE_CONFIDENCE;

        private Builder() {}

        /** Add an explicit seed node id. */
        public Builder seedId(String id) {
            Objects.requireNonNull(id, "seedId");
            seedIds.add(id);
            return this;
        }

        /** Add multiple explicit seed node ids. */
        public Builder seedIds(Collection<String> ids) {
            Objects.requireNonNull(ids, "seedIds");
            seedIds.addAll(ids);
            return this;
        }

        /**
         * Add an entity type whose members are treated as implicit seeds. Matched by exact string
         * equality against {@link ai.kompile.graph.reasoning.model.GraphEntity#type()}.
         */
        public Builder seedEntityType(String entityType) {
            Objects.requireNonNull(entityType, "entityType");
            seedEntityTypes.add(entityType);
            return this;
        }

        /** Add multiple entity types as implicit seeds. */
        public Builder seedEntityTypes(Collection<String> types) {
            Objects.requireNonNull(types, "types");
            seedEntityTypes.addAll(types);
            return this;
        }

        /**
         * Allow a specific relation type during BFS traversal. If no type is added, all relation
         * types are allowed (the allow-list is open by default).
         */
        public Builder allowedRelationType(String relationType) {
            Objects.requireNonNull(relationType, "relationType");
            allowedRelationTypes.add(relationType);
            return this;
        }

        /** Allow multiple relation types. */
        public Builder allowedRelationTypes(Collection<String> types) {
            Objects.requireNonNull(types, "types");
            allowedRelationTypes.addAll(types);
            return this;
        }

        /**
         * BFS expansion radius from each seed node. {@code 0} = seeds only (no traversal).
         * Must be &ge; 0. Default: {@value SubgraphSpec#DEFAULT_RADIUS}.
         */
        public Builder radius(int radius) {
            if (radius < 0) throw new IllegalArgumentException("radius must be >= 0, got " + radius);
            this.radius = radius;
            return this;
        }

        /**
         * Maximum number of entities in the result. {@code 0} = unlimited.
         * Must be &ge; 0. Default: {@value SubgraphSpec#DEFAULT_MAX_NODES}.
         */
        public Builder maxNodes(int maxNodes) {
            if (maxNodes < 0) throw new IllegalArgumentException("maxNodes must be >= 0, got " + maxNodes);
            this.maxNodes = maxNodes;
            return this;
        }

        /**
         * Minimum edge confidence threshold; edges below this are excluded from traversal.
         * Must be in [0.0, 1.0]. Default: {@value SubgraphSpec#DEFAULT_MIN_EDGE_CONFIDENCE}.
         */
        public Builder minEdgeConfidence(double minEdgeConfidence) {
            if (minEdgeConfidence < 0.0 || minEdgeConfidence > 1.0) {
                throw new IllegalArgumentException(
                        "minEdgeConfidence must be in [0.0, 1.0], got " + minEdgeConfidence);
            }
            this.minEdgeConfidence = minEdgeConfidence;
            return this;
        }

        /** Build and validate the {@link SubgraphSpec}. */
        public SubgraphSpec build() {
            if (seedIds.isEmpty() && seedEntityTypes.isEmpty()) {
                throw new IllegalStateException(
                        "SubgraphSpec requires at least one seedId or seedEntityType");
            }
            return new SubgraphSpec(this);
        }
    }

    @Override
    public String toString() {
        return "SubgraphSpec{"
                + "seedIds=" + seedIds
                + ", seedEntityTypes=" + seedEntityTypes
                + ", allowedRelationTypes=" + allowedRelationTypes
                + ", radius=" + radius
                + ", maxNodes=" + maxNodes
                + ", minEdgeConfidence=" + minEdgeConfidence
                + '}';
    }
}
