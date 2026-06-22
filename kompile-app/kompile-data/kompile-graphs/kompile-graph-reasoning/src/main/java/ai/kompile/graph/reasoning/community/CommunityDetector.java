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
package ai.kompile.graph.reasoning.community;

import ai.kompile.graph.reasoning.model.ReasoningGraph;

/**
 * Strategy interface for community detection on a {@link ReasoningGraph}.
 *
 * <p>Implementations treat the graph as <em>undirected</em>: each relation contributes weight
 * symmetrically between its two endpoints regardless of {@link
 * ai.kompile.graph.reasoning.model.GraphRelation#directed()}. The edge weight used is
 * {@link ai.kompile.graph.reasoning.model.GraphRelation#confidence()} when available (multiplied
 * with {@link ai.kompile.graph.reasoning.model.GraphRelation#weight()} to form a combined
 * affinity), falling back to unit weight for unweighted graphs.</p>
 *
 * <p>Implementations must be deterministic given the same graph and the same seed (see
 * {@link #detect(ReasoningGraph, long)}); the no-seed variant is a convenience that uses a
 * default seed defined by each implementation.</p>
 *
 * <p>This interface is deliberately free of Spring, persistence, or IO — it is a pure algorithm
 * boundary within the kompile-graph-reasoning library.</p>
 */
public interface CommunityDetector {

    /**
     * Run community detection with a caller-supplied RNG seed.
     *
     * <p>Determinism guarantee: identical graph (same entity/relation iteration order) and
     * identical seed must produce identical {@link CommunityAssignment}.</p>
     *
     * @param graph the graph to partition; must be non-null
     * @param seed  RNG seed for deterministic behaviour
     * @return the community assignment; never {@code null}
     * @throws IllegalArgumentException if the graph is null
     */
    CommunityAssignment detect(ReasoningGraph graph, long seed);

    /**
     * Run community detection using the implementation's documented default seed.
     *
     * @param graph the graph to partition
     * @return the community assignment
     */
    default CommunityAssignment detect(ReasoningGraph graph) {
        return detect(graph, defaultSeed());
    }

    /**
     * The default seed used when no seed is supplied.
     * Implementations document this value in their class javadoc.
     *
     * @return the default seed
     */
    default long defaultSeed() {
        return 42L;
    }
}
