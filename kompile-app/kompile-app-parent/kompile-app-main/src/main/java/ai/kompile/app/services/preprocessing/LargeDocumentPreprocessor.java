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

package ai.kompile.app.services.preprocessing;

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.ingest.service.TextConversionService;
import ai.kompile.app.services.pipeline.ProcessingState;
import ai.kompile.app.services.pipeline.ProcessingStateRepository;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.LargeDocumentInfo;
import ai.kompile.core.loaders.StreamingDocumentLoader;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Handles preprocessing of large documents with streaming page-by-page loading.
 *
 * <p>Large documents (determined by size or page count) are processed differently from
 * small documents to avoid memory pressure. Instead of loading the entire document into
 * memory, pages/sections are streamed one at a time and chunks are pushed directly into
 * the embedding pipeline's queue.</p>
 *
 * <h2>Key Responsibilities</h2>
 * <ul>
 *   <li>Detect if a document qualifies as "large" based on configurable thresholds</li>
 *   <li>Stream pages one at a time using {@link StreamingDocumentLoader}</li>
 *   <li>Convert, chunk, and queue each page independently</li>
 *   <li>Track page-level progress for resume support</li>
 *   <li>Preserve source document metadata in all chunks</li>
 * </ul>
 *
 * <h2>Memory Efficiency</h2>
 * <p>By streaming pages directly into the chunk queue, we ensure:</p>
 * <ul>
 *   <li>Only one page in memory at a time</li>
 *   <li>Chunks flow immediately to embedding without waiting for all pages</li>
 *   <li>Backpressure is handled naturally (queue blocks when full)</li>
 * </ul>
 *
 * <h2>Resume Support</h2>
 * <p>Processing state is checkpointed after each page. If processing is interrupted,
 * it can resume from the last successfully processed page.</p>
 */
@Service
public class LargeDocumentPreprocessor {

    private static final Logger logger = LoggerFactory.getLogger(LargeDocumentPreprocessor.class);

    private final List<StreamingDocumentLoader> streamingLoaders;
    private final ProcessingStateRepository stateRepository;
    private final TextConversionService textConversionService;
    private final IngestConfiguration config;
    private final List<TextChunker> textChunkers;

    // Chunker is injected separately because it may be selected per-request
    private TextChunker defaultChunker;

    @Autowired
    public LargeDocumentPreprocessor(
            @Autowired(required = false) List<StreamingDocumentLoader> streamingLoaders,
            ProcessingStateRepository stateRepository,
            TextConversionService textConversionService,
            IngestConfiguration config,
            @Autowired(required = false) List<TextChunker> textChunkers) {
        this.streamingLoaders = streamingLoaders != null ? streamingLoaders : new ArrayList<>();
        this.stateRepository = stateRepository;
        this.textConversionService = textConversionService;
        this.config = config;
        this.textChunkers = textChunkers != null ? textChunkers : new ArrayList<>();

        // Select best default chunker from available chunkers
        this.defaultChunker = selectBestChunker();

        logger.info("LargeDocumentPreprocessor initialized with {} streaming loaders, {} chunkers",
                this.streamingLoaders.size(), this.textChunkers.size());
        for (StreamingDocumentLoader loader : this.streamingLoaders) {
            logger.info("  - Loader: {}", loader.getName());
        }
        if (this.defaultChunker != null) {
            logger.info("  - Default chunker: {}", this.defaultChunker.getName());
        }
    }

    /**
     * Selects the best chunker from available options.
     * Prefers recursive/sentence chunkers over others.
     */
    private TextChunker selectBestChunker() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            return null;
        }

        // Filter out NoOp chunkers
        List<TextChunker> realChunkers = textChunkers.stream()
                .filter(c -> !isNoOpChunker(c))
                .toList();

        if (realChunkers.isEmpty()) {
            return null;
        }

        // Prefer recursive or sentence chunkers
        for (String preferred : List.of("recursive", "opennlp", "sentence")) {
            for (TextChunker chunker : realChunkers) {
                String name = chunker.getName().toLowerCase();
                if (name.contains(preferred)) {
                    return chunker;
                }
            }
        }

        return realChunkers.get(0);
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null) return true;
        String className = chunker.getClass().getSimpleName().toLowerCase();
        String chunkerName = chunker.getName().toLowerCase();
        return className.contains("noop") || className.contains("dummy") ||
               chunkerName.contains("noop") || chunkerName.contains("no-op");
    }

    /**
     * Checks if a document qualifies for large document processing.
     *
     * <p>A document is considered "large" if EITHER:</p>
     * <ul>
     *   <li>File size exceeds the configured threshold (default: 10MB)</li>
     *   <li>Page count exceeds the configured threshold (default: 50 pages)</li>
     * </ul>
     *
     * @param filePath Path to the document file
     * @return true if document should use streaming processing
     */
    public boolean isLargeDocument(Path filePath) {
        try {
            // Quick size check first (fast)
            long fileSize = Files.size(filePath);
            if (fileSize > config.getLargeDocumentSizeThreshold()) {
                logger.debug("Document {} is large by size: {} bytes > {} threshold",
                            filePath.getFileName(), fileSize, config.getLargeDocumentSizeThreshold());
                return true;
            }

            // Check page count via streaming loader (requires opening file)
            DocumentSourceDescriptor descriptor = createDescriptor(filePath);
            for (StreamingDocumentLoader loader : streamingLoaders) {
                if (loader.supportsStreaming(descriptor)) {
                    try {
                        LargeDocumentInfo info = loader.getDocumentInfo(descriptor);
                        if (info.totalPages() > config.getLargeDocumentPageThreshold()) {
                            logger.debug("Document {} is large by pages: {} pages > {} threshold",
                                        filePath.getFileName(), info.totalPages(), config.getLargeDocumentPageThreshold());
                            return true;
                        }
                    } catch (Exception e) {
                        logger.warn("Could not get document info from {}: {}",
                                   loader.getName(), e.getMessage());
                    }
                }
            }

            return false;
        } catch (IOException e) {
            logger.warn("Could not check if document is large: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if streaming is supported for a document.
     *
     * @param filePath Path to the document
     * @return true if a streaming loader is available for this document type
     */
    public boolean supportsStreaming(Path filePath) {
        DocumentSourceDescriptor descriptor = createDescriptor(filePath);
        return streamingLoaders.stream()
            .anyMatch(loader -> loader.supportsStreaming(descriptor));
    }

    /**
     * Processes a large document by streaming pages directly into the pipeline's chunk queue.
     *
     * <p>Each page is: loaded → converted → chunked → queued (blocking if queue full)</p>
     *
     * @param source           Document source descriptor
     * @param chunkQueue       The pipeline's chunk queue (direct access)
     * @param chunker          Chunker to use (or null to use default)
     * @param chunkingOptions  Options for chunking
     * @param progressCallback Callback for progress updates
     * @return Final processing state
     * @throws Exception if processing fails
     */
    public ProcessingState processLargeDocument(
            DocumentSourceDescriptor source,
            BlockingQueue<RetrievedDoc> chunkQueue,
            TextChunker chunker,
            Map<String, Object> chunkingOptions,
            Consumer<LargeDocumentProgress> progressCallback) throws Exception {

        // Find appropriate streaming loader
        StreamingDocumentLoader loader = findStreamingLoader(source);
        if (loader == null) {
            throw new IllegalArgumentException("No streaming loader available for: " + source.getPathOrUrl());
        }

        // Use provided chunker or default
        TextChunker actualChunker = chunker != null ? chunker : defaultChunker;
        if (actualChunker == null) {
            throw new IllegalStateException("No chunker available for large document processing");
        }

        // Get document info
        LargeDocumentInfo docInfo = loader.getDocumentInfo(source);
        String taskId = generateTaskId(source);

        logger.info("Starting large document processing: {} ({} pages, {}MB)",
                   source.getOriginalFileName(), docInfo.totalPages(), docInfo.fileSizeMB());

        // Check for resumable state
        ProcessingState state = stateRepository.findResumable(source.getSourceId())
            .orElseGet(() -> ProcessingState.initial(taskId, source, docInfo));

        int startPage = state.lastProcessedPage() + 1;
        AtomicInteger chunksCreated = new AtomicInteger(state.chunksCreated());

        if (startPage > 1) {
            logger.info("Resuming from page {} (previously created {} chunks)",
                       startPage, chunksCreated.get());
        }

        // Update state to loading
        state = state.withPhase(ProcessingState.ProcessingPhase.LOADING);
        stateRepository.save(state);

        // Stream pages
        Iterator<Document> pages = loader.streamPages(source, progress -> {
            if (progressCallback != null) {
                progressCallback.accept(LargeDocumentProgress.of(
                    progress.currentPage(),
                    progress.totalPages(),
                    chunksCreated.get(),
                    "loading"
                ));
            }
        });

        int currentPage = 0;
        long startTime = System.currentTimeMillis();

        try {
            while (pages.hasNext() && !Thread.currentThread().isInterrupted()) {
                Document page = pages.next();
                currentPage++;

                // Skip already-processed pages (resume support)
                if (currentPage < startPage) {
                    continue;
                }

                // Convert text (normalize, clean)
                TextConversionService.ConversionResult conversionResult =
                    textConversionService.convert(List.of(page));
                Document convertedPage = conversionResult.documents().isEmpty() ?
                    page : conversionResult.documents().get(0);

                // Skip pages with no text content (e.g., image-only pages, scanned PDFs)
                String pageText = convertedPage.getText();
                if (pageText == null || pageText.trim().isEmpty()) {
                    logger.debug("Skipping page {} - no text content (possibly image-only)", currentPage);
                    // Update state without creating chunks
                    state = state.withPage(currentPage);
                    stateRepository.save(state);
                    continue;
                }

                // Create RetrievedDoc for chunking
                RetrievedDoc pageDoc = createPageDoc(convertedPage, source, currentPage, docInfo);

                // Chunk the page
                state = state.withPhase(ProcessingState.ProcessingPhase.CHUNKING);
                List<RetrievedDoc> pageChunks = actualChunker.chunk(pageDoc, chunkingOptions);

                // Stream chunks directly to queue (blocks if full - backpressure)
                for (RetrievedDoc chunk : pageChunks) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Processing cancelled");
                    }
                    chunkQueue.put(chunk);
                    chunksCreated.incrementAndGet();
                }

                // Update and persist state for resume capability
                state = state.withPage(currentPage).withChunks(chunksCreated.get());
                stateRepository.save(state);

                // Report progress
                if (progressCallback != null) {
                    progressCallback.accept(LargeDocumentProgress.of(
                        currentPage,
                        docInfo.totalPages(),
                        chunksCreated.get(),
                        "chunking"
                    ));
                }

                // Log periodic progress
                if (currentPage % 10 == 0 || currentPage == docInfo.totalPages()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double pagesPerSec = elapsed > 0 ? (currentPage * 1000.0 / elapsed) : 0;
                    logger.info("Processed page {}/{} ({} chunks, {} pages/sec)",
                               currentPage, docInfo.totalPages(), chunksCreated.get(), String.format("%.1f", pagesPerSec));
                }
            }

            // Check if cancelled
            if (Thread.currentThread().isInterrupted()) {
                state = state.cancelled();
                stateRepository.save(state);
                logger.info("Large document processing cancelled at page {}", currentPage);
                return state;
            }

            // Completed successfully
            state = state.completed();
            stateRepository.save(state);

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Completed large document processing: {} pages, {} chunks in {}ms",
                       currentPage, chunksCreated.get(), totalTime);

            return state;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = state.cancelled();
            stateRepository.save(state);
            throw e;
        } catch (Exception e) {
            state = state.failed(e.getMessage());
            stateRepository.save(state);
            logger.error("Large document processing failed at page {}: {}",
                        currentPage, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Gets document info without loading full content.
     */
    public Optional<LargeDocumentInfo> getDocumentInfo(Path filePath) {
        DocumentSourceDescriptor descriptor = createDescriptor(filePath);
        for (StreamingDocumentLoader loader : streamingLoaders) {
            if (loader.supportsStreaming(descriptor)) {
                try {
                    return Optional.of(loader.getDocumentInfo(descriptor));
                } catch (Exception e) {
                    logger.warn("Could not get document info: {}", e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    // ========== Helper Methods ==========

    private StreamingDocumentLoader findStreamingLoader(DocumentSourceDescriptor source) {
        return streamingLoaders.stream()
            .filter(loader -> loader.supportsStreaming(source))
            .findFirst()
            .orElse(null);
    }

    private DocumentSourceDescriptor createDescriptor(Path filePath) {
        String fileName = filePath.getFileName().toString();
        return DocumentSourceDescriptor.builder()
            .type(DocumentSourceDescriptor.SourceType.FILE)
            .pathOrUrl(filePath.toAbsolutePath().toString())
            .originalFileName(fileName)
            .sourceId(fileName)
            .build();
    }

    private String generateTaskId(DocumentSourceDescriptor source) {
        return "large-" + source.getOriginalFileName() + "-" + System.currentTimeMillis();
    }

    private RetrievedDoc createPageDoc(Document page, DocumentSourceDescriptor source,
                                       int pageNumber, LargeDocumentInfo docInfo) {
        Map<String, Object> metadata = new HashMap<>();

        // Copy page metadata
        if (page.getMetadata() != null) {
            metadata.putAll(page.getMetadata());
        }

        // Add source attribution
        metadata.put("source.id", source.getSourceId());
        metadata.put("source.fileName", source.getOriginalFileName());
        metadata.put("source.path", source.getPathOrUrl());
        metadata.put("source.type", docInfo.documentType());

        // Add page tracking (for resume and retrieval attribution)
        metadata.put("page.number", pageNumber);
        metadata.put("page.total", docInfo.totalPages());
        metadata.put("processing.streaming", true);
        metadata.put("processing.large", true);

        String id = source.getSourceId() + "-page-" + pageNumber;

        return RetrievedDoc.builder()
            .id(id)
            .text(page.getText() != null ? page.getText() : "")
            .metadata(metadata)
            .build();
    }

    /**
     * Progress information for large document processing.
     */
    public record LargeDocumentProgress(
        int currentPage,
        int totalPages,
        int chunksCreated,
        String phase
    ) {
        public static LargeDocumentProgress of(int currentPage, int totalPages,
                                               int chunksCreated, String phase) {
            return new LargeDocumentProgress(currentPage, totalPages, chunksCreated, phase);
        }

        public int progressPercent() {
            return totalPages > 0 ? Math.min(100, (currentPage * 100) / totalPages) : 0;
        }

        public String progressString() {
            return String.format("Page %d/%d (%d%%), %d chunks created",
                                currentPage, totalPages, progressPercent(), chunksCreated);
        }
    }
}
