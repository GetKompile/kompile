/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package io.anserini.encoder.samediff;

import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericDenseSameDiffEncoderPoolingTest {

    @Test
    void maskedMeanPoolExcludesPaddedTokens() {
        INDArray output = Nd4j.create(
                new float[]{1, 2, 3, 4, 100, 100},
                new long[]{1, 3, 2});
        INDArray pooled = null;
        try {
            pooled = GenericDenseSameDiffEncoder.maskedMeanPool(
                    output, new long[]{1, 1, 0});
            assertEquals(2, pooled.length());
            assertEquals(2.0f, pooled.getFloat(0), 1.0e-6f);
            assertEquals(3.0f, pooled.getFloat(1), 1.0e-6f);
        } finally {
            if (pooled != null && !pooled.wasClosed()) pooled.close();
            if (!output.wasClosed()) output.close();
        }
    }

    @Test
    void inputPrefixIsAppliedExactlyOnce() {
        assertEquals("query: revenue forecast",
                GenericDenseSameDiffEncoder.applyInputPrefix("query: ", "revenue forecast"));
        assertEquals("query: revenue forecast",
                GenericDenseSameDiffEncoder.applyInputPrefix("query: ", "query: revenue forecast"));
        assertEquals("revenue forecast",
                GenericDenseSameDiffEncoder.applyInputPrefix("", "revenue forecast"));
    }

    @Test
    void maskedMeanPoolBatchExcludesPaddingAndPhysicalRows() {
        INDArray output = Nd4j.create(
                new float[]{
                        1, 2, 3, 4, 100, 100,
                        10, 20, 30, 40, 50, 60,
                        999, 999, 999, 999, 999, 999},
                new long[]{3, 3, 2});
        INDArray pooled = null;
        try {
            pooled = GenericDenseSameDiffEncoder.maskedMeanPoolBatch(
                    output,
                    new long[][]{{1, 1, 0}, {1, 1, 1}, {1, 1, 1}},
                    2);
            assertEquals(2, pooled.size(0));
            assertEquals(2, pooled.size(1));
            assertEquals(2.0f, pooled.getFloat(0, 0), 1.0e-6f);
            assertEquals(3.0f, pooled.getFloat(0, 1), 1.0e-6f);
            assertEquals(30.0f, pooled.getFloat(1, 0), 1.0e-6f);
            assertEquals(40.0f, pooled.getFloat(1, 1), 1.0e-6f);
        } finally {
            if (pooled != null && !pooled.wasClosed()) pooled.close();
            if (!output.wasClosed()) output.close();
        }
    }
}
