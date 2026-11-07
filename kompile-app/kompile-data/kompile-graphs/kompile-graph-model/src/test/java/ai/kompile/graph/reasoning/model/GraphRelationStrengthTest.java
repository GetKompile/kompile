/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphRelationStrengthTest {

    @Test
    void aScoreCopiedIntoBothFieldsIsReadOnce() {
        assertEquals(0.6, relation(0.6, 0.6).strength(), 1e-12, "the product would read 0.36");
    }

    @Test
    void eitherFieldCapsTheOther() {
        assertEquals(0.7, relation(1.0, 0.7).strength(), 1e-12, "unit weight, stored confidence");
        assertEquals(0.4, relation(0.4, 1.0).strength(), 1e-12, "stored weight, unit confidence");
        assertEquals(0.42, relation(0.42, 0.7).strength(), 1e-12,
                "a strength already folded with its confidence is not multiplied again");
    }

    @Test
    void outOfRangeValuesClampAndNaNReadsAsZero() {
        assertEquals(0.0, relation(Double.NaN, 0.9).strength());
        assertEquals(0.0, relation(0.9, Double.NaN).strength());
        assertEquals(0.0, relation(-0.5, 0.9).strength());
        assertEquals(1.0, relation(3.0, 2.0).strength());
        assertEquals(0.8, GraphRelation.strength(3.0, 0.8), 1e-12);
    }

    private static GraphRelation relation(double weight, double confidence) {
        return GraphRelation.builder("r", "a", "b").weight(weight).confidence(confidence).build();
    }
}
