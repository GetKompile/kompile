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

package ai.kompile.crawl.graph.postprocessing;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Post-extraction normalizer that runs automatically after each graph build
 * completes, via the {@link GraphBuildCompletedEvent} application event.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li><b>Canonicalize entity names</b> — trims whitespace, collapses internal
 *       whitespace runs, strips surrounding punctuation, and normalizes Unicode
 *       to NFC so that "Alice Smith " and "Alice Smith" map to the same
 *       canonical title.</li>
 *   <li><b>Merge trivially-duplicate nodes</b> — two ENTITY nodes in the same
 *       fact sheet are considered trivial duplicates when their canonical titles
 *       are equal (case-insensitive). The shorter-titled node (or the later one
 *       if equal) is deleted and its metadata is merged into the survivor before
 *       deletion. This catches LLM-extraction jitter (e.g. "Acme Corp" vs
 *       "Acme Corp.") that {@link ai.kompile.knowledgegraph.resolution.GraphCompactionService}
 *       may not see because it uses embedding similarity, not exact-string
 *       matching.</li>
 *   <li><b>Drop degenerate nodes</b> — ENTITY nodes with a null/blank title, or
 *       a title of 1–2 characters that is not purely numeric (likely a stray
 *       bullet character or punctuation), are removed.</li>
 * </ol>
 *
 * <h3>Extension point</h3>
 * This bean listens on {@link GraphBuildCompletedEvent}.  No edits to
 * {@code UnifiedCrawlGraphServiceImpl} or {@code GraphHydrationOrchestrator}
 * are required — Spring delivers the event automatically once the crawl marks
 * a job COMPLETED or COMPLETED_PENDING_EMBEDDING.
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>Only ENTITY-level nodes are touched (SOURCE, DOCUMENT, TABLE, etc. are
 *       left untouched).</li>
 *   <li>If {@code factSheetId} is null in the event the normalizer silently
 *       skips (avoids cross-graph contamination).</li>
 *   <li>All exceptions are caught and logged; the normalizer never propagates
 *       an exception back through the event bus.</li>
 *   <li>Runs on the calling thread (same as the event publisher) — wrap in
 *       {@code @Async} if the crawl thread must not block.</li>
 * </ul>
 *
 * <h3>KbConfig tunables (for the lead to add)</h3>
 * <ul>
 *   <li>{@code postExtraction.normalizer.enabled} (boolean, default true) —
 *       master switch.</li>
 *   <li>{@code postExtraction.normalizer.dropDegenerateMinTitleLength} (int,
 *       default 3) — titles shorter than this (and not purely numeric) are
 *       treated as degenerate.</li>
 *   <li>{@code postExtraction.normalizer.maxNodesToProcess} (int, default
 *       50_000) — safety cap; fact sheets with more ENTITY nodes are skipped
 *       with a warning.</li>
 * </ul>
 */
@Component
public class GraphPostExtractionNormalizer {

    private static final Logger log = LoggerFactory.getLogger(GraphPostExtractionNormalizer.class);

    // ── Defaults (tunables) ───────────────────────────────────────────────────

    /**
     * Titles shorter than this (and not purely numeric) are treated as
     * degenerate and dropped. Tunable via KbConfig.
     */
    static final int DEFAULT_MIN_TITLE_LENGTH = 3;

    /**
     * Maximum ENTITY nodes to process in a single fact sheet. Prevents
     * very large graphs from blocking the crawl thread for too long.
     * Tunable via KbConfig.
     */
    static final int DEFAULT_MAX_NODES = 50_000;

    // ── Normalization patterns ────────────────────────────────────────────────

    /** Runs of internal whitespace (including non-breaking space). */
    private static final Pattern INTERNAL_WHITESPACE = Pattern.compile("[\\s\\u00a0\\u200b]+");

    /** Leading/trailing punctuation that the LLM occasionally emits. */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\[({\"'`\\-_:;,.!?]+|[\\])}\"'`\\-_:;,.!?]+$");

    /** Purely numeric title (e.g. "42", "3.14") — these are kept even if short. */
    private static final Pattern PURELY_NUMERIC = Pattern.compile("^[0-9.,+-]+$");

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public GraphPostExtractionNormalizer(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    /**
     * Triggered automatically when a crawl job publishes
     * {@link GraphBuildCompletedEvent}. Skips if {@code factSheetId} is null.
     */
    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GraphPostExtractionNormalizer: skipping event with null factSheetId (job={})",
                    event.getJobId());
            return;
        }

        try {
            normalize(factSheetId, DEFAULT_MIN_TITLE_LENGTH, DEFAULT_MAX_NODES);
        } catch (Exception e) {
            log.warn("GraphPostExtractionNormalizer: non-fatal error during post-extraction normalization "
                    + "(factSheetId={}, job={}): {}", factSheetId, event.getJobId(), e.getMessage(), e);
        }
    }

    // ── Core normalization ────────────────────────────────────────────────────

    /**
     * Run all three normalization passes over the ENTITY nodes of the given
     * fact sheet and return a summary result.
     *
     * <p>This method is also callable directly (e.g. from a REST endpoint or
     * scheduled maintenance job) without going through the event bus.</p>
     *
     * @param factSheetId       the fact sheet whose ENTITY nodes are processed
     * @param minTitleLength    minimum canonical title length; shorter nodes are dropped
     * @param maxNodesToProcess safety cap
     * @return summary of changes made
     */
    public NormalizationResult normalize(Long factSheetId, int minTitleLength, int maxNodesToProcess) {
        log.debug("GraphPostExtractionNormalizer: starting normalization for factSheetId={}", factSheetId);

        List<GraphNode> entityNodes = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);

        if (entityNodes == null || entityNodes.isEmpty()) {
            log.debug("GraphPostExtractionNormalizer: no ENTITY nodes in factSheetId={}", factSheetId);
            return NormalizationResult.empty(factSheetId);
        }

        if (entityNodes.size() > maxNodesToProcess) {
            log.warn("GraphPostExtractionNormalizer: factSheetId={} has {} ENTITY nodes, exceeds cap {}; skipping",
                    factSheetId, entityNodes.size(), maxNodesToProcess);
            return NormalizationResult.skipped(factSheetId, entityNodes.size());
        }

        int canonicalized = 0;
        int degenDropped  = 0;
        int mergedDups    = 0;

        // ── Pass 1: Canonicalize titles + drop degenerate nodes ───────────────
        List<GraphNode> survivors = new ArrayList<>(entityNodes.size());
        for (GraphNode node : entityNodes) {
            String rawTitle = node.getTitle();
            String canonical = canonicalizeTitle(rawTitle);

            if (isDegenerate(canonical, minTitleLength)) {
                try {
                    knowledgeGraphService.deleteNode(node.getNodeId());
                    degenDropped++;
                } catch (Exception e) {
                    log.debug("Could not delete degenerate node {}: {}", node.getNodeId(), e.getMessage());
                }
                continue;
            }

            if (!canonical.equals(rawTitle)) {
                try {
                    knowledgeGraphService.updateNode(node.getNodeId(), canonical, node.getDescription(), null);
                    canonicalized++;
                    // Update the in-memory view for pass 2
                    node.setTitle(canonical);
                } catch (Exception e) {
                    log.debug("Could not canonicalize node {}: {}", node.getNodeId(), e.getMessage());
                }
            }
            survivors.add(node);
        }

        // ── Pass 2: Merge trivially-duplicate nodes ───────────────────────────
        // Build canonical-title → first-seen node index
        Map<String, Integer> titleIndex = new LinkedHashMap<>();
        List<GraphNode> deduped = new ArrayList<>(survivors.size());

        for (GraphNode node : survivors) {
            String key = node.getTitle() == null ? "" : node.getTitle().toLowerCase().trim();
            if (titleIndex.containsKey(key)) {
                int origIdx = titleIndex.get(key);
                GraphNode original = deduped.get(origIdx);
                // Merge metadata from duplicate into original, then delete duplicate
                Map<String, Object> mergedMeta = mergeMetadata(original, node);
                try {
                    if (!mergedMeta.isEmpty()) {
                        knowledgeGraphService.updateNode(original.getNodeId(),
                                original.getTitle(), original.getDescription(), mergedMeta);
                    }
                    knowledgeGraphService.deleteNode(node.getNodeId());
                    mergedDups++;
                } catch (Exception e) {
                    log.debug("Could not merge/delete duplicate node {}: {}", node.getNodeId(), e.getMessage());
                    deduped.add(node); // keep it if we can't delete
                }
            } else {
                titleIndex.put(key, deduped.size());
                deduped.add(node);
            }
        }

        NormalizationResult result = new NormalizationResult(
                factSheetId, entityNodes.size(), canonicalized, degenDropped, mergedDups, false);
        log.info("GraphPostExtractionNormalizer: factSheetId={} — {} nodes processed, " +
                        "{} canonicalized, {} degenerate dropped, {} duplicates merged",
                factSheetId, entityNodes.size(), canonicalized, degenDropped, mergedDups);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Canonicalizes a node title:
     * <ol>
     *   <li>Normalize Unicode to NFC.</li>
     *   <li>Collapse internal whitespace runs to a single space.</li>
     *   <li>Strip leading/trailing whitespace.</li>
     *   <li>Strip leading/trailing punctuation artefacts.</li>
     * </ol>
     */
    static String canonicalizeTitle(String title) {
        if (title == null) return "";
        String result = Normalizer.normalize(title, Normalizer.Form.NFC);
        result = INTERNAL_WHITESPACE.matcher(result).replaceAll(" ");
        result = result.strip();
        result = EDGE_PUNCTUATION.matcher(result).replaceAll("").strip();
        return result;
    }

    /**
     * Returns true when the canonical title is too short to represent a
     * meaningful entity — unless it is a purely numeric value (dates, IDs, etc.).
     */
    static boolean isDegenerate(String canonical, int minLength) {
        if (canonical == null || canonical.isBlank()) return true;
        if (canonical.length() < minLength && !PURELY_NUMERIC.matcher(canonical).matches()) return true;
        return false;
    }

    /**
     * Merges the metadata of the {@code duplicate} node into the
     * {@code original} node without overwriting existing keys.
     * Returns only the keys that were added (so the caller can decide
     * whether an update call is necessary).
     */
    static Map<String, Object> mergeMetadata(GraphNode original, GraphNode duplicate) {
        Map<String, Object> origMeta  = original.getMetadata();  // may be empty/unmodifiable
        Map<String, Object> dupMeta   = duplicate.getMetadata();
        if (dupMeta == null || dupMeta.isEmpty()) return Map.of();

        Map<String, Object> additions = new HashMap<>();
        for (Map.Entry<String, Object> entry : dupMeta.entrySet()) {
            if (!origMeta.containsKey(entry.getKey())) {
                additions.put(entry.getKey(), entry.getValue());
            }
        }
        return additions;
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    /**
     * Summary of a normalization run. Useful for REST/CLI surfacing.
     */
    public record NormalizationResult(
            Long factSheetId,
            int totalNodes,
            int canonicalized,
            int degenerateDropped,
            int duplicatesMerged,
            boolean skipped
    ) {
        static NormalizationResult empty(Long fsId) {
            return new NormalizationResult(fsId, 0, 0, 0, 0, false);
        }

        static NormalizationResult skipped(Long fsId, int nodeCount) {
            return new NormalizationResult(fsId, nodeCount, 0, 0, 0, true);
        }
    }
}
