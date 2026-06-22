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

import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Materializes detected communities as standalone subgraph views — the bridge between community
 * detection ({@link CommunityAssignment}) and subgraph materialization ({@link SubgraphMaterializer}).
 *
 * <p>Each community's member set becomes the seed set of a {@link SubgraphSpec}, and the materializer
 * extracts that community as its own {@link SubgraphView}, so priors / reasoning / models can run
 * per-community rather than over the whole graph.</p>
 *
 * <p>Radius {@code 0} (the default) yields each community's <em>induced</em> subgraph: exactly its
 * members plus the edges among them — so the views partition the graph's nodes. A radius of {@code 1}
 * or more includes a boundary halo of neighbouring nodes (views then overlap at the boundary).</p>
 *
 * <p>Stateless and thread-safe (delegates to the stateless {@link SubgraphMaterializer}).</p>
 */
public final class CommunityViewMaterializer {

    private final SubgraphMaterializer materializer;

    /** Use the shared stateless {@link SubgraphMaterializer#INSTANCE}. */
    public CommunityViewMaterializer() {
        this(SubgraphMaterializer.INSTANCE);
    }

    /** Inject a specific materializer (e.g. for DI). */
    public CommunityViewMaterializer(SubgraphMaterializer materializer) {
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    /**
     * Materialize every community as its <em>induced</em> subgraph (radius 0, no node cap).
     *
     * @return ordered map of community id → its {@link SubgraphView}; empty communities are skipped
     */
    public Map<Integer, SubgraphView> materializeAll(ReasoningGraph graph, CommunityAssignment communities) {
        return materializeAll(graph, communities, 0, 0);
    }

    /**
     * Materialize every community as a subgraph view.
     *
     * @param graph       the source graph
     * @param communities the community assignment (from a {@code CommunityDetector})
     * @param radius      neighbourhood radius around each community's members
     *                    ({@code 0} = induced subgraph; {@code 1}+ = include a boundary halo)
     * @param maxNodes    per-view node cap ({@code 0} = unlimited)
     * @return ordered map of community id → its {@link SubgraphView}; empty communities are skipped
     */
    public Map<Integer, SubgraphView> materializeAll(ReasoningGraph graph, CommunityAssignment communities,
                                                     int radius, int maxNodes) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(communities, "communities");
        Map<Integer, SubgraphView> views = new LinkedHashMap<>();
        for (int cid = 0; cid < communities.communityCount(); cid++) {
            Set<String> members = communities.membersOf(cid);
            if (members.isEmpty()) {
                continue;
            }
            views.put(cid, materialize(graph, members, radius, maxNodes));
        }
        return views;
    }

    /**
     * Materialize a single community (by id) as a subgraph view.
     *
     * @param communityId a community id in {@code [0, communityCount-1]}
     * @param radius      neighbourhood radius ({@code 0} = induced subgraph)
     * @throws IllegalArgumentException if the community has no members
     */
    public SubgraphView materialize(ReasoningGraph graph, CommunityAssignment communities,
                                    int communityId, int radius) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(communities, "communities");
        Set<String> members = communities.membersOf(communityId);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("No members for community id " + communityId);
        }
        return materialize(graph, members, radius, 0);
    }

    private SubgraphView materialize(ReasoningGraph graph, Set<String> members, int radius, int maxNodes) {
        SubgraphSpec spec = SubgraphSpec.builder()
                .seedIds(members)
                .radius(radius)
                .maxNodes(maxNodes)
                .build();
        return materializer.materialize(graph, spec);
    }
}
