/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.uncertainty;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.psl.AtomMarginal;
import ai.kompile.graph.reasoning.psl.PslMarginalInference;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code uncertainty/} package: per-variable variance/entropy (Bayesian + PSL) and
 * Expected Information Gain (the "next best node to observe" acquisition function).
 */
class UncertaintyEstimatorTest {

    /** A -> B chain (B is noisy-OR on A). */
    private BayesianNetwork chainAB() {
        BayesianNetwork net = new BayesianNetwork();
        net.addNode(new BayesianNode("A", "kgA", "A"));
        net.addNode(new BayesianNode("B", "kgB", "B"));
        net.addEdge("A", "B");
        net.getNode("A").setCpt(NoisyOrCpt.buildPrior("A", 0.5));
        net.getNode("B").setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.9}, 0.05));
        return net;
    }

    /** A -> B chain plus an isolated, independent node C. */
    private BayesianNetwork chainABwithIsolatedC() {
        BayesianNetwork net = chainAB();
        net.addNode(new BayesianNode("C", "kgC", "C"));
        net.getNode("C").setCpt(NoisyOrCpt.buildPrior("C", 0.5));
        return net;
    }

    @Test
    void bayesian_perVariableVarianceAndEntropy() {
        Map<String, VariableUncertainty> u = BayesianUncertaintyEstimator.allUncertainties(chainAB(), Map.of());

        VariableUncertainty a = u.get("A");
        assertNotNull(a);
        assertEquals(0.5, a.marginal(), 1e-6, "root prior 0.5");
        assertEquals(0.25, a.variance(), 1e-6, "Var = p(1-p) = 0.25 at p=0.5");
        assertEquals(1.0, a.entropyBits(), 1e-6, "max entropy 1 bit at p=0.5");
        assertEquals(VariableUncertainty.InferencePath.BAYESIAN, a.path());
    }

    @Test
    void bayesian_evidenceVariableHasZeroUncertainty() {
        Map<String, VariableUncertainty> u =
                BayesianUncertaintyEstimator.allUncertainties(chainAB(), Map.of("A", 1));
        assertEquals(0.0, u.get("A").variance(), 1e-9);
        assertEquals(0.0, u.get("A").entropyBits(), 1e-9);
    }

    @Test
    void bayesian_rankByUncertainty_mostUncertainFirst_excludesEvidence() {
        List<VariableUncertainty> ranked = BayesianUncertaintyEstimator.rankByUncertainty(chainAB(), Map.of());
        assertFalse(ranked.isEmpty());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).entropyBits() >= ranked.get(i).entropyBits(),
                    "entropy non-increasing down the ranking");
        }
        assertEquals("A", ranked.get(0).variableKey(), "A (p=0.5, H=1 bit) is the most uncertain");

        List<VariableUncertainty> withEv = BayesianUncertaintyEstimator.rankByUncertainty(chainAB(), Map.of("A", 1));
        assertTrue(withEv.stream().noneMatch(v -> v.variableKey().equals("A")), "evidence excluded");
    }

    @Test
    void informationGain_parentInformsChild_independentNodeDoesNot() {
        BayesianNetwork net = chainABwithIsolatedC();
        double eigA = InformationGainEstimator.expectedInformationGain(net, Map.of(), "A", "B");
        double eigC = InformationGainEstimator.expectedInformationGain(net, Map.of(), "C", "B");

        assertTrue(eigA > 0.0, "observing parent A reduces uncertainty about child B");
        assertEquals(0.0, eigC, 1e-6, "independent node C is uninformative about B");
        assertTrue(eigA > eigC);

        List<Map.Entry<String, Double>> ranked = InformationGainEstimator.rankCandidatesByEIG(net, Map.of(), "B");
        assertEquals("A", ranked.get(0).getKey(), "A is the best node to observe for B");
        assertTrue(ranked.stream().noneMatch(e -> e.getKey().equals("B")), "target excluded");
    }

    @Test
    void psl_fromMarginals_mapsVarianceAndEntropy_ranksByVariance() {
        Map<String, AtomMarginal> marginals = new LinkedHashMap<>();
        marginals.put("P(a)", new AtomMarginal("P(a)", 0.5, 0.20, 16));
        marginals.put("P(b)", new AtomMarginal("P(b)", 0.95, 0.02, 16));
        PslMarginalInference.Result result = new PslMarginalInference.Result(marginals, Map.of(), 16);

        Map<String, VariableUncertainty> u = PslUncertaintyAdapter.fromMarginals(result);
        assertEquals(0.20, u.get("P(a)").variance(), 1e-9, "PSL sample variance carried through");
        assertEquals(1.0, u.get("P(a)").entropyBits(), 1e-6, "binary-entropy proxy of mean 0.5 = 1 bit");
        assertEquals(VariableUncertainty.InferencePath.PSL, u.get("P(a)").path());

        List<VariableUncertainty> ranked = PslUncertaintyAdapter.rankByVariance(result);
        assertEquals("P(a)", ranked.get(0).variableKey(), "highest sample variance ranked first");
    }

    // ── Sensitivity (§1.6) + Value of Information (§3.6) ────────────────────────────

    @Test
    void sensitivity_parentShiftsChild_independentDoesNot() {
        BayesianNetwork net = chainABwithIsolatedC();
        double sensA = SensitivityAnalyzer.sensitivity(net, Map.of(), "A", "B");
        double sensC = SensitivityAnalyzer.sensitivity(net, Map.of(), "C", "B");
        assertTrue(sensA > 0.0, "observing parent A shifts P(B)");
        assertEquals(0.0, sensC, 1e-6, "independent node C does not shift P(B)");
        assertEquals("A", SensitivityAnalyzer.rankBySensitivity(net, Map.of(), "B").get(0).getKey());
    }

    @Test
    void valueOfInformation_bayesian_ranksParentFirst_carriesCurrentEntropy() {
        BayesianNetwork net = chainABwithIsolatedC();
        List<ValueOfInformation.VoIResult> voi = ValueOfInformation.forBayesianNetwork(net, Map.of(), "B");
        assertEquals("A", voi.get(0).variableKey());
        assertTrue(voi.get(0).expectedInformationGain() > 0.0);
        assertEquals(1.0, voi.get(0).currentEntropy(), 1e-6, "A's current entropy (p=0.5) ~ 1 bit");
        assertEquals(VariableUncertainty.InferencePath.BAYESIAN, voi.get(0).path());
    }

    // ── PSL sample-based info-gain (§3.5) + BALD (§1.5) ─────────────────────────────

    /** Build a PSL Result with explicit raw samples (4-arg form) for sample-based tests. */
    private PslMarginalInference.Result pslResult(List<Map<String, Double>> samples, String... atomKeys) {
        Map<String, AtomMarginal> marginals = new LinkedHashMap<>();
        for (String k : atomKeys) {
            double mean = samples.stream().mapToDouble(s -> s.getOrDefault(k, 0.0)).average().orElse(0.0);
            double var = samples.stream()
                    .mapToDouble(s -> { double d = s.getOrDefault(k, 0.0) - mean; return d * d; })
                    .average().orElse(0.0);
            marginals.put(k, new AtomMarginal(k, mean, var, samples.size()));
        }
        return new PslMarginalInference.Result(marginals, Map.of(), samples.size(), samples);
    }

    @Test
    void psl_sampleSplitEIG_higherForCorrelatedThanIndependent() {
        PslMarginalInference.Result correlated = pslResult(List.of(
                Map.of("C", 0.9, "T", 0.85), Map.of("C", 0.95, "T", 0.8),
                Map.of("C", 0.1, "T", 0.2), Map.of("C", 0.05, "T", 0.15)), "C", "T");
        double eigCorr = PslInformationGainApproximator.sampleSplitEIG(correlated, "C", "T");
        assertTrue(eigCorr > 0.1, "a candidate correlated with the target is informative");

        PslMarginalInference.Result independent = pslResult(List.of(
                Map.of("C", 0.9, "T", 0.5), Map.of("C", 0.1, "T", 0.5),
                Map.of("C", 0.9, "T", 0.5), Map.of("C", 0.1, "T", 0.5)), "C", "T");
        double eigIndep = PslInformationGainApproximator.sampleSplitEIG(independent, "C", "T");
        assertEquals(0.0, eigIndep, 1e-6, "a candidate uncorrelated with the target carries ~0 info");
        assertTrue(eigCorr > eigIndep);
    }

    @Test
    void psl_bald_highForEpistemicDisagreement_zeroForAleatoric() {
        // Confident within each sample (0.95 / 0.05) but disagreeing across samples -> epistemic.
        double baldEpistemic = PslUncertaintyAdapter.bald(pslResult(List.of(
                Map.of("X", 0.95), Map.of("X", 0.05), Map.of("X", 0.95), Map.of("X", 0.05)), "X"), "X");
        assertTrue(baldEpistemic > 0.3, "disagreeing-but-confident samples = high epistemic uncertainty");

        // Every sample uniformly uncertain at 0.5 -> pure aleatoric -> BALD 0.
        double baldAleatoric = PslUncertaintyAdapter.bald(pslResult(List.of(
                Map.of("X", 0.5), Map.of("X", 0.5), Map.of("X", 0.5)), "X"), "X");
        assertEquals(0.0, baldAleatoric, 1e-9, "uniformly uncertain samples = pure aleatoric, BALD 0");
    }
}
