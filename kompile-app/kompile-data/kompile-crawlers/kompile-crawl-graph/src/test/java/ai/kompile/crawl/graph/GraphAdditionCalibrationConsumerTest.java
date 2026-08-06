/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphAdditionCalibration;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAdditionCalibrationConsumerTest {

    @Test
    void persistenceUsesItsOwnThresholdAndCalibratedStringIdentity() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.builder()
                        .extractionMinConfidence(0.2)
                        .persistenceMinConfidence(0.8)
                        .stringIdentitySimilarity(0.73)
                        .build())
                .build();
        GraphPersistenceHelper helper = new GraphPersistenceHelper();

        assertTrue(helper.belowMinConfidence(0.5, config));
        assertFalse(0.5 < config.getEffectiveExtractionMinConfidence());
        assertEquals(0.73, helper.entityResolutionSimilarityThreshold(config));
    }

    @Test
    void defaultAndProfilesResolveWithoutChangingHardInvariants() {
        GraphExtractionConfig defaults = GraphExtractionConfig.builder().build();
        GraphExtractionConfig recall = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.recallBiased()).build();
        GraphExtractionConfig conservative = GraphExtractionConfig.builder()
                .graphAdditionCalibration(GraphAdditionCalibration.conservative()).build();

        assertEquals(0.55, defaults.getEffectiveCandidateMinScore());
        assertEquals(0.5, defaults.getEffectiveExtractionMinConfidence());
        assertEquals(0.5, defaults.getEffectivePersistenceMinConfidence());
        assertEquals(0.85, defaults.getEffectiveStringIdentitySimilarity());
        assertEquals(0.88, defaults.getEffectiveEmbeddingIdentitySimilarity());
        assertTrue(recall.getEffectiveCandidateMinScore() < defaults.getEffectiveCandidateMinScore());
        assertTrue(conservative.getEffectiveCandidateMinScore() > defaults.getEffectiveCandidateMinScore());
        assertEquals(recall.getResolvedGraphAdditionCalibration().hardInvariants(),
                conservative.getResolvedGraphAdditionCalibration().hardInvariants());
    }
}
