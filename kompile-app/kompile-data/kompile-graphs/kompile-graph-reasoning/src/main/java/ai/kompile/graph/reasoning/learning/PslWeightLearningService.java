/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.fol.FolInferenceService;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production entry point for PSL weight learning: it fits PSL rule weights to labeled ground-truth
 * observations so an inference run emits facts whose weights are <em>learned from data</em> rather
 * than hand-set. It wraps the existing {@link WeightLearner} implementations
 * ({@link StructuredPerceptronLearner} / {@link PseudolikelihoodLearner}), applies the learned weights
 * back onto a program via {@link PslProgram#withRules}, and serializes them so they survive sessions.
 *
 * <p>End-to-end (with {@code FolInferenceService.inferFacts}): build a program from a graph, learn its
 * weights against labels, persist them, then infer with the learned weights → probabilistic facts with
 * learned weights.</p>
 */
public final class PslWeightLearningService {

    private final WeightLearner learner;
    private final int maxEpochs;

    /** Defaults: structured-perceptron learner, 50 epochs. */
    public PslWeightLearningService() {
        this(new StructuredPerceptronLearner(), 50);
    }

    public PslWeightLearningService(WeightLearner learner, int maxEpochs) {
        this.learner = learner;
        this.maxEpochs = maxEpochs;
    }

    /** Learn rule weights for an already-built program against labeled ground truth (atomKey → [0,1]). */
    public List<PslRule> learn(PslProgram program, Map<String, Double> groundTruth) {
        return learner.learn(program, groundTruth, maxEpochs);
    }

    /**
     * Learn rule weights and return a copy of the program with those weights applied — ready to run
     * inference (e.g. via {@code FolInferenceService} / {@code HlMrfMapInference}) with learned weights.
     */
    public PslProgram learnAndApply(PslProgram program, Map<String, Double> groundTruth) {
        return program.withRules(learn(program, groundTruth));
    }

    /**
     * Incremental mini-batch update: warm-starts from the program's CURRENT rule weights and runs only
     * {@code steps} epochs of the configured learner against a mini-batch of labels — it does NOT reset
     * the weights or train to convergence. Because every {@link WeightLearner} initializes from the
     * program's existing weights, repeated calls accumulate; combined with {@link #weightsToJson} +
     * {@link #applyWeights} this gives online learning across batches: load persisted weights →
     * {@code updateOnBatch} on the new batch → persist again.
     *
     * @param program         a program whose rules carry the weights learned so far (e.g. re-loaded via
     *                        {@link #applyWeights}); these are the warm-start
     * @param miniBatchLabels labels for this batch (atomKey → [0,1])
     * @param steps           number of update epochs to run on this batch
     * @return a copy of the program with the incrementally-updated weights applied
     */
    public PslProgram updateOnBatch(PslProgram program, Map<String, Double> miniBatchLabels, int steps) {
        return program.withRules(learner.learn(program, miniBatchLabels, steps));
    }

    /** Build the program from a graph + rules (no solve), then learn its rule weights. */
    public List<PslRule> learnForGraph(FolInferenceService inference, ReasoningGraph graph,
                                       FolRuleSet ruleSet, Map<String, Double> groundTruth) {
        return learn(inference.buildProgram(graph, ruleSet), groundTruth);
    }

    // ─── Persistence: learned weights survive across sessions ───────────────────────────

    /**
     * Re-inject persisted weights — the {@code ruleDisplay → weight} map produced by {@link #parseWeights}
     * — onto a freshly built program's rules, matched by rule display ({@link PslRule#toString()}), and
     * return the re-weighted program. This is the load counterpart to {@link #weightsToJson}: it warm-starts
     * a new program with previously learned weights so a later {@link #updateOnBatch} or {@link #learn}
     * <em>continues</em> training instead of restarting from scratch. Rules with no persisted entry keep
     * their current weight.
     *
     * @param program          the program to re-weight (rule structure preserved)
     * @param weightsByDisplay  learned weights keyed by {@link PslRule#toString()} display
     * @return a copy of the program with matching rules' weights replaced
     */
    public static PslProgram applyWeights(PslProgram program, Map<String, Double> weightsByDisplay) {
        List<PslRule> updated = new ArrayList<>(program.rules().size());
        for (PslRule r : program.rules()) {
            Double w = weightsByDisplay.get(r.toString());
            updated.add(w == null ? r
                    : new PslRule(w, r.hard(), r.squared(), r.body(), r.head(), r.distinct()));
        }
        return program.withRules(updated);
    }

    /**
     * Serialize learned rule weights as JSON ({@code "ruleDisplay": weight}). Hand-rolled because the
     * library carries no {@code jackson-databind}; the output is standard JSON, parseable by
     * {@link #parseWeights}.
     */
    public static String weightsToJson(List<PslRule> rules) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (PslRule rule : rules) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(rule.toString())).append("\":").append(rule.weight());
        }
        return sb.append('}').toString();
    }

    /** Parse the {@code "ruleDisplay": weight} JSON produced by {@link #weightsToJson}. */
    public static Map<String, Double> parseWeights(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        String s = json.trim();
        if (s.startsWith("{")) {
            s = s.substring(1);
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length() - 1);
        }
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) <= ' ') {
                i++;
            }
            if (i >= n || s.charAt(i) != '"') {
                break;
            }
            int keyStart = i + 1;
            i = endQuote(s, keyStart);
            String key = unescape(s.substring(keyStart, i));
            i++; // skip closing quote
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ':')) {
                i++;
            }
            int valStart = i;
            while (i < n && s.charAt(i) != ',') {
                i++;
            }
            try {
                out.put(key, Double.parseDouble(s.substring(valStart, i).trim()));
            } catch (NumberFormatException ignored) {
                // skip malformed value
            }
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) {
                i++;
            }
        }
        return out;
    }

    private static int endQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (s.charAt(i) == '"') {
                return i;
            }
        }
        return s.length();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
