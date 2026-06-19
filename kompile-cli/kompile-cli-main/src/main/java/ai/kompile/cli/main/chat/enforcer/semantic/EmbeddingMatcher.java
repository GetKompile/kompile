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

package ai.kompile.cli.main.chat.enforcer.semantic;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Embedding-based semantic matcher. Uses cosine similarity between the banned
 * concept embedding and sliding windows of text to detect reworded equivalents.
 *
 * <p>Requires a running kompile embedding endpoint (kompile-app with an embedding
 * model loaded). Falls back gracefully to unavailable if the endpoint is unreachable.</p>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code embeddingUrl} — URL of the embedding endpoint (e.g., http://localhost:8080/api/embed)</li>
 *   <li>{@code similarityThreshold} — minimum cosine similarity to consider a match (default: 0.78)</li>
 *   <li>{@code windowSize} — number of words per sliding window (default: 5)</li>
 *   <li>{@code windowStride} — stride between windows (default: 2)</li>
 * </ul></p>
 */
public class EmbeddingMatcher implements SemanticMatcher {

    private final String embeddingUrl;
    private final double similarityThreshold;
    private final int windowSize;
    private final int windowStride;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean available;

    // Cache concept embeddings to avoid redundant API calls
    private final Map<String, double[]> embeddingCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
            return size() > 256;
        }
    };

    public EmbeddingMatcher(String embeddingUrl) {
        this(embeddingUrl, 0.78, 5, 2);
    }

    public EmbeddingMatcher(String embeddingUrl, double similarityThreshold,
                            int windowSize, int windowStride) {
        this.embeddingUrl = embeddingUrl;
        this.similarityThreshold = similarityThreshold;
        this.windowSize = windowSize;
        this.windowStride = windowStride;
        this.objectMapper = JsonUtils.standardMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.available = checkAvailability();
    }

    @Override
    public List<String> expand(String phrase) {
        // Embedding matcher doesn't pre-expand — it works at match time via similarity
        return List.of(phrase != null ? phrase.toLowerCase() : "");
    }

    @Override
    public SemanticMatch matches(String text, String concept) {
        if (!available || text == null || text.isBlank() || concept == null) return null;

        double[] conceptEmbedding = getEmbedding(concept);
        if (conceptEmbedding == null) return null;

        // Sliding window over text
        String[] words = text.toLowerCase().split("\\s+");
        if (words.length < 2) {
            // Short text: embed the whole thing
            double[] textEmbedding = getEmbedding(text);
            if (textEmbedding != null) {
                double sim = cosineSimilarity(conceptEmbedding, textEmbedding);
                if (sim >= similarityThreshold) {
                    return new SemanticMatch(concept, text.trim(), sim, matcherType());
                }
            }
            return null;
        }

        double bestSimilarity = 0;
        String bestWindow = null;

        for (int i = 0; i <= words.length - windowSize; i += windowStride) {
            int end = Math.min(i + windowSize, words.length);
            String window = String.join(" ", Arrays.copyOfRange(words, i, end));

            double[] windowEmbedding = getEmbedding(window);
            if (windowEmbedding == null) continue;

            double sim = cosineSimilarity(conceptEmbedding, windowEmbedding);
            if (sim > bestSimilarity) {
                bestSimilarity = sim;
                bestWindow = window;
            }
        }

        if (bestSimilarity >= similarityThreshold && bestWindow != null) {
            return new SemanticMatch(concept, bestWindow, bestSimilarity, matcherType());
        }

        return null;
    }

    @Override
    public SemanticMatch matchesWithExpansion(String text, String concept, List<String> expandedVariants) {
        // Embedding matcher ignores pre-expanded variants (it does its own similarity matching)
        return matches(text, concept);
    }

    @Override
    public String matcherType() {
        return "embedding";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    // ── Embedding API ────────────────────────────────────────────────────────

    private double[] getEmbedding(String text) {
        if (text == null || text.isBlank()) return null;

        String key = text.toLowerCase().trim();
        double[] cached = embeddingCache.get(key);
        if (cached != null) return cached;

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("text", key));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.path("embedding");
            if (!embeddingNode.isArray()) {
                // Try alternative response format
                embeddingNode = root.path("data").path(0).path("embedding");
            }
            if (!embeddingNode.isArray()) return null;

            double[] embedding = new double[embeddingNode.size()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = embeddingNode.get(i).asDouble();
            }

            embeddingCache.put(key, embedding);
            return embedding;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean checkAvailability() {
        if (embeddingUrl == null || embeddingUrl.isBlank()) return false;
        try {
            // Quick health check — try to embed a single word
            double[] test = getEmbedding("test");
            return test != null && test.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Math ─────────────────────────────────────────────────────────────────

    private static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
