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
package ai.kompile.graph.reasoning.hybrid;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks entities by a <b>hybrid</b> of <i>structural</i> reasoning and <i>semantic</i> similarity —
 * the library's answer to "given this graph (and optionally a query), which entities matter most?".
 *
 * <ul>
 *   <li><b>Structural</b> score: collective inference over the graph structure — either PSL/HL-MRF
 *       soft-truth activation ({@code State(entity)}) or a Bayesian posterior {@code P(entity)} via
 *       exact variable elimination. This uses each entity's {@link GraphEntity#weight()} as its
 *       prior and each relation's {@link ai.kompile.graph.reasoning.model.GraphRelation#weight()} as
 *       its causal strength, so it is purely structural/probabilistic.</li>
 *   <li><b>Semantic</b> score: cosine similarity of each entity's {@link GraphEntity#embedding()} to
 *       a supplied query vector (0 for entities with no embedding).</li>
 * </ul>
 *
 * <p>The two are blended as {@code (structuralWeight·structural + semanticWeight·semantic) / (sum of
 * weights)}. Set {@code semanticWeight = 0} for pure structural reasoning, or
 * {@code structuralWeight = 0} for pure vector search; the default 0.6/0.4 favors structure. This
 * mirrors the knowledge graph's HYBRID retrieval mode, but over the generic
 * {@link ReasoningGraph} so any consumer can reuse it.</p>
 */
public class HybridReasoner {

    /** Which structural engine produces the per-entity structural score. */
    public enum Structural {
        /** PSL / HL-MRF collective soft-truth activation. */
        PSL,
        /** Bayesian network posterior via exact variable elimination. */
        BAYESIAN
    }

    private Structural structural = Structural.PSL;
    private double structuralWeight = 0.6;
    private double semanticWeight = 0.4;
    private int semanticResolutionHops;

    public HybridReasoner structural(Structural structural) { this.structural = structural; return this; }
    public HybridReasoner structuralWeight(double w) { this.structuralWeight = w; return this; }
    public HybridReasoner semanticWeight(double w) { this.semanticWeight = w; return this; }

    /**
     * Resolve missing entity vectors through relation embeddings and neighboring entities before
     * semantic scoring. The default is {@code 0}, preserving direct-vector-only behavior; a small
     * value such as {@code 2} is useful for sparse crawled graphs.
     */
    public HybridReasoner semanticResolutionHops(int hops) {
        this.semanticResolutionHops = Math.max(0, hops);
        return this;
    }

    /**
     * @param entityId        the ranked entity
     * @param score           blended hybrid score in {@code [0, 1]}
     * @param structuralScore the structural component (PSL activation or Bayesian posterior)
     * @param semanticScore   the semantic component (cosine similarity, clamped to {@code [0, 1]})
     */
    public record ScoredEntity(String entityId, double score, double structuralScore, double semanticScore) {
    }

    /** Pure structural ranking (no embeddings / query). */
    public List<ScoredEntity> rank(ReasoningGraph graph) {
        return rank(graph, null);
    }

    /**
     * Rank every entity in {@code graph} by the hybrid score. When {@code queryEmbedding} is
     * {@code null} the semantic component is omitted (equivalent to a pure structural ranking).
     */
    public List<ScoredEntity> rank(ReasoningGraph graph, double[] queryEmbedding) {
        Map<String, Double> structuralScores = structuralScores(graph);
        return rankWithStructuralScores(graph, structuralScores, queryEmbedding);
    }

    /**
     * Rank with structural scores already computed by a caller. This is the inexpensive entry point
     * for online learning and cascades that already ran PSL/MEBN inference and only need to add the
     * semantic component without repeating structural inference.
     */
    public List<ScoredEntity> rankWithStructuralScores(ReasoningGraph graph,
                                                        Map<String, Double> structuralScores,
                                                        double[] queryEmbedding) {
        Map<String, Double> suppliedStructural = structuralScores == null ? Map.of() : structuralScores;

        double sw = structuralWeight;
        double mw = (queryEmbedding == null) ? 0.0 : semanticWeight;
        double total = sw + mw;
        if (total <= 0.0) {
            sw = 1.0;
            mw = 0.0;
            total = 1.0;
        }

        List<ScoredEntity> out = new ArrayList<>(graph.entityCount());
        for (GraphEntity e : graph.entities()) {
            double s = suppliedStructural.getOrDefault(e.id(), 0.0);
            double directMagnitude = e.hasEmbedding() ? Embeddings.magnitude(e.embedding()) : 0.0;
            double[] semanticVector = Double.isFinite(directMagnitude) && directMagnitude > 0.0
                    ? e.embedding() : null;
            if (mw > 0.0 && semanticVector == null && semanticResolutionHops > 0) {
                GraphEmbeddingResolver.Resolved resolved = GraphEmbeddingResolver.resolve(
                        graph, e.id(), semanticResolutionHops);
                semanticVector = resolved.present() ? resolved.vector() : null;
            }
            double cosine = mw > 0.0 ? Embeddings.cosine(semanticVector, queryEmbedding) : 0.0;
            double sem = Double.isFinite(cosine) ? Math.max(0.0, cosine) : 0.0;
            double blended = (sw * s + mw * sem) / total;
            out.add(new ScoredEntity(e.id(), blended, s, sem));
        }
        out.sort(Comparator.comparingDouble(ScoredEntity::score).reversed()
                .thenComparing(ScoredEntity::entityId));
        return out;
    }

    private Map<String, Double> structuralScores(ReasoningGraph graph) {
        return structural == Structural.BAYESIAN ? bayesianPosteriors(graph) : pslActivations(graph);
    }

    private Map<String, Double> pslActivations(ReasoningGraph graph) {
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        PslProgram program = builder.build(graph);
        if (graph instanceof UnifiedGraph unifiedGraph) {
            program = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                    unifiedGraph, program);
        }
        HlMrfMapInference.Result res = HlMrfMapInference.solve(program);
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : builder.entityIdToConstant().entrySet()) {
            Double v = res.values().get(GraphPslProgramBuilder.STATE + "(" + e.getValue() + ")");
            out.put(e.getKey(), v != null ? v : 0.0);
        }
        return out;
    }

    private Map<String, Double> bayesianPosteriors(ReasoningGraph graph) {
        GraphBayesianNetworkBuilder builder = new GraphBayesianNetworkBuilder();
        BayesianNetwork network = builder.build(graph);
        Map<String, Double> raw = VariableElimination.queryAll(network, Map.of());
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : builder.entityIdToVariable().entrySet()) {
            Double v = raw.get(e.getValue());
            out.put(e.getKey(), v != null ? v : 0.0);
        }
        return out;
    }
}
