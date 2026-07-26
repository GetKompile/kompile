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

package ai.kompile.app.web.controllers;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.repository.IndexingJobHistoryRepository;
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.SourceMarkdownConversionService;
import ai.kompile.app.services.VectorStorePopulationService;
import ai.kompile.app.web.dto.AsyncUploadResponse;
import ai.kompile.app.web.dto.BatchAsyncUploadResponse;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceDocumentStorageService;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles file upload and ingest-tracking endpoints.
 * Shares the /api/documents base path with DocumentManagementController.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentUploadController.class);

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties;
    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private IndexerService indexerService;
    private final DocumentIngestService documentIngestService;
    private final IngestProgressTracker progressTracker;
    private final VectorStorePopulationService vectorStorePopulationService;
    private final SourceDocumentStorageService sourceDocumentStorageService;
    private final SourceMarkdownConversionService sourceMarkdownConversionService;
    private final FactSheetService factSheetService;
    private final IndexingJobHistoryRepository jobHistoryRepository;

    @Autowired
    public DocumentUploadController(
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) List<IndexerService> indexerService,
            @Autowired(required = false) DocumentIngestService documentIngestService,
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) VectorStorePopulationService vectorStorePopulationService,
            @Autowired(required = false) SourceDocumentStorageService sourceDocumentStorageService,
            @Autowired(required = false) SourceMarkdownConversionService sourceMarkdownConversionService,
            @Autowired(required = false) FactSheetService factSheetService,
            @Autowired(required = false) IndexingJobHistoryRepository jobHistoryRepository) {
        this.sourceProperties = appDocumentSourceProperties;
        this.jobHistoryRepository = jobHistoryRepository;
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.documentIngestService = documentIngestService;
        this.progressTracker = progressTracker;
        this.vectorStorePopulationService = vectorStorePopulationService;
        this.sourceDocumentStorageService = sourceDocumentStorageService != null ? sourceDocumentStorageService : new SourceDocumentStorageService();
        this.sourceMarkdownConversionService = sourceMarkdownConversionService != null
                ? sourceMarkdownConversionService
                : new SourceMarkdownConversionService(this.sourceDocumentStorageService, appDocumentSourceProperties);
        this.factSheetService = factSheetService;

        if (documentIngestService == null) {
            logger.warn("DocumentUploadController: DocumentIngestService is not available");
        }
        if (progressTracker == null) {
            logger.warn("DocumentUploadController: IngestProgressTracker is not available");
        }
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("DocumentUploadController: No DocumentLoaders available");
        }
        if (textChunkers == null || textChunkers.isEmpty()) {
            logger.warn("DocumentUploadController: No TextChunkers available");
        }

        if (indexerService != null && !indexerService.isEmpty()) {
            if (indexerService.size() > 1) {
                for (IndexerService indexerService1 : indexerService) {
                    if (indexerService1 instanceof NoOpIndexerService) {
                        continue;
                    } else {
                        this.indexerService = indexerService1;
                        break;
                    }
                }
            } else {
                this.indexerService = indexerService.get(0);
            }
        } else {
            this.indexerService = null;
            logger.warn("DocumentUploadController: No IndexerService available");
        }

        if (appDocumentSourceProperties == null ||
                appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            logger.warn(
                    "'app.document.uploads-path' is not configured in application.properties. Using default uploads path.");
            this.uploadsPath = Paths.get("./uploads").toAbsolutePath();
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    @PostConstruct
    private void initializeUploadsDirectory() {
        try {
            if (this.uploadsPath != null
                    && !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
                if (!Files.exists(this.uploadsPath)) {
                    Files.createDirectories(this.uploadsPath);
                    logger.info("Created uploads directory for DocumentUploadController: {}", this.uploadsPath);
                } else {
                    logger.info("Uploads directory already exists: {}", this.uploadsPath);
                }
            } else {
                logger.error(
                        "Uploads path is not properly configured (remains as fallback). Upload functionality will likely fail.");
            }
        } catch (IOException e) {
            logger.error("FATAL: Could not create or access uploads directory at {}: {}", this.uploadsPath,
                    e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------

    public record ControllerBatchLoadRequestItem(
            DocumentSourceDescriptor source,
            String loaderName,
            String chunkerName,
            Map<String, Object> chunkerOptions,
            String vectorStoreName,
            Map<String, Object> metadata) {
    }

    public record BatchProcessRequest(
            List<ControllerBatchLoadRequestItem> items,
            String defaultLoaderName,
            String defaultChunkerName,
            Map<String, Object> defaultChunkerOptions,
            String defaultVectorStoreName) {
    }

    public record DocumentProcessingResult(
            int originalDocumentCount,
            int finalChunkCount,
            List<String> processedDocumentIds,
            String loaderUsed,
            String chunkerUsed,
            boolean indexingSuccessful,
            String processingDetails) {
    }

    // ---------------------------------------------------------------------------
    // Chunker helpers (copied — shared logic with DocumentManagementController)
    // ---------------------------------------------------------------------------

    /**
     * Mapping of UI chunker strategy IDs to backend chunker names.
     */
    private static final Map<String, String> CHUNKER_ALIASES = Map.ofEntries(
            Map.entry("spring_recursive_character", "recursive-character"),
            Map.entry("custom_recursive_character", "recursive-character"),
            Map.entry("recursive-character", "recursive-character"),
            Map.entry("opennlp_sentence", "opennlp_sentence"),
            Map.entry("sentence", "sentence"),
            Map.entry("spring_token", "spring_token"),
            Map.entry("custom_markdown", "custom_markdown"),
            Map.entry("spring_markdown", "spring_markdown"));

    /**
     * Determines if a chunker is a no-op/stub implementation that should be avoided
     * when real chunkers are available.
     */
    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null)
            return true;

        String className = chunker.getClass().getSimpleName().toLowerCase();
        String chunkerName = chunker.getName().toLowerCase();
        String fullClassName = chunker.getClass().getName().toLowerCase();

        boolean isNoOpClass = className.contains("noop") ||
                className.contains("dummy") ||
                className.contains("mock") ||
                className.contains("stub") ||
                className.contains("default") ||
                className.contains("fallback") ||
                className.contains("empty");

        boolean isNoOpName = chunkerName.contains("noop") ||
                chunkerName.contains("no-op") ||
                chunkerName.contains("dummy") ||
                chunkerName.contains("mock") ||
                chunkerName.contains("stub") ||
                chunkerName.contains("disabled") ||
                chunkerName.contains("default") ||
                chunkerName.contains("fallback") ||
                chunkerName.contains("empty") ||
                chunkerName.equals("none");

        boolean isCorePackage = fullClassName.contains(".core.") &&
                (fullClassName.contains("noop") ||
                        fullClassName.contains("default") ||
                        fullClassName.contains("stub"));

        return isNoOpClass || isNoOpName || isCorePackage;
    }

    /**
     * Finds a chunker by name, supporting both exact matches and UI alias mappings.
     */
    private TextChunker findChunkerByName(String requestedName) {
        if (requestedName == null || requestedName.isEmpty() || textChunkers == null) {
            return null;
        }

        Optional<TextChunker> exactMatch = textChunkers.stream()
                .filter(chunker -> requestedName.equals(chunker.getName()))
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }

        String mappedName = CHUNKER_ALIASES.get(requestedName);
        if (mappedName != null) {
            Optional<TextChunker> aliasMatch = textChunkers.stream()
                    .filter(chunker -> mappedName.equals(chunker.getName()))
                    .findFirst();
            if (aliasMatch.isPresent()) {
                logger.info("Resolved chunker: UI name '{}' -> backend name '{}' ({})",
                        requestedName, mappedName, aliasMatch.get().getClass().getSimpleName());
                return aliasMatch.get();
            }
        }

        String normalizedRequest = requestedName.toLowerCase().replace("_", "-").replace("spring-", "");
        Optional<TextChunker> partialMatch = textChunkers.stream()
                .filter(chunker -> {
                    String chunkerName = chunker.getName().toLowerCase();
                    return chunkerName.contains(normalizedRequest) || normalizedRequest.contains(chunkerName);
                })
                .findFirst();
        if (partialMatch.isPresent()) {
            logger.debug("Found chunker via partial match: requested='{}', found='{}'",
                    requestedName, partialMatch.get().getName());
            return partialMatch.get();
        }

        return null;
    }

    /**
     * Gets the best available chunker, prioritizing real implementations over stubs.
     */
    private TextChunker selectBestChunker() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            return null;
        }

        List<TextChunker> realChunkers = textChunkers.stream()
                .filter(chunker -> !isNoOpChunker(chunker))
                .collect(Collectors.toList());

        logger.debug("Total chunkers: {}, Real chunkers: {}, Filtered out: {}",
                textChunkers.size(), realChunkers.size(), textChunkers.size() - realChunkers.size());

        if (realChunkers.isEmpty()) {
            logger.warn("No real chunkers available, only stub/no-op chunkers found: {}",
                    textChunkers.stream().map(c -> c.getName() + "(" + c.getClass().getSimpleName() + ")")
                            .collect(Collectors.joining(", ")));
            return null;
        }

        List<String> preferredPatterns = Arrays.asList(
                "opennlp",
                "recursive",
                "character",
                "sentence",
                "markdown",
                "token");

        for (String pattern : preferredPatterns) {
            Optional<TextChunker> preferred = realChunkers.stream()
                    .filter(chunker -> chunker.getName().toLowerCase().contains(pattern) ||
                            chunker.getClass().getSimpleName().toLowerCase().contains(pattern))
                    .findFirst();
            if (preferred.isPresent()) {
                logger.info("Selected preferred chunker: {} ({})",
                        preferred.get().getName(), preferred.get().getClass().getSimpleName());
                return preferred.get();
            }
        }

        TextChunker selected = realChunkers.get(0);
        logger.info("No preferred chunker found, using first real chunker: {} ({})",
                selected.getName(), selected.getClass().getSimpleName());
        return selected;
    }

    // ---------------------------------------------------------------------------
    // Document conversion helpers
    // ---------------------------------------------------------------------------

    /**
     * Converts a Spring AI Document to a RetrievedDoc.
     */
    private RetrievedDoc convertToRetrievedDoc(Document document) {
        return RetrievedDoc.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(document.getMetadata())
                .build();
    }

    /**
     * Converts a list of Spring AI Documents to RetrievedDocs.
     */
    private List<RetrievedDoc> convertToRetrievedDocs(List<Document> documents) {
        return documents.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------------
    // Internal file-processing pipeline
    // ---------------------------------------------------------------------------

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing. This overload does not track progress via WebSocket.
     */
    private DocumentProcessingResult processUploadedFile(Path filePath, String loaderName, String chunkerName)
            throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, null, null);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing. This overload accepts an optional source URL for web-sourced documents.
     */
    private DocumentProcessingResult processUploadedFile(Path filePath, String loaderName, String chunkerName,
            String sourceUrl) throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, null, sourceUrl);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing. If taskId is provided, sends real-time progress updates via WebSocket.
     */
    private DocumentProcessingResult processUploadedFileWithTracking(Path filePath, String loaderName,
            String chunkerName, String taskId) throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, taskId, null);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing. If taskId is provided, sends real-time progress updates via WebSocket.
     * If sourceUrl is provided, stores the original URL for web-sourced documents.
     */
    private DocumentProcessingResult processUploadedFileWithTracking(Path filePath, String loaderName,
            String chunkerName, String taskId, String sourceUrl) throws Exception {
        String fileName = filePath.getFileName().toString();
        IngestProgressTracker.TaskProgressContext progressContext = null;

        if (taskId != null && progressTracker != null) {
            Long factSheetId = null;
            if (factSheetService != null) {
                try {
                    FactSheet activeSheet = factSheetService.getActiveSheet();
                    if (activeSheet != null) {
                        factSheetId = activeSheet.getId();
                    }
                } catch (Exception e) {
                    logger.warn("Could not get active fact sheet for task {}: {}", taskId, e.getMessage());
                }
            }
            progressContext = progressTracker.createContext(taskId, fileName, factSheetId);
        }

        try {
            return doProcessUploadedFile(filePath, loaderName, chunkerName, progressContext, sourceUrl);
        } catch (Exception e) {
            if (progressContext != null) {
                progressContext.fail(IngestProgressUpdate.IngestPhase.FAILED, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * Internal method that performs the actual file processing with optional
     * progress tracking.
     * @param sourceUrl Optional original URL for web-sourced documents
     */
    private DocumentProcessingResult doProcessUploadedFile(Path filePath, String loaderName, String chunkerName,
            IngestProgressTracker.TaskProgressContext progress, String sourceUrl) throws Exception {
        logger.info("Starting end-to-end processing for uploaded file: {} with loader: '{}', chunker: '{}', sourceUrl: '{}'",
                filePath, loaderName, chunkerName, sourceUrl != null ? sourceUrl : "none");

        logger.debug("Available loaders: {}", documentLoaders.stream()
                .map(loader -> loader.getName() + " (" + loader.getClass().getSimpleName() + ")")
                .collect(Collectors.joining(", ")));
        logger.debug("Available chunkers: {}", textChunkers.stream()
                .map(chunker -> chunker.getName() + " (" + chunker.getClass().getSimpleName() + ")")
                .collect(Collectors.joining(", ")));

        // Step 1: Find appropriate loader
        DocumentLoader selectedLoader = null;
        if (loaderName != null && !loaderName.isEmpty()) {
            selectedLoader = documentLoaders.stream()
                    .filter(loader -> loaderName.equals(loader.getName()))
                    .findFirst()
                    .orElse(null);
            if (selectedLoader == null) {
                throw new IllegalArgumentException("Specified loader '" + loaderName + "' not found. Available loaders: " +
                        documentLoaders.stream().map(DocumentLoader::getName).collect(Collectors.joining(", ")));
            }
        } else {
            DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(filePath.getFileName().toString())
                    .build();

            selectedLoader = documentLoaders.stream()
                    .filter(loader -> loader.supports(tempDescriptor))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No suitable loader found for file: " + filePath +
                            ". Available loaders: "
                            + documentLoaders.stream().map(DocumentLoader::getName).collect(Collectors.joining(", "))));
        }

        logger.info("Selected loader: {} ({})", selectedLoader.getName(), selectedLoader.getClass().getSimpleName());

        if (progress != null) {
            progress.setLoaderUsed(selectedLoader.getName());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.LOADING, 10,
                    "Loading document", "Using loader: " + selectedLoader.getName());
        }

        // Step 2: Load documents
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("upload_timestamp", System.currentTimeMillis());
        metadata.put("upload_path", filePath.toString());
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            metadata.put(ai.kompile.core.source.SourceMetadataConstants.SOURCE_URL, sourceUrl);
        }

        DocumentSourceDescriptor.SourceType descriptorType = (sourceUrl != null && !sourceUrl.isEmpty())
                ? DocumentSourceDescriptor.SourceType.URL
                : DocumentSourceDescriptor.SourceType.FILE;

        String storedChecksum = null;
        if (sourceDocumentStorageService != null && sourceDocumentStorageService.isEnabled()) {
            try {
                var storageResult = sourceDocumentStorageService.storeDocument(filePath);
                if (storageResult.isPresent()) {
                    storedChecksum = storageResult.get().checksum();
                    sourceDocumentStorageService.storeMetadata(storedChecksum, metadata);
                    logger.debug("Stored document with checksum {} and metadata (source_url: {})",
                            storedChecksum.substring(0, 16) + "...", sourceUrl);
                }
            } catch (Exception e) {
                logger.warn("Failed to store document for source URL tracking: {}", e.getMessage());
            }
        }

        DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                .type(descriptorType)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .sourceId((sourceUrl != null ? "url_" : "upload_") + filePath.getFileName().toString())
                .checksum(storedChecksum)
                .metadata(metadata)
                .build();

        List<Document> loadedDocuments = selectedLoader.load(sourceDescriptor);
        logger.info("Loaded {} documents from file: {} using loader: '{}'",
                loadedDocuments.size(), filePath, selectedLoader.getName());

        if (progress != null) {
            progress.setDocumentsLoaded(loadedDocuments.size());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.LOADING, 25,
                    "Documents loaded", String.format("Loaded %d documents from file", loadedDocuments.size()));
        }

        if (loadedDocuments.isEmpty()) {
            logger.error("CRITICAL: No documents loaded from file: {}", filePath);
            return new DocumentProcessingResult(
                    0, 0, Collections.emptyList(), selectedLoader.getName(),
                    chunkerName != null ? chunkerName : "none", false,
                    "No documents could be loaded from the file.");
        }

        for (int i = 0; i < loadedDocuments.size(); i++) {
            Document doc = loadedDocuments.get(i);
            String content = doc.getText();
            Map<String, Object> docMetadata = doc.getMetadata();

            logger.info("=== DOCUMENT {} ANALYSIS ===", i);
            logger.info("Document ID: {}", doc.getId());
            logger.info("Content is null: {}", content == null);

            if (content != null) {
                logger.info("Content length: {} characters", content.length());
                logger.info("Content is empty/whitespace only: {}", content.trim().isEmpty());

                if (content.length() > 0) {
                    String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;
                    logger.info("Content preview (first 500 chars): '{}'", preview);

                    if (content.length() > 200) {
                        String ending = content.substring(Math.max(0, content.length() - 200));
                        logger.info("Content ending (last 200 chars): '{}'", ending);
                    }

                    long lineCount = content.lines().count();
                    long wordCount = content.split("\\s+").length;
                    boolean containsCommonWords = content.toLowerCase().contains("the") ||
                            content.toLowerCase().contains("and") ||
                            content.toLowerCase().contains("chapter");

                    logger.info("Content stats - Lines: {}, Words: {}, Contains common words: {}",
                            lineCount, wordCount, containsCommonWords);

                    boolean hasSpecialChars = content.contains("") || content.contains("\ufffd");
                    boolean hasEncodingIssues = content.matches(".*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F].*");

                    logger.info("Potential issues - Special chars: {}, Encoding issues: {}",
                            hasSpecialChars, hasEncodingIssues);
                } else {
                    logger.warn("ISSUE: Document has 0 length content!");
                }
            } else {
                logger.error("CRITICAL: Document content is null!");
            }

            if (docMetadata != null && !docMetadata.isEmpty()) {
                logger.info("Metadata keys: {}", docMetadata.keySet());
                if (docMetadata.containsKey("source")) {
                    logger.info("Source metadata: {}", docMetadata.get("source"));
                }
                if (docMetadata.containsKey("page_count") || docMetadata.containsKey("pageCount")) {
                    logger.info("Page count: {}", docMetadata.getOrDefault("page_count", docMetadata.get("pageCount")));
                }
            } else {
                logger.info("No metadata available");
            }
            logger.info("=== END DOCUMENT {} ANALYSIS ===", i);
        }

        // Step 3: Apply chunking
        List<RetrievedDoc> finalDocuments = new ArrayList<>(convertToRetrievedDocs(loadedDocuments));
        String actualChunkerUsed = "none";

        TextChunker selectedChunker = null;

        if (chunkerName != null && !chunkerName.isEmpty()) {
            selectedChunker = findChunkerByName(chunkerName);

            if (selectedChunker == null) {
                logger.warn("Specified chunker '{}' not found. Available chunkers: {}. Will attempt auto-selection.",
                        chunkerName, textChunkers.stream().map(TextChunker::getName).collect(Collectors.joining(", ")));
                selectedChunker = selectBestChunker();
            } else if (isNoOpChunker(selectedChunker)) {
                logger.warn(
                        "Specified chunker '{}' is a no-op/stub implementation. Will attempt to find a better chunker.",
                        chunkerName);
                TextChunker betterChunker = selectBestChunker();
                if (betterChunker != null) {
                    selectedChunker = betterChunker;
                    logger.info("Upgraded from no-op chunker to: {}", selectedChunker.getName());
                }
            }
        } else {
            selectedChunker = selectBestChunker();
            if (selectedChunker != null) {
                logger.info("Auto-selected chunker: {} ({}). Available real chunkers: {}",
                        selectedChunker.getName(), selectedChunker.getClass().getSimpleName(),
                        textChunkers.stream()
                                .filter(c -> !isNoOpChunker(c))
                                .map(c -> c.getName() + "(" + c.getClass().getSimpleName() + ")")
                                .collect(Collectors.joining(", ", "[", "]")));
            }
        }

        if (selectedChunker != null) {
            logger.info("Applying chunker '{}' ({}) to {} loaded documents",
                    selectedChunker.getName(), selectedChunker.getClass().getSimpleName(), loadedDocuments.size());

            if (progress != null) {
                progress.setChunkerUsed(selectedChunker.getName());
                progress.updateProgress(IngestProgressUpdate.IngestPhase.CHUNKING, 35,
                        "Starting chunking", "Using chunker: " + selectedChunker.getName());
            }

            finalDocuments.clear();

            Map<String, Object> chunkingOptions = new HashMap<>();
            chunkingOptions.put("chunkSize", 1000);
            chunkingOptions.put("overlap", 200);
            chunkingOptions.put("maxChunkSize", 2000);
            chunkingOptions.put("minChunkSize", 100);
            chunkingOptions.put("collectGarbage", false);

            int totalChunksCreated = 0;
            for (int i = 0; i < loadedDocuments.size(); i++) {
                RetrievedDoc doc = convertToRetrievedDoc(loadedDocuments.get(i));
                String docContent = doc.getText();

                logger.info("=== CHUNKING DOCUMENT {} ===", i);
                logger.info("Document ID: {}", doc.getId());
                logger.info("Content length before chunking: {}", docContent != null ? docContent.length() : 0);

                if (docContent == null || docContent.trim().isEmpty()) {
                    logger.error("CRITICAL: Document {} has no content to chunk!", i);
                    finalDocuments.add(doc);
                    totalChunksCreated++;
                    continue;
                }

                try {
                    logger.info("Attempting to chunk with '{}' using options: {}", selectedChunker.getName(),
                            chunkingOptions);

                    List<RetrievedDoc> chunks = selectedChunker.chunk(doc, chunkingOptions);

                    logger.info("Chunker '{}' returned {} chunks for document {}",
                            selectedChunker.getName(), chunks.size(), i);

                    if (chunks.isEmpty()) {
                        logger.warn("WARNING: Chunker returned 0 chunks for document {}. Adding original document.", i);
                        finalDocuments.add(doc);
                        totalChunksCreated++;
                    } else {
                        logger.info("=== CHUNK ANALYSIS FOR DOCUMENT {} ===", i);
                        for (int j = 0; j < chunks.size(); j++) {
                            RetrievedDoc chunk = chunks.get(j);
                            String chunkText = chunk.getText();

                            logger.info("Chunk {}: ID={}, Length={}, Is null: {}, Is empty: {}",
                                    j, chunk.getId(),
                                    chunkText != null ? chunkText.length() : 0,
                                    chunkText == null,
                                    chunkText != null && chunkText.trim().isEmpty());

                            if (chunkText != null && chunkText.length() > 0) {
                                String chunkPreview = chunkText.length() > 100 ? chunkText.substring(0, 100) + "..."
                                        : chunkText;
                                logger.info("  Chunk {} preview: '{}'", j, chunkPreview);

                                if (chunkText.equals(docContent)) {
                                    logger.warn(
                                            "  WARNING: Chunk {} is identical to original document - chunker may not be working!",
                                            j);
                                }
                            } else {
                                logger.warn("  WARNING: Chunk {} has no content!", j);
                            }
                        }
                        logger.info("=== END CHUNK ANALYSIS FOR DOCUMENT {} ===", i);

                        finalDocuments.addAll(chunks);
                        totalChunksCreated += chunks.size();
                    }
                } catch (Exception e) {
                    logger.error("CHUNKING ERROR for document {}: {} - {}", i, e.getClass().getSimpleName(),
                            e.getMessage(), e);
                    logger.error("Chunker class: {}", selectedChunker.getClass().getName());
                    logger.error("Document content sample: '{}'",
                            docContent.length() > 200 ? docContent.substring(0, 200) + "..." : docContent);

                    finalDocuments.add(doc);
                    totalChunksCreated++;
                }
                logger.info("=== END CHUNKING DOCUMENT {} ===", i);
            }

            actualChunkerUsed = selectedChunker.getName();
            logger.info("Chunking completed with '{}'. {} original documents became {} chunks (total created: {})",
                    selectedChunker.getName(), loadedDocuments.size(), finalDocuments.size(), totalChunksCreated);

            if (progress != null) {
                progress.setChunksCreated(finalDocuments.size());
                progress.updateProgress(IngestProgressUpdate.IngestPhase.CHUNKING, 60,
                        "Chunking complete", String.format("Created %d chunks from %d documents", finalDocuments.size(),
                                loadedDocuments.size()));
            }
        } else {
            logger.info(
                    "No suitable chunker available (only no-op/stub chunkers found). Using documents as-is without chunking. Available chunkers: {}",
                    textChunkers.stream()
                            .map(c -> c.getName() + "(" + c.getClass().getSimpleName()
                                    + (isNoOpChunker(c) ? "-NOOP" : "-REAL") + ")")
                            .collect(Collectors.joining(", ", "[", "]")));
        }

        // Step 4: Index the documents
        boolean indexingSuccessful = false;
        logger.info("Indexing {} processed documents", finalDocuments.size());

        if (progress != null) {
            progress.updateProgress(IngestProgressUpdate.IngestPhase.INDEXING, 70,
                    "Indexing documents", String.format("Indexing %d documents", finalDocuments.size()));
        }

        for (int i = 0; i < Math.min(finalDocuments.size(), 5); i++) {
            RetrievedDoc doc = finalDocuments.get(i);
            String content = doc.getText();
            logger.debug("Final document {}: ID={}, Content length={}",
                    i, doc.getId(), content != null ? content.length() : 0);
        }

        if (indexerService != null) {
            indexerService.indexDocuments(finalDocuments);
            indexingSuccessful = true;
            logger.info("Successfully indexed {} documents", finalDocuments.size());
        } else {
            logger.warn("IndexerService not available - skipping indexing phase");
        }

        if (progress != null) {
            progress.setDocumentsIndexed(finalDocuments.size());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.INDEXING, 95,
                    "Indexing complete", String.format("Indexed %d documents successfully", finalDocuments.size()));
        }

        List<String> processedDocumentIds = finalDocuments.stream()
                .map(RetrievedDoc::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (progress != null) {
            progress.setProcessedDocumentIds(processedDocumentIds);
            progress.complete();
        }

        String processingDetails = String.format(
                "Successfully processed file through complete pipeline. Loader: %s, Chunker: %s, Original docs: %d, Final chunks: %d, Indexed: %s. "
                        +
                        "Content lengths: %s",
                selectedLoader.getName(), actualChunkerUsed, loadedDocuments.size(), finalDocuments.size(),
                indexingSuccessful,
                loadedDocuments.stream().map(doc -> String.valueOf(doc.getText() != null ? doc.getText().length() : 0))
                        .collect(Collectors.joining(", ", "[", "]")));

        return new DocumentProcessingResult(
                loadedDocuments.size(),
                finalDocuments.size(),
                processedDocumentIds,
                selectedLoader.getName(),
                actualChunkerUsed,
                indexingSuccessful,
                processingDetails);
    }

    // ---------------------------------------------------------------------------
    // Public endpoints
    // ---------------------------------------------------------------------------

    @PostMapping("/upload")
    public ResponseEntity<?> handleFileUpload(@RequestParam("file") MultipartFile file,
            @RequestParam(name = "loader", required = false) String loaderName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName,
            @RequestParam(name = "processImmediately", required = false, defaultValue = "true") boolean processImmediately,
            @RequestParam(name = "trackProgress", required = false, defaultValue = "true") boolean trackProgress) {
        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error",
                            "Uploads directory is not configured correctly on the server. Cannot save file."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty."));
        }

        String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(),
                "uploaded_file_" + UUID.randomUUID());
        String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitizedFileName.isEmpty()) {
            sanitizedFileName = "upload_" + UUID.randomUUID().toString().substring(0, 8);
        }

        Path destinationFile = null;
        try {
            destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save file outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file path (directory traversal attempt)."));
            }

            if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                try (Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
            try (InputStream inputStream = file.getInputStream();
                    OutputStream outputStream = Files.newOutputStream(destinationFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                inputStream.transferTo(outputStream);
            }
            logger.info("File uploaded successfully to: {}", destinationFile);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "File '" + sanitizedFileName + "' uploaded successfully.");
            response.put("fileName", sanitizedFileName);
            response.put("filePath", destinationFile.toString());
            response.put("selectedLoader", loaderName != null ? loaderName : "Auto-detect");
            response.put("selectedChunkerName", chunkerName != null ? chunkerName : "None");

            String taskId = (trackProgress && progressTracker != null) ? progressTracker.generateTaskId() : null;
            if (taskId != null) {
                response.put("taskId", taskId);
                response.put("websocketTopic", "/topic/ingest/" + taskId);
            }

            if (processImmediately) {
                try {
                    logger.info("Processing uploaded file immediately: {} (taskId: {})", sanitizedFileName, taskId);
                    DocumentProcessingResult processingResult = processUploadedFileWithTracking(destinationFile,
                            loaderName, chunkerName, taskId);

                    response.put("processingCompleted", true);
                    response.put("originalDocumentCount", processingResult.originalDocumentCount());
                    response.put("finalChunkCount", processingResult.finalChunkCount());
                    response.put("processedDocumentIds", processingResult.processedDocumentIds());
                    response.put("loaderUsed", processingResult.loaderUsed());
                    response.put("chunkerUsed", processingResult.chunkerUsed());
                    response.put("indexingSuccessful", processingResult.indexingSuccessful());
                    response.put("processingDetails", processingResult.processingDetails());
                    response.put("message", response.get("message") + " Document processed and indexed successfully.");

                    logger.info("File upload and processing completed successfully: {}", sanitizedFileName);

                } catch (Exception e) {
                    logger.error("File uploaded successfully but processing failed for {}: {}", sanitizedFileName,
                            e.getMessage(), e);

                    boolean isPostgresMLError = false;
                    Throwable current = e;
                    while (current != null && !isPostgresMLError) {
                        if (current.getMessage() != null && current.getMessage().contains("pgml.embed")) {
                            isPostgresMLError = true;
                        }
                        current = current.getCause();
                    }

                    if (isPostgresMLError) {
                        logger.error("PostgresML ERROR DETECTED IN DOCUMENT UPLOAD! Error: {}", e.getMessage());

                        try {
                            javax.sql.DataSource dataSource = null;

                            try {
                                org.springframework.context.ApplicationContext context = org.springframework.web.context.support.WebApplicationContextUtils
                                        .getWebApplicationContext(
                                                ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder
                                                        .currentRequestAttributes())
                                                        .getRequest().getServletContext());
                                dataSource = context.getBean(javax.sql.DataSource.class);
                            } catch (Exception contextError) {
                                logger.warn("Could not get DataSource from context: {}", contextError.getMessage());
                            }

                            if (dataSource != null) {
                                try (java.sql.Connection conn = dataSource.getConnection()) {

                                    logger.error("1. SCHEMA CHECK:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        java.sql.ResultSet rs = stmt.executeQuery(
                                                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'");
                                        rs.next();

                                        if (rs.getInt(1) > 0) {
                                            logger.error("   pgml schema EXISTS");
                                        } else {
                                            logger.error("   pgml schema MISSING!");
                                        }
                                    }

                                    logger.error("2. FUNCTION CHECK:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        java.sql.ResultSet rs = stmt.executeQuery(
                                                "SELECT COUNT(*) FROM information_schema.routines " +
                                                        "WHERE routine_schema = 'pgml' AND routine_name = 'embed'");
                                        rs.next();

                                        int funcCount = rs.getInt(1);
                                        if (funcCount > 0) {
                                            logger.error("   pgml.embed function EXISTS ({} variants)", funcCount);
                                        } else {
                                            logger.error("   pgml.embed function MISSING! This is the ROOT CAUSE of the upload failure.");
                                        }
                                    }

                                    logger.error("3. FUNCTION TEST:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        logger.error("   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)");
                                        stmt.executeQuery(
                                                "SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)");
                                        logger.error("   Function call SUCCEEDED");
                                    } catch (Exception testError) {
                                        logger.error("   Function call FAILED: {}", testError.getMessage());
                                    }

                                } catch (Exception dbError) {
                                    logger.error("Database connection failed: {}", dbError.getMessage());
                                }
                            }
                        } catch (Exception debugError) {
                            logger.error("PostgresML debug check failed: {}", debugError.getMessage());
                        }

                        logger.error("IMMEDIATE FIX - Run this SQL in your database:\n" +
                                "CREATE SCHEMA IF NOT EXISTS pgml;\n" +
                                "CREATE OR REPLACE FUNCTION pgml.embed(\n" +
                                "  model_name character varying,\n" +
                                "  text_input text,\n" +
                                "  kwargs jsonb DEFAULT '{}'\n" +
                                ") RETURNS FLOAT[] AS $$\n" +
                                "BEGIN\n" +
                                "  RAISE EXCEPTION 'PostgresML not installed';\n" +
                                "END;\n" +
                                "$$ LANGUAGE plpgsql;");
                    }

                    response.put("processingCompleted", false);
                    response.put("processingError", e.getMessage());
                    response.put("message", response.get("message")
                            + " However, automatic processing failed. You can trigger processing manually through batch operations or index rebuild.");

                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
                }
            } else {
                response.put("processingCompleted", false);
                response.put("message", response.get("message")
                        + " File saved but not processed immediately. Use batch processing or rebuild index to include it.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            String fileNameForError = (destinationFile != null) ? destinationFile.getFileName().toString()
                    : sanitizedFileName;
            logger.error("Failed to store uploaded file {}: {}", fileNameForError, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to store uploaded file: " + e.getMessage()));
        }
    }

    /**
     * Async file upload endpoint that returns immediately with a task ID.
     * Progress can be tracked via WebSocket subscription to /topic/ingest/{taskId}
     */
    @PostMapping("/upload-async")
    public ResponseEntity<AsyncUploadResponse> handleAsyncFileUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "loader", required = false) String loaderName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName,
            @RequestParam(name = "processingMode", required = false, defaultValue = "auto") String processingMode,
            @RequestParam(name = "subprocessHeapSize", required = false) String subprocessHeapSize,
            @RequestParam(name = "subprocessTimeoutMinutes", required = false) Integer subprocessTimeoutMinutes,
            @RequestParam(name = "subprocessHeartbeatSeconds", required = false) Integer subprocessHeartbeatSeconds,
            @RequestParam(name = "subprocessStaleThresholdSeconds", required = false) Integer subprocessStaleThresholdSeconds) {

        if (documentIngestService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(AsyncUploadResponse.error(file.getOriginalFilename(),
                            "Document ingest service is not available."));
        }
        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AsyncUploadResponse.error(file.getOriginalFilename(),
                            "Uploads directory is not configured correctly on the server."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(AsyncUploadResponse.error(file.getOriginalFilename(), "File cannot be empty."));
        }

        DocumentIngestService.ProcessingMode mode = parseProcessingMode(processingMode);
        logger.info("=== UPLOAD REQUEST === file={}, processingMode param='{}', parsed mode={}",
                file.getOriginalFilename(), processingMode, mode);

        String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(),
                "uploaded_file_" + UUID.randomUUID());
        String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitizedFileName.isEmpty()) {
            sanitizedFileName = "upload_" + UUID.randomUUID().toString().substring(0, 8);
        }

        try {
            Path destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save file outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest()
                        .body(AsyncUploadResponse.error(sanitizedFileName,
                                "Invalid file path (directory traversal attempt)."));
            }

            if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                try (Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
            try (InputStream inputStream = file.getInputStream();
                    OutputStream outputStream = Files.newOutputStream(destinationFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                inputStream.transferTo(outputStream);
            }
            logger.info("File uploaded successfully to: {}", destinationFile);

            String taskId = UUID.randomUUID().toString();

            Map<String, Object> subprocessOptions = buildSubprocessOptions(
                    subprocessHeapSize, subprocessTimeoutMinutes,
                    subprocessHeartbeatSeconds, subprocessStaleThresholdSeconds);

            if (progressTracker != null) {
                progressTracker.startTask(taskId, sanitizedFileName);
                logger.debug("Sent initial QUEUED event for task {} before HTTP response", taskId);
            }

            documentIngestService.processDocumentAsync(taskId, destinationFile, loaderName, chunkerName, mode, subprocessOptions);

            logger.info("File {} queued for async processing with task ID: {} (mode={}, subprocessOptions={})",
                    sanitizedFileName, taskId, mode, subprocessOptions);

            return ResponseEntity.accepted()
                    .body(AsyncUploadResponse.accepted(taskId, sanitizedFileName));

        } catch (Exception e) {
            logger.error("Failed to store uploaded file {}: {}", sanitizedFileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AsyncUploadResponse.error(sanitizedFileName,
                            "Failed to store uploaded file: " + e.getMessage()));
        }
    }

    /**
     * Batch async file upload endpoint that accepts multiple files.
     * All files are processed concurrently. Progress can be tracked via WebSocket.
     */
    @PostMapping("/upload-batch-async")
    public ResponseEntity<BatchAsyncUploadResponse> handleBatchAsyncFileUpload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(name = "loader", required = false) String loaderName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName,
            @RequestParam(name = "processingMode", required = false, defaultValue = "auto") String processingMode,
            @RequestParam(name = "subprocessHeapSize", required = false) String subprocessHeapSize,
            @RequestParam(name = "subprocessTimeoutMinutes", required = false) Integer subprocessTimeoutMinutes,
            @RequestParam(name = "subprocessHeartbeatSeconds", required = false) Integer subprocessHeartbeatSeconds,
            @RequestParam(name = "subprocessStaleThresholdSeconds", required = false) Integer subprocessStaleThresholdSeconds) {

        if (documentIngestService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(BatchAsyncUploadResponse.error("Document ingest service is not available."));
        }
        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BatchAsyncUploadResponse
                            .error("Uploads directory is not configured correctly on the server."));
        }

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest()
                    .body(BatchAsyncUploadResponse.error("No files provided for upload."));
        }

        DocumentIngestService.ProcessingMode mode = parseProcessingMode(processingMode);

        Map<String, Object> subprocessOptions = buildSubprocessOptions(
                subprocessHeapSize, subprocessTimeoutMinutes,
                subprocessHeartbeatSeconds, subprocessStaleThresholdSeconds);

        List<AsyncUploadResponse> results = new ArrayList<>();
        int acceptedCount = 0;
        int rejectedCount = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                results.add(AsyncUploadResponse.error(
                        file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown",
                        "File is empty"));
                rejectedCount++;
                continue;
            }

            String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(),
                    "uploaded_file_" + UUID.randomUUID());
            String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (sanitizedFileName.isEmpty()) {
                sanitizedFileName = "upload_" + UUID.randomUUID().toString().substring(0, 8);
            }

            try {
                Path destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

                if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                    logger.warn("Attempt to save file outside designated uploads directory: {}", destinationFile);
                    results.add(AsyncUploadResponse.error(sanitizedFileName,
                            "Invalid file path (directory traversal attempt)."));
                    rejectedCount++;
                    continue;
                }

                if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                    logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                    try (Stream<Path> walk = Files.walk(destinationFile)) {
                        walk.sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(File::delete);
                    }
                }
                try (InputStream inputStream = file.getInputStream();
                        OutputStream outputStream = Files.newOutputStream(destinationFile,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                    inputStream.transferTo(outputStream);
                }
                logger.info("File uploaded successfully to: {}", destinationFile);

                String taskId = UUID.randomUUID().toString();

                if (progressTracker != null) {
                    progressTracker.startTask(taskId, sanitizedFileName);
                }

                documentIngestService.processDocumentAsync(taskId, destinationFile, loaderName, chunkerName, mode, subprocessOptions);

                logger.info("File {} queued for async processing with task ID: {} (mode={})", sanitizedFileName, taskId,
                        mode);
                results.add(AsyncUploadResponse.accepted(taskId, sanitizedFileName));
                acceptedCount++;

            } catch (Exception e) {
                logger.error("Failed to store uploaded file {}: {}", sanitizedFileName, e.getMessage(), e);
                results.add(AsyncUploadResponse.error(sanitizedFileName, "Failed to store file: " + e.getMessage()));
                rejectedCount++;
            }
        }

        BatchAsyncUploadResponse response = new BatchAsyncUploadResponse(
                results,
                acceptedCount,
                rejectedCount,
                String.format("%d file(s) queued for processing, %d rejected", acceptedCount, rejectedCount),
                "/topic/ingest/all");

        return ResponseEntity.accepted().body(response);
    }

    /**
     * Get the current status of an async ingest task.
     */
    @GetMapping("/ingest-status/{taskId}")
    public ResponseEntity<?> getIngestStatus(@PathVariable String taskId) {
        if (documentIngestService == null && progressTracker == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Document ingest status is not available."));
        }

        Optional<IngestProgressUpdate> status = Optional.empty();
        if (documentIngestService != null) {
            status = documentIngestService.getTaskStatus(taskId);
        }
        if (status.isEmpty() && progressTracker != null) {
            status = progressTracker.getTaskStatus(taskId);
        }

        return status.map(s -> ResponseEntity.ok((Object) s))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cancel an async ingest task.
     * The task will be stopped as soon as possible and marked as CANCELLED.
     */
    @PostMapping("/ingest-cancel/{taskId}")
    public ResponseEntity<?> cancelIngestTask(@PathVariable String taskId) {
        if (documentIngestService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Document ingest service is not available."));
        }

        logger.info("Received cancel request for task: {}", taskId);

        boolean cancelled = documentIngestService.cancelTask(taskId);

        if (cancelled) {
            logger.info("Task {} marked for cancellation", taskId);
            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "cancelled", true,
                    "message", "Task has been marked for cancellation. It will stop at the next checkpoint."));
        } else {
            Optional<IngestProgressUpdate> status = documentIngestService.getTaskStatus(taskId);
            if (status.isEmpty() && progressTracker != null) {
                status = progressTracker.getTaskStatus(taskId);
            }

            return status
                    .map(s -> {
                        String reason;
                        if (s.status() == IngestProgressUpdate.IngestStatus.COMPLETED) {
                            reason = "Task has already completed successfully.";
                        } else if (s.status() == IngestProgressUpdate.IngestStatus.FAILED) {
                            reason = "Task has already failed.";
                        } else if (s.status() == IngestProgressUpdate.IngestStatus.CANCELLED) {
                            reason = "Task has already been cancelled.";
                        } else {
                            reason = "Task could not be cancelled (unknown reason).";
                        }
                        return ResponseEntity.ok(Map.of(
                                "taskId", taskId,
                                "cancelled", false,
                                "message", reason));
                    })
                    .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                            "taskId", taskId,
                            "cancelled", false,
                            "message", "Task not found.")));
        }
    }

    /**
     * Get all active ingest tasks from both async (DocumentIngestService), sync
     * (IngestProgressTracker) flows, and persisted jobs from database.
     */
    @GetMapping("/ingest-tasks")
    public ResponseEntity<Collection<IngestProgressUpdate>> getAllIngestTasks() {
        Map<String, IngestProgressUpdate> allTasks = new HashMap<>();

        // 1. Load persisted active jobs from database (QUEUED or RUNNING status)
        if (jobHistoryRepository != null) {
            try {
                List<IndexingJobHistory> activeDbJobs = jobHistoryRepository.findActiveJobs();
                for (IndexingJobHistory job : activeDbJobs) {
                    // Skip crawl tasks — they are tracked via the unified crawl dashboard
                    if (job.getTaskId() != null && job.getTaskId().startsWith("crawl-")) continue;
                    IngestProgressUpdate update = convertJobHistoryToProgressUpdate(job);
                    allTasks.put(job.getTaskId(), update);
                }
                if (!activeDbJobs.isEmpty()) {
                    logger.debug("Loaded {} active jobs from database", activeDbJobs.size());
                }
            } catch (Exception e) {
                logger.warn("Failed to load active jobs from database: {}", e.getMessage());
            }
        }

        // 2. Add tasks from async flow (DocumentIngestService) - override DB entries
        if (documentIngestService != null) {
            for (IngestProgressUpdate task : documentIngestService.getAllActiveTasks()) {
                allTasks.put(task.taskId(), task);
            }
        }

        // 3. Add tasks from sync flow (IngestProgressTracker)
        if (progressTracker != null) {
            for (IngestProgressUpdate task : progressTracker.getAllTasks()) {
                allTasks.put(task.taskId(), task);
            }
        }

        if (!allTasks.isEmpty()) {
            logger.debug("getAllIngestTasks returning {} tasks", allTasks.size());
            for (IngestProgressUpdate task : allTasks.values()) {
                logger.debug("  Task {}: phase={}, progress={}%, status={}",
                        task.taskId(), task.phase(), task.progressPercent(), task.status());
            }
        }

        return ResponseEntity.ok(allTasks.values());
    }

    @PostMapping("/process-batch")
    public ResponseEntity<?> handleProcessBatch(@RequestBody BatchProcessRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch request items cannot be empty."));
        }
        logger.info(
                "Received batch processing request for {} items. Default Loader: '{}', Default Chunker: '{}', Default Chunker Options: {}",
                request.items().size(), request.defaultLoaderName(), request.defaultChunkerName(),
                request.defaultChunkerOptions());

        Map<String, Object> aggregatedResults = new LinkedHashMap<>();
        long successCount = 0;
        long errorCount = 0;

        for (ControllerBatchLoadRequestItem item : request.items()) {
            DocumentSourceDescriptor sourceDescriptor = item.source();
            String itemKey;

            if (sourceDescriptor.getSourceId() != null && !sourceDescriptor.getSourceId().isEmpty()) {
                itemKey = sourceDescriptor.getSourceId();
            } else if (sourceDescriptor.getPathOrUrl() != null && !sourceDescriptor.getPathOrUrl().isEmpty()) {
                itemKey = sourceDescriptor.getPathOrUrl();
            } else {
                itemKey = "item_" + UUID.randomUUID().toString();
            }

            try {
                String loaderToUse = (item.loaderName() != null && !item.loaderName().isEmpty()) ? item.loaderName()
                        : request.defaultLoaderName();
                String chunkerNameToUse = (item.chunkerName() != null && !item.chunkerName().isEmpty())
                        ? item.chunkerName()
                        : request.defaultChunkerName();

                if (loaderToUse == null || loaderToUse.isEmpty()) {
                    logger.warn("Skipping item {} as no loader is specified (neither item-specific nor default).",
                            itemKey);
                    aggregatedResults.put(itemKey, Map.of("error", "Loader not specified for item."));
                    errorCount++;
                    continue;
                }

                logger.info("Loading documents for item: {} with loader: '{}'", itemKey, loaderToUse);

                DocumentLoader selectedLoader = documentLoaders.stream()
                        .filter(loader -> loaderToUse.equals(loader.getName()))
                        .findFirst()
                        .orElse(null);

                if (selectedLoader == null) {
                    logger.warn("Loader '{}' not found for item {}.", loaderToUse, itemKey);
                    aggregatedResults.put(itemKey, Map.of("error", "Loader not found: " + loaderToUse));
                    errorCount++;
                    continue;
                }

                List<Document> loadedDocs = selectedLoader.load(sourceDescriptor);

                if (loadedDocs == null) {
                    logger.warn("Loader '{}' returned null for item {}. Treating as empty list.", loaderToUse, itemKey);
                    loadedDocs = Collections.emptyList();
                }
                logger.info("Loaded {} documents for item: {} with loader: '{}'", loadedDocs.size(), itemKey,
                        loaderToUse);

                List<RetrievedDoc> finalDocs = convertToRetrievedDocs(loadedDocs);

                if (chunkerNameToUse != null && !chunkerNameToUse.isEmpty()) {
                    TextChunker selectedChunker = findChunkerByName(chunkerNameToUse);

                    if (selectedChunker != null) {
                        Map<String, Object> effectiveChunkerOptions = new HashMap<>();
                        if (request.defaultChunkerOptions() != null) {
                            effectiveChunkerOptions.putAll(request.defaultChunkerOptions());
                        }
                        if (item.chunkerOptions() != null) {
                            effectiveChunkerOptions.putAll(item.chunkerOptions());
                        }

                        logger.info("Applying chunker: '{}' to {} loaded documents for item {} with options: {}",
                                chunkerNameToUse, loadedDocs.size(), itemKey, effectiveChunkerOptions);

                        List<RetrievedDoc> finalDocs2 = new ArrayList<>();
                        for (Document doc : loadedDocs) {
                            finalDocs2
                                    .addAll(selectedChunker.chunk(convertToRetrievedDoc(doc), effectiveChunkerOptions));
                        }
                        finalDocs = finalDocs2;
                        logger.info("Chunking resulted in {} documents for item {}", finalDocs.size(), itemKey);

                    } else {
                        logger.warn("Chunker '{}' not found for item {}. Using loaded documents without chunking.",
                                chunkerNameToUse, itemKey);
                    }
                }

                aggregatedResults.put(itemKey, finalDocs);
                successCount++;

            } catch (Exception e) {
                logger.error("Error processing batch item {}: {}", itemKey, e.getMessage(), e);
                aggregatedResults.put(itemKey, Map.of("error", "Failed during processing: " + e.getMessage()));
                errorCount++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Batch processing completed.",
                "successful_items", successCount,
                "failed_items", errorCount,
                "details", aggregatedResults));
    }

    /**
     * New endpoint to process a specific uploaded file by name.
     */
    @PostMapping("/process-uploaded-file")
    public ResponseEntity<?> processSpecificUploadedFile(
            @RequestParam("fileName") String fileName,
            @RequestParam(name = "loader", required = false) String loaderName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName) {

        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server."));
        }

        try {
            Path filePath = this.uploadsPath.resolve(fileName).normalize();

            if (!filePath.startsWith(this.uploadsPath.normalize())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file path (directory traversal attempt)."));
            }

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "File not found: " + fileName));
            }

            logger.info("Processing specific uploaded file: {}", fileName.replace('\n', ' ').replace('\r', ' '));
            DocumentProcessingResult processingResult = processUploadedFile(filePath, loaderName, chunkerName);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "File '" + fileName + "' processed successfully.");
            response.put("fileName", fileName);
            response.put("processingCompleted", true);
            response.put("originalDocumentCount", processingResult.originalDocumentCount());
            response.put("finalChunkCount", processingResult.finalChunkCount());
            response.put("processedDocumentIds", processingResult.processedDocumentIds());
            response.put("loaderUsed", processingResult.loaderUsed());
            response.put("chunkerUsed", processingResult.chunkerUsed());
            response.put("indexingSuccessful", processingResult.indexingSuccessful());
            response.put("processingDetails", processingResult.processingDetails());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to process uploaded file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process file: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Convert a persisted IndexingJobHistory to an IngestProgressUpdate for the frontend.
     */
    private IngestProgressUpdate convertJobHistoryToProgressUpdate(IndexingJobHistory job) {
        IngestProgressUpdate.IngestStatus status = switch (job.getStatus()) {
            case QUEUED -> IngestProgressUpdate.IngestStatus.PENDING;
            case RUNNING -> IngestProgressUpdate.IngestStatus.IN_PROGRESS;
            case COMPLETED -> IngestProgressUpdate.IngestStatus.COMPLETED;
            case FAILED, MEMORY_KILLED -> IngestProgressUpdate.IngestStatus.FAILED;
            case CANCELLED -> IngestProgressUpdate.IngestStatus.CANCELLED;
            case PAUSED -> IngestProgressUpdate.IngestStatus.IN_PROGRESS;
        };

        IngestProgressUpdate.IngestPhase phase = mapToIngestPhase(job.getLastPhase());

        IngestProgressUpdate.IngestStats.Builder statsBuilder = IngestProgressUpdate.IngestStats.builder()
                .documentsLoaded(job.getDocumentsLoaded() != null ? job.getDocumentsLoaded() : 0)
                .chunksCreated(job.getChunksCreated() != null ? job.getChunksCreated() : 0)
                .chunksEmbedded(job.getChunksEmbedded() != null ? job.getChunksEmbedded() : 0)
                .chunksIndexed(job.getDocumentsIndexed() != null ? job.getDocumentsIndexed() : 0)
                .documentsIndexed(job.getDocumentsIndexed() != null ? job.getDocumentsIndexed() : 0)
                .totalProcessingTimeMs(job.getTotalDurationMs() != null ? job.getTotalDurationMs() : 0)
                .loaderUsed(job.getLoaderUsed())
                .chunkerUsed(job.getChunkerUsed())
                .loadingTimeMs(job.getLoadingDurationMs())
                .chunkingTimeMs(job.getChunkingDurationMs())
                .embeddingTimeMs(job.getEmbeddingDurationMs())
                .indexingTimeMs(job.getIndexingDurationMs())
                .workerThreads(job.getWorkerThreads())
                .memoryUsagePercent(job.getPeakMemoryUsagePercent() != null ? job.getPeakMemoryUsagePercent() : 0.0);

        if ("vector-population".equalsIgnoreCase(job.getContentType())
                && vectorStorePopulationService != null
                && vectorStorePopulationService.isSubprocessModeEnabled()) {
            statsBuilder.subprocessRuntimeInfo(
                    IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"));
        }

        IngestProgressUpdate.IngestStats stats = statsBuilder.build();

        String message = switch (job.getStatus()) {
            case QUEUED -> "Waiting to start processing...";
            case RUNNING -> "Processing: " + phase.name().toLowerCase();
            case COMPLETED -> "Completed successfully";
            case FAILED -> job.getErrorMessage() != null ? job.getErrorMessage() : "Failed";
            case CANCELLED -> "Cancelled by user";
            case MEMORY_KILLED -> "Killed due to memory pressure";
            case PAUSED -> "Paused";
        };

        IngestProgressUpdate.FailureReason failureReason = mapToFailureReason(job.getFailureReason(), job.getStatus());

        return new IngestProgressUpdate(
                job.getTaskId(),
                job.getFileName(),
                phase,
                status,
                job.getProgressPercent() != null ? job.getProgressPercent() : 0,
                phase.name(),
                message,
                stats,
                job.getErrorMessage(),
                job.getStartTime(),
                null,
                failureReason,
                null
        );
    }

    /**
     * Map IndexingJobHistory.FailureReason to IngestProgressUpdate.FailureReason.
     */
    private IngestProgressUpdate.FailureReason mapToFailureReason(IndexingJobHistory.FailureReason reason,
                                                                    IndexingJobHistory.JobStatus status) {
        if (status == IndexingJobHistory.JobStatus.MEMORY_KILLED) {
            return IngestProgressUpdate.FailureReason.OOM_KILLED;
        }
        if (status == IndexingJobHistory.JobStatus.CANCELLED) {
            return IngestProgressUpdate.FailureReason.USER_CANCELLED;
        }

        if (reason == null) {
            return null;
        }

        return switch (reason) {
            case NONE -> null;
            case OUT_OF_MEMORY -> IngestProgressUpdate.FailureReason.OUT_OF_MEMORY;
            case MEMORY_KILLED -> IngestProgressUpdate.FailureReason.OOM_KILLED;
            case USER_CANCELLED -> IngestProgressUpdate.FailureReason.USER_CANCELLED;
            case LOAD_ERROR -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case EMBEDDING_ERROR -> IngestProgressUpdate.FailureReason.EMBEDDING_ERROR;
            case INDEXING_ERROR -> IngestProgressUpdate.FailureReason.INDEXING_ERROR;
            case TIMEOUT -> IngestProgressUpdate.FailureReason.PROCESS_STUCK;
            case MODEL_NOT_FOUND, STAGING_ERROR -> IngestProgressUpdate.FailureReason.EMBEDDING_ERROR;
            case CONVERSION_ERROR, CHUNKING_ERROR, SUBPROCESS_ERROR, IO_ERROR, INVALID_INPUT, UNKNOWN ->
                    IngestProgressUpdate.FailureReason.UNKNOWN;
        };
    }

    /**
     * Map IngestEvent.IngestPhase to IngestProgressUpdate.IngestPhase.
     */
    private IngestProgressUpdate.IngestPhase mapToIngestPhase(IngestEvent.IngestPhase eventPhase) {
        if (eventPhase == null) {
            return IngestProgressUpdate.IngestPhase.QUEUED;
        }
        return switch (eventPhase) {
            case QUEUED -> IngestProgressUpdate.IngestPhase.QUEUED;
            case LOADING -> IngestProgressUpdate.IngestPhase.LOADING;
            case OCR_PROCESSING -> IngestProgressUpdate.IngestPhase.OCR_PROCESSING;
            case CONVERTING -> IngestProgressUpdate.IngestPhase.CONVERTING;
            case CHUNKING -> IngestProgressUpdate.IngestPhase.CHUNKING;
            case EXTRACTION -> IngestProgressUpdate.IngestPhase.EXTRACTION;
            case GRAPH_EXTRACTION -> IngestProgressUpdate.IngestPhase.GRAPH_EXTRACTION;
            case INDEXING_AND_EMBEDDING -> IngestProgressUpdate.IngestPhase.INDEXING_AND_EMBEDDING;
            case EMBEDDING -> IngestProgressUpdate.IngestPhase.EMBEDDING;
            case INDEXING -> IngestProgressUpdate.IngestPhase.INDEXING;
            case COMPLETED -> IngestProgressUpdate.IngestPhase.COMPLETED;
            case FAILED -> IngestProgressUpdate.IngestPhase.FAILED;
        };
    }

    /**
     * Parse a processing mode string into a ProcessingMode enum.
     */
    private DocumentIngestService.ProcessingMode parseProcessingMode(String modeString) {
        if (modeString == null || modeString.isBlank()) {
            return DocumentIngestService.ProcessingMode.AUTO;
        }
        switch (modeString.toLowerCase().trim()) {
            case "subprocess":
                return DocumentIngestService.ProcessingMode.SUBPROCESS;
            case "inprocess":
            case "in-process":
            case "in_process":
                return DocumentIngestService.ProcessingMode.INPROCESS;
            case "auto":
            default:
                return DocumentIngestService.ProcessingMode.AUTO;
        }
    }

    /**
     * Build subprocess options map from per-request parameters.
     * Returns null if no options are specified (use global config).
     */
    private Map<String, Object> buildSubprocessOptions(String heapSize, Integer timeoutMinutes,
            Integer heartbeatSeconds, Integer staleThresholdSeconds) {
        if (heapSize == null && timeoutMinutes == null && heartbeatSeconds == null && staleThresholdSeconds == null) {
            return null;
        }
        Map<String, Object> options = new HashMap<>();
        if (heapSize != null && !heapSize.isBlank()) {
            options.put("heapSize", heapSize);
        }
        if (timeoutMinutes != null) {
            options.put("timeoutMinutes", timeoutMinutes);
        }
        if (heartbeatSeconds != null) {
            options.put("heartbeatSeconds", heartbeatSeconds);
        }
        if (staleThresholdSeconds != null) {
            options.put("staleThresholdSeconds", staleThresholdSeconds);
        }
        return options.isEmpty() ? null : options;
    }
}
