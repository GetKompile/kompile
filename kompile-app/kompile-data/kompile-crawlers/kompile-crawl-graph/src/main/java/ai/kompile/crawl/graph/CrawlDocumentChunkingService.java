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
import ai.kompile.core.crawl.graph.CrawlChunkingConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /** Project defaults emitted by {@code kompile project init}; request/source settings override. */
    @Value("${kompile.chunker.type:}")
    private String projectChunkerName = "";

    @Value("${kompile.chunker.chunkSize:0}")
    private int projectChunkSize;

    @Value("${kompile.chunker.chunkOverlap:-1}")
    private int projectChunkOverlap = -1;

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

        log.info("Chunking {} documents with project/crawl/source-aware policies", documents.size());

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
                chunkedDocuments.addAll(chunkConfiguredDocument(documents.get(docIndex), job));
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
                        chunked.addAll(chunkConfiguredDocument(document, job));
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

    record ChunkingPlan(TextChunker chunker, Map<String, Object> options) {
    }

    /** Resolves one document independently so mixed-source crawls retain source-local overrides. */
    private List<Document> chunkConfiguredDocument(Document document, UnifiedCrawlJob job) {
        ChunkingPlan plan = resolvePlan(document, job);
        if (plan.chunker() == null) {
            log.debug("No suitable chunker found for document {}; passing it through",
                    document != null ? document.getId() : null);
            return document == null ? List.of() : List.of(document);
        }
        return chunkOneDocument(document, plan.chunker(), plan.options(), job);
    }

    ChunkingPlan resolvePlan(Document document, UnifiedCrawlJob job) {
        UnifiedCrawlRequest request = job != null ? job.getRequest() : null;
        CrawlChunkingConfig crawl = request != null ? request.getChunking() : null;
        // Compatibility: the original unified request exposed these knobs under vectorIndex even
        // though the resulting chunks feed graph extraction too.
        VectorIndexConfig legacy = request != null ? request.getVectorIndex() : null;
        Map<String, Object> metadata = document != null && document.getMetadata() != null
                ? document.getMetadata() : Map.of();

        String requestedName = firstNonBlank(
                stringValue(metadata.get(GraphConstants.META_CHUNKER_NAME)),
                crawl != null ? crawl.getChunkerName() : null,
                legacy != null ? legacy.getChunkerName() : null,
                projectChunkerName);
        TextChunker chunker = requestedName != null ? findConfiguredChunker(requestedName) : null;
        if (chunker == null) {
            if (requestedName != null) {
                log.warn("Configured chunker '{}' is unavailable; using content-aware fallback",
                        requestedName);
            }
            chunker = resolveChunkerForContent(document == null ? List.of() : List.of(document));
        }
        if (chunker == null) {
            return new ChunkingPlan(null, Map.of());
        }

        Map<String, Object> options = new LinkedHashMap<>(chunker.getDefaultOptions());
        applyChunkSize(options, projectChunkSize > 0 ? projectChunkSize : null);
        applyChunkOverlap(options, projectChunkOverlap >= 0 ? projectChunkOverlap : null);
        if (legacy != null) {
            applyChunkSize(options, positive(legacy.getChunkSize()));
            applyChunkOverlap(options, nonNegative(legacy.getChunkOverlap()));
        }
        if (crawl != null) {
            if (crawl.getOptions() != null) {
                options.putAll(crawl.getOptions());
            }
            applyChunkSize(options, positive(crawl.getChunkSize()));
            applyChunkOverlap(options, nonNegative(crawl.getChunkOverlap()));
        }
        Object sourceOptions = metadata.get(GraphConstants.META_CHUNKER_OPTIONS);
        if (sourceOptions instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    options.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        applyChunkSize(options, positive(metadata.get(GraphConstants.META_CHUNK_SIZE_OVERRIDE)));
        applyChunkOverlap(options,
                nonNegative(metadata.get(GraphConstants.META_CHUNK_OVERLAP_OVERRIDE)));
        return new ChunkingPlan(chunker, Map.copyOf(options));
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
            Object sourceEventSpans = baseMeta.get(GraphConstants.META_SOURCE_EVENT_SPANS);
            int nextSourceSearchStart = 0;
            for (RetrievedDoc chunk : chunks) {
                // Chunk metadata from the chunker may contain chunk-specific fields
                // (chunk_index, chunk_start, etc.) layered on top of the base metadata.
                // If the chunker returned the same map reference as baseMeta, we must
                // still copy it into the Document since Document.getMetadata() is mutable.
                Map<String, Object> chunkMeta = chunk.getMetadata();
                Document chunkDoc;
                if (sourceEventSpans != null) {
                    // Source preprocessors emit offsets against the pre-chunk document. Never
                    // leak those offsets into a chunk unchanged: rebase exact-substring chunks,
                    // or remove the plan so atomization safely falls back to its configured mode.
                    Map<String, Object> projectedMeta = new HashMap<>(
                            chunkMeta == null ? baseMeta : chunkMeta);
                    SourceRange range = locateSourceRange(text, chunk.getText(), nextSourceSearchStart);
                    projectedMeta.remove(GraphConstants.META_SOURCE_EVENT_SPANS);
                    projectedMeta.remove(GraphConstants.META_CHUNK_SOURCE_START);
                    projectedMeta.remove(GraphConstants.META_CHUNK_SOURCE_END);
                    if (range != null) {
                        nextSourceSearchStart = Math.min(text.length(), range.start() + 1);
                        projectedMeta.put(GraphConstants.META_CHUNK_SOURCE_START, range.start());
                        projectedMeta.put(GraphConstants.META_CHUNK_SOURCE_END, range.end());
                        List<Map<String, Object>> rebased = rebaseSourceEventSpans(
                                sourceEventSpans, range, text.length());
                        if (!rebased.isEmpty()) {
                            projectedMeta.put(GraphConstants.META_SOURCE_EVENT_SPANS, rebased);
                        }
                    }
                    chunkDoc = new Document(chunk.getText(), projectedMeta);
                } else if (chunkMeta == baseMeta || chunkMeta == null) {
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
        boolean hasCodeContent = false;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            if (Boolean.TRUE.equals(meta.get(GraphConstants.META_VLM_PROCESSED))) hasVlmContent = true;
            String contentType = meta.get(GraphConstants.META_CONTENT_TYPE) instanceof String
                    ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            if ("table".equals(contentType) || "vlm_document".equals(contentType)) hasTables = true;
            if ("code".equals(contentType) || "source-code".equals(meta.get("documentType"))) {
                hasCodeContent = true;
            }
            String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                    ? (String) meta.get(GraphConstants.META_LOADER) : null;
            if (loaderName != null && loaderName.toLowerCase().contains("html")) hasHtmlContent = true;
            Object tableCount = meta.get(GraphConstants.META_TABLE_COUNT);
            if (tableCount instanceof Number && ((Number) tableCount).intValue() > 0) hasTables = true;
        }

        if (hasCodeContent) {
            TextChunker codeAware = findChunkerByName("code-aware");
            if (codeAware != null) {
                log.info("Auto-selecting 'code-aware' chunker for source-code content");
                return codeAware;
            }
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
                .filter(c -> !"code-aware".equals(normalizeName(c.getName())))
                .findFirst()
                .orElse(null);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private TextChunker findChunkerByName(String name) {
        if (textChunkers == null) return null;
        return textChunkers.stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElse(null);
    }

    private TextChunker findConfiguredChunker(String requestedName) {
        if (textChunkers == null || requestedName == null || requestedName.isBlank()) {
            return null;
        }
        String normalized = normalizeName(requestedName);
        TextChunker exact = textChunkers.stream()
                .filter(Objects::nonNull)
                .filter(chunker -> normalizeName(chunker.getName()).equals(normalized))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        if ("recursive".equals(normalized)) {
            return textChunkers.stream()
                    .filter(Objects::nonNull)
                    .filter(chunker -> normalizeName(chunker.getName()).contains("recursive"))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer positive(Object value) {
        Integer parsed = integerValue(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Integer nonNegative(Object value) {
        Integer parsed = integerValue(value);
        return parsed != null && parsed >= 0 ? parsed : null;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void applyChunkSize(Map<String, Object> options, Integer value) {
        if (value != null) {
            options.put("chunkSize", value);
        }
    }

    private static void applyChunkOverlap(Map<String, Object> options, Integer value) {
        if (value != null) {
            options.put("overlap", value);
            options.put("chunkOverlap", value);
        }
    }

    private static SourceRange locateSourceRange(String source, String chunk, int searchStart) {
        if (source == null || chunk == null || chunk.isEmpty()) {
            return null;
        }
        int boundedStart = Math.max(0, Math.min(searchStart, source.length()));
        int start = source.indexOf(chunk, boundedStart);
        if (start < 0 && boundedStart > 0) {
            // A normalization-aware chunker may revisit an earlier unique region. Accept a
            // unique exact match, but never guess between duplicate occurrences.
            int candidate = source.indexOf(chunk);
            if (candidate >= 0 && source.indexOf(chunk, candidate + 1) < 0) {
                start = candidate;
            }
        }
        return start < 0 ? null : new SourceRange(start, start + chunk.length());
    }

    private static List<Map<String, Object>> rebaseSourceEventSpans(Object raw,
                                                                    SourceRange chunk,
                                                                    int sourceLength) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> rebased = new ArrayList<>();
        for (Object value : values) {
            Integer start = null;
            Integer end = null;
            String kind = null;
            if (value instanceof ai.kompile.core.graphrag.GraphConstructor.SourceSpan span) {
                start = span.start();
                end = span.end();
                kind = span.kind();
            } else if (value instanceof Map<?, ?> map) {
                start = integerValue(map.get("start"));
                end = integerValue(map.get("end"));
                Object rawKind = map.get("kind");
                kind = rawKind == null ? null : String.valueOf(rawKind);
            }
            if (start == null || end == null || start < 0 || end <= start || end > sourceLength) {
                continue;
            }
            int clippedStart = Math.max(start, chunk.start());
            int clippedEnd = Math.min(end, chunk.end());
            if (clippedEnd <= clippedStart) {
                continue;
            }
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("start", clippedStart - chunk.start());
            projected.put("end", clippedEnd - chunk.start());
            if (kind != null && !kind.isBlank()) {
                projected.put("kind", kind);
            }
            rebased.add(projected);
        }
        return List.copyOf(rebased);
    }

    private record SourceRange(int start, int end) {
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        return chunker.getClass().getSimpleName().contains("NoOp");
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        return job != null && job.isCancellationRequested();
    }
}
