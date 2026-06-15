/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.resolution;

import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProbabilisticEntityResolutionTest {

    @Mock
    private KnowledgeGraphService graphService;

    private ProbabilisticEntityResolution resolution;

    @BeforeEach
    void setUp() {
        resolution = new ProbabilisticEntityResolution(graphService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIGH SIMILARITY → HIGH POSTERIOR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void highSimilarity_allSignalsStrong_highPosterior() {
        Optional<Double> prob = resolution.computeMatchProbability(
                "node-a", "node-b",
                0.95,   // very high name similarity
                0.85,   // strong property overlap
                true    // types compatible
        );

        assertTrue(prob.isPresent(), "Should produce a posterior");
        assertTrue(prob.get() > 0.5, "High similarity should yield P > 0.5, got: " + prob.get());
    }

    @Test
    void lowSimilarity_allSignalsWeak_lowPosterior() {
        Optional<Double> prob = resolution.computeMatchProbability(
                "node-a", "node-b",
                0.2,    // low name similarity
                0.1,    // weak property overlap
                false   // types not compatible
        );

        assertTrue(prob.isPresent(), "Should produce a posterior");
        assertTrue(prob.get() < 0.5, "Low similarity should yield P < 0.5, got: " + prob.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONOTONICITY: STRONGER SIGNALS → HIGHER POSTERIOR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void strongerNameSimilarity_yieldsHigherPosterior() {
        Optional<Double> probLow = resolution.computeMatchProbability(
                "node-a", "node-b", 0.3, 0.5, true);
        Optional<Double> probHigh = resolution.computeMatchProbability(
                "node-a", "node-b", 0.9, 0.5, true);

        assertTrue(probLow.isPresent() && probHigh.isPresent());
        assertTrue(probHigh.get() >= probLow.get(),
                "Higher name similarity should yield >= posterior: " +
                        probHigh.get() + " vs " + probLow.get());
    }

    @Test
    void typeCompatible_yieldsHigherPosterior_thanIncompatible() {
        Optional<Double> probIncompat = resolution.computeMatchProbability(
                "node-a", "node-b", 0.7, 0.5, false);
        Optional<Double> probCompat = resolution.computeMatchProbability(
                "node-a", "node-b", 0.7, 0.5, true);

        assertTrue(probIncompat.isPresent() && probCompat.isPresent());
        assertTrue(probCompat.get() >= probIncompat.get(),
                "Type compatible should yield >= posterior: " +
                        probCompat.get() + " vs " + probIncompat.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVIDENCE THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void nameSimilarityThreshold_belowAndAbove() {
        // nameSim = 0.55 is below the 0.6 evidence threshold
        Optional<Double> probBelow = resolution.computeMatchProbability(
                "node-a", "node-b", 0.55, 0.5, true);
        // nameSim = 0.65 is above the 0.6 evidence threshold
        Optional<Double> probAbove = resolution.computeMatchProbability(
                "node-a", "node-b", 0.65, 0.5, true);

        assertTrue(probBelow.isPresent() && probAbove.isPresent());
        // Above threshold should give >= posterior (evidence variable set to TRUE)
        assertTrue(probAbove.get() >= probBelow.get(),
                "Above name threshold should give >= posterior");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BOUNDARY VALUES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void posteriorBoundedBetweenZeroAndOne() {
        // Extreme high signals
        Optional<Double> probMax = resolution.computeMatchProbability(
                "node-a", "node-b", 1.0, 1.0, true);
        assertTrue(probMax.isPresent());
        assertTrue(probMax.get() >= 0.0 && probMax.get() <= 1.0,
                "Posterior must be in [0,1], got: " + probMax.get());

        // Extreme low signals
        Optional<Double> probMin = resolution.computeMatchProbability(
                "node-a", "node-b", 0.0, 0.0, false);
        assertTrue(probMin.isPresent());
        assertTrue(probMin.get() >= 0.0 && probMin.get() <= 1.0,
                "Posterior must be in [0,1], got: " + probMin.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH SCORING
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void scoreBatch_returnsResultsForAllPairs() {
        List<ProbabilisticEntityResolution.CandidatePair> candidates = List.of(
                new ProbabilisticEntityResolution.CandidatePair("a", "b", 0.9, 0.8, true),
                new ProbabilisticEntityResolution.CandidatePair("c", "d", 0.3, 0.1, false)
        );

        Map<String, Double> results = resolution.scoreBatch(candidates);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.containsKey("a:b"));
        assertTrue(results.containsKey("c:d"));

        // Strong pair should score higher than weak pair
        assertTrue(results.get("a:b") > results.get("c:d"),
                "Strong pair should score higher: " + results.get("a:b") + " vs " + results.get("c:d"));
    }

    @Test
    void scoreBatch_emptyList_returnsEmptyMap() {
        Map<String, Double> results = resolution.scoreBatch(List.of());
        assertTrue(results.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MTHEORY STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void computeMatchProbability_producesValidPosterior() {
        // Verify that for a middle-ground case we get a valid probabilistic answer
        Optional<Double> prob = resolution.computeMatchProbability(
                "entity-x", "entity-y",
                0.7,    // moderate name similarity (above threshold)
                0.4,    // moderate property overlap (above threshold)
                true    // types compatible
        );

        assertTrue(prob.isPresent());
        double p = prob.get();
        // With all three evidence signals positive, posterior should be meaningful
        assertTrue(p > 0.0, "Posterior should be > 0 with positive evidence");
        assertTrue(p <= 1.0, "Posterior should be <= 1.0");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEBN PROBABILISTIC SCORER ADAPTER
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void mebnProbabilisticScorer_delegatesToResolution() {
        MebnProbabilisticScorer scorer = new MebnProbabilisticScorer(graphService);

        Optional<Double> score = scorer.score("a", "b", 0.9, 0.8, true);
        assertTrue(score.isPresent());
        assertTrue(score.get() > 0.5, "Strong signals should yield high score");
    }

    @Test
    void mebnProbabilisticScorer_weakSignals_lowScore() {
        MebnProbabilisticScorer scorer = new MebnProbabilisticScorer(graphService);

        Optional<Double> score = scorer.score("a", "b", 0.1, 0.05, false);
        assertTrue(score.isPresent());
        assertTrue(score.get() < 0.5, "Weak signals should yield low score");
    }
}
