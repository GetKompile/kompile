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
package ai.kompile.graph.reasoning.simulation;

import java.util.List;

/**
 * One hydration increment of a {@link ScenarioRun}: the nodes introduced at this tick and the
 * edges whose endpoints all exist by this tick.
 *
 * <p>Invariant (enforced by generators, asserted in tests): an edge only ever appears in a tick
 * at or after the ticks that introduced both of its endpoints, so a runner can apply ticks
 * strictly in order without dangling references. Applying the same tick twice is idempotent at
 * the store level because node external-ids and edge (source,target) pairs are deterministic.</p>
 *
 * @param tickIndex zero-based position of this batch in the run
 * @param nodes     entities introduced at this tick (possibly empty)
 * @param edges     observations added at this tick (possibly empty)
 */
public record TickBatch(
        int tickIndex,
        List<ScenarioNode> nodes,
        List<ScenarioEdge> edges) {

    public TickBatch {
        nodes = (nodes == null) ? List.of() : List.copyOf(nodes);
        edges = (edges == null) ? List.of() : List.copyOf(edges);
    }
}
