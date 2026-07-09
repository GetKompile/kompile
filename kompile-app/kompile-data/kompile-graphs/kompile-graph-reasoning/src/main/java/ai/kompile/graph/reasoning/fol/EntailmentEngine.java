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

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.bayesian.Factor;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
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
 * <h3>MEBN path — soft evidence via Pearl's virtual-evidence method</h3>
 * <ol>
 *   <li>Collect hard findings from the {@link FindingStore}: map each grounded key to a state
 *       index and pass directly as BN evidence (clamped state).</li>
 *   <li>For each <em>soft</em> finding with likelihood vector {@code L[i]}, inject a binary
 *       <em>virtual child</em> node {@code __virtual_<key>} into the SSBN whose CPT is
 *       {@code P(virtual=TRUE | V=state_i) = L[i] / max(L)} (normalized to ≤ 1), then clamp
 *       {@code virtual=TRUE} as hard evidence. This implements Pearl's (1988) virtual-evidence
 *       method and yields the exact Jeffrey-rule posterior update for a single soft finding on
 *       one variable.  For multiple soft findings on different variables the updates are
 *       independent (each gets its own virtual child), which is exact when the findings are
 *       conditionally independent given the queried variable; the approximation degrades when
 *       two soft findings both bear on the same unobserved parent (iterate-to-convergence if
 *       exact multi-finding joint treatment is required).</li>
 *   <li>Run {@link MebnInferenceService#infer(ReasoningGraph, MTheory, Map)} with the evidence
 *       map; then strip {@code __virtual_*} variables from posteriors and
 *       {@link EntailmentRecord}s.</li>
 *   <li>Return a list of {@link EntailmentRecord}s for non-virtual variables only.</li>
 * </ol>
 *
 * <p><b>Why virtual-evidence rather than the posterior-reweighting fallback?</b>
 * {@link BayesianNetwork} supports {@link BayesianNetwork#addNode} and
 * {@link BayesianNetwork#addEdge} at any time, so we can inject virtual children
 * post-construction; the resulting evidence map entry ({@code __virtual → 1}) is then
 * processed by the existing {@link VariableElimination#query} path without any change to
 * the VE algorithm.  Posterior reweighting (multiply posterior by L[i] and renormalize)
 * would be an exact fallback only when querying V directly — the virtual-node method is
 * cleaner and correct for arbitrary query variables.</p>
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

    /**
     * Default distance-to-satisfaction threshold below which a rule is counted as "activated"
     * (i.e. contributing to the entailment of a head atom). Rules at or above this threshold
     * are excluded from {@link EntailmentRecord#activatedRules()} but may appear in
     * {@link EntailmentRecord#nearMissRules()} when their distance is below
     * {@link #DEFAULT_NEAR_MISS_THRESHOLD}.
     */
    public static final double DEFAULT_ACTIVATION_THRESHOLD = 0.1;

    /**
     * Default upper bound for the near-miss window. A rule with distance in
     * {@code [activationThreshold, nearMissThreshold)} is a near-miss: it almost satisfied
     * the constraint, and surfaces in {@link EntailmentRecord#nearMissRules()}.
     */
    public static final double DEFAULT_NEAR_MISS_THRESHOLD = 0.3;

    /** Matches PSL synthetic constant tokens of the form {@code n0}, {@code n1}, etc. */
    private static final Pattern CONSTANT_PATTERN = Pattern.compile("n\\d+");

    private EntailmentEngine() {}

    /** Prefix used for virtual-evidence child nodes injected for soft findings. */
    static final String VIRTUAL_NODE_PREFIX = "__virtual_";

    /**
     * Run MEBN inference and produce entailment records, applying findings as evidence.
     *
     * <p>Hard findings are passed directly as BN evidence (clamped state).  Soft findings
     * are converted to Pearl virtual evidence: for each soft finding on variable {@code V}
     * with likelihood vector {@code L}, a binary virtual child node
     * {@value #VIRTUAL_NODE_PREFIX}{@code <key>} is injected into the SSBN.  Its CPT is
     * {@code P(virtual=TRUE | V=state_i) = L[i] / max(L)}, and {@code virtual=TRUE} is
     * then clamped as hard evidence.  This is equivalent to Jeffrey conditioning (exact for
     * a single soft finding on one variable) without rewriting any CPT of the SSBN proper.
     * Virtual nodes are excluded from posteriors and {@link EntailmentRecord}s.</p>
     *
     * @param graph        the reasoning graph
     * @param findingStore findings to use as evidence
     * @param theory       the MTheory to instantiate and run
     * @return list of entailment records for all non-virtual inferred variables
     */
    public static List<EntailmentRecord> entailFromMebn(
            ReasoningGraph graph, FindingStore findingStore, MTheory theory) {

        String runId = UUID.randomUUID().toString();
        log.info("EntailmentEngine MEBN run={}, findings={}", runId, findingStore.size());

        // Partition findings into hard and soft.
        Map<String, Integer> hardEvidence = new LinkedHashMap<>();
        List<Finding> softFindings = new ArrayList<>();

        for (Finding finding : findingStore.allFindings()) {
            if (finding.isHard()) {
                hardEvidence.put(finding.groundedKey(), finding.stateIndex());
            } else if (finding.isSoft()) {
                softFindings.add(finding);
            }
        }

        // Generate the SSBN via the standard service path.
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);
        ai.kompile.graph.reasoning.mebn.SSBNGenerator generator =
                new ai.kompile.graph.reasoning.mebn.SSBNGenerator(theory, kb);
        BayesianNetwork ssbn = generator.generate();

        // Inject virtual child nodes for each soft finding (Pearl virtual evidence).
        // The combined evidence map starts from hard clamped states.
        Map<String, Integer> evidence = new LinkedHashMap<>(hardEvidence);
        for (Finding sf : softFindings) {
            String key = sf.groundedKey();
            BayesianNode parentNode = ssbn.getNode(key);
            if (parentNode == null) {
                log.debug("MEBN soft finding: node '{}' not in SSBN — skipping virtual evidence", key);
                continue;
            }
            double[] lh = sf.likelihood();
            int card = parentNode.getCardinality();

            // Normalize likelihood vector to ≤ 1 (divide by max) so CPT values are valid probs.
            double maxLh = lh[0];
            for (double v : lh) if (v > maxLh) maxLh = v;
            if (maxLh <= 0.0) {
                log.debug("MEBN soft finding: zero likelihood vector for '{}' — skipping", key);
                continue;
            }

            // Build virtual-node CPT: P(virtual=T | parent=state_i) = lh[i] / maxLh
            // Variables: [parent, virtual], cardinalities: [card, 2]
            // CPT entries (row-major over parent × virtual):
            //   for parent=i: [1 - lh[i]/maxLh, lh[i]/maxLh]
            String virtualName = VIRTUAL_NODE_PREFIX + key;
            BayesianNode virtualNode = new BayesianNode(virtualName, virtualName,
                    "virtual_evidence(" + key + ")", BayesianNode.BINARY_STATES);

            // CPT flat array: index = parentState * 2 + virtualState
            double[] cptValues = new double[card * 2];
            for (int i = 0; i < card; i++) {
                double probTrue = (i < lh.length) ? Math.min(1.0, lh[i] / maxLh) : 0.0;
                cptValues[i * 2]     = 1.0 - probTrue; // P(virtual=F | parent=i)
                cptValues[i * 2 + 1] = probTrue;        // P(virtual=T | parent=i)
            }
            Factor virtualCpt = new Factor(
                    List.of(key, virtualName),
                    new int[]{card, 2},
                    cptValues);
            virtualNode.addParent(parentNode);
            virtualNode.setCpt(virtualCpt);

            // Add the virtual node to the SSBN and wire it as a child of the parent.
            ssbn.addNode(virtualNode);
            ssbn.addEdge(key, virtualName);

            // Clamp virtual=TRUE as hard evidence.
            evidence.put(virtualName, 1);
            log.debug("MEBN virtual evidence: added '{}' for soft finding on '{}'", virtualName, key);
        }

        // Run VE over the augmented SSBN.
        Map<String, Double> posteriors;
        try {
            posteriors = VariableElimination.queryAll(ssbn, evidence);
        } catch (Exception e) {
            log.warn("MEBN inference failed: {}", e.getMessage(), e);
            posteriors = Map.of();
        }

        // Build entailment records, excluding virtual nodes from output.
        Instant now = Instant.now();
        List<EntailmentRecord> records = new ArrayList<>();

        for (Map.Entry<String, Double> entry : posteriors.entrySet()) {
            String varName = entry.getKey();
            if (varName.startsWith(VIRTUAL_NODE_PREFIX)) {
                continue; // skip virtual-evidence nodes
            }
            double posterior = entry.getValue();

            // Identify which findings contributed: any finding whose grounded key
            // equals this variable name, or whose rvName is a prefix of the variable name.
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

        log.info("EntailmentEngine MEBN run={} produced {} records (softFindings={})",
                runId, records.size(), softFindings.size());
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
     * every observed body atom is treated as a supporting fact. Uses default thresholds
     * ({@link #DEFAULT_ACTIVATION_THRESHOLD}, {@link #DEFAULT_NEAR_MISS_THRESHOLD}).
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
     * <p>Uses default thresholds ({@link #DEFAULT_ACTIVATION_THRESHOLD},
     * {@link #DEFAULT_NEAR_MISS_THRESHOLD}).</p>
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
        return entailFromPslResult(program, result, factStore, runId, constantToEntityId,
                constantToLabel, DEFAULT_ACTIVATION_THRESHOLD, DEFAULT_NEAR_MISS_THRESHOLD);
    }

    /**
     * Full-parameter variant: same as the 6-arg overload but with explicit
     * {@code activationThreshold} and {@code nearMissThreshold} controls.
     *
     * <p>Rules with distance-to-satisfaction {@code d < activationThreshold} are "activated"
     * and appear in {@link EntailmentRecord#activatedRules()}. Rules with
     * {@code activationThreshold ≤ d < nearMissThreshold} are near-misses and appear in
     * {@link EntailmentRecord#nearMissRules()} prefixed with {@code "d=<value>: "}.</p>
     *
     * @param program             the grounded PSL program
     * @param result              MAP inference result
     * @param factStore           optional fact store (may be null)
     * @param runId               run identifier stamped on every record
     * @param constantToEntityId  map from synthetic PSL constant to entity id
     * @param constantToLabel     map from synthetic PSL constant to display label
     * @param activationThreshold distance below which a rule is "activated" (default 0.1)
     * @param nearMissThreshold   distance below which a non-activated rule is a near-miss (default 0.3)
     * @return one {@link EntailmentRecord} per target atom, with translated keys
     */
    public static List<EntailmentRecord> entailFromPslResult(
            PslProgram program, HlMrfMapInference.Result result, FactStore factStore, String runId,
            Map<String, String> constantToEntityId, Map<String, String> constantToLabel,
            double activationThreshold, double nearMissThreshold) {

        Map<String, Double> values = result.values();
        List<GroundRule> groundRules = result.groundRules();
        Set<String> observedKeys = program.observedKeys();

        // Build index: for each head atom (raw PSL key) -> activated rules + near-miss rules
        Map<String, List<GroundRule>> headToActivated = new HashMap<>();
        Map<String, List<GroundRule>> headToNearMiss = new HashMap<>();
        Map<String, Set<String>> headToObservedAtoms = new HashMap<>();

        for (GroundRule gr : groundRules) {
            double dist = gr.distanceToSatisfaction(values);
            boolean activated = dist < activationThreshold;
            boolean nearMiss = !activated && dist < nearMissThreshold;

            if (activated || nearMiss) {
                for (GroundRule.Lit headLit : gr.head()) {
                    String atomKey = headLit.atomKey();

                    if (activated) {
                        headToActivated.computeIfAbsent(atomKey, k -> new ArrayList<>()).add(gr);

                        // Collect observed (input) atoms from the body
                        Set<String> observedInBody = headToObservedAtoms
                                .computeIfAbsent(atomKey, k -> new LinkedHashSet<>());
                        for (GroundRule.Lit bodyLit : gr.body()) {
                            if (observedKeys.contains(bodyLit.atomKey())) {
                                observedInBody.add(bodyLit.atomKey());
                            }
                        }
                    } else {
                        // near-miss
                        headToNearMiss.computeIfAbsent(atomKey, k -> new ArrayList<>()).add(gr);
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
            List<GroundRule> activatedList = headToActivated.getOrDefault(rawAtomKey, List.of());
            for (GroundRule gr : activatedList) {
                activatedRules.add(translateConstants(gr.display(), constantToLabel));
            }

            // Near-miss rules with distance prefix
            List<String> nearMissRules = new ArrayList<>();
            List<GroundRule> nearMissList = headToNearMiss.getOrDefault(rawAtomKey, List.of());
            for (GroundRule gr : nearMissList) {
                double d = gr.distanceToSatisfaction(values);
                String prefix = String.format(Locale.ROOT, "d=%.3f: ", d);
                nearMissRules.add(prefix + translateConstants(gr.display(), constantToLabel));
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
                    runId,
                    nearMissRules
            ));
        }

        log.info("EntailmentEngine PSL run={} produced {} records (thresholds: activated<{}, near-miss<{})",
                runId, records.size(), activationThreshold, nearMissThreshold);
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
