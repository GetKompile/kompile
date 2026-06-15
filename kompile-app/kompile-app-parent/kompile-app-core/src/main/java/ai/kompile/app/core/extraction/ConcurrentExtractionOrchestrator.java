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

package ai.kompile.app.core.extraction;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Orchestrates concurrent execution of multiple content extractors.
 *
 * <p>This class manages a pool of workers that can run different types of
 * extraction operations concurrently on the same set of documents. Each
 * extractor type can have its own worker pool with configurable thread counts.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 *                    Documents
 *                        │
 *                        ▼
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │             ConcurrentExtractionOrchestrator                │
 *  │  ┌──────────────────┐  ┌──────────────────────────────────┐ │
 *  │  │ Chunking Workers │  │ Structured Output Workers        │ │
 *  │  │   (4-8 threads)  │  │ ┌────────────┐ ┌──────────────┐  │ │
 *  │  │                  │  │ │Entity (2T) │ │Concept (2T)  │  │ │
 *  │  │                  │  │ └────────────┘ └──────────────┘  │ │
 *  │  │                  │  │ ┌────────────┐ ┌──────────────┐  │ │
 *  │  │                  │  │ │Table (1T)  │ │ Fact (2T)    │  │ │
 *  │  └────────┬─────────┘  │ └────────────┘ └──────────────┘  │ │
 *  │           │            └───────────────┬──────────────────┘ │
 *  │           └──────────────┬─────────────┘                    │
 *  │                          ▼                                   │
 *  │                 ResultMerger                                 │
 *  └─────────────────────────────────────────────────────────────┘
 *                          │
 *                          ▼
 *                  Merged Results
 *                  (chunks + structured items)
 * </pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Concurrent execution of multiple extractor types</li>
 *   <li>Per-extractor worker pools with configurable sizes</li>
 *   <li>Progress reporting per extractor</li>
 *   <li>Graceful cancellation and timeout handling</li>
 *   <li>Result merging from all extractors</li>
 * </ul>
 */
public class ConcurrentExtractionOrchestrator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrentExtractionOrchestrator.class);

    private static final int DEFAULT_CHUNKING_THREADS = 4;
    private static final int DEFAULT_STRUCTURED_THREADS = 2;
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600; // 1 hour

    // Registered extractors by type
    private final Map<ContentExtractor.ExtractorType, List<ContentExtractor>> extractorsByType;

    // Worker pools per extractor type
    private final Map<ContentExtractor.ExtractorType, ExecutorService> workerPools;

    // Thread counts per extractor type
    private final Map<ContentExtractor.ExtractorType, Integer> threadCounts;

    // State
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Progress tracking per extractor
    private final Map<String, AtomicInteger> progressByExtractor = new ConcurrentHashMap<>();
    private Consumer<ExtractionProgressReport> progressCallback;

    /**
     * Configuration for the orchestrator.
     */
    public record OrchestratorConfig(
            int chunkingThreads,
            int entityExtractionThreads,
            int conceptExtractionThreads,
            int tableExtractionThreads,
            int factExtractionThreads,
            int defaultStructuredThreads,
            long timeoutSeconds,
            boolean failFast
    ) {
        public static OrchestratorConfig defaults() {
            return new OrchestratorConfig(
                    DEFAULT_CHUNKING_THREADS,
                    DEFAULT_STRUCTURED_THREADS,
                    DEFAULT_STRUCTURED_THREADS,
                    1,
                    DEFAULT_STRUCTURED_THREADS,
                    DEFAULT_STRUCTURED_THREADS,
                    DEFAULT_TIMEOUT_SECONDS,
                    false
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int chunkingThreads = DEFAULT_CHUNKING_THREADS;
            private int entityExtractionThreads = DEFAULT_STRUCTURED_THREADS;
            private int conceptExtractionThreads = DEFAULT_STRUCTURED_THREADS;
            private int tableExtractionThreads = 1;
            private int factExtractionThreads = DEFAULT_STRUCTURED_THREADS;
            private int defaultStructuredThreads = DEFAULT_STRUCTURED_THREADS;
            private long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            private boolean failFast = false;

            public Builder chunkingThreads(int threads) {
                this.chunkingThreads = Math.max(1, threads);
                return this;
            }

            public Builder entityExtractionThreads(int threads) {
                this.entityExtractionThreads = Math.max(1, threads);
                return this;
            }

            public Builder conceptExtractionThreads(int threads) {
                this.conceptExtractionThreads = Math.max(1, threads);
                return this;
            }

            public Builder tableExtractionThreads(int threads) {
                this.tableExtractionThreads = Math.max(1, threads);
                return this;
            }

            public Builder factExtractionThreads(int threads) {
                this.factExtractionThreads = Math.max(1, threads);
                return this;
            }

            public Builder defaultStructuredThreads(int threads) {
                this.defaultStructuredThreads = Math.max(1, threads);
                return this;
            }

            public Builder timeoutSeconds(long timeout) {
                this.timeoutSeconds = timeout;
                return this;
            }

            public Builder failFast(boolean failFast) {
                this.failFast = failFast;
                return this;
            }

            public OrchestratorConfig build() {
                return new OrchestratorConfig(
                        chunkingThreads, entityExtractionThreads, conceptExtractionThreads,
                        tableExtractionThreads, factExtractionThreads, defaultStructuredThreads,
                        timeoutSeconds, failFast
                );
            }
        }
    }

    private final OrchestratorConfig config;

    public ConcurrentExtractionOrchestrator() {
        this(OrchestratorConfig.defaults());
    }

    public ConcurrentExtractionOrchestrator(OrchestratorConfig config) {
        this.config = config;
        this.extractorsByType = new ConcurrentHashMap<>();
        this.workerPools = new ConcurrentHashMap<>();
        this.threadCounts = new ConcurrentHashMap<>();

        // Initialize thread counts from config
        threadCounts.put(ContentExtractor.ExtractorType.CHUNKING, config.chunkingThreads());
        threadCounts.put(ContentExtractor.ExtractorType.ENTITY, config.entityExtractionThreads());
        threadCounts.put(ContentExtractor.ExtractorType.CONCEPT, config.conceptExtractionThreads());
        threadCounts.put(ContentExtractor.ExtractorType.TABLE, config.tableExtractionThreads());
        threadCounts.put(ContentExtractor.ExtractorType.FACT, config.factExtractionThreads());
        threadCounts.put(ContentExtractor.ExtractorType.RELATIONSHIP, config.defaultStructuredThreads());
    }

    /**
     * Registers an extractor with the orchestrator.
     */
    public void registerExtractor(ContentExtractor extractor) {
        Objects.requireNonNull(extractor, "Extractor cannot be null");

        extractorsByType
                .computeIfAbsent(extractor.getType(), k -> new CopyOnWriteArrayList<>())
                .add(extractor);

        logger.info("Registered extractor: {} (type={})", extractor.getName(), extractor.getType());
    }

    /**
     * Unregisters an extractor.
     */
    public void unregisterExtractor(ContentExtractor extractor) {
        if (extractor == null) return;

        List<ContentExtractor> list = extractorsByType.get(extractor.getType());
        if (list != null) {
            list.remove(extractor);
            logger.info("Unregistered extractor: {}", extractor.getName());
        }
    }

    /**
     * Sets the progress callback for real-time updates.
     */
    public void setProgressCallback(Consumer<ExtractionProgressReport> callback) {
        this.progressCallback = callback;
    }

    /**
     * Extracts content from documents using all registered extractors concurrently.
     *
     * @param documents The documents to process
     * @param options   Extraction options (passed to all extractors)
     * @return Combined extraction results from all extractors
     */
    public CombinedExtractionResult extractAll(
            List<RetrievedDoc> documents,
            Map<String, Object> options) throws Exception {

        if (running.getAndSet(true)) {
            throw new IllegalStateException("Orchestrator is already running");
        }

        try {
            return doExtractAll(documents, options);
        } finally {
            running.set(false);
        }
    }

    private CombinedExtractionResult doExtractAll(
            List<RetrievedDoc> documents,
            Map<String, Object> options) throws Exception {

        if (documents == null || documents.isEmpty()) {
            return CombinedExtractionResult.empty();
        }

        cancelled.set(false);
        long startTime = System.currentTimeMillis();

        // Get all enabled extractors sorted by priority
        List<ContentExtractor> allExtractors = getEnabledExtractors();

        if (allExtractors.isEmpty()) {
            logger.warn("No enabled extractors registered");
            return CombinedExtractionResult.empty();
        }

        logger.info("Starting concurrent extraction: {} documents, {} extractors",
                documents.size(), allExtractors.size());

        // Initialize progress tracking
        progressByExtractor.clear();
        for (ContentExtractor extractor : allExtractors) {
            progressByExtractor.put(extractor.getName(), new AtomicInteger(0));
        }

        // Create worker pools for each extractor type
        initializeWorkerPools();

        // Submit extraction tasks for each extractor
        Map<String, Future<List<ExtractionResult>>> futures = new HashMap<>();

        for (ContentExtractor extractor : allExtractors) {
            if (cancelled.get()) break;

            ExecutorService pool = getWorkerPool(extractor.getType());
            Future<List<ExtractionResult>> future = pool.submit(() ->
                    extractWithExtractor(extractor, documents, options));

            futures.put(extractor.getName(), future);
        }

        // Collect results from all extractors
        List<ExtractionResult> allResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, Future<List<ExtractionResult>>> entry : futures.entrySet()) {
            String extractorName = entry.getKey();
            Future<List<ExtractionResult>> future = entry.getValue();

            try {
                List<ExtractionResult> results = future.get(config.timeoutSeconds(), TimeUnit.SECONDS);
                allResults.addAll(results);
                logger.debug("Extractor {} completed with {} results", extractorName, results.size());

            } catch (TimeoutException e) {
                String error = "Extractor " + extractorName + " timed out";
                errors.add(error);
                logger.warn(error);
                future.cancel(true);

                if (config.failFast()) {
                    throw new TimeoutException(error);
                }

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String error = "Extractor " + extractorName + " failed: " + (cause != null ? cause.getMessage() : e.getMessage());
                errors.add(error);
                logger.error(error, cause);

                if (config.failFast()) {
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw e;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;

        // Merge all results
        ExtractionResult merged = ExtractionResult.merge(allResults);

        logger.info("Concurrent extraction complete: {} chunks, {} structured items in {}ms",
                merged.getChunkCount(), merged.getStructuredItemCount(), totalTime);

        return new CombinedExtractionResult(
                merged.getChunks(),
                merged.getStructuredItems(),
                groupItemsByType(merged.getStructuredItems()),
                allExtractors.stream().map(ContentExtractor::getName).toList(),
                documents.size(),
                totalTime,
                errors
        );
    }

    private List<ExtractionResult> extractWithExtractor(
            ContentExtractor extractor,
            List<RetrievedDoc> documents,
            Map<String, Object> options) {

        List<ExtractionResult> results = new ArrayList<>();
        AtomicInteger progress = progressByExtractor.get(extractor.getName());
        int total = documents.size();

        for (int i = 0; i < documents.size(); i++) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                logger.debug("Extractor {} interrupted at document {}", extractor.getName(), i);
                break;
            }

            RetrievedDoc doc = documents.get(i);
            try {
                ExtractionResult result = extractor.extract(doc, options, p -> {
                    // Report fine-grained progress
                    reportProgress(extractor, p);
                });
                results.add(result);

            } catch (Exception e) {
                logger.warn("Extractor {} failed on document {}: {}",
                        extractor.getName(), doc.getId(), e.getMessage());
                results.add(ExtractionResult.failed(doc.getId(), e.getMessage()));
            }

            // Update progress
            int currentProgress = progress.incrementAndGet();
            reportProgress(extractor.getName(), extractor.getType(), currentProgress, total);
        }

        return results;
    }

    private void reportProgress(ContentExtractor extractor, ContentExtractor.ExtractionProgress progress) {
        if (progressCallback != null) {
            progressCallback.accept(new ExtractionProgressReport(
                    extractor.getName(),
                    extractor.getType(),
                    progress.phase(),
                    progress.percentComplete(),
                    progress.itemsProcessed(),
                    progress.totalItems(),
                    progress.message()
            ));
        }
    }

    private void reportProgress(String extractorName, ContentExtractor.ExtractorType type,
                                int processed, int total) {
        if (progressCallback != null) {
            int percent = total > 0 ? (int) ((processed * 100L) / total) : 0;
            progressCallback.accept(new ExtractionProgressReport(
                    extractorName, type, "processing", percent, processed, total,
                    String.format("Processed %d/%d documents", processed, total)
            ));
        }
    }

    private List<ContentExtractor> getEnabledExtractors() {
        return extractorsByType.values().stream()
                .flatMap(List::stream)
                .filter(ContentExtractor::isEnabled)
                .sorted(Comparator.comparingInt(ContentExtractor::getPriority).reversed())
                .toList();
    }

    private void initializeWorkerPools() {
        for (ContentExtractor.ExtractorType type : extractorsByType.keySet()) {
            if (!workerPools.containsKey(type)) {
                int threads = threadCounts.getOrDefault(type, config.defaultStructuredThreads());
                ExecutorService pool = createWorkerPool(type.name().toLowerCase(), threads);
                workerPools.put(type, pool);
            }
        }
    }

    private ExecutorService getWorkerPool(ContentExtractor.ExtractorType type) {
        return workerPools.computeIfAbsent(type, t -> {
            int threads = threadCounts.getOrDefault(t, config.defaultStructuredThreads());
            return createWorkerPool(t.name().toLowerCase(), threads);
        });
    }

    private ExecutorService createWorkerPool(String name, int threads) {
        return new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "extraction-" + name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private Map<StructuredItem.ItemType, List<StructuredItem>> groupItemsByType(
            List<StructuredItem> items) {
        Map<StructuredItem.ItemType, List<StructuredItem>> grouped = new EnumMap<>(StructuredItem.ItemType.class);
        for (StructuredItem item : items) {
            grouped.computeIfAbsent(item.getType(), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /**
     * Cancels all running extractions.
     */
    public void cancel() {
        cancelled.set(true);
        for (Map.Entry<ContentExtractor.ExtractorType, List<ContentExtractor>> entry : extractorsByType.entrySet()) {
            for (ContentExtractor extractor : entry.getValue()) {
                extractor.reset();
            }
        }
    }

    /**
     * Returns true if the orchestrator is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the registered extractors.
     */
    public Map<ContentExtractor.ExtractorType, List<ContentExtractor>> getExtractors() {
        return Collections.unmodifiableMap(extractorsByType);
    }

    /**
     * Sets the thread count for a specific extractor type.
     */
    public void setThreadCount(ContentExtractor.ExtractorType type, int threads) {
        threadCounts.put(type, Math.max(1, threads));

        // Recreate pool if it exists
        ExecutorService oldPool = workerPools.remove(type);
        if (oldPool != null) {
            shutdownPool(oldPool, type.name());
        }
    }

    @Override
    public void close() {
        cancel();
        for (Map.Entry<ContentExtractor.ExtractorType, ExecutorService> entry : workerPools.entrySet()) {
            shutdownPool(entry.getValue(), entry.getKey().name());
        }
        workerPools.clear();
        logger.debug("ConcurrentExtractionOrchestrator closed");
    }

    private void shutdownPool(ExecutorService pool, String name) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                logger.warn("Force shutdown worker pool: {}", name);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Progress report for extraction operations.
     */
    public record ExtractionProgressReport(
            String extractorName,
            ContentExtractor.ExtractorType extractorType,
            String phase,
            int percentComplete,
            int itemsProcessed,
            int totalItems,
            String message
    ) {}

    /**
     * Combined result from all extractors.
     */
    public record CombinedExtractionResult(
            List<RetrievedDoc> chunks,
            List<StructuredItem> structuredItems,
            Map<StructuredItem.ItemType, List<StructuredItem>> itemsByType,
            List<String> extractorsUsed,
            int documentsProcessed,
            long totalTimeMs,
            List<String> errors
    ) {
        public static CombinedExtractionResult empty() {
            return new CombinedExtractionResult(
                    List.of(), List.of(), Map.of(), List.of(), 0, 0, List.of()
            );
        }

        public int getChunkCount() {
            return chunks.size();
        }

        public int getTotalStructuredItems() {
            return structuredItems.size();
        }

        public List<StructuredItem> getEntities() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.ENTITY, List.of());
        }

        public List<StructuredItem> getRelationships() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.RELATIONSHIP, List.of());
        }

        public List<StructuredItem> getTables() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.TABLE, List.of());
        }

        public List<StructuredItem> getConcepts() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.CONCEPT, List.of());
        }

        public List<StructuredItem> getFacts() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.FACT, List.of());
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public double getChunksPerSecond() {
            return totalTimeMs > 0 ? (chunks.size() * 1000.0) / totalTimeMs : 0;
        }
    }
}
