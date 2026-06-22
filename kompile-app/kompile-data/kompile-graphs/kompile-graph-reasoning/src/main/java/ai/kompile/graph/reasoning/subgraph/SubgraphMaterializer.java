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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Materializes a focused {@link SubgraphView} from a {@link ReasoningGraph} using the criteria
 * encoded in a {@link SubgraphSpec}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><strong>Seed resolution</strong>: collect explicit {@link SubgraphSpec#seedIds()} that
 *       exist in the source graph, then add every entity whose
 *       {@link GraphEntity#type()} matches a {@link SubgraphSpec#seedEntityTypes()} entry.</li>
 *   <li><strong>BFS expansion</strong>: level-by-level BFS up to {@link SubgraphSpec#radius()}
 *       hops. At each step, traverse both {@link ReasoningGraph#outgoing(String) outgoing} and
 *       {@link ReasoningGraph#incoming(String) incoming} edges (undirected neighbourhood
 *       semantics). Edges are admitted only when they pass the
 *       {@link SubgraphSpec#allowedRelationTypes() predicate allow-list} (empty = all allowed)
 *       and the {@link SubgraphSpec#minEdgeConfidence() confidence floor}.</li>
 *   <li><strong>maxNodes cap</strong>: when the node count would exceed
 *       {@link SubgraphSpec#maxNodes()} (and that value is non-zero), expansion stops. Nodes
 *       already admitted are included; pending frontier nodes are dropped.</li>
 *   <li><strong>Edge collection</strong>: after BFS, every relation in the source whose
 *       <em>both</em> endpoints are in the admitted node set is included, again filtered by
 *       predicate and confidence. This ensures no dangling edges appear.</li>
 *   <li><strong>Provenance</strong>: the returned {@link SubgraphView} carries a
 *       {@link SubgraphProvenance} record with the resolved seed ids, radius, source size, and
 *       whether the cap was hit.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>{@code SubgraphMaterializer} is stateless and thread-safe. Multiple threads may call
 * {@link #materialize} concurrently on the same instance (and with the same or different source
 * graphs). The source graph itself must not be mutated during materialization.</p>
 *
 * <h3>Tunables (for KbConfig integration)</h3>
 * <p>All tuning parameters are carried by the {@link SubgraphSpec}, not by this class. This keeps
 * the materializer stateless and allows per-call customization without recreating the service.
 * Defaults documented in {@link SubgraphSpec} can be surfaced as {@code KbConfig} keys:</p>
 * <ul>
 *   <li>{@code subgraph.default.radius} — default {@value SubgraphSpec#DEFAULT_RADIUS}, int, [0, ∞)</li>
 *   <li>{@code subgraph.default.max.nodes} — default {@value SubgraphSpec#DEFAULT_MAX_NODES}, int,
 *       [0, ∞) (0 = unlimited)</li>
 *   <li>{@code subgraph.default.min.edge.confidence} — default
 *       {@value SubgraphSpec#DEFAULT_MIN_EDGE_CONFIDENCE}, double, [0.0, 1.0]</li>
 * </ul>
 */
public final class SubgraphMaterializer {

    /**
     * Singleton stateless instance. Prefer using this directly; alternatively call
     * {@link #SubgraphMaterializer()} for DI injection sites that require a bean.
     */
    public static final SubgraphMaterializer INSTANCE = new SubgraphMaterializer();

    /** No configuration needed — all tunables live in {@link SubgraphSpec}. */
    public SubgraphMaterializer() {}

    // -----------------------------------------------------------------------
    // Primary API
    // -----------------------------------------------------------------------

    /**
     * Materialize a focused subgraph from {@code source} according to {@code spec}.
     *
     * @param source the full graph to draw from; must not be {@code null}
     * @param spec   the selection criteria; must not be {@code null}
     * @return a {@link SubgraphView} whose {@link SubgraphView#graph()} is a new
     *         {@link MutableReasoningGraph} containing only the matched neighbourhood
     */
    public SubgraphView materialize(ReasoningGraph source, SubgraphSpec spec) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(spec,   "spec");

        int sourceSize = source.entityCount();
        int maxNodes   = spec.maxNodes();

        // Step 1: resolve seeds
        Set<String> visited = new LinkedHashSet<>();
        List<String> seedList = resolveSeedIds(source, spec, visited);

        boolean capped = false;

        // Step 2: BFS expansion
        if (spec.radius() > 0 && !visited.isEmpty()) {
            Deque<String> frontier = new ArrayDeque<>(seedList);
            for (int hop = 0; hop < spec.radius() && !frontier.isEmpty(); hop++) {
                Deque<String> nextFrontier = new ArrayDeque<>();
                while (!frontier.isEmpty()) {
                    String nodeId = frontier.poll();
                    List<GraphRelation> incident = incidentEdges(source, nodeId, spec);
                    for (GraphRelation rel : incident) {
                        // Follow both directions (undirected neighbourhood)
                        String neighbour = rel.sourceId().equals(nodeId) ? rel.targetId() : rel.sourceId();
                        if (visited.contains(neighbour)) continue;
                        if (maxNodes > 0 && visited.size() >= maxNodes) {
                            capped = true;
                            break;
                        }
                        // Confirm neighbour actually exists (safety: dangling edges in source)
                        if (!source.containsEntity(neighbour)) continue;
                        visited.add(neighbour);
                        nextFrontier.add(neighbour);
                    }
                    if (capped) break;
                }
                if (capped) break;
                frontier = nextFrontier;
            }
        }

        // Step 3: Build the result graph — entities
        MutableReasoningGraph view = new MutableReasoningGraph();
        for (String id : visited) {
            source.entity(id).ifPresent(view::addEntity);
        }

        // Step 4: Add edges whose both endpoints are in the admitted set, filtered
        for (GraphRelation rel : source.relations()) {
            if (!visited.contains(rel.sourceId()) || !visited.contains(rel.targetId())) continue;
            if (!spec.isRelationTypeAllowed(rel.type())) continue;
            if (!spec.isConfidenceSufficient(rel.confidence())) continue;
            view.addRelation(rel);
        }

        // Step 5: provenance
        SubgraphProvenance prov = new SubgraphProvenance(
                Set.copyOf(seedList),
                spec.radius(),
                sourceSize,
                capped);

        return new SubgraphView(view, prov);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Collect seed ids from explicit ids and entity-type seeds; returns a list of those that
     * exist in {@code source}. Also populates {@code visited} as a side-effect.
     */
    private List<String> resolveSeedIds(ReasoningGraph source, SubgraphSpec spec, Set<String> visited) {
        List<String> resolved = new ArrayList<>();

        // Explicit ids
        for (String id : spec.seedIds()) {
            if (source.containsEntity(id) && !visited.contains(id)) {
                visited.add(id);
                resolved.add(id);
            }
        }

        // Type-based implicit seeds
        Set<String> seedTypes = spec.seedEntityTypes();
        if (!seedTypes.isEmpty()) {
            for (GraphEntity entity : source.entities()) {
                if (seedTypes.contains(entity.type()) && !visited.contains(entity.id())) {
                    // Respect maxNodes cap even for seeds
                    int maxNodes = spec.maxNodes();
                    if (maxNodes > 0 && visited.size() >= maxNodes) break;
                    visited.add(entity.id());
                    resolved.add(entity.id());
                }
            }
        }

        return resolved;
    }

    /**
     * Returns all incident edges of {@code nodeId} that pass the spec's predicate allow-list and
     * confidence filter. Combines outgoing and incoming for undirected BFS.
     */
    private List<GraphRelation> incidentEdges(ReasoningGraph source, String nodeId, SubgraphSpec spec) {
        List<GraphRelation> outgoing = source.outgoing(nodeId);
        List<GraphRelation> incoming = source.incoming(nodeId);

        List<GraphRelation> result = new ArrayList<>(outgoing.size() + incoming.size());
        addFiltered(outgoing, spec, result);
        addFiltered(incoming, spec, result);
        return result;
    }

    private void addFiltered(List<GraphRelation> edges, SubgraphSpec spec, List<GraphRelation> out) {
        for (GraphRelation rel : edges) {
            if (spec.isRelationTypeAllowed(rel.type()) && spec.isConfidenceSufficient(rel.confidence())) {
                out.add(rel);
            }
        }
    }
}
