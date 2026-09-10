/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.SameDiffEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilingualE5QualificationTest {

    @Test
    void managedEncoderProducesNormalizedCrossLanguageEmbeddings() throws Exception {
        try (SameDiffEncoder<float[]> encoder =
                     AnseriniEncoderFactory.createEncoder("multilingual-e5-small")) {
            List<float[]> vectors = encoder.encodeBatch(List.of(
                    "a telescope observes a distant galaxy",
                    "un telescopio observa una galaxia distante",
                    "a violin performs a classical melody",
                    "a telescope observes a distant galaxy"));

            assertEquals(4, vectors.size());
            for (float[] vector : vectors) {
                assertEquals(384, vector.length);
                assertTrue(allFinite(vector));
                assertEquals(1.0, norm(vector), 1.0e-4);
            }
            assertTrue(cosine(vectors.get(0), vectors.get(1))
                            > cosine(vectors.get(0), vectors.get(2)),
                    "cross-language semantic match must outrank unrelated text");
            assertEquals(1.0, cosine(vectors.get(0), vectors.get(3)), 1.0e-5);
        }
    }

    private static boolean allFinite(float[] vector) {
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static double norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) sum += (double) value * value;
        return Math.sqrt(sum);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }
}
