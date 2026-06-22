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
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;

import java.util.HashMap;
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
            HybridReasoner reasoner, double consensusWeight, int rounds) {
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
            Map<String, Double> consensusTargets, List<ScoredEntity> ranking, int rounds, int modelsTrained) {
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

        for (int round = 0; round < rounds; round++) {
            // (1) Co-train embeddings INTO the graph so the hybrid reasoner sees fresh semantic signal.
            //     learnInto requires a mutable graph; rank works on any ReasoningGraph.
            if (plan.trainEmbeddings() && plan.embLearner() != null
                    && graph instanceof MutableReasoningGraph mutableGraph && !graph.isEmpty()) {
                try {
                    plan.embLearner().learnInto(mutableGraph,
                            plan.embConfig() != null ? plan.embConfig() : EmbeddingConfig.defaults());
                    embTrained = true;
                } catch (RuntimeException ignored) {
                    // Embeddings are an optional signal — a training failure must not abort the cascade.
                }
            }
            // (2) Rank entities via the hybrid reasoner: the structural ⊕ semantic ranked response.
            ranking = plan.reasoner().rank(graph);
            // (3) Derive the single consensus signal: observed targets pulled toward the ranking.
            consensus = consensusTargets(observed, ranking, plan.consensusWeight());
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
        return new Result(trained, mebnTrained, embTrained, consensus, ranking, rounds, models);
    }

    // ── Consensus signal derivation (pure, unit-testable) ───────────────────────────────────────

    /**
     * Blend observed soft targets with the hybrid reasoner's per-entity ranking. Each atom's target is
     * pulled toward the (min-max-normalized) hybrid score of the most important entity it mentions:
     * {@code target = (1-w)·observed + w·hybrid}. {@code w=0} → pure observed; {@code w=1} → pure hybrid.
     * Atoms whose entities are not in the ranking keep their observed value.
     */
    public static Map<String, Double> consensusTargets(Map<String, Double> observed, List<ScoredEntity> ranking, double w) {
        double cw = clamp01(w);
        Map<String, Double> byEntity = normalizeScores(ranking);
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
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (ScoredEntity s : ranking) {
            min = Math.min(min, s.score());
            max = Math.max(max, s.score());
        }
        double range = max - min;
        Map<String, Double> out = new HashMap<>(ranking.size());
        for (ScoredEntity s : ranking) {
            out.put(s.entityId(), range <= 1e-12 ? 0.5 : (s.score() - min) / range);
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
