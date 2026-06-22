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

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import java.util.Objects;

/**
 * The result of a subgraph materialization: a self-contained
 * {@link MutableReasoningGraph} restricted to the neighbourhood specified by a
 * {@link SubgraphSpec}, together with {@link SubgraphProvenance provenance} metadata that
 * describes how the view was produced.
 *
 * <p>The {@link #graph()} can be handed directly to any reasoning engine (PSL, Bayesian, MEBN,
 * causal, etc.) as if it were the full source graph — the engine has no knowledge of the
 * restriction. This is the primary purpose of the subgraph materialization feature.</p>
 *
 * <p>Use {@link #provenance()} to understand scope: which seeds were resolved, what radius was
 * used, and whether the result was truncated by a {@link SubgraphSpec#maxNodes()} cap.</p>
 */
public final class SubgraphView {

    private final MutableReasoningGraph graph;
    private final SubgraphProvenance provenance;

    SubgraphView(MutableReasoningGraph graph, SubgraphProvenance provenance) {
        this.graph      = Objects.requireNonNull(graph, "graph");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    /**
     * The materialized subgraph. It is a new, independent {@link MutableReasoningGraph} populated
     * with copies (re-use of the same immutable entity/relation objects) from the source graph.
     * Mutations to this graph do not affect the source.
     */
    public MutableReasoningGraph graph() { return graph; }

    /**
     * Provenance of the view: resolved seed ids, BFS radius, source graph size, and whether the
     * result was capped.
     */
    public SubgraphProvenance provenance() { return provenance; }

    @Override
    public String toString() {
        return "SubgraphView{"
                + "entities=" + graph.entityCount()
                + ", relations=" + graph.relationCount()
                + ", provenance=" + provenance
                + '}';
    }
}
