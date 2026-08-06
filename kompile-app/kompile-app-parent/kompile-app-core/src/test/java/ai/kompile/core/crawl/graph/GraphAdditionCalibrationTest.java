/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.core.crawl.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAdditionCalibrationTest {

    @Test
    void absentCalibrationPreservesLegacyDefaultsAndRawOverrides() {
        GraphExtractionConfig defaults = GraphExtractionConfig.builder().build();
        assertEquals(0.55, defaults.getEffectiveCandidateMinScore());
        assertEquals(0.5, defaults.getEffectiveExtractionMinConfidence());
        assertEquals(0.5, defaults.getEffectivePersistenceMinConfidence());
        assertEquals(0.85, defaults.getEffectiveStringIdentitySimilarity());
        assertEquals(0.88, defaults.getEffectiveEmbeddingIdentitySimilarity());

        GraphExtractionConfig overridden = GraphExtractionConfig.builder()
                .decomposedCandidateMinScore(0.61)
                .minConfidence(0.42)
                .entityResolutionSimilarityThreshold(0.81)
                .entityResolutionEmbeddingThreshold(0.91)
                .build();
        assertEquals(0.61, overridden.getEffectiveCandidateMinScore());
        assertEquals(0.42, overridden.getEffectiveExtractionMinConfidence());
        assertEquals(0.42, overridden.getEffectivePersistenceMinConfidence());
        assertEquals(0.81, overridden.getEffectiveStringIdentitySimilarity());
        assertEquals(0.91, overridden.getEffectiveEmbeddingIdentitySimilarity());
    }

    @Test
    void profilesAndRigidityMoveEveryProbabilisticThreshold() {
        GraphExtractionConfig recall = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.recallBiased())
                .build();
        GraphExtractionConfig conservative = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.conservative())
                .build();
        GraphExtractionConfig halfRecall = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder()
                        .profile(GraphAdditionCalibration.Profile.RECALL_BIASED)
                        .rigidity(0.5)
                        .build())
                .build();

        assertTrue(recall.getEffectiveCandidateMinScore() < halfRecall.getEffectiveCandidateMinScore());
        assertTrue(halfRecall.getEffectiveCandidateMinScore() < 0.55);
        assertTrue(recall.getEffectiveExtractionMinConfidence() < 0.5);
        assertTrue(recall.getEffectivePersistenceMinConfidence() < 0.5);
        assertTrue(recall.getEffectiveStringIdentitySimilarity() < 0.85);
        assertTrue(recall.getEffectiveEmbeddingIdentitySimilarity() < 0.88);
        assertTrue(conservative.getEffectiveCandidateMinScore() > 0.55);
        assertTrue(conservative.getEffectiveExtractionMinConfidence() > 0.5);
        assertTrue(conservative.getEffectivePersistenceMinConfidence() > 0.5);
        assertTrue(conservative.getEffectiveStringIdentitySimilarity() > 0.85);
        assertTrue(conservative.getEffectiveEmbeddingIdentitySimilarity() > 0.88);
    }

    @Test
    void explicitOverridesWinAndExtractionCanDifferFromPersistence() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder()
                        .profile(GraphAdditionCalibration.Profile.CONSERVATIVE)
                        .candidateMinScore(0.11)
                        .extractionMinConfidence(0.22)
                        .persistenceMinConfidence(0.77)
                        .stringIdentitySimilarity(0.33)
                        .embeddingIdentitySimilarity(0.44)
                        .build())
                .build();

        assertEquals(0.11, config.getEffectiveCandidateMinScore());
        assertEquals(0.22, config.getEffectiveExtractionMinConfidence());
        assertEquals(0.77, config.getEffectivePersistenceMinConfidence());
        assertEquals(0.33, config.getEffectiveStringIdentitySimilarity());
        assertEquals(0.44, config.getEffectiveEmbeddingIdentitySimilarity());
        assertNotEquals(config.getEffectiveExtractionMinConfidence(),
                config.getEffectivePersistenceMinConfidence());
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder().rigidity(Double.NaN).build())
                .build().getResolvedGraphAdditionCalibration());
        assertThrows(IllegalArgumentException.class, () -> GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder().candidateMinScore(-0.01).build())
                .build().getResolvedGraphAdditionCalibration());
        assertThrows(IllegalArgumentException.class, () -> GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder()
                        .embeddingIdentitySimilarity(Double.POSITIVE_INFINITY).build())
                .build().getResolvedGraphAdditionCalibration());
        assertThrows(IllegalArgumentException.class, () -> GraphExtractionConfig.builder()
                .minConfidence(1.01)
                .build().getResolvedGraphAdditionCalibration());
    }

    @Test
    void hardInvariantsAreCompleteAndConstantAcrossProfiles() {
        var standard = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.standard()).build()
                .getResolvedGraphAdditionCalibration();
        var recall = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.recallBiased()).build()
                .getResolvedGraphAdditionCalibration();
        var conservative = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.conservative()).build()
                .getResolvedGraphAdditionCalibration();

        assertEquals(GraphAdditionCalibration.hardInvariants(), standard.hardInvariants());
        assertEquals(standard.hardInvariants(), recall.hardInvariants());
        assertEquals(standard.hardInvariants(), conservative.hardInvariants());
        assertEquals(GraphAdditionCalibration.HardInvariant.values().length, standard.hardInvariants().size());
        assertThrows(UnsupportedOperationException.class,
                () -> standard.hardInvariants().remove(
                        GraphAdditionCalibration.HardInvariant.COMPLETE_RELATION_ENDPOINTS));
    }
}
