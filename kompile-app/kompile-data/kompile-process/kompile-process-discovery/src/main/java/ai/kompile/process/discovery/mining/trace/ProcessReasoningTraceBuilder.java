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

package ai.kompile.process.discovery.mining.trace;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.mining.causal.CausalDependency;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import ai.kompile.process.discovery.mining.entail.ProcessHybridActivation;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.tree.ProcessTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapts the mined-process evidence stack into the canonical reasoning trace tree.
 */
public final class ProcessReasoningTraceBuilder {

    private static final int MAX_VARIANTS = 5;
    private static final int MAX_PRECEDENCES = 12;
    private static final int MAX_FACTS_PER_PRECEDENCE = 8;
    private static final int MAX_RULES_PER_PRECEDENCE = 8;
    private static final int MAX_CAUSAL_DEPENDENCIES = 10;
    private static final int MAX_HYBRID_ACTIVITIES = 48;

    private ProcessReasoningTraceBuilder() {
    }

    public static ReasoningTrace build(ProcessSuggestion suggestion,
                                       EventLog eventLog,
                                       DirectlyFollowsGraph dfg,
                                       ProcessEntailmentResult entailment,
                                       ProcessCausalAnalyzer.ProcessCausalModel causalModel,
                                       ProcessHybridActivation.Result hybridActivation,
                                       Double bayesianAvgPosterior,
                                       ProcessTree tree) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        premises.add(eventLogStep(eventLog));
        premises.add(dfgStep(dfg));
        if (tree != null) {
            premises.add(ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.INFERENCE,
                    "process-tree: " + tree,
                    "inductive miner",
                    clamp(suggestion.getRawConformanceScore() != null
                            ? suggestion.getRawConformanceScore() : suggestion.getConfidence()),
                    List.of(eventLogStep(eventLog))));
        }
        if (hybridActivation != null && !hybridActivation.isEmpty()) {
            premises.add(hybridStep(hybridActivation, suggestion.getHybridReasoning()));
        } else if (bayesianAvgPosterior != null) {
            premises.add(ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.INFERENCE,
                    String.format(Locale.ROOT, "bayesian process posterior %.3f", bayesianAvgPosterior),
                    "Bayesian process inference",
                    clamp(bayesianAvgPosterior),
                    List.of()));
        }
        if (causalModel != null && !causalModel.dependencies().isEmpty()) {
            premises.add(causalStep(causalModel));
        }
        if (entailment != null && !entailment.isEmpty()) {
            premises.add(entailmentStep(entailment));
        }

        String conclusion = "process suggestion " + nullToEmpty(suggestion.getId())
                + ": " + nullToEmpty(suggestion.getName());
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION,
                conclusion,
                "confidence fusion over mined process modalities",
                clamp(suggestion.getConfidence()),
                premises);
        return ReasoningTrace.of(root);
    }

    private static ReasoningTrace.Step eventLogStep(EventLog log) {
        List<ReasoningTrace.Step> variants = new ArrayList<>();
        if (log != null) {
            log.variants().entrySet().stream()
                    .sorted(Map.Entry.<List<String>, Long>comparingByValue().reversed())
                    .limit(MAX_VARIANTS)
                    .forEach(e -> variants.add(ReasoningTrace.Step.fact(
                            "variant x" + e.getValue() + ": " + String.join(" -> ", e.getKey()),
                            1.0,
                            "event-log")));
        }
        int cases = log == null ? 0 : log.size();
        int activities = log == null ? 0 : log.activityNames().size();
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.QUERY,
                "event log: " + cases + " case(s), " + activities + " activity label(s)",
                "extract events from fact-sheet graph",
                cases > 0 ? 1.0 : 0.0,
                variants);
    }

    private static ReasoningTrace.Step dfgStep(DirectlyFollowsGraph dfg) {
        List<ReasoningTrace.Step> arcs = new ArrayList<>();
        if (dfg != null) {
            dfg.arcs().entrySet().stream()
                    .sorted(Map.Entry.<DirectlyFollowsGraph.Arc, Long>comparingByValue().reversed())
                    .limit(MAX_PRECEDENCES)
                    .forEach(e -> arcs.add(ReasoningTrace.Step.fact(
                            e.getKey().from() + " directly-follows " + e.getKey().to()
                                    + " x" + e.getValue(),
                            1.0,
                            "directly-follows graph")));
        }
        int activities = dfg == null ? 0 : dfg.activities().size();
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                "directly-follows graph: " + activities + " activities",
                "count adjacent activity transitions",
                activities > 1 ? 1.0 : 0.0,
                arcs);
    }

    private static ReasoningTrace.Step causalStep(ProcessCausalAnalyzer.ProcessCausalModel causalModel) {
        List<ReasoningTrace.Step> deps = causalModel.dependencies().stream()
                .sorted(Comparator.comparingDouble((CausalDependency d) -> d.dependency()).reversed())
                .limit(MAX_CAUSAL_DEPENDENCIES)
                .map(d -> ReasoningTrace.Step.fact(
                        String.format(Locale.ROOT,
                                "%s -> %s [%s dep=%.3f chi2=%.3f forward=%d reverse=%d]",
                                d.from(), d.to(), d.type(), d.dependency(), d.chiSquare(),
                                d.forward(), d.reverse()),
                        clamp(Math.abs(d.dependency())),
                        "causal analyzer"))
                .toList();
        double confidence = causalModel.dependencies().stream()
                .mapToDouble(d -> d.significant() ? 1.0 : 0.0)
                .average()
                .orElse(0.0);
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                "causal model: " + causalModel.dependencies().size()
                        + " dependencies, " + causalModel.pslRules().size() + " PSL rules",
                "dependency measure and chi-square causal classification",
                clamp(confidence),
                deps);
    }

    private static ReasoningTrace.Step hybridStep(
            ProcessHybridActivation.Result hybrid,
            ProcessSuggestion.HybridReasoningDetails details) {
        List<ReasoningTrace.Step> activities = hybrid.byActivity().values().stream()
                .sorted(Comparator.comparingDouble(ProcessHybridActivation.ActivityActivation::hybrid)
                        .reversed()
                        .thenComparing(ProcessHybridActivation.ActivityActivation::activity))
                .limit(MAX_HYBRID_ACTIVITIES)
                .map(activity -> hybridActivityStep(activity, hybrid))
                .toList();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("interpretation", "ACTIVITY_ACTIVATION_CONSENSUS");
        meta.put("semanticMode", hybrid.semanticEngaged() ? "ACTIVITY_CENTROID" : "STRUCTURAL_ONLY");
        meta.put("activityCount", Integer.toString(hybrid.byActivity().size()));
        meta.put("embeddedActivityCount", Integer.toString(hybrid.embeddedActivityCount()));
        meta.put("structuralWeight", decimal(hybrid.structuralWeight()));
        meta.put("semanticWeight", decimal(hybrid.semanticWeight()));
        meta.put("pslScore", decimal(hybrid.meanPsl()));
        meta.put("bayesianScore", decimal(hybrid.meanBayesian()));
        meta.put("pslStructuralScore", decimal(hybrid.meanPslStructural()));
        meta.put("bayesianStructuralScore", decimal(hybrid.meanBayesianStructural()));
        meta.put("semanticScore", decimal(hybrid.meanSemantic()));
        meta.put("pslAvailable", Boolean.toString(hybrid.pslAvailable()));
        meta.put("bayesianAvailable", Boolean.toString(hybrid.bayesianAvailable()));
        if (details != null) {
            if (details.getEmbeddingSource() != null) {
                meta.put("embeddingSource", details.getEmbeddingSource());
            }
            if (details.getEmbeddingModel() != null) {
                meta.put("embeddingModel", details.getEmbeddingModel());
            }
            meta.put("directlyEmbeddedActivityCount",
                    Integer.toString(details.getDirectlyEmbeddedActivityCount()));
            meta.put("inferredEmbeddingActivityCount",
                    Integer.toString(details.getInferredEmbeddingActivityCount()));
            meta.put("contextualizedActivityCount",
                    Integer.toString(details.getContextualizedActivityCount()));
        }
        if (!hybrid.warnings().isEmpty()) {
            meta.put("warnings", String.join(" | ", hybrid.warnings()));
        }
        String mode = hybrid.semanticEngaged() ? "semantic centroid" : "structural only";
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION,
                String.format(Locale.ROOT,
                        "hybrid activity activation consensus %.3f over %d activities (%s)",
                        hybrid.meanHybrid(), hybrid.byActivity().size(), mode),
                "average available PSL and Bayesian HybridReasoner activity scores",
                clamp(hybrid.meanHybrid()),
                null,
                meta,
                activities);
    }

    private static ReasoningTrace.Step hybridActivityStep(
            ProcessHybridActivation.ActivityActivation activity,
            ProcessHybridActivation.Result hybrid) {
        List<ReasoningTrace.Step> engines = new ArrayList<>();
        if (hybrid.pslAvailable()) {
            engines.add(hybridEngineStep("PSL", activity.activity(), activity.psl(),
                    activity.pslStructural(), activity.semantic(), activity.embedded(), hybrid));
        }
        if (hybrid.bayesianAvailable()) {
            engines.add(hybridEngineStep("BAYESIAN", activity.activity(), activity.bayesian(),
                    activity.bayesianStructural(), activity.semantic(), activity.embedded(), hybrid));
        }
        Map<String, String> meta = Map.of(
                "activity", activity.activity(),
                "pslScore", decimal(activity.psl()),
                "bayesianScore", decimal(activity.bayesian()),
                "semanticScore", decimal(activity.semantic()),
                "embedded", Boolean.toString(activity.embedded()));
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION,
                String.format(Locale.ROOT, "%s hybrid activation %.3f",
                        activity.activity(), activity.hybrid()),
                "mean of available HybridReasoner structural engines",
                clamp(activity.hybrid()),
                null,
                meta,
                engines);
    }

    private static ReasoningTrace.Step hybridEngineStep(
            String engine,
            String activity,
            double score,
            double structuralScore,
            double semanticScore,
            boolean embedded,
            ProcessHybridActivation.Result hybrid) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("engine", engine);
        meta.put("activity", activity);
        meta.put("score", decimal(score));
        meta.put("structuralScore", decimal(structuralScore));
        meta.put("semanticScore", decimal(semanticScore));
        meta.put("semanticMode", hybrid.semanticEngaged() ? "ACTIVITY_CENTROID" : "STRUCTURAL_ONLY");
        meta.put("embedded", Boolean.toString(embedded));
        meta.put("structuralWeight", decimal(hybrid.structuralWeight()));
        meta.put("semanticWeight", decimal(hybrid.semanticWeight()));
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                String.format(Locale.ROOT,
                        "%s %s score %.3f (structural %.3f, semantic %.3f)",
                        engine, activity, score, structuralScore, semanticScore),
                "HybridReasoner " + engine + " structural/semantic blend",
                clamp(score),
                null,
                meta,
                List.of());
    }

    private static ReasoningTrace.Step entailmentStep(ProcessEntailmentResult entailment) {
        List<ReasoningTrace.Step> pairSteps = entailment.accepted().stream()
                .limit(MAX_PRECEDENCES)
                .map(ProcessReasoningTraceBuilder::precedenceStep)
                .toList();
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION,
                "entailed precedence relation: " + entailment.accepted().size()
                        + " accepted pair(s), run " + entailment.runId(),
                "HL-MRF posterior with temporal refutation and subjective-logic fusion",
                clamp(entailment.fusedOpinion().expectation()),
                pairSteps);
    }

    private static ReasoningTrace.Step precedenceStep(ProcessEntailmentResult.EntailedPrecedence p) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        p.supportingFactKeys().stream()
                .limit(MAX_FACTS_PER_PRECEDENCE)
                .map(fact -> ReasoningTrace.Step.fact(fact, clamp(p.posterior()), "process entailment fact"))
                .forEach(premises::add);
        p.activatedRules().stream()
                .limit(MAX_RULES_PER_PRECEDENCE)
                .map(rule -> ReasoningTrace.Step.derived(
                        ReasoningTrace.StepKind.RULE,
                        rule,
                        "activated ground rule",
                        clamp(p.posterior()),
                        List.of()))
                .forEach(premises::add);
        if (p.temporalOpinion() != null) {
            premises.add(ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.FUSION,
                    String.format(Locale.ROOT,
                            "temporal evidence ordered=%d reversed=%d overlapped=%d refuted=%s concurrent=%s",
                            p.orderedEvidence(), p.reversedEvidence(), p.overlappedEvidence(),
                            p.temporallyRefuted(), p.concurrent()),
                    "valid-time cross-examination",
                    clamp(p.temporalOpinion().expectation()),
                    List.of()));
        }
        return ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                p.kbAtomKey(),
                p.observed() ? "observed precedence posterior" : "entailed precedence posterior",
                clamp(p.posterior()),
                premises);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
