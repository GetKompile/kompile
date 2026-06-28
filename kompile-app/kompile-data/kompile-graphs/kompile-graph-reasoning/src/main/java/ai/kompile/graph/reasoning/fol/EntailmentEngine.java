/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facade for producing {@link EntailmentRecord}s from either MEBN or PSL inference,
 * integrating findings/facts from a {@link FindingStore} or {@link FactStore}.
 *
 * <h3>MEBN path</h3>
 * <ol>
 *   <li>Collect evidence from the {@link FindingStore}: for each finding, map its
 *       grounded key to a state index (hard) or to the argmax of the likelihood vector
 *       (soft, simplified Jeffrey's rule). TODO: implement full Jeffrey's rule for soft evidence.</li>
 *   <li>Run {@link MebnInferenceService#infer(ReasoningGraph, MTheory, Map)} with the evidence map.</li>
 *   <li>For each posterior, identify which findings contributed (by prefix / grounded key match).</li>
 *   <li>Return a list of {@link EntailmentRecord}s.</li>
 * </ol>
 *
 * <h3>PSL path</h3>
 * <ol>
 *   <li>Apply all facts from {@link FactStore} to the program via {@link FactStore#applyToProgram}.</li>
 *   <li>Run {@link HlMrfMapInference#solve(PslProgram)} to get MAP inference results.</li>
 *   <li>For each target atom, identify supporting facts (observed atoms in the same ground rules)
 *       and activated rules (ground rules with this atom in the head and low distance to satisfaction).</li>
 *   <li>Return a list of {@link EntailmentRecord}s.</li>
 * </ol>
 */
public final class EntailmentEngine {

    private static final Logger log = LoggerFactory.getLogger(EntailmentEngine.class);
    private static final double ACTIVATION_THRESHOLD = 0.1; // rule is "activated" if dist < threshold
    /** Matches PSL synthetic constant tokens of the form {@code n0}, {@code n1}, etc. */
    private static final Pattern CONSTANT_PATTERN = Pattern.compile("n\\d+");

    private EntailmentEngine() {}

    /**
     * Run MEBN inference and produce entailment records, applying findings as evidence.
     *
     * @param graph        the reasoning graph
     * @param findingStore findings to use as evidence
     * @param theory       the MTheory to instantiate and run
     * @return list of entailment records for all inferred variables
     */
    public static List<EntailmentRecord> entailFromMebn(
            ReasoningGraph graph, FindingStore findingStore, MTheory theory) {

        String runId = UUID.randomUUID().toString();
        log.info("EntailmentEngine MEBN run={}, findings={}", runId, findingStore.size());

        // Build evidence map from FindingStore
        Map<String, Integer> evidence = new LinkedHashMap<>();
        for (Finding finding : findingStore.allFindings()) {
            String key = finding.groundedKey();
            if (finding.isHard()) {
                evidence.put(key, finding.stateIndex());
            } else if (finding.isSoft()) {
                // Simplified Jeffrey's rule: use the argmax of the likelihood vector
                // TODO: implement full Jeffrey's rule (requires updating CPTs, not just clamping)
                double[] lh = finding.likelihood();
                int maxIdx = 0;
                for (int i = 1; i < lh.length; i++) {
                    if (lh[i] > lh[maxIdx]) maxIdx = i;
                }
                evidence.put(key, maxIdx);
            }
        }

        // Run MEBN inference
        MebnInferenceService svc = new MebnInferenceService();
        Map<String, Double> posteriors;
        try {
            posteriors = svc.infer(graph, theory, evidence);
        } catch (Exception e) {
            log.warn("MEBN inference failed: {}", e.getMessage(), e);
            posteriors = Map.of();
        }

        // Build entailment records
        Instant now = Instant.now();
        List<EntailmentRecord> records = new ArrayList<>();

        for (Map.Entry<String, Double> entry : posteriors.entrySet()) {
            String varName = entry.getKey();
            double posterior = entry.getValue();

            // Identify which findings contributed: any finding whose grounded key
            // equals this variable name, or whose rvName is a prefix of the variable name
            List<String> supportingKeys = new ArrayList<>();
            for (Finding finding : findingStore.allFindings()) {
                if (varName.equals(finding.groundedKey()) ||
                        varName.startsWith(finding.rvName())) {
                    supportingKeys.add(finding.groundedKey());
                }
            }

            records.add(new EntailmentRecord(
                    varName,
                    posterior,
                    supportingKeys,
                    mebnEdgeProvenance(theory, varName), // noisy-OR edge strengths that shaped this RV's CPT
                    now,
                    runId
            ));
        }

        log.info("EntailmentEngine MEBN run={} produced {} records", runId, records.size());
        return records;
    }

    /**
     * Learned-weight provenance for a grounded MEBN variable: the noisy-OR edge strengths of every
     * theory edge whose child RV matches the variable's predicate, each formatted
     * {@code "<strength>: parent->child"}. This makes {@link InferredFact#ruleWeights()} surface MEBN
     * CPT strengths exactly as it surfaces PSL rule weights — so a MEBN run under {@code MebnWeightLearner}
     * fit edge strengths yields probabilistic facts that carry the learned weights that produced them.
     *
     * @param theory  the MTheory whose edge strengths shaped the CPTs
     * @param varName the grounded variable name (e.g. {@code "effect(alice)"}); its predicate is matched
     *                against each edge's child RV
     * @return weight-prefixed provenance strings (empty for prior-only RVs that have no incoming edge)
     */
    private static List<String> mebnEdgeProvenance(MTheory theory, String varName) {
        int paren = varName.indexOf('(');
        String predicate = paren > 0 ? varName.substring(0, paren) : varName;
        List<String> provenance = new ArrayList<>();
        for (MFrag mfrag : theory.getMFrags()) {
            for (Map.Entry<String, Double> edge : mfrag.getEdgeStrengths().entrySet()) {
                String key = edge.getKey();
                int sep = key.indexOf("->");
                if (sep > 0 && key.substring(sep + 2).equals(predicate)) {
                    provenance.add(String.format(Locale.ROOT, "%.2f: %s", edge.getValue(), key));
                }
            }
        }
        return provenance;
    }

    /**
     * Run PSL MAP inference and produce entailment records, applying facts as observations.
     *
     * @param program   the PSL program (rules + atoms declared); facts will be observed into it
     * @param factStore facts to apply as observations
     * @return list of entailment records for all target atoms
     */
    public static List<EntailmentRecord> entailFromPsl(
            PslProgram program, FactStore factStore) {

        String runId = UUID.randomUUID().toString();
        log.info("EntailmentEngine PSL run={}, facts={}", runId, factStore.size());

        // Apply all facts to the program
        factStore.applyToProgram(program);

        // Run MAP inference
        HlMrfMapInference.Result result;
        try {
            result = HlMrfMapInference.solve(program);
        } catch (Exception e) {
            log.warn("PSL inference failed: {}", e.getMessage(), e);
            return List.of();
        }
        return entailFromPslResult(program, result, factStore, runId);
    }

    /**
     * Build entailment records from an <em>already-computed</em> PSL MAP result — no re-solve. A caller
     * that already ran {@link HlMrfMapInference#solve} (e.g. {@code FolInferenceService}) can reuse its
     * result instead of running inference twice. {@code factStore} may be {@code null}, in which case
     * every observed body atom is treated as a supporting fact.
     *
     * @param program    the grounded PSL program (for target/observed keys)
     * @param result     the MAP inference result to read posteriors and ground rules from
     * @param factStore  optional fact store used to confirm supporting observations; may be null
     * @param runId      identifier stamped on every produced record
     * @return one {@link EntailmentRecord} per target atom
     */
    public static List<EntailmentRecord> entailFromPslResult(
            PslProgram program, HlMrfMapInference.Result result, FactStore factStore, String runId) {
        return entailFromPslResult(program, result, factStore, runId, Map.of(), Map.of());
    }

    /**
     * As {@link #entailFromPslResult(PslProgram, HlMrfMapInference.Result, FactStore, String)} but
     * translates the opaque PSL synthetic constants ({@code n0}, {@code n1}, …) in atom keys and rule
     * display strings to human-readable values before building the records.
     *
     * <ul>
     *   <li>{@code constantToEntityId} — maps {@code n0} → stable entity id; used to rewrite atom
     *       keys and supporting-fact keys so downstream consumers see e.g. {@code State(entity-42)}
     *       instead of {@code State(n0)}.</li>
     *   <li>{@code constantToLabel} — maps {@code n0} → human-readable label (e.g. "Alice Smith");
     *       used only to rewrite the rule display strings for readability. If a constant is absent
     *       from either map, the original token is left unchanged.</li>
     * </ul>
     *
     * @param program            the grounded PSL program
     * @param result             MAP inference result
     * @param factStore          optional fact store (may be null)
     * @param runId              run identifier stamped on every record
     * @param constantToEntityId map from synthetic PSL constant to entity id (stable external key)
     * @param constantToLabel    map from synthetic PSL constant to human-readable display label
     * @return one {@link EntailmentRecord} per target atom, with translated keys
     */
    public static List<EntailmentRecord> entailFromPslResult(
            PslProgram program, HlMrfMapInference.Result result, FactStore factStore, String runId,
            Map<String, String> constantToEntityId, Map<String, String> constantToLabel) {

        Map<String, Double> values = result.values();
        List<GroundRule> groundRules = result.groundRules();
        Set<String> observedKeys = program.observedKeys();

        // Build index: for each head atom (raw PSL key) -> list of ground rules that fired for it
        Map<String, List<GroundRule>> headToRules = new HashMap<>();
        Map<String, Set<String>> headToObservedAtoms = new HashMap<>();

        for (GroundRule gr : groundRules) {
            double dist = gr.distanceToSatisfaction(values);
            if (dist < ACTIVATION_THRESHOLD) {
                // This rule is activated (satisfied / near-satisfied)
                for (GroundRule.Lit headLit : gr.head()) {
                    String atomKey = headLit.atomKey();
                    headToRules.computeIfAbsent(atomKey, k -> new ArrayList<>()).add(gr);

                    // Collect observed (input) atoms from the body
                    Set<String> observedInBody = headToObservedAtoms
                            .computeIfAbsent(atomKey, k -> new LinkedHashSet<>());
                    for (GroundRule.Lit bodyLit : gr.body()) {
                        if (observedKeys.contains(bodyLit.atomKey())) {
                            observedInBody.add(bodyLit.atomKey());
                        }
                    }
                }
            }
        }

        // Build entailment records for target atoms
        Instant now = Instant.now();
        List<EntailmentRecord> records = new ArrayList<>();

        for (String rawAtomKey : program.targetKeys()) {
            double posterior = values.getOrDefault(rawAtomKey, 0.0);

            // Translate the atom key (raw PSL constant) to a stable entity-id-based key
            String atomKey = translateConstants(rawAtomKey, constantToEntityId);

            // Activated rules translated to human-readable labels
            List<String> activatedRules = new ArrayList<>();
            List<GroundRule> relevantRules = headToRules.getOrDefault(rawAtomKey, List.of());
            for (GroundRule gr : relevantRules) {
                activatedRules.add(translateConstants(gr.display(), constantToLabel));
            }

            // Supporting facts translated to entity-id-based keys
            List<String> supportingFactKeys = new ArrayList<>();
            Set<String> observedInBody = headToObservedAtoms.getOrDefault(rawAtomKey, Set.of());
            for (String obsKey : observedInBody) {
                if (factStore == null || factStore.factFor(obsKey).isPresent()) {
                    supportingFactKeys.add(translateConstants(obsKey, constantToEntityId));
                }
            }

            records.add(new EntailmentRecord(
                    atomKey,
                    posterior,
                    supportingFactKeys,
                    activatedRules,
                    now,
                    runId
            ));
        }

        log.info("EntailmentEngine PSL run={} produced {} records", runId, records.size());
        return records;
    }

    /**
     * Replace every {@code n\d+} token in {@code text} with the corresponding value from
     * {@code map}; tokens absent from the map are left unchanged.
     */
    private static String translateConstants(String text, Map<String, String> map) {
        if (map.isEmpty()) return text;
        Matcher m = CONSTANT_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String replacement = map.getOrDefault(token, token);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
