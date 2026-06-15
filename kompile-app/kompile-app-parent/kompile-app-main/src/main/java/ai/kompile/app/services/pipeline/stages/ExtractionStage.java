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

import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.source.SourceAttributionHelper;
import ai.kompile.core.source.SourceDocumentStorageService;
import ai.kompile.core.source.SourceMetadataConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extraction stage: Loads documents from files using the appropriate DocumentLoader.
 *
 * <p>This stage handles:</p>
 * <ul>
 *   <li>Auto-detection of file type and loader selection</li>
 *   <li>Explicit loader specification via configuration</li>
 *   <li>Error handling with fallback options</li>
 *   <li>Metrics collection for load times and byte throughput</li>
 * </ul>
 *
 * <p>Input: {@link ExtractionInput} containing file path and metadata</p>
 * <p>Output: {@link ExtractionOutput} containing loaded documents</p>
 */
public class ExtractionStage implements PipelineStage<ExtractionStage.ExtractionInput, ExtractionStage.ExtractionOutput> {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionStage.class);

    private final List<DocumentLoader> availableLoaders;
    private final SourceDocumentStorageService sourceStorageService;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private String preferredLoaderName;
    private boolean autoDetectLoader = true;

    public ExtractionStage(List<DocumentLoader> availableLoaders) {
        this(availableLoaders, null);
    }

    public ExtractionStage(List<DocumentLoader> availableLoaders,
                          SourceDocumentStorageService sourceStorageService) {
        this.availableLoaders = availableLoaders;
        this.sourceStorageService = sourceStorageService;
    }

    @Override
    public String getName() {
        return "extraction";
    }

    @Override
    public ExtractionOutput process(ExtractionInput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Extraction stage cancelled");
        }

        long startNanos = System.nanoTime();
        long fileSize = 0;

        try {
            Path filePath = input.filePath();
            String fileName = filePath.getFileName().toString();

            // Get file size for metrics
            try {
                fileSize = filePath.toFile().length();
            } catch (Exception e) {
                logger.debug("Could not determine file size for {}", fileName);
            }

            // Find appropriate loader
            DocumentLoader loader = findLoader(filePath, input.preferredLoader());
            if (loader == null) {
                throw new RuntimeException("No suitable loader found for file: " + fileName);
            }

            logger.debug("Using loader '{}' for file '{}'", loader.getName(), fileName);

            // Create source descriptor with file path and metadata
            DocumentSourceDescriptor.DocumentSourceDescriptorBuilder descriptorBuilder =
                    DocumentSourceDescriptor.builder()
                            .type(DocumentSourceDescriptor.SourceType.FILE)
                            .pathOrUrl(filePath.toAbsolutePath().toString())
                            .originalFileName(fileName)
                            .sourceId(input.sourceId() != null ? input.sourceId() : "file:" + filePath.toAbsolutePath())
                            .sizeBytes(fileSize)
                            .metadata(input.metadata() != null ? new HashMap<>(input.metadata()) : new HashMap<>());

            // Store original document if storage service is available
            if (sourceStorageService != null && sourceStorageService.isEnabled()) {
                Optional<SourceDocumentStorageService.StorageResult> storageResult =
                        sourceStorageService.storeDocument(filePath);
                if (storageResult.isPresent()) {
                    SourceDocumentStorageService.StorageResult result = storageResult.get();
                    descriptorBuilder.storedCopyPath(result.getStoredPathString());
                    descriptorBuilder.checksum(result.checksum());
                    logger.debug("Stored document copy: {} -> {} (checksum: {})",
                            fileName, result.storedPath(), result.checksum().substring(0, 16) + "...");

                    // Store metadata alongside the document (including source_url if present)
                    if (input.metadata() != null && !input.metadata().isEmpty()) {
                        sourceStorageService.storeMetadata(result.checksum(), input.metadata());
                    }
                }
            }

            DocumentSourceDescriptor sourceDescriptor = descriptorBuilder.build();

            // Load documents
            List<Document> documents = loader.load(sourceDescriptor);

            // Add source attribution metadata to all loaded documents
            documents = addSourceAttribution(documents, sourceDescriptor, loader.getName());

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, fileSize, documents.size());

            logger.debug("Extracted {} documents from '{}' in {}ms using {} (source_id: {})",
                    documents.size(), fileName, elapsedNanos / 1_000_000, loader.getName(),
                    sourceDescriptor.getEffectiveSourceId());

            return new ExtractionOutput(
                    documents,
                    loader.getName(),
                    fileSize,
                    elapsedNanos / 1_000_000,
                    input.taskId(),
                    input.metadata()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    private DocumentLoader findLoader(Path filePath, String preferredLoader) {
        DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .build();

        // Try preferred loader first
        String loaderToFind = preferredLoader != null ? preferredLoader : this.preferredLoaderName;
        if (loaderToFind != null && !loaderToFind.isEmpty()) {
            DocumentLoader specified = availableLoaders.stream()
                    .filter(loader -> loaderToFind.equals(loader.getName()))
                    .findFirst()
                    .orElse(null);
            if (specified != null && specified.supports(tempDescriptor)) {
                return specified;
            }
            logger.warn("Preferred loader '{}' not found or doesn't support file, falling back to auto-detect",
                    loaderToFind);
        }

        // Auto-detect based on file type
        if (autoDetectLoader) {
            return availableLoaders.stream()
                    .filter(loader -> loader.supports(tempDescriptor))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("preferredLoader")) {
            this.preferredLoaderName = (String) options.get("preferredLoader");
        }
        if (options.containsKey("autoDetectLoader")) {
            this.autoDetectLoader = (Boolean) options.get("autoDetectLoader");
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
    }

    /**
     * Returns list of available loader names for UI display.
     */
    public List<String> getAvailableLoaderNames() {
        return availableLoaders.stream()
                .map(DocumentLoader::getName)
                .toList();
    }

    /**
     * Input for the extraction stage.
     */
    public record ExtractionInput(
            Path filePath,
            String preferredLoader,
            String taskId,
            String sourceId,
            Map<String, Object> metadata
    ) {
        public static ExtractionInput of(Path filePath) {
            return new ExtractionInput(filePath, null, null, null, null);
        }

        public static ExtractionInput of(Path filePath, String taskId) {
            return new ExtractionInput(filePath, null, taskId, null, null);
        }
    }

    /**
     * Output from the extraction stage.
     */
    public record ExtractionOutput(
            List<Document> documents,
            String loaderUsed,
            long fileSizeBytes,
            long extractionTimeMs,
            String taskId,
            Map<String, Object> metadata
    ) {
        public int documentCount() {
            return documents != null ? documents.size() : 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE ATTRIBUTION HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds source attribution metadata to all loaded documents.
     *
     * This ensures that when documents are indexed (both keyword and vector),
     * they carry source information that allows them to be traced back to
     * their original source file.
     *
     * @param documents       The loaded documents
     * @param sourceDescriptor The source descriptor with path and storage info
     * @param loaderName      Name of the loader that was used
     * @return Documents with source attribution metadata added
     */
    private List<Document> addSourceAttribution(
            List<Document> documents,
            DocumentSourceDescriptor sourceDescriptor,
            String loaderName) {

        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        // Build base source metadata from descriptor
        Map<String, Object> sourceMetadata = sourceDescriptor.toSourceMetadata();
        sourceMetadata.put(SourceMetadataConstants.LOADER_NAME, loaderName);
        sourceMetadata.put(SourceMetadataConstants.INDEXED_AT, Instant.now().toString());

        int totalChunks = documents.size();
        List<Document> enrichedDocuments = new ArrayList<>(documents.size());

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);

            // Create new metadata map with source attribution
            Map<String, Object> enrichedMetadata = new HashMap<>();

            // Copy source metadata first
            enrichedMetadata.putAll(sourceMetadata);

            // Add chunk info
            enrichedMetadata.put(SourceMetadataConstants.CHUNK_INDEX, i);
            enrichedMetadata.put(SourceMetadataConstants.TOTAL_CHUNKS, totalChunks);

            // Overlay document's own metadata (don't overwrite source tracking keys)
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                    String key = entry.getKey();
                    // Don't let document metadata overwrite core source keys
                    boolean isCoreSourceKey = false;
                    for (String coreKey : SourceMetadataConstants.CORE_SOURCE_KEYS) {
                        if (coreKey.equals(key)) {
                            isCoreSourceKey = true;
                            break;
                        }
                    }
                    if (!isCoreSourceKey) {
                        enrichedMetadata.put(key, entry.getValue());
                    }
                }
            }

            // Create new document with enriched metadata
            Document enrichedDoc = new Document(
                    doc.getId(),
                    doc.getText(),
                    enrichedMetadata
            );

            enrichedDocuments.add(enrichedDoc);
        }

        logger.trace("Added source attribution to {} documents (source_id: {}, stored_copy: {})",
                documents.size(),
                sourceDescriptor.getEffectiveSourceId(),
                sourceDescriptor.getStoredCopyPath() != null ? "yes" : "no");

        return enrichedDocuments;
    }
}
