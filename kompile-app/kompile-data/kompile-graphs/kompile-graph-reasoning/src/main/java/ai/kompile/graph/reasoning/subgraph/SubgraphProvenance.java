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

import java.util.Set;

/**
 * Lightweight provenance record for a materialized subgraph view.
 *
 * <p>Describes <em>how</em> the view was produced so downstream reasoning engines and callers can
 * understand its scope without re-inspecting the full source graph. It is deliberately value-only
 * (a {@code record}) with no framework coupling.</p>
 *
 * <p>The {@link SubgraphMaterializer} attaches one of these to every view it returns via
 * {@link SubgraphView#provenance()}. It also writes the same information into the returned
 * {@link ai.kompile.graph.reasoning.model.MutableReasoningGraph} as entity-level metadata on a
 * sentinel "__subgraph_provenance__" key so that consumers who only see the bare graph can still
 * inspect origin data.</p>
 *
 * @param resolvedSeedIds  the entity ids that were actually found in the source graph and used as
 *                         BFS roots (intersection of explicit seeds + type-matched seeds with
 *                         present entities).
 * @param radius           the BFS hop radius applied.
 * @param sourceEntityCount the number of entities in the source graph at materialization time.
 * @param cappedByMaxNodes  {@code true} if the result was truncated because the BFS expansion
 *                          reached the {@link SubgraphSpec#maxNodes()} cap.
 */
public record SubgraphProvenance(
        Set<String> resolvedSeedIds,
        int radius,
        int sourceEntityCount,
        boolean cappedByMaxNodes
) {
    public SubgraphProvenance {
        resolvedSeedIds = Set.copyOf(resolvedSeedIds);
    }

    @Override
    public String toString() {
        return "SubgraphProvenance{"
                + "resolvedSeedIds=" + resolvedSeedIds
                + ", radius=" + radius
                + ", sourceEntityCount=" + sourceEntityCount
                + ", cappedByMaxNodes=" + cappedByMaxNodes
                + '}';
    }
}
