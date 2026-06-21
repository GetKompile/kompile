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
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identifies entities that have no incident relations (neither as source nor as target).
 *
 * <p>This is a pure decision function: it inspects the {@link ReasoningGraph} and returns the
 * set of entity ids that are orphans. No store access, no deletion, no side effects.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * ReasoningGraph graph = adapter.subgraph(allNodeIds);
 * PruneResult result = new OrphanPruningPolicy().evaluate(graph);
 * // apply result.entityIds() via the store-specific deletion API
 * }</pre>
 */
public final class OrphanPruningPolicy {

    private static final Logger log = LoggerFactory.getLogger(OrphanPruningPolicy.class);

    /** Singleton instance — the policy is stateless. */
    public static final OrphanPruningPolicy INSTANCE = new OrphanPruningPolicy();

    public OrphanPruningPolicy() {}

    /**
     * Evaluate which entities in {@code graph} are orphans.
     *
     * <p>An entity is an orphan if {@link ReasoningGraph#relationsOf(String)} is empty
     * (no outgoing <em>and</em> no incoming relations).</p>
     *
     * @param graph the graph to inspect; must not be {@code null}
     * @return a {@link PruneResult} listing orphan entity ids with reason strings
     */
    public PruneResult evaluate(ReasoningGraph graph) {
        PruneResult.Builder builder = PruneResult.builder();
        int total = 0;
        int orphans = 0;

        for (GraphEntity entity : graph.entities()) {
            total++;
            if (graph.relationsOf(entity.id()).isEmpty()) {
                orphans++;
                builder.addEntity(entity.id(),
                        "orphan: no incident relations (type=" + entity.type()
                                + ", label=" + entity.label() + ")");
            }
        }

        log.debug("OrphanPruningPolicy: {}/{} entities are orphans", orphans, total);
        return builder.build();
    }
}
