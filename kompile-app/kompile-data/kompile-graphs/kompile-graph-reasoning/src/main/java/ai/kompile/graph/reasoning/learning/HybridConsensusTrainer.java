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
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingConfig;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingLearner;
import ai.kompile.graph.reasoning.embedding.Embeddings;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <h2>Joint, hybrid-supervised training of every learned parameter in the KB</h2>
 *
 * <p>Previously each learned model was trained in isolation: PSL rule weights and MEBN parameters in
 * two separate, sequential steps of the grounding cascade (each re-deriving its own observed targets),
 * and entity embeddings in a wholly separate offline job. The {@link HybridReasoner} — which fuses
 * structural (PSL/HL-MRF or Bayesian) and semantic (embedding) signal into a single ranked response —
 * was used only for explanation, never for learning.</p>
 *
 * <p>This trainer composes them into <b>one</b> training step driven by <b>one shared signal</b>:</p>
 * <ol>
 *   <li><b>Co-train embeddings into the graph</b> ({@link EmbeddingLearner#learnInto}) so the hybrid
 *       reasoner has fresh semantic vectors.</li>
 *   <li><b>Rank with the hybrid reasoner</b> ({@link HybridReasoner#rank}) — the structural⊕semantic
 *       ranked response over entities.</li>
 *   <li><b>Derive one consensus signal</b>: the observed soft targets pulled toward the hybrid ranking
 *       (each atom's target blends its observed value with the ranked importance of the entities it
 *       mentions).</li>
 *   <li><b>Co-train PSL weights and MEBN parameters against that SAME consensus</b> — simultaneously,
 *       not on independently re-derived targets.</li>
 * </ol>
 *
 * <p>The whole thing is iterated for {@code rounds} so the models converge to a shared consensus
 * (retrain → re-rank → retrain). Expensive learners (embeddings, MEBN finite-difference) can be
 * disabled per call via the {@link Plan} flags so the caller controls cadence (e.g. PSL every cascade,
 * MEBN + embeddings on a throttle) without splitting the training path back apart.</p>
 *
 * <p>Pure orchestration over the existing learners — no new optimizer. Infra-free and unit-testable
 * against a hand-built {@link MutableReasoningGraph}.</p>
 */
public final class HybridConsensusTrainer {

    /** Named layer used when embeddings are learned into a {@link UnifiedGraph}. */
    public static final String LEARNED_EMBEDDING_LAYER = "hybrid-consensus.learned";

    private HybridConsensusTrainer() {
    }

    /**
     * Which models to co-train this step and with what effort. Disabled models (null learner or false
     * flag) are simply skipped — the others still train against the shared hybrid consensus.
     *
     * @param pslLearner       PSL weight learner (warm-start mini-batch); null skips PSL
     * @param pslSteps         PSL update steps per round (≥1)
     * @param trainMebn        co-train MEBN parameters this step
     * @param mebnLearner      MEBN finite-difference learner
     * @param mebnTheory       the MEBN theory to update in place
     * @param mebnGraph        the graph the MEBN net is templated over
     * @param mebnEpochs       MEBN epochs per round (≥1)
     * @param trainEmbeddings  co-train entity embeddings into {@code graph} this step
     * @param embLearner       embedding learner (Node2Vec / RotatE …)
     * @param embConfig        embedding hyper-parameters (defaults if null)
     * @param reasoner         the hybrid reasoner producing the ranked response (required)
     * @param consensusWeight  how strongly the hybrid ranking pulls the targets, in [0,1]
     * @param rounds           consensus iterations (≥1): retrain → re-rank → retrain
     */
    public record Plan(
            PslWeightLearningService pslLearner, int pslSteps,
            boolean trainMebn, MebnWeightLearner mebnLearner, MTheory mebnTheory, ReasoningGraph mebnGraph, int mebnEpochs,
            boolean trainEmbeddings, EmbeddingLearner embLearner, EmbeddingConfig embConfig,
            HybridReasoner reasoner, double consensusWeight, int rounds,
            Map<String, String> entityAliases, double[] queryEmbedding) {

        public Plan {
            entityAliases = entityAliases == null ? Map.of() : Map.copyOf(entityAliases);
            queryEmbedding = queryEmbedding == null ? null : queryEmbedding.clone();
        }

        /** Backward-compatible plan without explicit PSL aliases or semantic query. */
        public Plan(PslWeightLearningService pslLearner, int pslSteps,
                    boolean trainMebn, MebnWeightLearner mebnLearner, MTheory mebnTheory,
                    ReasoningGraph mebnGraph, int mebnEpochs,
                    boolean trainEmbeddings, EmbeddingLearner embLearner, EmbeddingConfig embConfig,
                    HybridReasoner reasoner, double consensusWeight, int rounds) {
            this(pslLearner, pslSteps, trainMebn, mebnLearner, mebnTheory, mebnGraph,
                    mebnEpochs, trainEmbeddings, embLearner, embConfig, reasoner,
                    consensusWeight, rounds, Map.of(), null);
        }
    }

    /**
     * @param trainedProgram    the PSL program after co-training (== input program when PSL skipped)
     * @param mebnTrained       whether MEBN parameters were updated
     * @param embeddingsTrained whether embeddings were retrained into the graph
     * @param consensusTargets  the shared hybrid-consensus signal all models trained against
     * @param ranking           the hybrid reasoner's final ranked response
     * @param rounds            consensus iterations performed
     * @param modelsTrained     how many distinct models were co-trained (1–3)
     */
    public record Result(
            PslProgram trainedProgram, boolean mebnTrained, boolean embeddingsTrained,
            Map<String, Double> consensusTargets, List<ScoredEntity> ranking, int rounds,
            int modelsTrained, boolean semanticConsensus, int semanticAnchorCount,
            String embeddingLayer) {
    }

    /**
     * A cheap online consensus that combines caller-supplied structural scores with graph semantic
     * context. No PSL or Bayesian inference is performed by this operation.
     */
    public record ContextualConsensus(
            Map<String, Double> targets, List<ScoredEntity> ranking,
            boolean semanticConsensus, int semanticAnchorCount,
            int inferredSemanticAnchorCount) {
        public ContextualConsensus {
            targets = targets == null ? Map.of() : Map.copyOf(targets);
            ranking = ranking == null ? List.of() : List.copyOf(ranking);
            semanticAnchorCount = Math.max(0, semanticAnchorCount);
            inferredSemanticAnchorCount = Math.max(0, inferredSemanticAnchorCount);
        }

        public static ContextualConsensus observedOnly(Map<String, Double> observed) {
            return new ContextualConsensus(observed, List.of(), false, 0, 0);
        }
    }

    private record SemanticQuery(double[] vector, int anchorCount, int inferredAnchorCount) {
        private static SemanticQuery empty() {
            return new SemanticQuery(null, 0, 0);
        }

        private boolean present() {
            return vector != null && vector.length > 0;
        }
    }

    /** Run one joint, hybrid-supervised training step over all enabled models. */
    public static Result train(ReasoningGraph graph, PslProgram program,
                               Map<String, Double> observedTargets, Plan plan) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(plan.reasoner(), "plan.reasoner");
        Map<String, Double> observed = observedTargets == null ? Map.of() : observedTargets;

        PslProgram trained = program;
        Map<String, Double> consensus = new HashMap<>(observed);
        List<ScoredEntity> ranking = List.of();
        boolean mebnTrained = false;
        boolean embTrained = false;
        int rounds = Math.max(1, plan.rounds());
        ReasoningGraph rankingGraph = graph;
        String embeddingLayer = null;
        SemanticQuery semanticQuery = SemanticQuery.empty();

        for (int round = 0; round < rounds; round++) {
            // (1) Co-train embeddings INTO the graph so the hybrid reasoner sees fresh semantic signal.
            //     learnInto requires a mutable graph; rank works on any ReasoningGraph.
            if (plan.trainEmbeddings() && plan.embLearner() != null
                    && graph instanceof MutableReasoningGraph mutableGraph && !graph.isEmpty()) {
                try {
                    plan.embLearner().learnInto(mutableGraph,
                            plan.embConfig() != null ? plan.embConfig() : EmbeddingConfig.defaults());
                    embTrained = true;
                    rankingGraph = graph;
                } catch (RuntimeException ignored) {
                    // Embeddings are an optional signal — a training failure must not abort the cascade.
                }
            } else if (plan.trainEmbeddings() && plan.embLearner() != null
                    && graph instanceof UnifiedGraph unifiedGraph && !graph.isEmpty()) {
                try {
                    plan.embLearner().learnIntoLayer(unifiedGraph, LEARNED_EMBEDDING_LAYER,
                            plan.embConfig() != null ? plan.embConfig() : EmbeddingConfig.defaults());
                    embTrained = true;
                    embeddingLayer = LEARNED_EMBEDDING_LAYER;
                    rankingGraph = unifiedGraph.withEmbeddingLayer(LEARNED_EMBEDDING_LAYER);
                } catch (RuntimeException ignored) {
                    // Embeddings are an optional signal — a training failure must not abort the cascade.
                }
            }
            // (2) Rank entities via the hybrid reasoner: the structural ⊕ semantic ranked response.
            semanticQuery = semanticQuery(rankingGraph, observed, plan.entityAliases(),
                    plan.queryEmbedding());
            ranking = semanticQuery.present()
                    ? plan.reasoner().rank(rankingGraph, semanticQuery.vector())
                    : plan.reasoner().rank(rankingGraph);
            // (3) Derive the single consensus signal: observed targets pulled toward the ranking.
            consensus = consensusTargets(observed, ranking, plan.consensusWeight(),
                    plan.entityAliases());
            // (4) Co-train PSL rule weights against the consensus.
            if (plan.pslLearner() != null && !trained.rules().isEmpty() && !consensus.isEmpty()) {
                trained = plan.pslLearner().updateOnBatch(trained, consensus, Math.max(1, plan.pslSteps()));
            }
            // (5) Co-train MEBN parameters against the SAME consensus (in place).
            if (plan.trainMebn() && plan.mebnLearner() != null && plan.mebnTheory() != null
                    && plan.mebnGraph() != null && !consensus.isEmpty()) {
                plan.mebnLearner().learn(plan.mebnTheory(), plan.mebnGraph(), consensus, Math.max(1, plan.mebnEpochs()));
                mebnTrained = true;
            }
        }
        int models = 1 + (mebnTrained ? 1 : 0) + (embTrained ? 1 : 0);
        return new Result(trained, mebnTrained, embTrained, consensus, ranking, rounds,
                models, semanticQuery.present(), semanticQuery.anchorCount(), embeddingLayer);
    }

    /**
     * Add semantic context to structural scores that a caller already computed, then derive the
     * observed-to-hybrid training targets. Sparse vectors are resolved from the graph, and raw
     * structural consensus remains the fallback when no usable semantic context exists.
     */
    public static ContextualConsensus contextualConsensus(
            ReasoningGraph graph, Map<String, Double> observedTargets,
            Map<String, Double> structuralScores, Map<String, String> entityAliases,
            HybridReasoner reasoner, double consensusWeight) {
        Map<String, Double> observed = observedTargets == null ? Map.of() : observedTargets;
        Map<String, String> aliases = entityAliases == null ? Map.of() : entityAliases;
        Map<String, Double> structural = structuralScores == null ? Map.of() : structuralScores;
        if (observed.isEmpty()) {
            return ContextualConsensus.observedOnly(observed);
        }
        if (graph == null || graph.isEmpty()) {
            return new ContextualConsensus(
                    consensusTargets(observed, structural, consensusWeight, aliases),
                    List.of(), false, 0, 0);
        }

        Objects.requireNonNull(reasoner, "reasoner");
        SemanticQuery query = semanticQuery(graph, observed, aliases, null);
        if (!query.present()) {
            return new ContextualConsensus(
                    consensusTargets(observed, structural, consensusWeight, aliases),
                    List.of(), false, 0, 0);
        }

        List<ScoredEntity> ranking = reasoner.rankWithStructuralScores(
                graph, structural, query.vector());
        return new ContextualConsensus(
                consensusTargets(observed, ranking, consensusWeight, aliases),
                ranking, true, query.anchorCount(), query.inferredAnchorCount());
    }

    /**
     * Build the semantic query used by joint training. Observed atom arguments are resolved through
     * {@code entityAliases} (for example PSL constants {@code n0 -> graph-entity-id}) and weighted by
     * their observed truth. If no observed argument resolves, the graph's embedded entities form a
     * confidence-weighted fallback context.
     */
    static SemanticQuery semanticQuery(ReasoningGraph graph, Map<String, Double> observed,
                                       Map<String, String> entityAliases, double[] explicitQuery) {
        if (explicitQuery != null && explicitQuery.length > 0
                && Embeddings.magnitude(explicitQuery) > 0.0) {
            return new SemanticQuery(Embeddings.normalize(explicitQuery), 0, 0);
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, String> aliases = entityAliases == null ? Map.of() : entityAliases;
        if (observed != null) {
            for (Map.Entry<String, Double> entry : observed.entrySet()) {
                double weight = clamp01(entry.getValue() == null ? 0.0 : entry.getValue());
                if (weight <= 0.0) {
                    continue;
                }
                for (String token : atomArguments(entry.getKey())) {
                    String entityId = aliases.getOrDefault(token, token);
                    if (graph.entity(entityId).isPresent()) {
                        weights.merge(entityId, weight, Math::max);
                    }
                }
            }
        }

        Map<String, GraphEmbeddingResolver.Resolved> resolved = resolveAnchors(graph, weights);
        if (resolved.values().stream().noneMatch(GraphEmbeddingResolver.Resolved::present)) {
            weights.clear();
            for (GraphEntity entity : graph.entities()) {
                if (entity.hasEmbedding()) {
                    weights.put(entity.id(), Math.max(1.0e-6, clamp01(entity.confidence())));
                }
            }
            resolved = resolveAnchors(graph, weights);
        }
        if (weights.isEmpty()) {
            return SemanticQuery.empty();
        }

        Map<Integer, Integer> dimensionCounts = new HashMap<>();
        for (String entityId : weights.keySet()) {
            GraphEmbeddingResolver.Resolved anchor = resolved.get(entityId);
            if (anchor != null && anchor.present()) {
                dimensionCounts.merge(anchor.vector().length, 1, Integer::sum);
            }
        }
        int dimension = dimensionCounts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(0);
        if (dimension == 0) {
            return SemanticQuery.empty();
        }

        double[] sum = new double[dimension];
        double totalWeight = 0.0;
        int anchors = 0;
        int inferredAnchors = 0;
        for (Map.Entry<String, Double> weighted : weights.entrySet()) {
            GraphEmbeddingResolver.Resolved anchor = resolved.get(weighted.getKey());
            double[] vector = anchor != null && anchor.present() ? anchor.vector() : null;
            if (vector == null || vector.length != dimension || Embeddings.magnitude(vector) == 0.0) {
                continue;
            }
            double weight = weighted.getValue();
            for (int i = 0; i < dimension; i++) {
                sum[i] += vector[i] * weight;
            }
            totalWeight += weight;
            anchors++;
            if (anchor.origin() != GraphEmbeddingResolver.Origin.DIRECT_ENTITY) {
                inferredAnchors++;
            }
        }
        if (anchors == 0 || totalWeight == 0.0 || Embeddings.magnitude(sum) == 0.0) {
            return SemanticQuery.empty();
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= totalWeight;
        }
        return new SemanticQuery(Embeddings.normalize(sum), anchors, inferredAnchors);
    }

    private static Map<String, GraphEmbeddingResolver.Resolved> resolveAnchors(
            ReasoningGraph graph, Map<String, Double> weights) {
        Map<String, GraphEmbeddingResolver.Resolved> resolved = new LinkedHashMap<>();
        for (String entityId : weights.keySet()) {
            resolved.put(entityId, GraphEmbeddingResolver.resolve(graph, entityId));
        }
        return resolved;
    }

    private static List<String> atomArguments(String atomKey) {
        if (atomKey == null || atomKey.isBlank()) {
            return List.of();
        }
        int open = atomKey.indexOf('(');
        int close = atomKey.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of(atomKey.trim());
        }
        return Arrays.stream(atomKey.substring(open + 1, close).split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    // ── Consensus signal derivation (pure, unit-testable) ───────────────────────────────────────

    /**
     * Blend observed soft targets with the hybrid reasoner's per-entity ranking. Each atom's target is
     * pulled toward the (min-max-normalized) hybrid score of the most important entity it mentions:
     * {@code target = (1-w)·observed + w·hybrid}. {@code w=0} → pure observed; {@code w=1} → pure hybrid.
     * Atoms whose entities are not in the ranking keep their observed value.
     */
    public static Map<String, Double> consensusTargets(Map<String, Double> observed, List<ScoredEntity> ranking, double w) {
        return consensusTargets(observed, ranking, w, Map.of());
    }

    /**
     * Consensus overload that resolves atom constants to ranked graph entity ids. This is required
     * for programs built by {@code GraphPslProgramBuilder}, which intentionally replaces arbitrary
     * entity ids with safe constants such as {@code n0}.
     */
    public static Map<String, Double> consensusTargets(Map<String, Double> observed,
                                                       List<ScoredEntity> ranking,
                                                       double w,
                                                       Map<String, String> entityAliases) {
        Map<String, Double> byEntity = new HashMap<>(normalizeScores(ranking));
        if (entityAliases != null) {
            for (Map.Entry<String, String> alias : entityAliases.entrySet()) {
                Double score = byEntity.get(alias.getValue());
                if (score != null) {
                    byEntity.put(alias.getKey(), score);
                }
            }
        }
        return blend(observed, byEntity, clamp01(w));
    }

    /**
     * Consensus from precomputed per-entity scores (e.g. the cascade's MAP posteriors aggregated per
     * entity) — the SAME observed↔structural blend as the ranking overload, but without constructing a
     * ranking. Lets a caller reuse a structural signal it already has instead of running a second
     * inference purely to rank, which is what makes a per-cascade (online) consensus affordable.
     */
    public static Map<String, Double> consensusTargets(Map<String, Double> observed, Map<String, Double> entityScores, double w) {
        return consensusTargets(observed, entityScores, w, Map.of());
    }

    /** Precomputed-score overload with PSL/entity alias resolution. */
    public static Map<String, Double> consensusTargets(Map<String, Double> observed,
                                                       Map<String, Double> entityScores,
                                                       double w,
                                                       Map<String, String> entityAliases) {
        Map<String, Double> byEntity = new HashMap<>(normalizeScoreMap(entityScores));
        if (entityAliases != null) {
            for (Map.Entry<String, String> alias : entityAliases.entrySet()) {
                Double score = byEntity.get(alias.getValue());
                if (score != null) {
                    byEntity.put(alias.getKey(), score);
                }
            }
        }
        return blend(observed, byEntity, clamp01(w));
    }

    /** Shared blend: each atom's target = (1-w)·observed + w·(normalized structural score of its entities). */
    private static Map<String, Double> blend(Map<String, Double> observed, Map<String, Double> byEntity, double cw) {
        if (byEntity.isEmpty() || cw == 0.0) {
            return new HashMap<>(observed);
        }
        Map<String, Double> out = new HashMap<>(observed.size());
        for (Map.Entry<String, Double> e : observed.entrySet()) {
            Double hybrid = hybridForAtom(e.getKey(), byEntity);
            double obs = e.getValue();
            out.put(e.getKey(), hybrid == null ? obs : clamp01((1.0 - cw) * obs + cw * hybrid));
        }
        return out;
    }

    /** Min-max normalize {@link ScoredEntity#score()} to [0,1], keyed by entity id (uniform 0.5 if flat). */
    static Map<String, Double> normalizeScores(List<ScoredEntity> ranking) {
        if (ranking == null || ranking.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> raw = new HashMap<>(ranking.size());
        for (ScoredEntity s : ranking) {
            raw.put(s.entityId(), s.score());
        }
        return normalizeScoreMap(raw);
    }

    /** Min-max normalize a per-entity score map to [0,1] (uniform 0.5 when all scores are equal). */
    static Map<String, Double> normalizeScoreMap(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : scores.values()) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double range = max - min;
        Map<String, Double> out = new HashMap<>(scores.size());
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            out.put(e.getKey(), range <= 1e-12 ? 0.5 : (e.getValue() - min) / range);
        }
        return out;
    }

    /** Hybrid score for an atom = the MAX normalized score among the entity ids in its argument list. */
    static Double hybridForAtom(String atomKey, Map<String, Double> byEntity) {
        if (atomKey == null) {
            return null;
        }
        int open = atomKey.indexOf('(');
        int close = atomKey.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return byEntity.get(atomKey.trim());
        }
        String args = atomKey.substring(open + 1, close);
        Double best = null;
        for (String raw : args.split(",")) {
            Double v = byEntity.get(raw.trim());
            if (v != null && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
