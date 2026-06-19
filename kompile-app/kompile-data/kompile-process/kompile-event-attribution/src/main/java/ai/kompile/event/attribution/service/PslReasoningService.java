/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.service;

import ai.kompile.event.attribution.algorithm.psl.GroundRule;
import ai.kompile.event.attribution.algorithm.psl.HlMrfMapInference;
import ai.kompile.event.attribution.algorithm.psl.KgPslProgramBuilder;
import ai.kompile.event.attribution.algorithm.psl.PslProgram;
import ai.kompile.event.attribution.algorithm.psl.PslRule;
import ai.kompile.event.attribution.domain.PslInferenceResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for building a {@link PslProgram} from the knowledge graph and running PSL
 * (Probabilistic Soft Logic / HL-MRF) MAP inference.
 *
 * <p>The soft-logic sibling of {@code BayesianNetworkService}: both reason over the same
 * KG subgraphs and share the same construction pattern (seed nodes + depth/size bounds +
 * evidence), but this service infers continuous {@code [0,1]} soft-truth values for node
 * states by minimizing weighted first-order-rule violations, rather than computing discrete
 * Bayesian posteriors. It also accepts arbitrary user-supplied PSL rules for collective
 * classification beyond the built-in causal-propagation model.</p>
 */
@Service
public class PslReasoningService {

    private static final Logger log = LoggerFactory.getLogger(PslReasoningService.class);
    private static final int TOP_VIOLATIONS = 15;
    private static final Pattern CONSTANT_TOKEN = Pattern.compile("n\\d+");

    private final KnowledgeGraphService graphService;

    @Autowired
    public PslReasoningService(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Build the default causal-propagation PSL program from a KG subgraph and run inference.
     *
     * @param seedNodeIds KG node IDs defining the subgraph scope
     * @param evidence    map of KG node ID → observed soft truth in {@code [0,1]} (may be null)
     * @param maxDepth    maximum BFS traversal depth
     * @param maxNodes    maximum nodes in the program
     */
    public PslInferenceResult infer(Collection<String> seedNodeIds,
                                    Map<String, Double> evidence, int maxDepth, int maxNodes) {
        long start = System.currentTimeMillis();
        KgPslProgramBuilder builder = new KgPslProgramBuilder(graphService)
                .maxDepth(maxDepth).maxNodes(maxNodes);
        PslProgram program = builder.build(seedNodeIds);
        applyEvidence(program, builder, evidence);
        return run(builder, program, start);
    }

    /**
     * Build the KG atoms (no default rules) and run inference using caller-supplied PSL
     * rules over the {@code State}/{@code Link}/{@code Prior} predicates.
     *
     * @param ruleStrings PSL rules, e.g. {@code "2.0: State(X) & Link(X, Y) -> State(Y) ^2"}
     * @throws IllegalArgumentException if a rule cannot be parsed
     */
    public PslInferenceResult inferWithRules(Collection<String> seedNodeIds, List<String> ruleStrings,
                                             Map<String, Double> evidence, int maxDepth, int maxNodes) {
        long start = System.currentTimeMillis();
        KgPslProgramBuilder builder = new KgPslProgramBuilder(graphService)
                .maxDepth(maxDepth).maxNodes(maxNodes).includeDefaultRules(false);
        PslProgram program = builder.build(seedNodeIds);
        if (ruleStrings != null) {
            for (String rule : ruleStrings) {
                if (rule != null && !rule.isBlank()) {
                    program.addRule(PslRule.parse(rule));
                }
            }
        }
        applyEvidence(program, builder, evidence);
        return run(builder, program, start);
    }

    /** Program/grounding statistics without running inference. */
    public Map<String, Object> programStatistics(Collection<String> seedNodeIds, int maxDepth, int maxNodes) {
        KgPslProgramBuilder builder = new KgPslProgramBuilder(graphService)
                .maxDepth(maxDepth).maxNodes(maxNodes);
        PslProgram program = builder.build(seedNodeIds);
        List<GroundRule> ground = program.ground();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", builder.constantToNodeId().size());
        stats.put("atoms", program.atomCount());
        stats.put("targets", program.targetKeys().size());
        stats.put("observed", program.observedKeys().size());
        stats.put("rules", program.rules().size());
        stats.put("groundRules", ground.size());
        return stats;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void applyEvidence(PslProgram program, KgPslProgramBuilder builder, Map<String, Double> evidence) {
        if (evidence == null) return;
        for (Map.Entry<String, Double> e : evidence.entrySet()) {
            String constant = builder.nodeIdToConstant().get(e.getKey());
            if (constant == null) {
                log.debug("Evidence node {} not in PSL program, skipping", e.getKey());
                continue;
            }
            double value = e.getValue() != null ? e.getValue() : 0.0;
            program.observe(KgPslProgramBuilder.STATE, value, constant);
        }
    }

    private PslInferenceResult run(KgPslProgramBuilder builder, PslProgram program, long start) {
        if (program.atomCount() == 0) {
            return PslInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - start)
                    .build();
        }
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        return buildResult(builder, program, result, start);
    }

    private PslInferenceResult buildResult(KgPslProgramBuilder builder, PslProgram program,
                                           HlMrfMapInference.Result result, long start) {
        Map<String, Double> values = result.values();
        Map<String, String> constantToNodeId = builder.constantToNodeId();
        Map<String, String> constantToTitle = builder.constantToTitle();

        Map<String, Double> inferred = new LinkedHashMap<>();
        Map<String, Double> observed = new LinkedHashMap<>();
        Map<String, Double> priors = new LinkedHashMap<>();
        Map<String, String> atomToNodeId = new LinkedHashMap<>();
        Map<String, String> atomToTitle = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : constantToNodeId.entrySet()) {
            String constant = entry.getKey();
            String nodeId = entry.getValue();
            String stateKey = KgPslProgramBuilder.STATE + "(" + constant + ")";
            if (!program.contains(stateKey)) continue;

            String readable = KgPslProgramBuilder.STATE + "(" + nodeId + ")";
            double value = values.getOrDefault(stateKey, 0.0);
            if (program.isObserved(stateKey)) {
                observed.put(readable, value);
            } else {
                inferred.put(readable, value);
            }
            atomToNodeId.put(readable, nodeId);
            atomToTitle.put(readable, constantToTitle.getOrDefault(constant, nodeId));

            String priorKey = KgPslProgramBuilder.PRIOR + "(" + constant + ")";
            if (program.contains(priorKey)) {
                priors.put(readable, values.getOrDefault(priorKey, 0.0));
            }
        }

        List<String> rules = new ArrayList<>();
        for (PslRule rule : program.rules()) rules.add(rule.toString());

        List<String> topViolations = collectTopViolations(result, values, constantToTitle, constantToNodeId);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", constantToNodeId.size());
        stats.put("atoms", program.atomCount());
        stats.put("targets", program.targetKeys().size());
        stats.put("observed", program.observedKeys().size());
        stats.put("rules", program.rules().size());
        stats.put("groundRules", result.groundRules().size());
        stats.put("iterations", result.iterations());
        stats.put("objective", result.objective());
        stats.put("converged", result.converged());

        long elapsed = System.currentTimeMillis() - start;
        log.info("PSL inference complete in {}ms: {} targets, {} ground rules, objective={}, converged={}",
                elapsed, inferred.size(), result.groundRules().size(), result.objective(), result.converged());

        return PslInferenceResult.builder()
                .inferredTruth(inferred)
                .observedTruth(observed)
                .priors(priors)
                .atomToNodeId(atomToNodeId)
                .atomToTitle(atomToTitle)
                .rules(rules)
                .topViolations(topViolations)
                .stats(stats)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .build();
    }

    private List<String> collectTopViolations(HlMrfMapInference.Result result, Map<String, Double> values,
                                              Map<String, String> constantToTitle,
                                              Map<String, String> constantToNodeId) {
        List<GroundRule> sorted = new ArrayList<>(result.groundRules());
        sorted.sort((a, b) -> Double.compare(
                b.distanceToSatisfaction(values), a.distanceToSatisfaction(values)));

        List<String> violations = new ArrayList<>();
        for (GroundRule rule : sorted) {
            double distance = rule.distanceToSatisfaction(values);
            if (distance <= 1e-6) break;
            violations.add(String.format("%.3f  %s", distance,
                    humanize(rule.display(), constantToTitle, constantToNodeId)));
            if (violations.size() >= TOP_VIOLATIONS) break;
        }
        return violations;
    }

    /** Replace synthetic {@code nN} constants with node titles for human-readable output. */
    private String humanize(String text, Map<String, String> constantToTitle,
                            Map<String, String> constantToNodeId) {
        Matcher matcher = CONSTANT_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String constant = matcher.group();
            String label = constantToTitle.getOrDefault(constant,
                    constantToNodeId.getOrDefault(constant, constant));
            if (label.length() > 50) label = label.substring(0, 47) + "...";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
