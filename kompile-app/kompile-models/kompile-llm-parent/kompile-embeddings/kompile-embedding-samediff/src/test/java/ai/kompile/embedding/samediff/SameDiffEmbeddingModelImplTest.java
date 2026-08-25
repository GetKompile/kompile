/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.embedding.samediff;

import ai.kompile.embedding.samediff.config.SameDiffEmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SameDiffEmbeddingModelImplTest {

    @Test
    void maskedMeanPoolingExcludesPadding() {
        INDArray output = Nd4j.create(new float[]{
                1, 2, 3, 4, 100, 100,
                2, 0, 0, 2, 2, 2
        }, new long[]{2, 3, 2});
        INDArray mask = Nd4j.createFromArray(new long[][]{
                {1, 1, 0},
                {1, 1, 1}
        });
        INDArray pooled = null;
        try {
            pooled = SameDiffEmbeddingModelImpl.pool(
                    output, mask, SameDiffEmbeddingProperties.PoolingStrategy.MEAN);
            assertArrayEquals(new long[]{2, 2}, pooled.shape());
            assertEquals(2.0f, pooled.getFloat(0, 0), 1.0e-6f);
            assertEquals(3.0f, pooled.getFloat(0, 1), 1.0e-6f);
            assertEquals(4.0f / 3.0f, pooled.getFloat(1, 0), 1.0e-6f);
            assertEquals(4.0f / 3.0f, pooled.getFloat(1, 1), 1.0e-6f);
        } finally {
            close(pooled);
            close(mask);
            close(output);
        }
    }

    @Test
    void clsPoolingSelectsFirstTokenForEveryBatchRow() {
        INDArray output = Nd4j.create(new float[]{
                1, 2, 3, 4,
                5, 6, 7, 8
        }, new long[]{2, 2, 2});
        INDArray pooled = null;
        try {
            pooled = SameDiffEmbeddingModelImpl.pool(
                    output, null, SameDiffEmbeddingProperties.PoolingStrategy.CLS);
            assertArrayEquals(new float[]{1, 2, 5, 6}, pooled.data().asFloat(), 1.0e-6f);
        } finally {
            close(pooled);
            close(output);
        }
    }

    @Test
    void rowNormalizationProducesUnitVectors() {
        INDArray values = Nd4j.create(new float[]{3, 4, 1, 1}, new long[]{2, 2});
        INDArray normalized = null;
        try {
            normalized = SameDiffEmbeddingModelImpl.normalizeRows(values);
            assertEquals(0.6f, normalized.getFloat(0, 0), 1.0e-6f);
            assertEquals(0.8f, normalized.getFloat(0, 1), 1.0e-6f);
            assertEquals(1.0, normalized.getRow(0).norm2Number().doubleValue(), 1.0e-6);
            assertEquals(1.0, normalized.getRow(1).norm2Number().doubleValue(), 1.0e-6);
        } finally {
            close(normalized);
            close(values);
        }
    }

    private static void close(INDArray array) {
        if (array != null && !array.wasClosed()) {
            array.close();
        }
    }
}
