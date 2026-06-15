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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.core.extraction.*;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Content extraction stage that runs multiple extractors concurrently.
 *
 * <p>This stage replaces the separate chunking and entity extraction stages,
 * running all extraction types in parallel for better throughput:</p>
 *
 * <ul>
 *   <li><b>Chunking</b> - Breaking documents into smaller pieces for embedding</li>
 *   <li><b>Entity Extraction</b> - Identifying named entities</li>
 *   <li><b>Relationship Extraction</b> - Finding relationships between entities</li>
 *   <li><b>Concept Extraction</b> - Identifying key concepts and themes</li>
 *   <li><b>Table Extraction</b> - Extracting structured tables</li>
 *   <li><b>Fact Extraction</b> - Extracting fact statements</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * TokenizationOutput
 *       │
 *       ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │             ContentExtractionStage                          │
 * │  ┌──────────────────┐  ┌──────────────────────────────────┐ │
 * │  │ Chunking Workers │  │ Structured Output Workers        │ │
 * │  │   (4-8 threads)  │  │ (entities, concepts, facts, etc.)│ │
 * │  └────────┬─────────┘  └───────────────┬──────────────────┘ │
 * │           └───────────────┬────────────┘                    │
 * │                           ▼                                  │
 * │                    Result Merger                             │
 * └─────────────────────────────────────────────────────────────┘
 *       │
 *       ▼
 * ContentExtractionOutput
 *   (chunks + structured items)
 * </pre>
 *
 * <p>Input: {@link TokenizationStage.TokenizationOutput}</p>
 * <p>Output: {@link ContentExtractionOutput}</p>
 */
public class ContentExtractionStage implements PipelineStage<TokenizationStage.TokenizationOutput, ContentExtractionStage.ContentExtractionOutput> {

    private static final Logger logger = LoggerFactory.getLogger(ContentExtractionStage.class);

    private final ConcurrentExtractionOrchestrator orchestrator;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Audit logging service (optional)
    private IngestEventService eventService;

    // Configuration
    private int chunkingThreads = 4;
    private int structuredExtractionThreads = 2;
    private boolean enableStructuredExtraction = true;

    // Progress callback
    private Consumer<ExtractionProgressUpdate> progressCallback;

    // Progress tracking for audit logging
    private final AtomicInteger currentChunks = new AtomicInteger(0);
    private final AtomicInteger currentStructuredItems = new AtomicInteger(0);

    /**
     * Creates a content extraction stage with the given chunker.
     * Only chunking will be enabled initially.
     */
    public ContentExtractionStage(TextChunker chunker) {
        this(chunker, List.of());
    }

    /**
     * Creates a content extraction stage with chunker and additional extractors.
     */
    public ContentExtractionStage(TextChunker chunker, List<ContentExtractor> additionalExtractors) {
        this.orchestrator = new ConcurrentExtractionOrchestrator(
                ConcurrentExtractionOrchestrator.OrchestratorConfig.builder()
                        .chunkingThreads(chunkingThreads)
                        .entityExtractionThreads(structuredExtractionThreads)
                        .conceptExtractionThreads(structuredExtractionThreads)
                        .factExtractionThreads(structuredExtractionThreads)
                        .tableExtractionThreads(1)
                        .build()
        );

        // Register chunking extractor
        if (chunker != null) {
            orchestrator.registerExtractor(new ChunkingExtractor(chunker));
        }

        // Register additional extractors
        for (ContentExtractor extractor : additionalExtractors) {
            orchestrator.registerExtractor(extractor);
        }
    }

    /**
     * Creates a content extraction stage with a pre-configured orchestrator.
     */
    public ContentExtractionStage(ConcurrentExtractionOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Sets the event service for audit logging.
     * When set, extraction events will be logged to the audit system.
     */
    public void setEventService(IngestEventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Returns the event service if set.
     */
    public IngestEventService getEventService() {
        return eventService;
    }

    @Override
    public String getName() {
        return "content-extraction";
    }

    /**
     * Registers an additional extractor with this stage.
     */
    public void registerExtractor(ContentExtractor extractor) {
        orchestrator.registerExtractor(extractor);
    }

    /**
     * Unregisters an extractor from this stage.
     */
    public void unregisterExtractor(ContentExtractor extractor) {
        orchestrator.unregisterExtractor(extractor);
    }

    /**
     * Sets the progress callback for extraction updates.
     * Note: The actual progress callback registration happens in the process method
     * to integrate with audit logging.
     */
    public void setProgressCallback(Consumer<ExtractionProgressUpdate> callback) {
        this.progressCallback = callback;
    }

    @Override
    public ContentExtractionOutput process(TokenizationStage.TokenizationOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Content extraction stage cancelled");
        }

        long startNanos = System.nanoTime();
        int documentsProcessed = 0;
        long totalBytes = 0;
        String taskId = input.taskId();
        String fileName = extractFileName(input);

        // Reset progress counters
        currentChunks.set(0);
        currentStructuredItems.set(0);

        try {
            // Convert TokenizedDocument to RetrievedDoc
            List<RetrievedDoc> documents = new ArrayList<>();
            for (TokenizationStage.TokenizedDocument tokenizedDoc : input.documents()) {
                if (cancelled.get()) {
                    throw new InterruptedException("Content extraction cancelled during conversion");
                }

                String text = tokenizedDoc.getText();
                if (text != null) {
                    totalBytes += text.length() * 2L;
                }

                RetrievedDoc doc = RetrievedDoc.builder()
                        .id(tokenizedDoc.getId())
                        .text(text)
                        .metadata(tokenizedDoc.getMetadata())
                        .build();
                documents.add(doc);
            }

            // Get list of extractors for audit logging
            List<String> extractorNames = getExtractorNames();

            // Log extraction started
            logExtractionStarted(taskId, fileName, documents.size(), extractorNames);

            // Build extraction options
            Map<String, Object> options = buildExtractionOptions();

            // Set up progress callback for audit logging
            orchestrator.setProgressCallback(report -> {
                // Update progress counters
                if (report.extractorType() == ContentExtractor.ExtractorType.CHUNKING) {
                    currentChunks.set(report.itemsProcessed());
                } else {
                    currentStructuredItems.addAndGet(1);
                }

                // Log progress to audit system (periodically)
                if (eventService != null && taskId != null && report.itemsProcessed() % 10 == 0) {
                    try {
                        eventService.logExtractionProgress(
                                taskId, fileName,
                                report.extractorName(),
                                report.itemsProcessed(),
                                report.totalItems(),
                                currentChunks.get(),
                                currentStructuredItems.get(),
                                report.message()
                        );
                    } catch (Exception e) {
                        logger.warn("Failed to log extraction progress: {}", e.getMessage());
                    }
                }

                // Forward to external progress callback if set
                if (progressCallback != null) {
                    progressCallback.accept(new ExtractionProgressUpdate(
                            report.extractorName(),
                            report.extractorType().name(),
                            report.phase(),
                            report.percentComplete(),
                            report.itemsProcessed(),
                            report.totalItems(),
                            report.message()
                    ));
                }
            });

            // Run concurrent extraction
            ConcurrentExtractionOrchestrator.CombinedExtractionResult result =
                    orchestrator.extractAll(documents, options);

            documentsProcessed = result.documentsProcessed();

            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = elapsedNanos / 1_000_000;
            metrics.recordSuccess(elapsedNanos, totalBytes, result.getChunkCount() + result.getTotalStructuredItems());

            // Log extraction completed with detailed summary
            logExtractionCompleted(taskId, fileName, result, elapsedMs);

            // Log any errors from extractors
            if (result.hasErrors()) {
                for (String error : result.errors()) {
                    logExtractorError(taskId, fileName, "extraction", error, null);
                }
            }

            logger.debug("Content extraction: {} documents -> {} chunks, {} structured items in {}ms",
                    documentsProcessed, result.getChunkCount(), result.getTotalStructuredItems(),
                    elapsedMs);

            return new ContentExtractionOutput(
                    result.chunks(),
                    result.structuredItems(),
                    result.itemsByType(),
                    documentsProcessed,
                    result.getChunkCount(),
                    result.getTotalStructuredItems(),
                    elapsedMs,
                    result.extractorsUsed(),
                    input.loaderUsed(),
                    taskId,
                    input.metadata(),
                    result.errors()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            // Log the error
            logExtractorError(taskId, fileName, "content-extraction", e.getMessage(), e);
            throw e;
        }
    }

    private String extractFileName(TokenizationStage.TokenizationOutput input) {
        // Try to extract filename from metadata
        if (input.metadata() != null && input.metadata().containsKey("fileName")) {
            return String.valueOf(input.metadata().get("fileName"));
        }
        if (input.metadata() != null && input.metadata().containsKey("source")) {
            return String.valueOf(input.metadata().get("source"));
        }
        // Fall back to task ID or a generic name
        return input.taskId() != null ? input.taskId() : "unknown";
    }

    private List<String> getExtractorNames() {
        List<String> names = new ArrayList<>();
        for (List<ContentExtractor> extractors : orchestrator.getExtractors().values()) {
            for (ContentExtractor extractor : extractors) {
                if (extractor.isEnabled()) {
                    names.add(extractor.getName());
                }
            }
        }
        return names;
    }

    private void logExtractionStarted(String taskId, String fileName, int documentCount, List<String> extractors) {
        if (eventService == null || taskId == null) return;

        try {
            // Build details JSON
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("documentCount", documentCount);
            details.put("extractors", extractors);
            details.put("chunkingThreads", chunkingThreads);
            details.put("structuredExtractionThreads", structuredExtractionThreads);
            details.put("enableStructuredExtraction", enableStructuredExtraction);

            String detailsJson = objectMapper.writeValueAsString(details);
            eventService.logExtractionStarted(taskId, fileName, documentCount, extractors, detailsJson);
        } catch (Exception e) {
            logger.warn("Failed to log extraction started: {}", e.getMessage());
        }
    }

    private void logExtractionCompleted(String taskId, String fileName,
                                         ConcurrentExtractionOrchestrator.CombinedExtractionResult result,
                                         long elapsedMs) {
        if (eventService == null || taskId == null) return;

        try {
            // Count items by type
            int entities = result.getEntities().size();
            int relationships = result.getRelationships().size();
            int concepts = result.getConcepts().size();
            int facts = result.getFacts().size();
            int tables = result.getTables().size();

            // Build detailed summary
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("documentsProcessed", result.documentsProcessed());
            details.put("chunksCreated", result.getChunkCount());
            details.put("entitiesExtracted", entities);
            details.put("relationshipsExtracted", relationships);
            details.put("conceptsExtracted", concepts);
            details.put("factsExtracted", facts);
            details.put("tablesExtracted", tables);
            details.put("totalStructuredItems", result.getTotalStructuredItems());
            details.put("extractorsUsed", result.extractorsUsed());
            details.put("errors", result.errors());
            details.put("durationMs", elapsedMs);
            details.put("chunksPerSecond", result.getChunksPerSecond());

            String detailsJson = objectMapper.writeValueAsString(details);

            eventService.logExtractionCompleted(
                    taskId, fileName,
                    result.documentsProcessed(),
                    result.getChunkCount(),
                    entities, relationships, concepts, facts, tables,
                    result.extractorsUsed(),
                    detailsJson
            );
        } catch (Exception e) {
            logger.warn("Failed to log extraction completed: {}", e.getMessage());
        }
    }

    private void logExtractorError(String taskId, String fileName, String extractorName,
                                    String errorMessage, Throwable exception) {
        if (eventService == null || taskId == null) return;

        try {
            eventService.logExtractorError(taskId, fileName, extractorName, errorMessage, exception);
        } catch (Exception e) {
            logger.warn("Failed to log extractor error: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildExtractionOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("enableStructuredExtraction", enableStructuredExtraction);
        return options;
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("chunkingThreads")) {
            this.chunkingThreads = ((Number) options.get("chunkingThreads")).intValue();
            orchestrator.setThreadCount(ContentExtractor.ExtractorType.CHUNKING, chunkingThreads);
        }
        if (options.containsKey("structuredExtractionThreads")) {
            this.structuredExtractionThreads = ((Number) options.get("structuredExtractionThreads")).intValue();
            orchestrator.setThreadCount(ContentExtractor.ExtractorType.ENTITY, structuredExtractionThreads);
            orchestrator.setThreadCount(ContentExtractor.ExtractorType.CONCEPT, structuredExtractionThreads);
            orchestrator.setThreadCount(ContentExtractor.ExtractorType.FACT, structuredExtractionThreads);
        }
        if (options.containsKey("enableStructuredExtraction")) {
            this.enableStructuredExtraction = (Boolean) options.get("enableStructuredExtraction");
        }
    }

    @Override
    public StageMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        orchestrator.cancel();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void reset() {
        cancelled.set(false);
        metrics.reset();
    }

    /**
     * Returns the underlying orchestrator.
     */
    public ConcurrentExtractionOrchestrator getOrchestrator() {
        return orchestrator;
    }

    /**
     * Progress update for extraction operations.
     */
    public record ExtractionProgressUpdate(
            String extractorName,
            String extractorType,
            String phase,
            int percentComplete,
            int itemsProcessed,
            int totalItems,
            String message
    ) {}

    /**
     * Output from the content extraction stage.
     */
    public record ContentExtractionOutput(
            List<RetrievedDoc> chunks,
            List<StructuredItem> structuredItems,
            Map<StructuredItem.ItemType, List<StructuredItem>> itemsByType,
            int documentsProcessed,
            int chunkCount,
            int structuredItemCount,
            long extractionTimeMs,
            List<String> extractorsUsed,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata,
            List<String> errors
    ) {
        public double averageChunksPerDocument() {
            return documentsProcessed > 0 ? (double) chunkCount / documentsProcessed : 0;
        }

        public boolean hasStructuredOutput() {
            return structuredItemCount > 0;
        }

        public List<StructuredItem> getEntities() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.ENTITY, List.of());
        }

        public List<StructuredItem> getRelationships() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.RELATIONSHIP, List.of());
        }

        public List<StructuredItem> getConcepts() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.CONCEPT, List.of());
        }

        public List<StructuredItem> getTables() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.TABLE, List.of());
        }

        public List<StructuredItem> getFacts() {
            return itemsByType.getOrDefault(StructuredItem.ItemType.FACT, List.of());
        }

        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }
}
