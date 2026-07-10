/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.mining.causal.DependencyMeasures;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-activity activation of the discovered process through the library's {@link HybridReasoner}
 * as the unified orchestration point instead of separate bespoke PSL/Bayesian assemblies. The
 * mined structure becomes a {@link MutableReasoningGraph} whose <b>relations carry the mined
 * metadata as weights</b>:
 *
 * <ul>
 *   <li>observed directly-follows arcs at their dependency strength;</li>
 *   <li>entailed-but-unobserved precedence at its posterior, discounted by the temporal
 *       cross-examination opinion (a temporally shaky ordering influences activation less);</li>
 *   <li>activity priors from start-frequency and trace support.</li>
 * </ul>
 *
 * The reasoner then ranks activities structurally under BOTH engines (PSL/HL-MRF activation and
 * Bayesian posterior via variable elimination) and the per-activity hybrid score is their
 * consensus mean: engine-level agreement, the same shape the cascade's hybrid consensus uses.
 * When an {@code ActivityEmbedder} supplies label vectors, each engine's score additionally blends
 * cosine similarity to the process's semantic centroid ({@code semanticWeight} knob); off-theme
 * strays score below what topology alone would suggest.
 */
public final class ProcessHybridActivation {

    private static final Logger log = LoggerFactory.getLogger(ProcessHybridActivation.class);
    private static final double STRUCTURAL_WEIGHT = 0.6;

    private ProcessHybridActivation() {
    }

    /** One activity's native HybridReasoner components plus the cross-engine consensus. */
    public record ActivityActivation(String activity,
                                     double psl,
                                     double bayesian,
                                     double hybrid,
                                     double pslStructural,
                                     double bayesianStructural,
                                     double semantic,
                                     boolean embedded) {
    }

    public record Result(Map<String, ActivityActivation> byActivity,
                         double meanHybrid,
                         double meanPsl,
                         double meanBayesian,
                         double meanPslStructural,
                         double meanBayesianStructural,
                         double meanSemantic,
                         boolean semanticEngaged,
                         int embeddedActivityCount,
                         double structuralWeight,
                         double semanticWeight,
                         boolean pslAvailable,
                         boolean bayesianAvailable,
                         List<String> warnings) {

        public Result {
            byActivity = byActivity == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(byActivity));
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static Result empty() {
            return new Result(Map.of(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    false, 0, 1.0, 0.0, false, false, List.of());
        }

        public boolean isEmpty() {
            return byActivity.isEmpty();
        }
    }

    private record EngineScores(Map<String, HybridReasoner.ScoredEntity> byActivity,
                                boolean available,
                                String warning) {
    }

    /** Convert the algorithm result to the stable process/API contract without losing components. */
    public static ProcessSuggestion.HybridReasoningDetails toSuggestionDetails(Result result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        List<ProcessSuggestion.HybridActivityReasoning> activities = result.byActivity().values().stream()
                .sorted(Comparator.comparingDouble(ActivityActivation::hybrid).reversed()
                        .thenComparing(ActivityActivation::activity))
                .map(activity -> ProcessSuggestion.HybridActivityReasoning.builder()
                        .activity(activity.activity())
                        .score(activity.hybrid())
                        .pslScore(activity.psl())
                        .bayesianScore(activity.bayesian())
                        .pslStructuralScore(activity.pslStructural())
                        .bayesianStructuralScore(activity.bayesianStructural())
                        .semanticScore(activity.semantic())
                        .embedded(activity.embedded())
                        .build())
                .toList();
        return ProcessSuggestion.HybridReasoningDetails.builder()
                .interpretation("ACTIVITY_ACTIVATION_CONSENSUS")
                .score(result.meanHybrid())
                .pslScore(result.meanPsl())
                .bayesianScore(result.meanBayesian())
                .pslStructuralScore(result.meanPslStructural())
                .bayesianStructuralScore(result.meanBayesianStructural())
                .semanticScore(result.meanSemantic())
                .semanticMode(result.semanticEngaged() ? "ACTIVITY_CENTROID" : "STRUCTURAL_ONLY")
                .embeddedActivityCount(result.embeddedActivityCount())
                .activityCount(result.byActivity().size())
                .structuralWeight(result.structuralWeight())
                .semanticWeight(result.semanticWeight())
                .pslAvailable(result.pslAvailable())
                .bayesianAvailable(result.bayesianAvailable())
                .activities(activities)
                .warnings(result.warnings())
                .build();
    }

    /**
     * Activate the discovered process structurally (no embeddings). Empty when there is nothing
     * to reason over (fewer than two activities).
     */
    public static Result activate(EventLog eventLog, DirectlyFollowsGraph dfg,
                                  ProcessEntailmentResult entailment) {
        return activate(eventLog, dfg, entailment, Map.of(), 0.0);
    }

    /**
     * Activate the discovered process, blending semantics when activity embeddings are supplied:
     * entities carry their vectors, the query is the normalized centroid of all embedded
     * activities (the process's semantic theme), and each engine's score becomes
     * {@code (structuralWeight * structural + semanticWeight * cosine) / sum(weights)}; an off-theme
     * activity (a mis-clustered stray) scores lower than its topology alone would suggest.
     *
     * @param embeddings     activity label to dense vector (absent labels contribute 0 semantic
     *                       score); empty map = pure structural, identical to the 3-arg overload
     * @param semanticWeight blend weight for the semantic component; requires at least 2 embedded
     *                       activities to engage ({@code <= 0} disables)
     */
    public static Result activate(EventLog eventLog, DirectlyFollowsGraph dfg,
                                  ProcessEntailmentResult entailment,
                                  Map<String, double[]> embeddings, double semanticWeight) {
        Set<String> activities = new LinkedHashSet<>(dfg.activities());
        if (activities.size() < 2) {
            return Result.empty();
        }

        MutableReasoningGraph graph = new MutableReasoningGraph();

        // Entity priors: start-activity share + trace support, bounded away from the extremes.
        Map<String, Long> starts = dfg.startActivities();
        long maxStart = starts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        int traceCount = Math.max(1, eventLog.size());
        Map<String, Long> containing = new LinkedHashMap<>();
        for (Trace trace : eventLog.traces()) {
            for (String activity : new LinkedHashSet<>(trace.activitySequence())) {
                containing.merge(activity, 1L, Long::sum);
            }
        }
        int embedded = 0;
        for (String activity : activities) {
            double startShare = starts.containsKey(activity)
                    ? (double) starts.get(activity) / Math.max(1L, maxStart) : 0.0;
            double support = containing.getOrDefault(activity, 0L) / (double) traceCount;
            double prior = Math.max(0.1, Math.min(0.9, 0.1 + 0.5 * startShare + 0.4 * support));
            double[] vector = embeddings != null ? embeddings.get(activity) : null;
            if (vector != null && vector.length > 0) {
                embedded++;
            }
            graph.addEntity(GraphEntity.builder(activity)
                    .type("ACTIVITY").label(activity)
                    .weight(prior)
                    .embedding(vector != null && vector.length > 0 ? vector : null)
                    .build());
        }

        // Relations = the mined metadata: dependency strengths for observed flow, temporal-
        // discounted posteriors for entailed-only orderings.
        for (DirectlyFollowsGraph.Arc arc : dfg.arcs().keySet()) {
            double strength = clamp01(DependencyMeasures.dependency(dfg, arc.from(), arc.to()));
            if (strength <= 0.0) {
                continue;
            }
            graph.addRelation(SimpleGraphRelation.directed(
                    "df:" + arc.from() + ">" + arc.to(), arc.from(), arc.to(),
                    PrecedenceMaterializer.DIRECTLY_FOLLOWS, strength));
        }
        if (entailment != null) {
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.accepted()) {
                if (p.observed() || p.temporallyRefuted() || p.concurrent()
                        || !activities.contains(p.from()) || !activities.contains(p.to())) {
                    continue;
                }
                double temporalConsistency = p.temporalOpinion() != null
                        ? clamp01(p.temporalOpinion().expectation()) : 1.0;
                double weight = clamp01(p.posterior() * temporalConsistency);
                if (weight <= 0.0) {
                    continue;
                }
                graph.addRelation(SimpleGraphRelation.directed(
                        "precedes:" + p.from() + ">" + p.to(), p.from(), p.to(),
                        PrecedenceMaterializer.PRECEDES, weight));
            }
        }

        // Both structural engines through the ONE orchestrator; hybrid = engine consensus.
        // Semantic blending engages only with at least 2 embedded activities (one vector against its own
        // centroid is a tautology): the query is the process's semantic centroid.
        boolean semantic = semanticWeight > 0 && embedded >= 2;
        double[] query = semantic ? centroid(embeddings, activities) : null;
        double weight = semantic && query != null ? semanticWeight : 0.0;
        EngineScores psl = scores("PSL", new HybridReasoner()
                .structural(HybridReasoner.Structural.PSL)
                .structuralWeight(STRUCTURAL_WEIGHT).semanticWeight(weight), graph, query);
        EngineScores bayesian = scores("Bayesian", new HybridReasoner()
                .structural(HybridReasoner.Structural.BAYESIAN)
                .structuralWeight(STRUCTURAL_WEIGHT).semanticWeight(weight), graph, query);

        Map<String, ActivityActivation> byActivity = new LinkedHashMap<>();
        double sum = 0.0;
        double pslSum = 0.0;
        double bayesianSum = 0.0;
        double pslStructuralSum = 0.0;
        double bayesianStructuralSum = 0.0;
        double semanticSum = 0.0;
        int availableEngines = (psl.available() ? 1 : 0) + (bayesian.available() ? 1 : 0);
        for (String activity : activities) {
            HybridReasoner.ScoredEntity pslScore = psl.byActivity().get(activity);
            HybridReasoner.ScoredEntity bayesianScore = bayesian.byActivity().get(activity);
            double p = pslScore == null ? 0.0 : clamp01(pslScore.score());
            double b = bayesianScore == null ? 0.0 : clamp01(bayesianScore.score());
            double pStructural = pslScore == null ? 0.0 : clamp01(pslScore.structuralScore());
            double bStructural = bayesianScore == null ? 0.0 : clamp01(bayesianScore.structuralScore());
            double semanticScore = semanticComponent(pslScore, bayesianScore);
            double hybrid = availableEngines == 0 ? 0.0
                    : ((psl.available() ? p : 0.0) + (bayesian.available() ? b : 0.0))
                    / availableEngines;
            boolean hasEmbedding = embeddings != null
                    && embeddings.get(activity) != null
                    && embeddings.get(activity).length > 0;
            byActivity.put(activity, new ActivityActivation(activity, p, b, hybrid,
                    pStructural, bStructural, semanticScore, hasEmbedding));
            sum += hybrid;
            pslSum += p;
            bayesianSum += b;
            pslStructuralSum += pStructural;
            bayesianStructuralSum += bStructural;
            semanticSum += semanticScore;
        }
        int activityCount = byActivity.size();
        double mean = activityCount == 0 ? 0.0 : sum / activityCount;
        double effectiveTotal = STRUCTURAL_WEIGHT + weight;
        double effectiveStructuralWeight = weight > 0.0 ? STRUCTURAL_WEIGHT / effectiveTotal : 1.0;
        double effectiveSemanticWeight = weight > 0.0 ? weight / effectiveTotal : 0.0;
        List<String> warnings = new ArrayList<>();
        if (psl.warning() != null) {
            warnings.add(psl.warning());
        }
        if (bayesian.warning() != null) {
            warnings.add(bayesian.warning());
        }
        log.debug("Hybrid activation over {} activities: meanHybrid={}", byActivity.size(), mean);
        return new Result(byActivity, mean,
                activityCount == 0 ? 0.0 : pslSum / activityCount,
                activityCount == 0 ? 0.0 : bayesianSum / activityCount,
                activityCount == 0 ? 0.0 : pslStructuralSum / activityCount,
                activityCount == 0 ? 0.0 : bayesianStructuralSum / activityCount,
                activityCount == 0 ? 0.0 : semanticSum / activityCount,
                semantic && query != null, embedded, effectiveStructuralWeight,
                effectiveSemanticWeight, psl.available(), bayesian.available(), warnings);
    }

    private static EngineScores scores(String engineName, HybridReasoner reasoner,
                                       MutableReasoningGraph graph, double[] query) {
        Map<String, HybridReasoner.ScoredEntity> out = new LinkedHashMap<>();
        try {
            List<HybridReasoner.ScoredEntity> ranked = query != null
                    ? reasoner.rank(graph, query) : reasoner.rank(graph);
            for (HybridReasoner.ScoredEntity scored : ranked) {
                out.put(scored.entityId(), scored);
            }
            return new EngineScores(out, true, null);
        } catch (Exception e) {
            String warning = engineName + " HybridReasoner failed: " + e.getMessage();
            log.warn(warning);
            return new EngineScores(Map.of(), false, warning);
        }
    }

    private static double semanticComponent(HybridReasoner.ScoredEntity psl,
                                            HybridReasoner.ScoredEntity bayesian) {
        if (psl != null && bayesian != null) {
            return clamp01((psl.semanticScore() + bayesian.semanticScore()) / 2.0);
        }
        HybridReasoner.ScoredEntity available = psl != null ? psl : bayesian;
        return available == null ? 0.0 : clamp01(available.semanticScore());
    }

    /** Normalized mean of the embedded activities' vectors: the process's semantic theme. */
    private static double[] centroid(Map<String, double[]> embeddings, Set<String> activities) {
        double[] sum = null;
        int count = 0;
        for (String activity : activities) {
            double[] v = embeddings.get(activity);
            if (v == null || v.length == 0) {
                continue;
            }
            if (sum == null) {
                sum = new double[v.length];
            }
            if (v.length != sum.length) {
                continue; // Mixed dimensionality; skip the stray.
            }
            for (int i = 0; i < v.length; i++) {
                sum[i] += v[i];
            }
            count++;
        }
        if (sum == null || count < 2) {
            return null;
        }
        double norm = 0;
        for (double x : sum) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-12) {
            return null;
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= norm;
        }
        return sum;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
