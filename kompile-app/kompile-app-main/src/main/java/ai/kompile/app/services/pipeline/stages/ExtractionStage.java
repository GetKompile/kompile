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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private String preferredLoaderName;
    private boolean autoDetectLoader = true;

    public ExtractionStage(List<DocumentLoader> availableLoaders) {
        this.availableLoaders = availableLoaders;
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

            // Create source descriptor
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(fileName)
                    .sourceId(input.sourceId() != null ? input.sourceId() : "file_" + fileName)
                    .metadata(input.metadata() != null ? input.metadata() : Map.of())
                    .build();

            // Load documents
            List<Document> documents = loader.load(sourceDescriptor);

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, fileSize, documents.size());

            logger.debug("Extracted {} documents from '{}' in {}ms using {}",
                    documents.size(), fileName, elapsedNanos / 1_000_000, loader.getName());

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
}
