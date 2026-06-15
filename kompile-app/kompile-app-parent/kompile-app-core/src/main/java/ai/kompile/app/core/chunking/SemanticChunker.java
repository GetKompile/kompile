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

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Semantic chunker that uses embedding-based topic boundary detection.
 * <p>
 * Algorithm:
 * 1. Split text into sentences
 * 2. Embed each sentence
 * 3. Compute cosine distances between consecutive sentence embeddings
 * 4. Find breakpoints where distance exceeds the Nth percentile threshold
 * 5. Group sentences between breakpoints into chunks
 * 6. Enforce min/max chunk size constraints
 */
public class SemanticChunker implements TextChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);

    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "(?<=[.!?])\\s+(?=[A-Z])|(?<=\\n)\\s*(?=\\S)");

    private final EmbeddingModel embeddingModel;

    public SemanticChunker(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);

        Map<String, Object> mergedOptions = prepareOptions(options);
        int breakpointPercentile = getIntOption(mergedOptions, "breakpointPercentile", 90);
        int minChunkSize = getIntOption(mergedOptions, "minChunkSize", 100);
        int maxChunkSize = getIntOption(mergedOptions, "maxChunkSize", 2000);

        String text = document.getText();

        // Split into sentences
        List<String> sentences = splitIntoSentences(text);
        if (sentences.size() <= 1) {
            return createSingleChunk(document);
        }

        // Embed all sentences
        List<float[]> embeddings = embedSentences(sentences);
        if (embeddings.size() != sentences.size()) {
            // Fallback if embedding fails
            return createSingleChunk(document);
        }

        // Compute cosine distances between consecutive sentences
        double[] distances = computeConsecutiveDistances(embeddings);

        // Find breakpoints at the Nth percentile threshold
        List<Integer> breakpoints = findBreakpoints(distances, breakpointPercentile);

        // Group sentences into chunks
        List<String> chunks = groupSentencesIntoChunks(sentences, breakpoints, minChunkSize, maxChunkSize);

        // Create chunk documents
        return createChunkedDocuments(document, chunks);
    }

    List<String> splitIntoSentences(String text) {
        String[] parts = SENTENCE_PATTERN.split(text);
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    private List<float[]> embedSentences(List<String> sentences) {
        List<float[]> embeddings = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            try {
                INDArray embedding = embeddingModel.embed(sentence);
                if (embedding != null && !embedding.isEmpty()) {
                    embeddings.add(embedding.toFloatVector());
                } else {
                    embeddings.add(null);
                }
            } catch (Exception e) {
                log.warn("Failed to generate embedding for chunk {}: {}", i, e.getMessage());
                embeddings.add(null);
            }
        }

        // If too many nulls, return empty to trigger fallback
        long nullCount = embeddings.stream().filter(Objects::isNull).count();
        if (nullCount > embeddings.size() / 2) {
            return Collections.emptyList();
        }

        // Fill nulls with zero vectors
        int dim = embeddings.stream().filter(Objects::nonNull).findFirst()
                .map(e -> e.length).orElse(0);
        if (dim == 0) return Collections.emptyList();

        float[] zero = new float[dim];
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i) == null) {
                embeddings.set(i, zero);
            }
        }
        return embeddings;
    }

    double[] computeConsecutiveDistances(List<float[]> embeddings) {
        double[] distances = new double[embeddings.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = cosineDistance(embeddings.get(i), embeddings.get(i + 1));
        }
        return distances;
    }

    List<Integer> findBreakpoints(double[] distances, int percentile) {
        if (distances.length == 0) return Collections.emptyList();

        // Compute the threshold at the given percentile
        double[] sorted = Arrays.copyOf(distances, distances.length);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        double threshold = sorted[idx];

        List<Integer> breakpoints = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] >= threshold) {
                breakpoints.add(i + 1); // Break AFTER sentence i
            }
        }
        return breakpoints;
    }

    List<String> groupSentencesIntoChunks(List<String> sentences, List<Integer> breakpoints,
                                                   int minChunkSize, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        Set<Integer> breakSet = new HashSet<>(breakpoints);

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);

            if (current.length() + sentence.length() + 1 > maxChunkSize && current.length() >= minChunkSize) {
                // Forced break due to max size
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (!current.isEmpty()) {
                current.append(" ");
            }
            current.append(sentence);

            if (breakSet.contains(i + 1) && current.length() >= minChunkSize) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
        }

        // Handle remaining content
        if (!current.isEmpty()) {
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                if (remaining.length() < minChunkSize && !chunks.isEmpty()) {
                    // Merge with last chunk if too small
                    String last = chunks.remove(chunks.size() - 1);
                    chunks.add(last + " " + remaining);
                } else {
                    chunks.add(remaining);
                }
            }
        }

        return chunks;
    }

    private double cosineDistance(float[] a, float[] b) {
        return 1.0 - cosineSimilarity(a, b);
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        if (denom < 1e-9) return 0.0;
        return dotProduct / denom;
    }

    private List<RetrievedDoc> createSingleChunk(RetrievedDoc document) {
        return createChunkedDocuments(document, List.of(document.getText()));
    }

    private List<RetrievedDoc> createChunkedDocuments(RetrievedDoc originalDoc, List<String> chunks) {
        List<RetrievedDoc> result = new ArrayList<>(chunks.size());
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            Map<String, Object> chunkMetadata = new HashMap<>(originalDoc.getMetadata());
            chunkMetadata.put("chunk.strategy", getName());
            chunkMetadata.put("chunk.index", i);
            chunkMetadata.put("chunk.total", total);
            chunkMetadata.put("chunk.originalId", originalDoc.getId());
            chunkMetadata.put("chunk.size", chunks.get(i).length());

            result.add(RetrievedDoc.builder()
                    .id(originalDoc.getId() + "-chunk-" + i)
                    .text(chunks.get(i).trim())
                    .metadata(chunkMetadata)
                    .score(originalDoc.getScore())
                    .build());
        }
        return result;
    }

    @Override
    public String getName() {
        return "semantic";
    }

    @Override
    public List<String> getSupportedLanguages() {
        return List.of("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>(TextChunker.super.getDefaultOptions());
        defaults.put("breakpointPercentile", 90);
        defaults.put("minChunkSize", 100);
        defaults.put("maxChunkSize", 2000);
        return defaults;
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}
