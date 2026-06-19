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

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects and removes exact-duplicate and near-duplicate documents within a
 * crawl batch. Prevents redundant embeddings and inflated search results.
 *
 * <p>Uses content hashing for exact dedup and SimHash for near-duplicate
 * detection based on configurable similarity threshold.</p>
 *
 * <p>Order: 400 (deduplication phase — runs last, after all content
 * transformations are complete).</p>
 */
@Component
public class DeduplicationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationPreprocessor.class);

    @Override
    public String id() {
        return "deduplication";
    }

    @Override
    public String displayName() {
        return "Content Deduplication";
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getDeduplication() == null || !config.getDeduplication().isEnabled()) {
            return false;
        }
        return document.getText() != null && !document.getText().isBlank();
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        PreprocessingConfig.DeduplicationConfig dedupConfig = config.getDeduplication();
        double threshold = dedupConfig.getSimilarityThreshold();
        String strategy = dedupConfig.getStrategy();
        boolean trackRelations = dedupConfig.isTrackDuplicateRelations();

        // Content hash → first document index
        Map<String, Integer> hashIndex = new LinkedHashMap<>();
        // SimHash → first document index (for near-dedup)
        Map<Long, Integer> simHashIndex = new LinkedHashMap<>();

        List<Document> unique = new ArrayList<>();
        int duplicates = 0;

        for (int i = 0; i < documents.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;

            Document doc = documents.get(i);
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                unique.add(doc);
                continue;
            }

            String contentHash = sha256(text);

            // Exact duplicate check
            if (hashIndex.containsKey(contentHash)) {
                int origIdx = hashIndex.get(contentHash);
                handleDuplicate(unique, origIdx, doc, strategy, trackRelations);
                duplicates++;
                continue;
            }

            // Near-duplicate check via SimHash
            if (threshold < 1.0) {
                long simHash = computeSimHash(text);
                boolean isNearDup = false;

                for (Map.Entry<Long, Integer> entry : simHashIndex.entrySet()) {
                    double similarity = simHashSimilarity(simHash, entry.getKey());
                    if (similarity >= threshold) {
                        int origIdx = entry.getValue();
                        handleDuplicate(unique, origIdx, doc, strategy, trackRelations);
                        duplicates++;
                        isNearDup = true;
                        break;
                    }
                }

                if (isNearDup) continue;
                simHashIndex.put(simHash, unique.size());
            }

            hashIndex.put(contentHash, unique.size());
            unique.add(doc);
        }

        log.info("Deduplication: {} duplicates removed from {} documents ({} unique)",
                duplicates, documents.size(), unique.size());
        return unique;
    }

    private void handleDuplicate(List<Document> unique, int origIdx, Document duplicate,
                                  String strategy, boolean trackRelations) {
        if (origIdx < 0 || origIdx >= unique.size()) return;

        Document original = unique.get(origIdx);

        if (trackRelations) {
            // Record the duplicate source for potential graph edges
            @SuppressWarnings("unchecked")
            List<String> dupSources = (List<String>) original.getMetadata()
                    .computeIfAbsent("duplicate_sources", k -> new ArrayList<String>());
            String dupSource = (String) duplicate.getMetadata().get("source_path");
            if (dupSource != null) dupSources.add(dupSource);
        }

        switch (strategy != null ? strategy.toUpperCase() : "KEEP_FIRST") {
            case "KEEP_LONGEST":
                if (duplicate.getText().length() > original.getText().length()) {
                    unique.set(origIdx, duplicate);
                }
                break;
            case "MERGE_METADATA":
                // Merge metadata from duplicate into original (don't overwrite existing keys)
                for (Map.Entry<String, Object> entry : duplicate.getMetadata().entrySet()) {
                    original.getMetadata().putIfAbsent(entry.getKey(), entry.getValue());
                }
                break;
            case "KEEP_FIRST":
            default:
                // Keep original, discard duplicate (already handled by not adding)
                break;
        }
    }

    static long computeSimHash(String text) {
        int[] v = new int[64];
        String[] tokens = text.toLowerCase().split("\\s+");

        for (String token : tokens) {
            long hash = fnv1a64(token);
            for (int i = 0; i < 64; i++) {
                if (((hash >> i) & 1) == 1) {
                    v[i]++;
                } else {
                    v[i]--;
                }
            }
        }

        long simhash = 0;
        for (int i = 0; i < 64; i++) {
            if (v[i] > 0) {
                simhash |= (1L << i);
            }
        }
        return simhash;
    }

    static double simHashSimilarity(long a, long b) {
        int hammingDistance = Long.bitCount(a ^ b);
        return 1.0 - (double) hammingDistance / 64.0;
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
