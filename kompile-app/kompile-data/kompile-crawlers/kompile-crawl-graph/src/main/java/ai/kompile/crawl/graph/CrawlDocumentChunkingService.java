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

package ai.kompile.crawl.graph;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Document chunking service for the unified crawl pipeline.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 * Responsibilities:</p>
 * <ul>
 *   <li>Content-type-aware chunker selection ({@link #resolveChunkerForContent})</li>
 *   <li>Cost-batch planning and parallel/sequential chunk dispatch
 *       ({@link #chunkDocuments})</li>
 *   <li>Per-document chunking with progress tracking ({@link #chunkOneDocument})</li>
 * </ul>
 */
@Component
class CrawlDocumentChunkingService {

    private static final Logger log = LoggerFactory.getLogger(CrawlDocumentChunkingService.class);

    // ── Dependencies ────────────────────────────────────────────────────────

    @Autowired(required = false)
    private List<TextChunker> textChunkers;

    private final CrawlBatchPlanner batchPlanner;
    private final PipelineStepTracker pipelineStepTracker;
    private final CrawlDocumentTracker documentTracker;

    CrawlDocumentChunkingService(CrawlBatchPlanner batchPlanner,
                                 PipelineStepTracker pipelineStepTracker,
                                 CrawlDocumentTracker documentTracker) {
        this.batchPlanner = batchPlanner;
        this.pipelineStepTracker = pipelineStepTracker;
        this.documentTracker = documentTracker;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Chunks documents using content-type-aware chunker selection.
     * If no chunkers are available, returns the original documents unchanged.
     *
     * @param documents              documents to chunk
     * @param job                    the running crawl job
     * @param chunkingParallelism    max parallel chunking tasks
     * @param chunkingTargetCharsPerTask target char budget per task batch
     * @param costSortChunks         whether to cost-sort chunks across tasks
     * @param sharedChunkingPool     shared executor (reused across jobs; never shutdown here)
     * @return chunked (or passthrough) documents
     */
    List<Document> chunkDocuments(List<Document> documents,
                                  UnifiedCrawlJob job,
                                  int chunkingParallelism,
                                  int chunkingTargetCharsPerTask,
                                  boolean costSortChunks,
                                  ExecutorService sharedChunkingPool) {
        if (documents == null || documents.isEmpty()) return List.of();
        if (textChunkers == null || textChunkers.isEmpty()) {
            log.debug("No text chunkers available, passing documents through unchunked");
            return documents;
        }

        // Select the appropriate chunker based on content type
        TextChunker chunker = resolveChunkerForContent(documents);
        if (chunker == null) {
            log.debug("No suitable chunker found, passing documents through unchunked");
            return documents;
        }

        log.info("Using chunker '{}' for {} documents", chunker.getName(), documents.size());
        Map<String, Object> options = chunker.getDefaultOptions();

        int parallelism = Math.min(Math.max(1, chunkingParallelism), documents.size());
        List<CrawlBatchPlanner.CostBatch<Document>> batches = batchPlanner.planCostBatches(
                documents,
                batchPlanner::estimateDocumentCost,
                Math.max(1, documents.size()),
                Math.max(1, chunkingTargetCharsPerTask),
                costSortChunks);
        UnifiedCrawlJob.PipelineStepProgress chunkStep = pipelineStepTracker.ensurePipelineStep(job, "CHUNKING");
        chunkStep.getTotalItems().set(documents.size());
        chunkStep.getTotalBatches().set(batches.size());
        documentTracker.recordEvent(job, "CHUNKING", "INFO",
                "Planned chunking tasks",
                "documents=" + documents.size() + ", tasks=" + batches.size()
                        + ", parallelism=" + parallelism + ", targetChars=" + chunkingTargetCharsPerTask);

        if (parallelism <= 1 || batches.size() <= 1) {
            List<Document> chunkedDocuments = new ArrayList<>();
            for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                if (isCancelled(job)) return chunkedDocuments;
                if (docIndex % 10 == 0) {
                    documentTracker.recordEvent(job, "CHUNKING", "INFO",
                            "Chunking document " + (docIndex + 1) + "/" + documents.size(),
                            chunkedDocuments.size() + " chunk(s) created");
                }
                chunkedDocuments.addAll(chunkOneDocument(documents.get(docIndex), chunker, options, job));
            }
            return chunkedDocuments;
        }

        // Use shared chunking pool — avoids per-job thread creation/teardown overhead
        ExecutorService chunkExec = sharedChunkingPool;
        try {
            List<Future<List<Document>>> futures = new ArrayList<>(batches.size());
            for (CrawlBatchPlanner.CostBatch<Document> batch : batches) {
                futures.add(chunkExec.submit(() -> {
                    job.getCurrentBatchSize().set(batch.items().size());
                    job.getCurrentBatchStep().set("CHUNK_TASK " + batch.index() + "/" + batches.size());
                    List<Document> chunked = new ArrayList<>();
                    for (Document document : batch.items()) {
                        if (isCancelled(job)) break;
                        chunked.addAll(chunkOneDocument(document, chunker, options, job));
                    }
                    documentTracker.recordEvent(job, "CHUNKING", "INFO",
                            "Completed chunking task " + batch.index() + "/" + batches.size(),
                            chunked.size() + " chunk(s), cost=" + batch.cost());
                    pipelineStepTracker.incrementPipelineStep(job, "CHUNKING", 0, 1,
                            "Completed chunking task " + batch.index() + "/" + batches.size());
                    return chunked;
                }));
            }

            List<Document> chunkedDocuments = new ArrayList<>();
            for (Future<List<Document>> future : futures) {
                if (isCancelled(job)) break;
                try {
                    chunkedDocuments.addAll(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("Chunking task failed: {}", cause.getMessage());
                    job.getErrors().add("Chunking task failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                    documentTracker.recordEvent(job, "CHUNKING", "WARN",
                            "Chunking task failed", cause.getMessage());
                }
            }
            return chunkedDocuments;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            // Don't shutdown — shared pool is reused across jobs
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    /**
     * Chunks a single document using the resolved chunker.
     * Falls back to the original document if chunking fails.
     */
    List<Document> chunkOneDocument(Document doc,
                                    TextChunker chunker,
                                    Map<String, Object> options,
                                    UnifiedCrawlJob job) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            pipelineStepTracker.incrementPipelineStep(job, "CHUNKING", 1, 0, "Skipped blank document");
            documentTracker.recordDocumentProgress(job, doc, "CHUNKING", "SKIPPED", 0, 0, 0,
                    "Skipped blank document", null, List.of(chunker.getName()), false);
            return List.of();
        }

        try {
            // Build source metadata once, filtering nulls. This single copy is shared
            // across all chunks via the RetrievedDoc — avoids O(chunks × metadata_size)
            // HashMap allocations that dominated GC for metadata-rich documents.
            Map<String, Object> baseMeta = new HashMap<>();
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> e : doc.getMetadata().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        baseMeta.put(e.getKey(), e.getValue());
                    }
                }
            }
            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            RetrievedDoc retrievedDoc = new RetrievedDoc(id, text, baseMeta);

            List<RetrievedDoc> chunks = chunker.chunk(retrievedDoc, options);
            List<Document> chunkedDocuments = new ArrayList<>(chunks.size());
            for (RetrievedDoc chunk : chunks) {
                // Chunk metadata from the chunker may contain chunk-specific fields
                // (chunk_index, chunk_start, etc.) layered on top of the base metadata.
                // If the chunker returned the same map reference as baseMeta, we must
                // still copy it into the Document since Document.getMetadata() is mutable.
                Map<String, Object> chunkMeta = chunk.getMetadata();
                Document chunkDoc;
                if (chunkMeta == baseMeta || chunkMeta == null) {
                    // Chunker didn't add chunk-specific fields — share base via shallow copy
                    chunkDoc = new Document(chunk.getText(), new HashMap<>(baseMeta));
                } else {
                    // Chunker added fields — use its map directly (already contains base fields)
                    chunkDoc = new Document(chunk.getText(), chunkMeta);
                }
                chunkedDocuments.add(chunkDoc);
                job.getChunksCreated().incrementAndGet();
                job.getChunksProcessed().incrementAndGet();
            }
            pipelineStepTracker.incrementPipelineStep(job, "CHUNKING", 1, 0,
                    chunkedDocuments.size() + " chunk(s) created");
            documentTracker.recordDocumentProgress(job, doc, "CHUNKING", "COMPLETED", chunkedDocuments.size(), 0, 0,
                    chunkedDocuments.size() + " chunk(s) created", null, List.of(chunker.getName()), false);
            return chunkedDocuments;
        } catch (Exception e) {
            log.warn("Chunking failed for document, using original: {}", e.getMessage());
            job.getChunksCreated().incrementAndGet();
            job.getChunksProcessed().incrementAndGet();
            documentTracker.recordEvent(job, "CHUNKING", "WARN",
                    "Chunking failed; using original document", e.getMessage());
            pipelineStepTracker.incrementPipelineStep(job, "CHUNKING", 1, 0,
                    "Chunking failed; using original document");
            documentTracker.recordDocumentProgress(job, doc, "CHUNKING", "FAILED", 1, 0, 0,
                    "Chunking failed; using original document", e.getMessage(), List.of(chunker.getName()), true);
            return List.of(doc);
        }
    }

    /**
     * Content-type-aware chunker selection. Inspects document metadata to pick
     * the most appropriate chunker (HTML, table-aware, or default).
     */
    TextChunker resolveChunkerForContent(List<Document> documents) {
        if (textChunkers == null || textChunkers.isEmpty()) return null;

        boolean hasVlmContent = false;
        boolean hasHtmlContent = false;
        boolean hasTables = false;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            if (Boolean.TRUE.equals(meta.get(GraphConstants.META_VLM_PROCESSED))) hasVlmContent = true;
            String contentType = meta.get(GraphConstants.META_CONTENT_TYPE) instanceof String
                    ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            if ("table".equals(contentType) || "vlm_document".equals(contentType)) hasTables = true;
            String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                    ? (String) meta.get(GraphConstants.META_LOADER) : null;
            if (loaderName != null && loaderName.toLowerCase().contains("html")) hasHtmlContent = true;
            Object tableCount = meta.get(GraphConstants.META_TABLE_COUNT);
            if (tableCount instanceof Number && ((Number) tableCount).intValue() > 0) hasTables = true;
        }

        // Try HTML chunker for HTML content
        if (hasHtmlContent) {
            TextChunker html = findChunkerByName("html");
            if (html != null) {
                log.info("Auto-selecting 'html' chunker for HTML content");
                return html;
            }
        }

        // Try table-aware chunker for VLM or table content
        if (hasVlmContent || hasTables) {
            TextChunker tableAware = findChunkerByName("table-aware");
            if (tableAware != null) {
                log.info("Auto-selecting 'table-aware' chunker for {} content",
                        hasVlmContent ? "VLM-processed" : "table-heavy");
                return tableAware;
            }
        }

        // Fall back to first available real chunker
        return textChunkers.stream()
                .filter(c -> !isNoOpChunker(c))
                .findFirst()
                .orElse(null);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private TextChunker findChunkerByName(String name) {
        if (textChunkers == null) return null;
        return textChunkers.stream()
                .filter(c -> name.equals(c.getName()) && !isNoOpChunker(c))
                .findFirst()
                .orElse(null);
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        return chunker.getClass().getSimpleName().contains("NoOp");
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        return job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED;
    }
}
