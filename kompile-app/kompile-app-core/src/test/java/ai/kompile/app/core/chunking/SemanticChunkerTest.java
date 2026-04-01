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

package ai.kompile.app.core.chunking;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SemanticChunker's pure-logic methods.
 * ND4J-dependent integration tests (full chunk() calls) are in kompile-app-main tests.
 */
class SemanticChunkerTest {

    // Use null embedding model for logic-only tests
    private final SemanticChunker chunker = new SemanticChunker(null);

    @Test
    void testCosineDistanceOrthogonalVectors() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};

        double similarity = SemanticChunker.cosineSimilarity(a, b);
        assertEquals(0.0, similarity, 0.01, "Orthogonal vectors should have 0 similarity");
    }

    @Test
    void testCosineDistanceSameVector() {
        float[] a = {1.0f, 0.0f, 0.0f};

        double selfSimilarity = SemanticChunker.cosineSimilarity(a, a);
        assertEquals(1.0, selfSimilarity, 0.01, "Vector should have 1.0 similarity with itself");
    }

    @Test
    void testCosineDistanceSimilarVectors() {
        float[] a = {0.9f, 0.1f, 0.0f};
        float[] b = {0.8f, 0.2f, 0.0f};

        double similarity = SemanticChunker.cosineSimilarity(a, b);
        assertTrue(similarity > 0.9, "Similar vectors should have high similarity");
    }

    @Test
    void testCosineDistanceZeroVector() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};

        double similarity = SemanticChunker.cosineSimilarity(a, b);
        assertEquals(0.0, similarity, 0.01, "Zero vector should have 0 similarity");
    }

    @Test
    void testFindBreakpointsHighDistances() {
        double[] distances = {0.1, 0.2, 0.9, 0.1, 0.8, 0.15};
        List<Integer> breakpoints = chunker.findBreakpoints(distances, 80);

        // The top 20% distances are 0.9 and 0.8
        assertTrue(breakpoints.contains(3), "Should detect breakpoint at index 2->3 (distance 0.9)");
        assertTrue(breakpoints.contains(5), "Should detect breakpoint at index 4->5 (distance 0.8)");
    }

    @Test
    void testFindBreakpointsAllEqual() {
        double[] distances = {0.5, 0.5, 0.5, 0.5};
        List<Integer> breakpoints = chunker.findBreakpoints(distances, 90);

        // All distances equal — top 10% means the threshold equals 0.5
        // All distances >= 0.5, so all would be breakpoints at 90th percentile
        assertNotNull(breakpoints);
    }

    @Test
    void testFindBreakpointsEmpty() {
        double[] distances = {};
        List<Integer> breakpoints = chunker.findBreakpoints(distances, 90);
        assertTrue(breakpoints.isEmpty());
    }

    @Test
    void testComputeConsecutiveDistances() {
        List<float[]> embeddings = List.of(
                new float[]{1.0f, 0.0f},
                new float[]{0.9f, 0.1f},
                new float[]{0.0f, 1.0f}
        );

        double[] distances = chunker.computeConsecutiveDistances(embeddings);
        assertEquals(2, distances.length);
        assertTrue(distances[0] < distances[1],
                "Distance between similar vectors should be less than between dissimilar ones");
    }

    @Test
    void testGroupSentencesIntoChunksWithBreakpoints() {
        List<String> sentences = List.of(
                "Sentence one about topic A.",
                "Sentence two about topic A.",
                "Sentence three about topic B.",
                "Sentence four about topic B."
        );
        List<Integer> breakpoints = List.of(2); // Break between 2nd and 3rd sentence

        List<String> chunks = chunker.groupSentencesIntoChunks(sentences, breakpoints, 10, 2000);

        assertEquals(2, chunks.size(), "Should produce 2 chunks at the breakpoint");
        assertTrue(chunks.get(0).contains("topic A"));
        assertTrue(chunks.get(1).contains("topic B"));
    }

    @Test
    void testGroupSentencesMinChunkSize() {
        List<String> sentences = List.of("A.", "B.", "C.", "D.", "E.");
        List<Integer> breakpoints = List.of(1, 2, 3, 4); // Break after every sentence

        List<String> chunks = chunker.groupSentencesIntoChunks(sentences, breakpoints, 500, 2000);

        // All sentences too short to meet min, should all be one chunk
        assertEquals(1, chunks.size(), "Short sentences should be merged when below min size");
    }

    @Test
    void testGroupSentencesMaxChunkSize() {
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sentences.add("This is a reasonably long sentence number " + i + " that takes up some space.");
        }
        List<Integer> breakpoints = List.of(); // No semantic breakpoints

        List<String> chunks = chunker.groupSentencesIntoChunks(sentences, breakpoints, 10, 100);

        assertTrue(chunks.size() > 1, "Should split into multiple chunks due to max size");
    }

    @Test
    void testGroupSentencesTrailingContentMerge() {
        List<String> sentences = List.of(
                "This is a very long first sentence that should form its own chunk easily.",
                "Short end."
        );
        List<Integer> breakpoints = List.of(1);

        List<String> chunks = chunker.groupSentencesIntoChunks(sentences, breakpoints, 50, 2000);

        // "Short end." is < 50 chars (minChunkSize), should merge with previous
        assertEquals(1, chunks.size(), "Short trailing content should merge with last chunk");
    }

    @Test
    void testSplitIntoSentences() {
        String text = "First sentence here. Second sentence there. Third one follows.";
        List<String> sentences = chunker.splitIntoSentences(text);
        assertFalse(sentences.isEmpty());
    }

    @Test
    void testSplitIntoSentencesWithNewlines() {
        String text = "First paragraph.\n\nSecond paragraph.\nThird line.";
        List<String> sentences = chunker.splitIntoSentences(text);
        assertFalse(sentences.isEmpty());
    }

    @Test
    void testGetName() {
        assertEquals("semantic", chunker.getName());
    }

    @Test
    void testGetSupportedLanguages() {
        assertEquals(List.of("*"), chunker.getSupportedLanguages());
    }

    @Test
    void testGetDefaultOptions() {
        Map<String, Object> defaults = chunker.getDefaultOptions();
        assertEquals(90, defaults.get("breakpointPercentile"));
        assertEquals(100, defaults.get("minChunkSize"));
        assertEquals(2000, defaults.get("maxChunkSize"));
    }
}
