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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Post-extraction normalizer that runs automatically after each graph build
 * completes, via the {@link GraphBuildCompletedEvent} application event.
 *
 * <h3>What it does</h3>
 * Canonicalizes entity names by trimming whitespace, collapsing internal
 * whitespace runs, stripping surrounding punctuation, and normalizing Unicode
 * to NFC. It deliberately does not decide entity identity or delete extracted
 * facts. Identity resolution belongs to
 * {@link ai.kompile.knowledgegraph.resolution.GraphCompactionService}, which has
 * schema/type, embedding, identifier, and decision-trace context. Short names
 * such as initials, codes, and variables are valid graph entities and are kept.
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
 *   <li>No node or relation is deleted and no pair of entities is merged.</li>
 *   <li>If {@code factSheetId} is null in the event the normalizer silently
 *       skips (avoids cross-graph contamination).</li>
 *   <li>All exceptions are caught and logged; the normalizer never propagates
 *       an exception back through the event bus.</li>
 *   <li>Runs on the calling thread (same as the event publisher) — wrap in
 *       {@code @Async} if the crawl thread must not block.</li>
 * </ul>
 *
 * Processing is bounded by {@link #DEFAULT_MAX_NODES}; larger fact sheets are
 * left unchanged for the dedicated graph maintenance pipeline.
 */
@Component
public class GraphPostExtractionNormalizer {

    private static final Logger log = LoggerFactory.getLogger(GraphPostExtractionNormalizer.class);

    // ── Defaults (tunables) ───────────────────────────────────────────────────

    /** Retained for compatibility with callers of the legacy three-argument API. */
    @Deprecated(forRemoval = false)
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
     *
     * <p>Runs {@code @Async} so the crawl thread is released immediately after
     * COMPLETED is published; normalization proceeds in the application task pool.</p>
     */
    @Async
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
     * Canonicalize the ENTITY titles of the given fact sheet and return a
     * summary result. This pass never performs identity resolution or deletion.
     *
     * <p>This method is also callable directly (e.g. from a REST endpoint or
     * scheduled maintenance job) without going through the event bus.</p>
     *
     * @param factSheetId       the fact sheet whose ENTITY nodes are processed
     * @param legacyMinTitleLength retained for source compatibility; ignored because
     *                             short entity names are valid and must not be deleted
     * @param maxNodesToProcess safety cap
     * @return summary of changes made
     */
    public NormalizationResult normalize(Long factSheetId, int legacyMinTitleLength, int maxNodesToProcess) {
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
        // Canonicalization is intentionally non-destructive. Identity resolution,
        // duplicate merging, and fact deletion belong to the dedicated resolver.
        List<KnowledgeGraphService.NodeUpdate> canonBatch = new ArrayList<>();
        for (GraphNode node : entityNodes) {
            String rawTitle = node.getTitle();
            String canonical = canonicalizeTitle(rawTitle);

            // A punctuation-only or blank title is left untouched for validation/audit;
            // canonicalization must never turn an existing fact into a blank fact.
            if (!canonical.isBlank() && !canonical.equals(rawTitle)) {
                node.setTitle(canonical);
                canonBatch.add(new KnowledgeGraphService.NodeUpdate(
                        node.getNodeId(), canonical, null, null));
                canonicalized++;
            }
        }

        if (!canonBatch.isEmpty()) {
            try {
                knowledgeGraphService.updateNodesBatch(canonBatch);
            } catch (Exception e) {
                log.debug("GraphPostExtractionNormalizer: batch title-rename flush failed: {}", e.getMessage());
            }
        }

        NormalizationResult result = new NormalizationResult(
                factSheetId, entityNodes.size(), canonicalized, 0, 0, false);
        log.info("GraphPostExtractionNormalizer: factSheetId={} — {} nodes processed, {} canonicalized",
                factSheetId, entityNodes.size(), canonicalized);
        return result;
    }

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

    // ── Result DTO ────────────────────────────────────────────────────────────

    /**
     * Summary of a normalization run. The deletion/merge counts are retained for
     * API compatibility and are always zero in the non-destructive normalizer.
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
