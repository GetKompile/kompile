/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.encoder.samediff;

import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.indexing.BooleanIndexing;

import static org.junit.Assert.*;

/**
 * Tests for {@link GenericDenseSameDiffEncoder#sanitizeNonFinite(INDArray)} and the
 * normalization contract it enables.
 *
 * <p>The contract under test: after sanitizeNonFinite() + L2 normalisation, every returned
 * float[] must pass the same acceptance predicate as {@code isIndexableEmbedding()} in
 * VectorIndexingHelper — namely all elements finite AND vector magnitude > 1e-18.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Row with some NaN → finite unit vector preserving the finite-component direction.
 *   <li>Row with +Inf, -Inf, and finite values → finite unit vector.
 *   <li>Row mixing NaN, +Inf, -Inf, and finite values → finite unit vector.
 *   <li>All-finite row → unchanged direction, still a unit vector.
 *   <li>Fully-NaN row → all-zero after sanitize (magnitude ≤ 1e-18, correctly non-indexable).
 * </ol>
 */
public class SanitizeNonFiniteTest {

    // -----------------------------------------------------------------------
    // Helpers that mirror VectorIndexingHelper.isIndexableEmbedding
    // -----------------------------------------------------------------------

    private static boolean isIndexableEmbedding(float[] v) {
        if (v == null || v.length == 0) return false;
        double sumSq = 0.0;
        for (float f : v) {
            if (!Float.isFinite(f)) return false;
            sumSq += (double) f * f;
        }
        return Math.sqrt(sumSq) > 1e-18;
    }

    private static boolean allFinite(float[] v) {
        for (float f : v) {
            if (!Float.isFinite(f)) return false;
        }
        return true;
    }

    private static double magnitude(float[] v) {
        double sumSq = 0.0;
        for (float f : v) sumSq += (double) f * f;
        return Math.sqrt(sumSq);
    }

    // -----------------------------------------------------------------------
    // Normalise a 1-row [1, dim] INDArray using the same logic as the single-doc
    // path: sanitize → sum-of-squares → epsilon-clamp → div.
    // -----------------------------------------------------------------------

    private float[] sanitizeThenNormalize(float[] raw) {
        INDArray arr = Nd4j.create(raw).reshape(1, raw.length);
        GenericDenseSameDiffEncoder.sanitizeNonFinite(arr);

        // Replicates the single-doc normalisation path exactly
        INDArray squared = arr.mul(arr);
        INDArray sumOfSquares = squared.sum(true, 1);
        double sumVal = sumOfSquares.getDouble(0);
        if (Double.isNaN(sumVal) || sumVal < 0) sumVal = 0.0;
        double clampedVal = Math.max(sumVal, 1e-12);
        double normVal = Math.sqrt(clampedVal);
        INDArray normScalar = Nd4j.scalar(arr.dataType(), normVal);
        INDArray normalized = arr.div(normScalar);
        float[] result = normalized.toFloatVector();
        squared.close();
        sumOfSquares.close();
        normScalar.close();
        normalized.close();
        arr.close();
        return result;
    }

    // -----------------------------------------------------------------------
    // Normalise a multi-row [rows, dim] INDArray using the same logic as the
    // batch path: sanitize → norm2(1) → Transforms.max(epsilon) → div.
    // Returns rows as float[][].
    // -----------------------------------------------------------------------

    private float[][] sanitizeBatchThenNormalize(float[][] rows) {
        int batchSize = rows.length;
        int dim = rows[0].length;
        float[] flat = new float[batchSize * dim];
        for (int i = 0; i < batchSize; i++) {
            System.arraycopy(rows[i], 0, flat, i * dim, dim);
        }
        INDArray arr = Nd4j.create(flat, new int[]{batchSize, dim}, 'c');
        GenericDenseSameDiffEncoder.sanitizeNonFinite(arr);

        INDArray norms = arr.norm2(1);  // [batch]
        INDArray epsilon = Nd4j.scalar(1e-12f);
        norms = Transforms.max(norms, epsilon, false);
        epsilon.close();
        norms = norms.reshape(batchSize, 1);
        INDArray normalized = arr.div(norms);

        // Use dup('c') + bulk-read (the general path fix we're also testing)
        INDArray contig = normalized.dup('c');
        float[] flatOut = contig.data().getFloatsAt(contig.offset(), batchSize * dim);
        float[][] result = new float[batchSize][dim];
        for (int i = 0; i < batchSize; i++) {
            System.arraycopy(flatOut, i * dim, result[i], 0, dim);
        }
        norms.close();
        normalized.close();
        contig.close();
        arr.close();
        return result;
    }

    // -----------------------------------------------------------------------
    // Test: sanitizeNonFinite in isolation
    // -----------------------------------------------------------------------

    @Test
    public void testSanitizeNonFinite_replacesNaNAndInf() {
        float[] raw = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.0f, -2.0f};
        INDArray arr = Nd4j.create(raw);
        GenericDenseSameDiffEncoder.sanitizeNonFinite(arr);
        float[] out = arr.toFloatVector();
        arr.close();

        assertEquals("NaN should be replaced with 0", 0.0f, out[0], 0.0f);
        assertEquals("+Inf should be replaced with 0", 0.0f, out[1], 0.0f);
        assertEquals("-Inf should be replaced with 0", 0.0f, out[2], 0.0f);
        assertEquals("finite 1.0 should be unchanged", 1.0f, out[3], 1e-6f);
        assertEquals("finite -2.0 should be unchanged", -2.0f, out[4], 1e-6f);
    }

    // -----------------------------------------------------------------------
    // Test: single-doc path — partially-NaN row produces indexable unit vector
    // -----------------------------------------------------------------------

    @Test
    public void testSingleDocPath_partialNaN_producesIndexableVector() {
        // Row with some NaN and some finite values (simulates FP16 partial corruption)
        float[] raw = {Float.NaN, Float.NaN, 1.0f, 2.0f, -1.0f};
        float[] result = sanitizeThenNormalize(raw);

        assertTrue("All output elements must be finite", allFinite(result));
        assertTrue("Magnitude must be > 1e-18", magnitude(result) > 1e-18);
        assertTrue("Must pass isIndexableEmbedding", isIndexableEmbedding(result));
        // Direction: only indices 2,3,4 had finite values; result[0] and result[1] must be 0
        assertEquals("NaN-originated slot must be 0 after sanitize", 0.0f, result[0], 1e-6f);
        assertEquals("NaN-originated slot must be 0 after sanitize", 0.0f, result[1], 1e-6f);
    }

    @Test
    public void testSingleDocPath_infValues_producesIndexableVector() {
        float[] raw = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 3.0f, 4.0f};
        float[] result = sanitizeThenNormalize(raw);

        assertTrue("All output elements must be finite", allFinite(result));
        assertTrue("Must pass isIndexableEmbedding", isIndexableEmbedding(result));
        assertEquals("Inf-originated slot must be 0 after sanitize", 0.0f, result[0], 1e-6f);
        assertEquals("Inf-originated slot must be 0 after sanitize", 0.0f, result[1], 1e-6f);
    }

    @Test
    public void testSingleDocPath_mixedNaNInfFinite_producesIndexableVector() {
        float[] raw = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 5.0f, -3.0f, 0.1f};
        float[] result = sanitizeThenNormalize(raw);

        assertTrue("All output elements must be finite", allFinite(result));
        assertTrue("Must pass isIndexableEmbedding", isIndexableEmbedding(result));
        // Check approximate unit norm
        assertEquals("Result should be a unit vector", 1.0, magnitude(result), 1e-5);
    }

    @Test
    public void testSingleDocPath_allFinite_isUnchangedInDirection() {
        float[] raw = {3.0f, 4.0f};  // known: mag=5, unit=[0.6, 0.8]
        float[] result = sanitizeThenNormalize(raw);

        assertTrue("All finite input stays finite", allFinite(result));
        assertTrue("Must pass isIndexableEmbedding", isIndexableEmbedding(result));
        assertEquals("Unit vector x-component", 0.6f, result[0], 1e-5f);
        assertEquals("Unit vector y-component", 0.8f, result[1], 1e-5f);
    }

    /**
     * Fully-NaN row: after sanitize the vector is all-zero → magnitude ≈ epsilon-floor value.
     * isIndexableEmbedding CORRECTLY returns false — this is the expected behaviour.
     * The test documents and asserts this contract (not-indexable is acceptable here).
     */
    @Test
    public void testSingleDocPath_fullyNaN_producesNonIndexableZeroVector() {
        float[] raw = {Float.NaN, Float.NaN, Float.NaN};
        float[] result = sanitizeThenNormalize(raw);

        // All elements are finite (no NaN leaked through)
        assertTrue("All output elements must be finite even for all-NaN input", allFinite(result));
        // The vector should be near-zero (epsilon-normalized); isIndexableEmbedding returns false
        // because magnitude ≤ 1e-18 (epsilon clamp produces norm ≈ 1e-6 / sqrt(3) ≈ 5.77e-7).
        assertFalse("All-NaN input should produce a non-indexable vector (correct rejection)",
                isIndexableEmbedding(result));
    }

    // -----------------------------------------------------------------------
    // Test: batch path — partial NaN in a row produces indexable unit vector
    // -----------------------------------------------------------------------

    @Test
    public void testBatchPath_partialNaNRow_producesIndexableVector() {
        float[][] input = {
            {Float.NaN, Float.NaN, 1.0f, 2.0f, -1.0f},     // partial NaN — should be fixed
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f},                  // all finite
            {Float.POSITIVE_INFINITY, 3.0f, 4.0f, 0.0f, 0.0f} // Inf mixed with finite
        };
        float[][] results = sanitizeBatchThenNormalize(input);

        // Row 0: partial NaN — must become indexable
        assertTrue("Row 0: all finite", allFinite(results[0]));
        assertTrue("Row 0: isIndexable", isIndexableEmbedding(results[0]));
        assertEquals("Row 0 slot 0: NaN→0", 0.0f, results[0][0], 1e-6f);
        assertEquals("Row 0 slot 1: NaN→0", 0.0f, results[0][1], 1e-6f);

        // Row 1: all finite, check unit norm
        assertTrue("Row 1: all finite", allFinite(results[1]));
        assertEquals("Row 1: unit norm", 1.0, magnitude(results[1]), 1e-5);

        // Row 2: Inf in slot 0 → replaced with 0; [3,4] normalized to [0.6, 0.8]
        assertTrue("Row 2: all finite", allFinite(results[2]));
        assertTrue("Row 2: isIndexable", isIndexableEmbedding(results[2]));
        assertEquals("Row 2 slot 0: Inf→0", 0.0f, results[2][0], 1e-6f);
        assertEquals("Row 2 unit norm", 1.0, magnitude(results[2]), 1e-5);
    }

    @Test
    public void testBatchPath_fullyNaNRow_producesNonIndexableVector() {
        float[][] input = {
            {1.0f, 0.0f, 0.0f},           // good row
            {Float.NaN, Float.NaN, Float.NaN} // fully NaN — must be finite but non-indexable
        };
        float[][] results = sanitizeBatchThenNormalize(input);

        assertTrue("Row 0: indexable", isIndexableEmbedding(results[0]));
        assertTrue("Row 1: all elements finite", allFinite(results[1]));
        assertFalse("Row 1: all-NaN row is non-indexable (correct rejection)",
                isIndexableEmbedding(results[1]));
    }
}
