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
package ai.kompile.graph.reasoning.maintenance;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Identifies entities that belong to weakly-connected components smaller than a configurable
 * minimum size.
 *
 * <p>Connectivity is determined by treating all relations as undirected (source ↔ target),
 * which mirrors the behaviour of the existing {@code ComponentPruner} in the knowledge-graph
 * module. Components smaller than {@link #minComponentSize()} are selected for removal.</p>
 *
 * <p>This is a pure decision function: no store access, no deletion, no side effects.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * ComponentPruningPolicy policy = new ComponentPruningPolicy(3);
 * PruneResult result = policy.evaluate(graph);
 * // apply result.entityIds() via the store's deletion API
 * }</pre>
 */
public final class ComponentPruningPolicy {

    private static final Logger log = LoggerFactory.getLogger(ComponentPruningPolicy.class);

    /** Components strictly smaller than this value are selected for pruning. */
    private final int minComponentSize;

    /**
     * Construct a policy that selects entities in components smaller than {@code minComponentSize}.
     *
     * @param minComponentSize entities in components with fewer than this many members are selected
     *                         (a value of {@code 1} selects nothing; a value of {@code 2} selects
     *                         all isolated singleton entities)
     */
    public ComponentPruningPolicy(int minComponentSize) {
        if (minComponentSize < 1) {
            throw new IllegalArgumentException("minComponentSize must be >= 1, got: " + minComponentSize);
        }
        this.minComponentSize = minComponentSize;
    }

    /**
     * Evaluate the graph and return ids of entities in small connected components.
     *
     * @param graph the graph to inspect; must not be {@code null}
     * @return a {@link PruneResult} listing entity ids in small components
     */
    public PruneResult evaluate(ReasoningGraph graph) {
        // Build undirected adjacency over entity ids using the graph's own relation index
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (GraphEntity e : graph.entities()) {
            adj.put(e.id(), new HashSet<>());
        }
        for (GraphRelation rel : graph.relations()) {
            String src = rel.sourceId();
            String tgt = rel.targetId();
            if (adj.containsKey(src) && adj.containsKey(tgt)) {
                adj.get(src).add(tgt);
                adj.get(tgt).add(src);
            }
        }

        // BFS to find all connected components
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();

        for (String startId : adj.keySet()) {
            if (visited.contains(startId)) continue;
            Set<String> component = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(startId);
            visited.add(startId);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (String neighbor : adj.getOrDefault(current, Set.of())) {
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }

        log.debug("ComponentPruningPolicy: {} total components, minSize={}", components.size(), minComponentSize);

        PruneResult.Builder builder = PruneResult.builder();
        int selected = 0;
        for (Set<String> component : components) {
            if (component.size() >= minComponentSize) continue;
            for (String entityId : component) {
                selected++;
                builder.addEntity(entityId,
                        "small component: size=" + component.size() + " < minSize=" + minComponentSize);
            }
        }

        log.debug("ComponentPruningPolicy: selected {} entities across small components", selected);
        return builder.build();
    }

    public int minComponentSize() {
        return minComponentSize;
    }
}
