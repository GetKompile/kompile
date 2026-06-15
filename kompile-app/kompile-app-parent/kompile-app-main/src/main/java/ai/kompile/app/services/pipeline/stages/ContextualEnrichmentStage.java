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

package ai.kompile.app.services.pipeline.stages;

import ai.kompile.app.services.ContextualChunkEnricher;
import ai.kompile.app.services.ContextualRagConfigService;
import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Pipeline stage for contextual chunk enrichment.
 *
 * <p>This stage processes chunks after the chunking stage and enriches them with
 * contextual information using an LLM. It implements the Contextual Retrieval
 * approach to improve search quality.</p>
 *
 * <p>The stage:</p>
 * <ul>
 *   <li>Groups chunks by source document</li>
 *   <li>Generates document summaries for context</li>
 *   <li>Adds contextual prefixes to each chunk using LLM</li>
 *   <li>Enhances source attribution metadata</li>
 * </ul>
 *
 * <p>Input: {@link ChunkingStage.ChunkingOutput} containing document chunks</p>
 * <p>Output: {@link ContextualEnrichmentOutput} containing enriched chunks</p>
 */
public class ContextualEnrichmentStage implements PipelineStage<ChunkingStage.ChunkingOutput, ContextualEnrichmentStage.ContextualEnrichmentOutput> {

    private static final Logger logger = LoggerFactory.getLogger(ContextualEnrichmentStage.class);

    private final ContextualChunkEnricher enricher;
    private final ContextualRagConfigService configService;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Original document texts keyed by source ID (populated from earlier stage).
    // Volatile so that the reference written by setDocumentTexts()/reset() is
    // immediately visible to the thread executing process().
    private volatile Map<String, String> documentTexts = new HashMap<>();

    public ContextualEnrichmentStage(ContextualChunkEnricher enricher,
                                      ContextualRagConfigService configService) {
        this.enricher = enricher;
        this.configService = configService;
    }

    @Override
    public String getName() {
        return "contextual-enrichment";
    }

    @Override
    public ContextualEnrichmentOutput process(ChunkingStage.ChunkingOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Contextual enrichment stage cancelled");
        }

        long startNanos = System.nanoTime();
        List<RetrievedDoc> allEnrichedChunks = new ArrayList<>();
        int documentsProcessed = 0;
        int chunksEnriched = 0;

        try {
            // Check if enrichment is enabled
            boolean enrichmentEnabled = configService.isEnabled();
            logger.info("Contextual enrichment stage started. Enabled: {}, Input chunks: {}",
                    enrichmentEnabled, input.chunkCount());

            if (!enrichmentEnabled) {
                // Pass through with source attribution only
                logger.debug("Contextual enrichment disabled, passing through with source attribution");
                long elapsedNanos = System.nanoTime() - startNanos;
                metrics.recordSuccess(elapsedNanos, 0, input.chunkCount());

                return new ContextualEnrichmentOutput(
                        input.chunks(),
                        input.documentsChunked(),
                        0,
                        elapsedNanos / 1_000_000,
                        false,
                        input.loaderUsed(),
                        input.taskId(),
                        input.metadata()
                );
            }

            // Group chunks by source document
            Map<String, List<RetrievedDoc>> chunksByDocument = groupChunksBySource(input.chunks());
            logger.info("Processing {} documents with contextual enrichment", chunksByDocument.size());

            // Process each document's chunks
            for (Map.Entry<String, List<RetrievedDoc>> entry : chunksByDocument.entrySet()) {
                if (cancelled.get()) {
                    throw new InterruptedException("Contextual enrichment cancelled during processing");
                }

                String sourceId = entry.getKey();
                List<RetrievedDoc> chunks = entry.getValue();
                String documentTitle = extractDocumentTitle(chunks);
                String documentText = documentTexts.getOrDefault(sourceId, null);

                logger.debug("Enriching {} chunks for document: {}", chunks.size(), documentTitle);

                try {
                    List<RetrievedDoc> enrichedChunks = enricher.enrichChunks(
                            chunks, documentText, documentTitle);
                    allEnrichedChunks.addAll(enrichedChunks);
                    chunksEnriched += enrichedChunks.size();
                } catch (Exception e) {
                    logger.error("Error enriching chunks for document {}: {}", sourceId, e.getMessage());
                    // Add original chunks if enrichment fails
                    allEnrichedChunks.addAll(chunks);
                }

                documentsProcessed++;

                if (documentsProcessed % 10 == 0) {
                    logger.info("Enriched {}/{} documents, {} chunks so far",
                            documentsProcessed, chunksByDocument.size(), allEnrichedChunks.size());
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            long totalBytes = allEnrichedChunks.stream()
                    .mapToLong(c -> c.getText() != null ? c.getText().length() * 2L : 0)
                    .sum();

            metrics.recordSuccess(elapsedNanos, totalBytes, allEnrichedChunks.size());

            logger.info("Contextual enrichment completed: {} documents, {} chunks enriched in {}ms",
                    documentsProcessed, chunksEnriched, elapsedNanos / 1_000_000);

            return new ContextualEnrichmentOutput(
                    allEnrichedChunks,
                    documentsProcessed,
                    chunksEnriched,
                    elapsedNanos / 1_000_000,
                    true,
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    /**
     * Groups chunks by their source document.
     */
    private Map<String, List<RetrievedDoc>> groupChunksBySource(List<RetrievedDoc> chunks) {
        return chunks.stream()
                .collect(Collectors.groupingBy(chunk -> {
                    if (chunk.getMetadata() == null) {
                        return "unknown";
                    }
                    Object sourceId = chunk.getMetadata().get(SourceMetadataConstants.SOURCE_ID);
                    if (sourceId != null) {
                        return sourceId.toString();
                    }
                    Object sourcePath = chunk.getMetadata().get(SourceMetadataConstants.SOURCE_PATH);
                    if (sourcePath != null) {
                        return sourcePath.toString();
                    }
                    return chunk.getId() != null ? chunk.getId() : "unknown";
                }));
    }

    /**
     * Extracts the document title from chunk metadata.
     */
    private String extractDocumentTitle(List<RetrievedDoc> chunks) {
        if (chunks.isEmpty()) {
            return "Unknown Document";
        }

        RetrievedDoc first = chunks.get(0);
        if (first.getMetadata() == null) {
            return "Unknown Document";
        }

        // Try various metadata keys for the title
        Object filename = first.getMetadata().get(SourceMetadataConstants.SOURCE_FILENAME);
        if (filename != null) {
            return filename.toString();
        }

        Object path = first.getMetadata().get(SourceMetadataConstants.SOURCE_PATH);
        if (path != null) {
            String pathStr = path.toString();
            int lastSlash = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
            return lastSlash >= 0 ? pathStr.substring(lastSlash + 1) : pathStr;
        }

        return "Unknown Document";
    }

    /**
     * Sets the original document texts for contextual enrichment.
     * This should be called before processing to provide document context.
     *
     * @param documentTexts Map of source ID to document text
     */
    public void setDocumentTexts(Map<String, String> documentTexts) {
        this.documentTexts = documentTexts != null ? new HashMap<>(documentTexts) : new HashMap<>();
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        // Configuration is primarily handled through ContextualRagConfigService
        // but we can accept document texts here
        if (options.containsKey("documentTexts")) {
            @SuppressWarnings("unchecked")
            Map<String, String> texts = (Map<String, String>) options.get("documentTexts");
            setDocumentTexts(texts);
        }
    }

    @Override
    public StageMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void reset() {
        cancelled.set(false);
        metrics.reset();
        // Assign a fresh map rather than mutating the existing one so that
        // concurrent readers always see a fully-formed (possibly empty) map.
        documentTexts = new HashMap<>();
    }

    /**
     * Output from the contextual enrichment stage.
     */
    public record ContextualEnrichmentOutput(
            List<RetrievedDoc> chunks,
            int documentsProcessed,
            int chunksEnriched,
            long enrichmentTimeMs,
            boolean enrichmentApplied,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata
    ) {
        public int chunkCount() {
            return chunks != null ? chunks.size() : 0;
        }

        public double enrichmentRatio() {
            int total = chunkCount();
            return total > 0 ? (double) chunksEnriched / total : 0;
        }
    }
}
