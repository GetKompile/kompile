/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.embedding.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddingInitializer.
 *
 * <p>Tests use real Nd4j arrays since EmbeddingInitializer's output depends on
 * numerical properties of the initialization schemes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingInitializerTest {

    @Test
    void xavierUniform_producesCorrectShape() {
        INDArray result = EmbeddingInitializer.xavierUniform(10, 50);
        assertNotNull(result);
        assertArrayEquals(new long[]{10, 50}, result.shape());
    }

    @Test
    void xavierUniform_valuesInExpectedRange() {
        int embDim = 100;
        double limit = Math.sqrt(6.0 / embDim);
        INDArray result = EmbeddingInitializer.xavierUniform(20, embDim);

        float[] data = result.data().asFloat();
        for (float v : data) {
            assertTrue(v >= -limit && v <= limit,
                    "Xavier value " + v + " out of range [" + -limit + ", " + limit + "]");
        }
    }

    @Test
    void uniformTransE_producesCorrectShape() {
        INDArray result = EmbeddingInitializer.uniformTransE(5, 100);
        assertNotNull(result);
        assertArrayEquals(new long[]{5, 100}, result.shape());
    }

    @Test
    void uniformTransE_valuesInExpectedRange() {
        int embDim = 100;
        double limit = 1.0 / embDim;
        INDArray result = EmbeddingInitializer.uniformTransE(50, embDim);

        float[] data = result.data().asFloat();
        for (float v : data) {
            assertTrue(v >= -limit && v <= limit,
                    "TransE uniform value " + v + " out of range [" + -limit + ", " + limit + "]");
        }
    }

    @Test
    void uniform_withCustomBound_valuesInBoundRange() {
        double bound = 0.5;
        INDArray result = EmbeddingInitializer.uniform(30, 64, bound);

        assertNotNull(result);
        assertArrayEquals(new long[]{30, 64}, result.shape());

        float[] data = result.data().asFloat();
        for (float v : data) {
            assertTrue(v >= -bound && v <= bound,
                    "Uniform value " + v + " out of range [" + -bound + ", " + bound + "]");
        }
    }

    @Test
    void uniformRotatE_forEntity_valuesInMinusOneToOne() {
        INDArray result = EmbeddingInitializer.uniformRotatE(10, 50, false);
        assertNotNull(result);
        assertArrayEquals(new long[]{10, 50}, result.shape());

        float[] data = result.data().asFloat();
        for (float v : data) {
            assertTrue(v >= -1.0f && v <= 1.0f,
                    "RotatE entity value " + v + " out of range [-1, 1]");
        }
    }

    @Test
    void uniformRotatE_forRelation_valuesInZeroToTwoPi() {
        INDArray result = EmbeddingInitializer.uniformRotatE(10, 50, true);
        assertNotNull(result);

        float[] data = result.data().asFloat();
        for (float v : data) {
            assertTrue(v >= 0.0f && v <= 2 * Math.PI + 0.001,
                    "RotatE relation phase " + v + " out of range [0, 2π]");
        }
    }

    @Test
    void normalizeRows_producesUnitNormRows() {
        INDArray embeddings = Nd4j.rand(5, 10).muli(2).subi(1);
        INDArray normalized = EmbeddingInitializer.normalizeRows(embeddings);

        assertNotNull(normalized);
        assertArrayEquals(new long[]{5, 10}, normalized.shape());

        // Each row should have norm close to 1
        for (int i = 0; i < 5; i++) {
            double norm = normalized.getRow(i).norm2Number().doubleValue();
            assertEquals(1.0, norm, 1e-5, "Row " + i + " norm should be ~1.0, got " + norm);
        }
    }

    @Test
    void normalizeRows_doesNotModifyOriginal() {
        INDArray embeddings = Nd4j.rand(3, 4).muli(10);
        float[] originalData = embeddings.data().asFloat().clone();

        EmbeddingInitializer.normalizeRows(embeddings);

        // normalizeRows returns new array, original should be unchanged
        assertArrayEquals(originalData, embeddings.data().asFloat(), 1e-6f);
    }

    @Test
    void normalizeRowsInPlace_modifiesOriginalToUnitNorm() {
        INDArray embeddings = Nd4j.rand(5, 10).muli(5);
        EmbeddingInitializer.normalizeRowsInPlace(embeddings);

        for (int i = 0; i < 5; i++) {
            double norm = embeddings.getRow(i).norm2Number().doubleValue();
            assertEquals(1.0, norm, 1e-5, "Row " + i + " norm should be ~1.0 after in-place normalization");
        }
    }

    @Test
    void normalizeRowsInPlace_handlesZeroVector() {
        // Zero vector should not cause division-by-zero crash
        INDArray embeddings = Nd4j.zeros(3, 5);
        assertDoesNotThrow(() -> EmbeddingInitializer.normalizeRowsInPlace(embeddings));
    }

    @Test
    void xavierUniform_differentDims_scalesDifferently() {
        // Xavier limit = sqrt(6/dim), so larger dim => smaller values
        INDArray small = EmbeddingInitializer.xavierUniform(100, 10);
        INDArray large = EmbeddingInitializer.xavierUniform(100, 1000);

        double smallMax = Math.abs(small.maxNumber().doubleValue());
        double largeMax = Math.abs(large.maxNumber().doubleValue());

        // small dim should have larger values
        assertTrue(smallMax >= largeMax * 0.5,
                "Smaller dim should produce larger max values on average");
    }
}
