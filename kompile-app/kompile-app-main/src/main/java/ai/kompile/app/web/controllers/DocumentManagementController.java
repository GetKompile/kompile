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
import ai.kompile.app.services.YouTubeTranscriptService;
import ai.kompile.app.web.dto.AsyncUploadResponse;
import ai.kompile.app.web.dto.BatchAsyncUploadResponse;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentManagementController.class);

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties;
    private final RestTemplate restTemplate;
    private final List<DocumentLoader> documentLoaders;
    private final DocumentLoadingService documentLoadingService;
    private final List<TextChunker> textChunkers;
    private IndexerService indexerService;
    private final DocumentIngestService documentIngestService;
    private final IngestProgressTracker progressTracker;
    private final VectorStorePopulationService vectorStorePopulationService;
    private final YouTubeTranscriptService youTubeTranscriptService;
    private final SourceDocumentStorageService sourceDocumentStorageService;
    private final SourceMarkdownConversionService sourceMarkdownConversionService;
    private final FactSheetService factSheetService;
    private final IndexingJobHistoryRepository jobHistoryRepository;

    @Autowired
    public DocumentManagementController(
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) RestTemplate restTemplate,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) DocumentLoadingService documentLoadingService,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) List<IndexerService> indexerService,
            @Autowired(required = false) DocumentIngestService documentIngestService,
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) VectorStorePopulationService vectorStorePopulationService,
            @Autowired(required = false) YouTubeTranscriptService youTubeTranscriptService,
            @Autowired(required = false) SourceDocumentStorageService sourceDocumentStorageService,
            @Autowired(required = false) SourceMarkdownConversionService sourceMarkdownConversionService,
            @Autowired(required = false) FactSheetService factSheetService,
            @Autowired(required = false) IndexingJobHistoryRepository jobHistoryRepository) {
        this.sourceProperties = appDocumentSourceProperties;
        this.jobHistoryRepository = jobHistoryRepository;
        this.youTubeTranscriptService = youTubeTranscriptService;
        this.restTemplate = restTemplate;
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.documentLoadingService = documentLoadingService;
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.documentIngestService = documentIngestService;
        this.progressTracker = progressTracker;
        this.vectorStorePopulationService = vectorStorePopulationService;
        this.sourceDocumentStorageService = sourceDocumentStorageService != null ? sourceDocumentStorageService : new SourceDocumentStorageService();
        this.sourceMarkdownConversionService = sourceMarkdownConversionService != null
                ? sourceMarkdownConversionService
                : new SourceMarkdownConversionService(this.sourceDocumentStorageService, appDocumentSourceProperties);
        this.factSheetService = factSheetService;

        // Log which dependencies are not available
        if (documentIngestService == null) {
            logger.warn("DocumentManagementController: DocumentIngestService is not available");
        }
        if (progressTracker == null) {
            logger.warn("DocumentManagementController: IngestProgressTracker is not available");
        }
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("DocumentManagementController: No DocumentLoaders available");
        }
        if (textChunkers == null || textChunkers.isEmpty()) {
            logger.warn("DocumentManagementController: No TextChunkers available");
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
            logger.warn("DocumentManagementController: No IndexerService available");
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
                    logger.info("Created uploads directory for DocumentManagementController: {}", this.uploadsPath);
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

    public record AddUrlRequest(String url, String fileName, String loader, String chunkerName, boolean convertToMarkdown) {
    }

    public record LoaderInfo(String name, String className) {
    }

    public record ChunkerInfo(String name, String className) {
    }

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

    public record RetrievedDocInfo(
            String id,
            String text,
            Double score,
            Map<String, Object> metadata,
            int contentLength,
            String contentPreview) {
    }

    /**
     * Base path handler - returns API overview and available endpoints
     * This handles GET /api/documents requests
     */
    @GetMapping
    public ResponseEntity<?> getDocumentsApiInfo() {
        logger.debug("Received request to base /api/documents endpoint");
        Map<String, Object> apiInfo = new LinkedHashMap<>();
        apiInfo.put("message", "Document Management API");
        apiInfo.put("version", "1.0");
        apiInfo.put("endpoints", Map.of(
                "GET /api/documents", "This endpoint - API overview",
                "GET /api/documents/loaders", "List available document loaders",
                "GET /api/documents/chunkers", "List available text chunkers",
                "GET /api/documents/sources", "List configured document sources",
                "GET /api/documents/uploaded-files", "List uploaded files",
                "GET /api/documents/processing-status", "Get processing status",
                "POST /api/documents/upload", "Upload a file for processing",
                "POST /api/documents/add-url", "Add a URL for processing",
                "POST /api/documents/add-path", "Add a server path for processing"));

        // Add status info
        boolean uploadsDirectoryReady = this.uploadsPath != null &&
                !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString());
        apiInfo.put("uploadsDirectoryConfigured", uploadsDirectoryReady);
        apiInfo.put("loadersAvailable", documentLoaders != null ? documentLoaders.size() : 0);
        apiInfo.put("chunkersAvailable", textChunkers != null ? textChunkers.size() : 0);

        return ResponseEntity.ok(apiInfo);
    }

    public record AddPathRequest(String path, String loader, String chunkerName) {
    }

    /**
     * Endpoint to trigger ingestion from a server-side path.
     * Useful for ingesting directories, existing indices, or large files already
     * present on the server.
     */
    @PostMapping("/add-path")
    public ResponseEntity<?> addDocumentPath(@RequestBody AddPathRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        String pathStr = request.path().trim();
        Path path = Paths.get(pathStr);

        if (!Files.exists(path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path does not exist on server: " + pathStr));
        }

        String taskId = UUID.randomUUID().toString();
        logger.info("Received request to ingest path: {} (Task ID: {})", pathStr, taskId);

        try {
            // Trigger async processing
            documentIngestService.processDocumentAsync(
                    taskId,
                    path,
                    request.loader(),
                    request.chunkerName(),
                    null // Let service determine mode (will auto-detect large docs/indices)
            );

            return ResponseEntity.ok(Map.of(
                    "message", "Processing started for path: " + pathStr,
                    "taskId", taskId));

        } catch (Exception e) {
            logger.error("Failed to start processing for path: {}", pathStr, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start processing: " + e.getMessage()));
        }
    }

    @GetMapping("/loaders")
    public ResponseEntity<List<LoaderInfo>> listAvailableLoaders() {
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("No DocumentLoader beans found. listAvailableLoaders will return an empty list.");
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<LoaderInfo> loaderInfos = documentLoaders.stream()
                .map(loader -> new LoaderInfo(loader.getName(), loader.getClass().getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(loaderInfos);
    }

    @GetMapping("/chunkers")
    public ResponseEntity<List<ChunkerInfo>> listAvailableChunkers() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            logger.warn("No TextChunker beans found. listAvailableChunkers will return an empty list.");
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<ChunkerInfo> chunkerInfos = textChunkers.stream()
                .map(chunker -> new ChunkerInfo(chunker.getName(), chunker.getClass().getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(chunkerInfos);
    }

    /**
     * Debug endpoint to trace chunker resolution from UI name to backend chunker.
     * This tests the resolution logic in DocumentManagementController.
     *
     * @param requestedName the chunker name as sent from the UI
     * @return detailed debug info about chunker resolution
     */
    @GetMapping("/debug/chunker-resolution")
    public ResponseEntity<Map<String, Object>> debugChunkerResolution(
            @RequestParam(name = "name", required = false) String requestedName) {

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("requestedName", requestedName);
        debug.put("timestamp", System.currentTimeMillis());

        // List all available chunkers
        List<Map<String, String>> availableChunkers = new ArrayList<>();
        if (textChunkers != null) {
            for (TextChunker chunker : textChunkers) {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("getName", chunker.getName());
                info.put("className", chunker.getClass().getName());
                info.put("simpleClassName", chunker.getClass().getSimpleName());
                info.put("isNoOp", String.valueOf(isNoOpChunker(chunker)));
                availableChunkers.add(info);
            }
        }
        debug.put("availableChunkers", availableChunkers);
        debug.put("chunkerCount", availableChunkers.size());

        // Show alias mapping
        debug.put("aliasMap", CHUNKER_ALIASES);

        if (requestedName != null && !requestedName.isEmpty()) {
            // Trace resolution step by step
            Map<String, Object> resolution = new LinkedHashMap<>();

            // Step 1: Exact match
            TextChunker exactMatch = textChunkers.stream()
                    .filter(chunker -> requestedName.equals(chunker.getName()))
                    .findFirst()
                    .orElse(null);
            resolution.put("step1_exactMatch", exactMatch != null ? exactMatch.getName() : null);

            // Step 2: Alias lookup
            String mappedName = CHUNKER_ALIASES.get(requestedName);
            resolution.put("step2_aliasLookup", mappedName);

            TextChunker aliasMatch = null;
            if (mappedName != null) {
                aliasMatch = textChunkers.stream()
                        .filter(chunker -> mappedName.equals(chunker.getName()))
                        .findFirst()
                        .orElse(null);
            }
            resolution.put("step2_aliasMatch", aliasMatch != null ? aliasMatch.getName() : null);

            // Step 3: Partial match
            String normalizedRequest = requestedName.toLowerCase().replace("_", "-").replace("spring-", "");
            resolution.put("step3_normalizedName", normalizedRequest);

            TextChunker partialMatch = textChunkers.stream()
                    .filter(chunker -> {
                        String name = chunker.getName().toLowerCase();
                        return name.contains(normalizedRequest) || normalizedRequest.contains(name);
                    })
                    .findFirst()
                    .orElse(null);
            resolution.put("step3_partialMatch", partialMatch != null ? partialMatch.getName() : null);

            // Final result using findChunkerByName
            TextChunker finalResult = findChunkerByName(requestedName);
            resolution.put("finalResult", finalResult != null ? finalResult.getName() : null);
            resolution.put("finalResultClass", finalResult != null ? finalResult.getClass().getSimpleName() : null);

            // What selectBestChunker would return (fallback)
            TextChunker fallback = selectBestChunker();
            resolution.put("fallbackChunker", fallback != null ? fallback.getName() : null);
            resolution.put("fallbackChunkerClass", fallback != null ? fallback.getClass().getSimpleName() : null);

            debug.put("resolution", resolution);
        }

        return ResponseEntity.ok(debug);
    }

    /**
     * Debug endpoint to trace chunker resolution via DocumentIngestService.
     * This tests the ACTUAL resolution logic used during document processing.
     *
     * @param requestedName the chunker name as sent from the UI
     * @return detailed debug info about chunker resolution in the ingest service
     */
    @GetMapping("/debug/ingest-chunker-resolution")
    public ResponseEntity<Map<String, Object>> debugIngestChunkerResolution(
            @RequestParam(name = "name", required = false) String requestedName) {
        if (documentIngestService == null) {
            return ResponseEntity.ok(Map.of(
                    "error", "DocumentIngestService not available",
                    "endpoint", "ingest-service-resolution"));
        }
        Map<String, Object> result = documentIngestService.debugChunkerResolution(requestedName);
        result.put("endpoint", "ingest-service-resolution");
        result.put("note", "This tests the chunker resolution logic in DocumentIngestService.findChunker()");
        return ResponseEntity.ok(result);
    }

    /**
     * Converts a Spring AI Document to a RetrievedDoc
     */
    private RetrievedDoc convertToRetrievedDoc(Document document) {
        return RetrievedDoc.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(document.getMetadata())
                .build();
    }

    /**
     * Converts a list of Spring AI Documents to RetrievedDocs
     */
    private List<RetrievedDoc> convertToRetrievedDocs(List<Document> documents) {
        return documents.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());
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
                List<org.springframework.ai.document.Document> loadedDocs = documentLoadingService
                        .loadDocumentsFromSource(sourceDescriptor, loaderToUse);

                if (loadedDocs == null) {
                    logger.warn("Loader '{}' returned null for item {}. Treating as empty list.", loaderToUse, itemKey);
                    loadedDocs = Collections.emptyList();
                }
                logger.info("Loaded {} documents for item: {} with loader: '{}'", loadedDocs.size(), itemKey,
                        loaderToUse);

                List<RetrievedDoc> finalDocs = convertToRetrievedDocs(loadedDocs);

                if (chunkerNameToUse != null && !chunkerNameToUse.isEmpty()) {
                    // Use flexible name matching for UI compatibility
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
     * Test endpoint specifically for debugging chunker behavior
     */
    @PostMapping("/test-chunker")
    public ResponseEntity<?> testChunker(@RequestParam("fileName") String fileName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName,
            @RequestParam(name = "testText", required = false) String testText) {

        Map<String, Object> testResults = new HashMap<>();

        try {
            // Test with either uploaded file or provided test text
            String contentToTest = null;

            if (testText != null && !testText.trim().isEmpty()) {
                contentToTest = testText;
                testResults.put("source", "provided_test_text");
                testResults.put("contentLength", contentToTest.length());
            } else if (fileName != null) {
                // Load content from uploaded file
                if (this.uploadsPath == null
                        || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Uploads directory is not configured correctly."));
                }

                Path filePath = this.uploadsPath.resolve(fileName).normalize();
                if (!filePath.startsWith(this.uploadsPath.normalize()) || !Files.exists(filePath)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "File not found: " + fileName));
                }

                // Load document content
                DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                        .type(DocumentSourceDescriptor.SourceType.FILE)
                        .pathOrUrl(filePath.toString())
                        .originalFileName(fileName)
                        .sourceId("test_" + fileName)
                        .build();

                DocumentLoader loader = documentLoaders.stream()
                        .filter(l -> l.supports(sourceDescriptor))
                        .findFirst()
                        .orElse(null);

                if (loader == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "No suitable loader found for file"));
                }

                List<Document> docs = loader.load(sourceDescriptor);
                if (docs.isEmpty() || docs.get(0).getText() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "No content loaded from file"));
                }

                contentToTest = docs.get(0).getText();
                testResults.put("source", "file_" + fileName);
                testResults.put("contentLength", contentToTest.length());
                testResults.put("loadedDocuments", docs.size());
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Either fileName or testText must be provided"));
            }

            testResults.put("contentPreview",
                    contentToTest.length() > 300 ? contentToTest.substring(0, 300) + "..." : contentToTest);

            // Test all available chunkers or specific one
            List<TextChunker> chunkersToTest = new ArrayList<>();
            if (chunkerName != null && !chunkerName.isEmpty()) {
                // Use flexible name matching for UI compatibility
                TextChunker specific = findChunkerByName(chunkerName);
                if (specific != null) {
                    chunkersToTest.add(specific);
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "Chunker not found: " + chunkerName +
                            ". Available: "
                            + textChunkers.stream().map(TextChunker::getName).collect(Collectors.joining(", "))));
                }
            } else {
                chunkersToTest.addAll(textChunkers);
            }

            List<Map<String, Object>> chunkerResults = new ArrayList<>();

            for (TextChunker chunker : chunkersToTest) {
                Map<String, Object> result = new HashMap<>();
                result.put("chunkerName", chunker.getName());
                result.put("chunkerClass", chunker.getClass().getName());
                result.put("isNoOp", isNoOpChunker(chunker));

                if (isNoOpChunker(chunker)) {
                    result.put("skipped", true);
                    result.put("reason", "Detected as no-op chunker");
                } else {
                    try {
                        // Create test document
                        Document testDoc = new Document(contentToTest);
                        testDoc.getMetadata().put("test", true);

                        // Test with different chunk sizes
                        int[] chunkSizes = { 500, 1000, 2000 };
                        List<Map<String, Object>> sizeTests = new ArrayList<>();

                        for (int chunkSize : chunkSizes) {
                            Map<String, Object> chunkingOptions = new HashMap<>();
                            chunkingOptions.put("chunkSize", chunkSize);
                            chunkingOptions.put("overlap", chunkSize / 5); // 20% overlap

                            Map<String, Object> sizeTest = new HashMap<>();
                            sizeTest.put("chunkSize", chunkSize);
                            sizeTest.put("options", chunkingOptions);

                            try {
                                long startTime = System.currentTimeMillis();
                                List<RetrievedDoc> chunks = chunker.chunk(convertToRetrievedDoc(testDoc),
                                        chunkingOptions);
                                long duration = System.currentTimeMillis() - startTime;

                                sizeTest.put("success", true);
                                sizeTest.put("chunksCreated", chunks.size());
                                sizeTest.put("processingTimeMs", duration);

                                if (!chunks.isEmpty()) {
                                    // Analyze chunk quality
                                    List<Integer> chunkLengths = chunks.stream()
                                            .map(c -> c.getText() != null ? c.getText().length() : 0)
                                            .collect(Collectors.toList());

                                    sizeTest.put("chunkLengths", chunkLengths);
                                    sizeTest.put("avgChunkLength",
                                            chunkLengths.stream().mapToInt(Integer::intValue).average().orElse(0));
                                    sizeTest.put("minChunkLength",
                                            chunkLengths.stream().mapToInt(Integer::intValue).min().orElse(0));
                                    sizeTest.put("maxChunkLength",
                                            chunkLengths.stream().mapToInt(Integer::intValue).max().orElse(0));

                                    // Sample chunks
                                    List<Map<String, Object>> samples = new ArrayList<>();
                                    for (int i = 0; i < Math.min(3, chunks.size()); i++) {
                                        RetrievedDoc chunk = chunks.get(i);
                                        Map<String, Object> sample = new HashMap<>();
                                        sample.put("index", i);
                                        sample.put("length", chunk.getText() != null ? chunk.getText().length() : 0);
                                        sample.put("preview",
                                                chunk.getText() != null && chunk.getText().length() > 100
                                                        ? chunk.getText().substring(0, 100) + "..."
                                                        : chunk.getText());
                                        samples.add(sample);
                                    }
                                    sizeTest.put("sampleChunks", samples);
                                }

                            } catch (Exception e) {
                                sizeTest.put("success", false);
                                sizeTest.put("error", e.getMessage());
                                sizeTest.put("errorClass", e.getClass().getSimpleName());
                            }

                            sizeTests.add(sizeTest);
                        }

                        result.put("sizeTests", sizeTests);

                    } catch (Exception e) {
                        result.put("success", false);
                        result.put("error", e.getMessage());
                        result.put("errorClass", e.getClass().getSimpleName());
                    }
                }

                chunkerResults.add(result);
            }

            testResults.put("chunkerResults", chunkerResults);
            testResults.put("totalChunkersTested", chunkersToTest.size());

            return ResponseEntity.ok(testResults);

        } catch (Exception e) {
            logger.error("Error in chunker test: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Test failed: " + e.getMessage()));
        }
    }

    /**
     * Determines if a chunker is a no-op/stub implementation that should be avoided
     * when real chunkers are available
     */
    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null)
            return true;

        String className = chunker.getClass().getSimpleName().toLowerCase();
        String chunkerName = chunker.getName().toLowerCase();
        String fullClassName = chunker.getClass().getName().toLowerCase();

        // Check for common no-op/stub indicators in class names
        boolean isNoOpClass = className.contains("noop") ||
                className.contains("dummy") ||
                className.contains("mock") ||
                className.contains("stub") ||
                className.contains("default") ||
                className.contains("fallback") ||
                className.contains("empty");

        // Check for no-op indicators in chunker names
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

        // Check for core package indicators (these are likely stubs/defaults)
        boolean isCorePackage = fullClassName.contains(".core.") &&
                (fullClassName.contains("noop") ||
                        fullClassName.contains("default") ||
                        fullClassName.contains("stub"));

        return isNoOpClass || isNoOpName || isCorePackage;
    }

    /**
     * Mapping of UI chunker strategy IDs to backend chunker names.
     * This handles the mismatch between frontend naming conventions and backend
     * getName() values.
     * Key = UI name (from CHUNKER_STRATEGIES in api-models.ts)
     * Value = Backend chunker getName() return value
     */
    private static final Map<String, String> CHUNKER_ALIASES = Map.ofEntries(
            // UI "spring_recursive_character" -> backend "recursive-character"
            // (RecursiveCharacterTextChunker)
            Map.entry("spring_recursive_character", "recursive-character"),
            Map.entry("custom_recursive_character", "recursive-character"),
            Map.entry("recursive-character", "recursive-character"),
            // UI "opennlp_sentence" -> backend "opennlp_sentence" (OpenNLPSentenceChunker)
            Map.entry("opennlp_sentence", "opennlp_sentence"),
            // UI "sentence" -> backend "sentence" (SentenceTextChunker)
            Map.entry("sentence", "sentence"),
            // Token chunker
            Map.entry("spring_token", "spring_token"),
            // Markdown chunkers
            Map.entry("custom_markdown", "custom_markdown"),
            Map.entry("spring_markdown", "spring_markdown"));

    /**
     * Finds a chunker by name, supporting both exact matches and UI alias mappings.
     * This allows the UI to use different naming conventions than the backend.
     *
     * @param requestedName the chunker name requested (from UI or API)
     * @return the matching TextChunker, or null if not found
     */
    private TextChunker findChunkerByName(String requestedName) {
        if (requestedName == null || requestedName.isEmpty() || textChunkers == null) {
            return null;
        }

        // First try exact match
        Optional<TextChunker> exactMatch = textChunkers.stream()
                .filter(chunker -> requestedName.equals(chunker.getName()))
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }

        // Try alias mapping (UI name -> backend name)
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

        // Try partial/contains match as fallback (e.g., "recursive" matches
        // "recursive-character")
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
     * Gets the best available chunker, prioritizing real implementations over stubs
     */
    private TextChunker selectBestChunker() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            return null;
        }

        // First, filter out no-op chunkers
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

        // Prioritize chunkers by type/name preference
        List<String> preferredPatterns = Arrays.asList(
                "opennlp", // OpenNLP chunkers (your preference)
                "recursive", // Recursive character chunkers
                "character", // Character-based chunkers
                "sentence", // Sentence-based chunkers
                "markdown", // Markdown-aware chunkers
                "token" // Token-based chunkers
        );

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

        // If no preferred chunker found, use the first real chunker
        TextChunker selected = realChunkers.get(0);
        logger.info("No preferred chunker found, using first real chunker: {} ({})",
                selected.getName(), selected.getClass().getSimpleName());
        return selected;
    }

    /**
     * Debug endpoint to analyze document content without processing
     */
    @PostMapping("/debug-document")
    public ResponseEntity<?> debugDocument(@RequestParam("fileName") String fileName) {
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

            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("fileName", fileName);
            debugInfo.put("filePath", filePath.toString());
            debugInfo.put("fileSize", Files.size(filePath));

            // Find appropriate loader
            DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(filePath.getFileName().toString())
                    .build();

            List<Map<String, Object>> loaderInfo = new ArrayList<>();
            for (DocumentLoader loader : documentLoaders) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", loader.getName());
                info.put("className", loader.getClass().getName());
                info.put("supports", loader.supports(tempDescriptor));
                loaderInfo.add(info);
            }
            debugInfo.put("availableLoaders", loaderInfo);

            // Try to load with auto-detected loader
            DocumentLoader selectedLoader = documentLoaders.stream()
                    .filter(loader -> loader.supports(tempDescriptor))
                    .findFirst()
                    .orElse(null);

            if (selectedLoader != null) {
                debugInfo.put("selectedLoader", selectedLoader.getName());

                try {
                    DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                            .type(DocumentSourceDescriptor.SourceType.FILE)
                            .pathOrUrl(filePath.toString())
                            .originalFileName(filePath.getFileName().toString())
                            .sourceId("debug_" + fileName)
                            .build();

                    List<Document> loadedDocuments = selectedLoader.load(sourceDescriptor);
                    debugInfo.put("documentsLoaded", loadedDocuments.size());

                    List<Map<String, Object>> documentDetails = new ArrayList<>();
                    for (int i = 0; i < loadedDocuments.size(); i++) {
                        Document doc = loadedDocuments.get(i);
                        Map<String, Object> docInfo = new HashMap<>();
                        docInfo.put("index", i);
                        docInfo.put("id", doc.getId());
                        docInfo.put("contentLength", doc.getText() != null ? doc.getText().length() : 0);
                        docInfo.put("hasContent", doc.getText() != null && !doc.getText().trim().isEmpty());
                        docInfo.put("contentPreview",
                                doc.getText() != null && doc.getText().length() > 200
                                        ? doc.getText().substring(0, 200) + "..."
                                        : doc.getText());
                        docInfo.put("metadata", doc.getMetadata());
                        documentDetails.add(docInfo);
                    }
                    debugInfo.put("documentDetails", documentDetails);

                    // Test chunking with available chunkers
                    if (!loadedDocuments.isEmpty() && !textChunkers.isEmpty()) {
                        List<Map<String, Object>> chunkingResults = new ArrayList<>();

                        // Show all chunkers and which ones are filtered out
                        List<TextChunker> realChunkers = textChunkers.stream()
                                .filter(chunker -> !isNoOpChunker(chunker))
                                .collect(Collectors.toList());

                        debugInfo.put("totalChunkers", textChunkers.size());
                        debugInfo.put("realChunkers", realChunkers.size());
                        debugInfo.put("filteredOutChunkers", textChunkers.size() - realChunkers.size());

                        for (TextChunker chunker : textChunkers) {
                            Map<String, Object> chunkResult = new HashMap<>();
                            chunkResult.put("chunkerName", chunker.getName());
                            chunkResult.put("chunkerClass", chunker.getClass().getName());
                            chunkResult.put("isNoOp", isNoOpChunker(chunker));

                            if (!isNoOpChunker(chunker)) {
                                try {
                                    Map<String, Object> chunkingOptions = new HashMap<>();
                                    chunkingOptions.put("chunkSize", 1000);
                                    chunkingOptions.put("overlap", 200);

                                    List<RetrievedDoc> chunks = chunker
                                            .chunk(convertToRetrievedDoc(loadedDocuments.get(0)), chunkingOptions);
                                    chunkResult.put("chunksCreated", chunks.size());
                                    chunkResult.put("success", true);

                                    // Sample chunk info
                                    if (!chunks.isEmpty()) {
                                        List<Map<String, Object>> sampleChunks = new ArrayList<>();
                                        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
                                            RetrievedDoc chunk = chunks.get(i);
                                            Map<String, Object> chunkInfo = new HashMap<>();
                                            chunkInfo.put("index", i);
                                            chunkInfo.put("length",
                                                    chunk.getText() != null ? chunk.getText().length() : 0);
                                            chunkInfo.put("preview",
                                                    chunk.getText() != null && chunk.getText().length() > 100
                                                            ? chunk.getText().substring(0, 100) + "..."
                                                            : chunk.getText());
                                            sampleChunks.add(chunkInfo);
                                        }
                                        chunkResult.put("sampleChunks", sampleChunks);
                                    }
                                } catch (Exception e) {
                                    chunkResult.put("success", false);
                                    chunkResult.put("error", e.getMessage());
                                    chunkResult.put("chunksCreated", 0);
                                }
                            } else {
                                chunkResult.put("skipped", true);
                                chunkResult.put("reason", "Identified as no-op chunker");
                            }

                            chunkingResults.add(chunkResult);
                        }
                        debugInfo.put("chunkingResults", chunkingResults);

                        // Show which chunker would be auto-selected
                        if (!realChunkers.isEmpty()) {
                            TextChunker autoSelected = realChunkers.stream()
                                    .filter(chunker -> {
                                        String name = chunker.getName().toLowerCase();
                                        return name.contains("opennlp") ||
                                                name.contains("recursive") ||
                                                name.contains("character") ||
                                                name.contains("markdown") ||
                                                name.contains("sentence");
                                    })
                                    .findFirst()
                                    .orElse(realChunkers.get(0));

                            debugInfo.put("autoSelectedChunker", autoSelected.getName());
                        }
                    }

                } catch (Exception e) {
                    debugInfo.put("loadingError", e.getMessage());
                }
            } else {
                debugInfo.put("selectedLoader", "None found");
            }

            return ResponseEntity.ok(debugInfo);

        } catch (Exception e) {
            logger.error("Error debugging document {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to debug document: " + e.getMessage()));
        }
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * This overload does not track progress via WebSocket.
     */
    private DocumentProcessingResult processUploadedFile(Path filePath, String loaderName, String chunkerName)
            throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, null, null);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * This overload accepts an optional source URL for web-sourced documents.
     */
    private DocumentProcessingResult processUploadedFile(Path filePath, String loaderName, String chunkerName,
            String sourceUrl) throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, null, sourceUrl);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * If taskId is provided, sends real-time progress updates via WebSocket.
     */
    private DocumentProcessingResult processUploadedFileWithTracking(Path filePath, String loaderName,
            String chunkerName, String taskId) throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, taskId, null);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * If taskId is provided, sends real-time progress updates via WebSocket.
     * If sourceUrl is provided, stores the original URL for web-sourced documents.
     */
    private DocumentProcessingResult processUploadedFileWithTracking(Path filePath, String loaderName,
            String chunkerName, String taskId, String sourceUrl) throws Exception {
        String fileName = filePath.getFileName().toString();
        IngestProgressTracker.TaskProgressContext progressContext = null;

        // Initialize progress tracking if taskId is provided and tracker is available
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

        // Debug: List available loaders and chunkers
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
            // Auto-detect loader
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

        // Send progress update: Loading phase started
        if (progress != null) {
            progress.setLoaderUsed(selectedLoader.getName());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.LOADING, 10,
                    "Loading document", "Using loader: " + selectedLoader.getName());
        }

        // Step 2: Load documents
        // Build metadata - if this is a URL source, store the original URL in metadata
        // Note: pathOrUrl must point to the local file for the loader to read it
        // The source_url in metadata preserves the original URL for attribution
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("upload_timestamp", System.currentTimeMillis());
        metadata.put("upload_path", filePath.toString());
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            // Store the original URL in the standard metadata key
            metadata.put(ai.kompile.core.source.SourceMetadataConstants.SOURCE_URL, sourceUrl);
        }

        // Use URL type if we have a source URL, but pathOrUrl must still be the local file path
        // so the loader can read the content from disk
        DocumentSourceDescriptor.SourceType descriptorType = (sourceUrl != null && !sourceUrl.isEmpty())
                ? DocumentSourceDescriptor.SourceType.URL
                : DocumentSourceDescriptor.SourceType.FILE;

        // Store the document and its metadata (including source_url) for later retrieval
        String storedChecksum = null;
        if (sourceDocumentStorageService != null && sourceDocumentStorageService.isEnabled()) {
            try {
                var storageResult = sourceDocumentStorageService.storeDocument(filePath);
                if (storageResult.isPresent()) {
                    storedChecksum = storageResult.get().checksum();
                    // Store metadata alongside the document (for source_url lookup)
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
                .pathOrUrl(filePath.toString())  // Always use local file path for loader to read
                .originalFileName(filePath.getFileName().toString())
                .sourceId((sourceUrl != null ? "url_" : "upload_") + filePath.getFileName().toString())
                .checksum(storedChecksum)
                .metadata(metadata)
                .build();

        List<Document> loadedDocuments = selectedLoader.load(sourceDescriptor);
        logger.info("Loaded {} documents from file: {} using loader: '{}'",
                loadedDocuments.size(), filePath, selectedLoader.getName());

        // Send progress update: Documents loaded
        if (progress != null) {
            progress.setDocumentsLoaded(loadedDocuments.size());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.LOADING, 25,
                    "Documents loaded", String.format("Loaded %d documents from file", loadedDocuments.size()));
        }

        // Enhanced debugging: Log detailed document content analysis
        if (loadedDocuments.isEmpty()) {
            logger.error("CRITICAL: No documents loaded from file: {}", filePath);
            return new DocumentProcessingResult(
                    0, 0, Collections.emptyList(), selectedLoader.getName(),
                    chunkerName != null ? chunkerName : "none", false,
                    "No documents could be loaded from the file.");
        }

        // Detailed content analysis for each loaded document
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
                    // Show first 500 characters to understand what was extracted
                    String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;
                    logger.info("Content preview (first 500 chars): '{}'", preview);

                    // Show last 200 characters to see end of content
                    if (content.length() > 200) {
                        String ending = content.substring(Math.max(0, content.length() - 200));
                        logger.info("Content ending (last 200 chars): '{}'", ending);
                    }

                    // Analyze content characteristics
                    long lineCount = content.lines().count();
                    long wordCount = content.split("\\s+").length;
                    boolean containsCommonWords = content.toLowerCase().contains("the") ||
                            content.toLowerCase().contains("and") ||
                            content.toLowerCase().contains("chapter");

                    logger.info("Content stats - Lines: {}, Words: {}, Contains common words: {}",
                            lineCount, wordCount, containsCommonWords);

                    // Check for PDF extraction issues
                    boolean hasSpecialChars = content.contains("�") || content.contains("\ufffd");
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
                // Log some common metadata that might indicate PDF processing issues
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

        // Step 3: Apply chunking - Enhanced logic with intelligent chunker selection
        List<RetrievedDoc> finalDocuments = new ArrayList<>(convertToRetrievedDocs(loadedDocuments));
        String actualChunkerUsed = "none";

        // Determine which chunker to use
        TextChunker selectedChunker = null;

        if (chunkerName != null && !chunkerName.isEmpty()) {
            // User specified a chunker - try to use it (with flexible name matching)
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
            // No chunker specified - auto-select the best available
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

            // Send progress update: Chunking phase started
            if (progress != null) {
                progress.setChunkerUsed(selectedChunker.getName());
                progress.updateProgress(IngestProgressUpdate.IngestPhase.CHUNKING, 35,
                        "Starting chunking", "Using chunker: " + selectedChunker.getName());
            }

            finalDocuments.clear();

            // Use configurable chunking options with sensible defaults
            Map<String, Object> chunkingOptions = new HashMap<>();
            chunkingOptions.put("chunkSize", 1000);
            chunkingOptions.put("overlap", 200);
            chunkingOptions.put("maxChunkSize", 2000); // For safety
            chunkingOptions.put("minChunkSize", 100); // Avoid tiny chunks
            // CRITICAL: Disable garbage collection to prevent all chunks from being filtered
            // into a single "garbage" chunk. The SentenceFilter marks any chunk not ending
            // with . ! ? as garbage, which is too aggressive for most PDF content.
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
                    finalDocuments.add(doc); // Add as-is if no content
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
                        // Detailed analysis of each chunk
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
                                // Show first 100 chars of each chunk
                                String chunkPreview = chunkText.length() > 100 ? chunkText.substring(0, 100) + "..."
                                        : chunkText;
                                logger.info("  Chunk {} preview: '{}'", j, chunkPreview);

                                // Check if chunk is just the original document (indicates chunker failure)
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

                    // Add original document if chunking fails
                    finalDocuments.add(doc);
                    totalChunksCreated++;
                }
                logger.info("=== END CHUNKING DOCUMENT {} ===", i);
            }

            actualChunkerUsed = selectedChunker.getName();
            logger.info("Chunking completed with '{}'. {} original documents became {} chunks (total created: {})",
                    selectedChunker.getName(), loadedDocuments.size(), finalDocuments.size(), totalChunksCreated);

            // Send progress update: Chunking complete
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

        // Send progress update: Indexing phase started
        if (progress != null) {
            progress.updateProgress(IngestProgressUpdate.IngestPhase.INDEXING, 70,
                    "Indexing documents", String.format("Indexing %d documents", finalDocuments.size()));
        }

        // Debug: Log final document details before indexing
        for (int i = 0; i < Math.min(finalDocuments.size(), 5); i++) { // Log first 5 documents
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

        // Send progress update: Indexing complete
        if (progress != null) {
            progress.setDocumentsIndexed(finalDocuments.size());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.INDEXING, 95,
                    "Indexing complete", String.format("Indexed %d documents successfully", finalDocuments.size()));
        }

        // Collect document IDs
        List<String> processedDocumentIds = finalDocuments.stream()
                .map(RetrievedDoc::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Send final completion update
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

            // Save the file - use REPLACE_EXISTING to handle concurrent uploads atomically
            // Handle edge case where destination might be a directory (can't replace with
            // REPLACE_EXISTING)
            if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                try (java.util.stream.Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
            }
            // Use OutputStream with CREATE+TRUNCATE_EXISTING to avoid race condition
            // (Files.copy with REPLACE_EXISTING has a non-atomic delete-then-create
            // pattern)
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

            // Generate task ID for progress tracking (only if progress tracker is
            // available)
            String taskId = (trackProgress && progressTracker != null) ? progressTracker.generateTaskId() : null;
            if (taskId != null) {
                response.put("taskId", taskId);
                response.put("websocketTopic", "/topic/ingest/" + taskId);
            }

            // Step 5: Process the file immediately if requested (default behavior)
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

                    // DEBUG POSTGRESQL FUNCTION ERRORS - check the entire exception chain
                    boolean isPostgresMLError = false;
                    Throwable current = e;
                    while (current != null && !isPostgresMLError) {
                        if (current.getMessage() != null && current.getMessage().contains("pgml.embed")) {
                            isPostgresMLError = true;
                        }
                        current = current.getCause();
                    }

                    if (isPostgresMLError) {
                        System.err.println("\n" + "!".repeat(100));
                        System.err.println("PostgresML ERROR DETECTED IN DOCUMENT UPLOAD!");
                        System.err.println("Error: " + e.getMessage());
                        System.err.println("!".repeat(100));

                        // Quick database check using existing DataSource
                        try {
                            javax.sql.DataSource dataSource = null;

                            // Get DataSource from Spring context
                            try {
                                org.springframework.context.ApplicationContext context = org.springframework.web.context.support.WebApplicationContextUtils
                                        .getWebApplicationContext(
                                                ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder
                                                        .currentRequestAttributes())
                                                        .getRequest().getServletContext());
                                dataSource = context.getBean(javax.sql.DataSource.class);
                            } catch (Exception contextError) {
                                System.err
                                        .println("Could not get DataSource from context: " + contextError.getMessage());
                            }

                            if (dataSource != null) {
                                try (java.sql.Connection conn = dataSource.getConnection()) {

                                    // Check schema
                                    System.err.println("\n1. SCHEMA CHECK:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        java.sql.ResultSet rs = stmt.executeQuery(
                                                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'");
                                        rs.next();

                                        if (rs.getInt(1) > 0) {
                                            System.err.println("   ✓ pgml schema EXISTS");
                                        } else {
                                            System.err.println("   ✗ pgml schema MISSING!");
                                        }
                                    }

                                    // Check function
                                    System.err.println("\n2. FUNCTION CHECK:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        java.sql.ResultSet rs = stmt.executeQuery(
                                                "SELECT COUNT(*) FROM information_schema.routines " +
                                                        "WHERE routine_schema = 'pgml' AND routine_name = 'embed'");
                                        rs.next();

                                        int funcCount = rs.getInt(1);
                                        if (funcCount > 0) {
                                            System.err.println(
                                                    "   ✓ pgml.embed function EXISTS (" + funcCount + " variants)");
                                        } else {
                                            System.err.println("   ✗ pgml.embed function MISSING!");
                                            System.err.println("   → This is the ROOT CAUSE of the upload failure");
                                        }
                                    }

                                    // Test function call
                                    System.err.println("\n3. FUNCTION TEST:");
                                    try (java.sql.Statement stmt = conn.createStatement()) {
                                        System.err.println(
                                                "   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)");
                                        stmt.executeQuery(
                                                "SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)");
                                        System.err.println("   ✓ Function call SUCCEEDED");
                                    } catch (Exception testError) {
                                        System.err.println("   ✗ Function call FAILED: " + testError.getMessage());
                                    }

                                } catch (Exception dbError) {
                                    System.err.println("Database connection failed: " + dbError.getMessage());
                                }
                            }
                        } catch (Exception debugError) {
                            System.err.println("Debug failed: " + debugError.getMessage());
                        }

                        // Show fix
                        System.err.println("\n" + "-".repeat(80));
                        System.err.println("IMMEDIATE FIX - Run this SQL in your database:");
                        System.err.println("-".repeat(80));
                        System.err.println("CREATE SCHEMA IF NOT EXISTS pgml;");
                        System.err.println("CREATE OR REPLACE FUNCTION pgml.embed(");
                        System.err.println("  model_name character varying,");
                        System.err.println("  text_input text,");
                        System.err.println("  kwargs jsonb DEFAULT '{}'");
                        System.err.println(") RETURNS FLOAT[] AS $$");
                        System.err.println("BEGIN");
                        System.err.println("  RAISE EXCEPTION 'PostgresML not installed';");
                        System.err.println("END;");
                        System.err.println("$$ LANGUAGE plpgsql;");
                        System.err.println("-".repeat(80));
                        System.err.println("!".repeat(100));
                    }

                    response.put("processingCompleted", false);
                    response.put("processingError", e.getMessage());
                    response.put("message", response.get("message")
                            + " However, automatic processing failed. You can trigger processing manually through batch operations or index rebuild.");

                    // Return success since file was uploaded, but note the processing failure
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
     *
     * @param file           the file to upload
     * @param loaderName     optional loader name (auto-detected if not provided)
     * @param chunkerName    optional chunker name (default chunker used if not
     *                       provided)
     * @param processingMode optional processing mode: "auto" (default, use global
     *                       config),
     *                       "subprocess" (force isolated JVM), or "inprocess"
     *                       (force same JVM)
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

        // Parse processing mode
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

            // Save the file - handle edge case where destination might be a directory
            if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                try (java.util.stream.Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
            }
            // Use OutputStream with CREATE+TRUNCATE_EXISTING to avoid race condition
            try (InputStream inputStream = file.getInputStream();
                    OutputStream outputStream = Files.newOutputStream(destinationFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                inputStream.transferTo(outputStream);
            }
            logger.info("File uploaded successfully to: {}", destinationFile);

            // Generate task ID and queue for async processing
            String taskId = UUID.randomUUID().toString();

            // Build subprocess options map if any per-request config was provided
            Map<String, Object> subprocessOptions = buildSubprocessOptions(
                    subprocessHeapSize, subprocessTimeoutMinutes,
                    subprocessHeartbeatSeconds, subprocessStaleThresholdSeconds);

            // Send initial QUEUED progress event BEFORE starting async processing
            // This ensures the WebSocket event is sent before the HTTP response returns,
            // preventing race conditions where the frontend subscribes too late
            if (progressTracker != null) {
                progressTracker.startTask(taskId, sanitizedFileName);
                logger.debug("Sent initial QUEUED event for task {} before HTTP response", taskId);
            }

            // Start async processing with specified mode and options
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
     *
     * @param files          the files to upload
     * @param loaderName     optional loader name (auto-detected if not provided)
     * @param chunkerName    optional chunker name (default chunker used if not
     *                       provided)
     * @param processingMode optional processing mode: "auto" (default, use global
     *                       config),
     *                       "subprocess" (force isolated JVM), or "inprocess"
     *                       (force same JVM)
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

        // Parse processing mode
        DocumentIngestService.ProcessingMode mode = parseProcessingMode(processingMode);

        // Build subprocess options map if any per-request config was provided
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

                // Save the file - handle edge case where destination might be a directory
                if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                    logger.warn("Destination path exists as a directory, deleting before upload: {}", destinationFile);
                    try (java.util.stream.Stream<Path> walk = Files.walk(destinationFile)) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                    }
                }
                // Use OutputStream with CREATE+TRUNCATE_EXISTING to avoid race condition
                try (InputStream inputStream = file.getInputStream();
                        OutputStream outputStream = Files.newOutputStream(destinationFile,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                    inputStream.transferTo(outputStream);
                }
                logger.info("File uploaded successfully to: {}", destinationFile);

                // Generate task ID and queue for async processing
                String taskId = UUID.randomUUID().toString();

                // Send initial QUEUED progress event BEFORE starting async processing
                // This ensures the WebSocket event is sent before the HTTP response returns
                if (progressTracker != null) {
                    progressTracker.startTask(taskId, sanitizedFileName);
                }

                // Start async processing with specified mode and options (runs concurrently with other files)
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
            // Task not found or already completed
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
        // Combine tasks from all sources
        Map<String, IngestProgressUpdate> allTasks = new HashMap<>();

        // 1. Load persisted active jobs from database (QUEUED or RUNNING status)
        // These are jobs that survive restarts
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

        // 2. Add tasks from async flow (DocumentIngestService) - these override DB entries
        // as they have more current in-memory state
        if (documentIngestService != null) {
            for (IngestProgressUpdate task : documentIngestService.getAllActiveTasks()) {
                allTasks.put(task.taskId(), task);
            }
        }

        // 3. Add tasks from sync flow (IngestProgressTracker) - may override if same taskId
        if (progressTracker != null) {
            for (IngestProgressUpdate task : progressTracker.getAllTasks()) {
                allTasks.put(task.taskId(), task);
            }
        }

        // Log for debugging
        if (!allTasks.isEmpty()) {
            logger.debug("getAllIngestTasks returning {} tasks", allTasks.size());
            for (IngestProgressUpdate task : allTasks.values()) {
                logger.debug("  Task {}: phase={}, progress={}%, status={}",
                        task.taskId(), task.phase(), task.progressPercent(), task.status());
            }
        }

        return ResponseEntity.ok(allTasks.values());
    }

    /**
     * Convert a persisted IndexingJobHistory to an IngestProgressUpdate for the frontend.
     */
    private IngestProgressUpdate convertJobHistoryToProgressUpdate(IndexingJobHistory job) {
        // Map job status to ingest status
        IngestProgressUpdate.IngestStatus status = switch (job.getStatus()) {
            case QUEUED -> IngestProgressUpdate.IngestStatus.PENDING;
            case RUNNING -> IngestProgressUpdate.IngestStatus.IN_PROGRESS;
            case COMPLETED -> IngestProgressUpdate.IngestStatus.COMPLETED;
            case FAILED, MEMORY_KILLED -> IngestProgressUpdate.IngestStatus.FAILED;
            case CANCELLED -> IngestProgressUpdate.IngestStatus.CANCELLED;
            case PAUSED -> IngestProgressUpdate.IngestStatus.IN_PROGRESS; // Treat paused as in-progress for now
        };

        // Map phase from IngestEvent.IngestPhase to IngestProgressUpdate.IngestPhase
        IngestProgressUpdate.IngestPhase phase = mapToIngestPhase(job.getLastPhase());

        // Build stats from job history
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

        // Generate a message based on current state
        String message = switch (job.getStatus()) {
            case QUEUED -> "Waiting to start processing...";
            case RUNNING -> "Processing: " + phase.name().toLowerCase();
            case COMPLETED -> "Completed successfully";
            case FAILED -> job.getErrorMessage() != null ? job.getErrorMessage() : "Failed";
            case CANCELLED -> "Cancelled by user";
            case MEMORY_KILLED -> "Killed due to memory pressure";
            case PAUSED -> "Paused";
        };

        // Map failure reason from job history to DTO
        IngestProgressUpdate.FailureReason failureReason = mapToFailureReason(job.getFailureReason(), job.getStatus());

        return new IngestProgressUpdate(
                job.getTaskId(),
                job.getFileName(),
                phase,
                status,
                job.getProgressPercent() != null ? job.getProgressPercent() : 0,
                phase.name(),  // currentStep as string
                message,
                stats,
                job.getErrorMessage(),
                job.getStartTime(),  // Instant directly
                null,  // factSheetId - not stored in job history yet
                failureReason,
                null  // restartInfo - not stored in job history
        );
    }

    /**
     * Map IndexingJobHistory.FailureReason to IngestProgressUpdate.FailureReason
     */
    private IngestProgressUpdate.FailureReason mapToFailureReason(IndexingJobHistory.FailureReason reason,
                                                                    IndexingJobHistory.JobStatus status) {
        // First check status for memory killed (may not have failure reason set)
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
     * Map IngestEvent.IngestPhase to IngestProgressUpdate.IngestPhase
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

    @PostMapping("/add-url")
    public ResponseEntity<?> handleAddUrl(@RequestBody AddUrlRequest request) {
        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error",
                            "Uploads directory is not configured correctly on the server. Cannot save URL content."));
        }
        if (request.url() == null || request.url().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL cannot be empty."));
        }

        String urlString = request.url();
        String requestedFileName = request.fileName();
        String loaderName = request.loader();
        String chunkerName = request.chunkerName();

        String finalOutputFileName = "";
        Path destinationFile = null;

        try {
            URI uri = new URI(urlString);
            if (requestedFileName == null || requestedFileName.trim().isEmpty()) {
                Path urlPath = Paths.get(uri.getPath());
                String nameFromUrl = (urlPath.getFileName() != null) ? urlPath.getFileName().toString() : "";
                if (nameFromUrl.isEmpty() || nameFromUrl.equals("/") || nameFromUrl.equals("\\")) {
                    nameFromUrl = "webpage_" + UUID.randomUUID().toString().substring(0, 8);
                }
                finalOutputFileName = nameFromUrl.matches(".*\\.[a-zA-Z0-9]{1,5}$") ? nameFromUrl
                        : nameFromUrl + ".html";
            } else {
                finalOutputFileName = requestedFileName;
            }

            finalOutputFileName = finalOutputFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (finalOutputFileName.isEmpty()) {
                finalOutputFileName = "url_doc_" + UUID.randomUUID().toString().substring(0, 8) + ".html";
            }

            destinationFile = this.uploadsPath.resolve(finalOutputFileName).normalize();
            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save URL content outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file path derived from URL (directory traversal attempt)."));
            }

            logger.info("Fetching content from URL: {}", urlString);
            String content = restTemplate.getForObject(uri, String.class);
            if (content == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to fetch content from URL (received null): " + urlString));
            }

            // Handle edge case where destination might be a directory
            if (Files.exists(destinationFile) && Files.isDirectory(destinationFile)) {
                logger.warn("Destination path exists as a directory, deleting before writing: {}", destinationFile);
                try (java.util.stream.Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
            }
            Files.writeString(destinationFile, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Content from URL {} saved successfully to: {}", urlString, destinationFile);

            SourceMarkdownConversionService.ConversionResult markdownConversion = null;
            if (request.convertToMarkdown()) {
                markdownConversion = sourceMarkdownConversionService.convertPath(destinationFile, finalOutputFileName, urlString);
                destinationFile = Paths.get(markdownConversion.filePath()).toAbsolutePath().normalize();
                finalOutputFileName = markdownConversion.fileName();
                if (chunkerName == null || chunkerName.isBlank()) {
                    chunkerName = "custom_markdown";
                }
                logger.info("Converted URL content to markdown before indexing: {}", destinationFile);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message",
                    "Content from URL '" + urlString + "' saved successfully as '" + finalOutputFileName + "'.");
            response.put("fileName", finalOutputFileName);
            response.put("filePath", destinationFile.toString());
            response.put("selectedLoader", loaderName != null ? loaderName : "Auto-detect");
            response.put("selectedChunkerName", chunkerName != null ? chunkerName : "None");
            response.put("convertedToMarkdown", markdownConversion != null);
            if (markdownConversion != null) {
                response.put("markdownFileName", markdownConversion.fileName());
                response.put("markdownFilePath", markdownConversion.filePath());
                response.put("markdownChecksum", markdownConversion.checksum());
                response.put("message", "Content from URL '" + urlString + "' saved and converted to markdown as '"
                        + markdownConversion.fileName() + "'.");
            }

            // Process the downloaded content immediately
            try {
                logger.info("Processing URL content immediately: {} (source URL: {})", finalOutputFileName, urlString);
                DocumentProcessingResult processingResult = processUploadedFile(destinationFile, loaderName,
                        chunkerName, urlString);

                response.put("processingCompleted", true);
                response.put("originalDocumentCount", processingResult.originalDocumentCount());
                response.put("finalChunkCount", processingResult.finalChunkCount());
                response.put("processedDocumentIds", processingResult.processedDocumentIds());
                response.put("loaderUsed", processingResult.loaderUsed());
                response.put("chunkerUsed", processingResult.chunkerUsed());
                response.put("indexingSuccessful", processingResult.indexingSuccessful());
                response.put("processingDetails", processingResult.processingDetails());
                response.put("message", response.get("message") + " Document processed and indexed successfully.");

                logger.info("URL content download and processing completed successfully: {}", finalOutputFileName);

            } catch (Exception e) {
                logger.error("URL content downloaded successfully but processing failed for {}: {}",
                        finalOutputFileName, e.getMessage(), e);
                response.put("processingCompleted", false);
                response.put("processingError", e.getMessage());
                response.put("message", response.get("message")
                        + " However, automatic processing failed. You can trigger processing manually through batch operations or index rebuild.");

                // Return partial success since file was saved
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing URL {}: {}", urlString, e.getMessage(), e);
            String errorType = e.getClass().getSimpleName();
            if (e instanceof URISyntaxException) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid URL syntax: " + e.getMessage()));
            } else if (e instanceof RestClientException) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Failed to fetch content from URL: " + e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process URL (" + errorType + "): " + e.getMessage()));
        }
    }

    /**
     * Request record for YouTube transcript ingestion
     */
    public record AddYouTubeRequest(String url, String language, String chunkerName, boolean saveTranscriptFile) {
    }

    /**
     * Endpoint to add a YouTube video transcript as a document source.
     * Fetches the transcript from YouTube and processes it for indexing.
     *
     * @param request The request containing YouTube URL and optional parameters
     * @return Response with transcript details and processing status
     */
    @PostMapping("/add-youtube")
    public ResponseEntity<?> handleAddYouTube(@RequestBody AddYouTubeRequest request) {
        if (youTubeTranscriptService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "YouTube transcript service is not available"));
        }

        if (request.url() == null || request.url().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "YouTube URL cannot be empty."));
        }

        String urlString = request.url().trim();
        String preferredLanguage = request.language();
        String chunkerName = request.chunkerName();
        boolean saveFile = request.saveTranscriptFile();

        try {
            logger.info("Fetching YouTube transcript for URL: {}", urlString);

            // Fetch the transcript
            YouTubeTranscriptService.TranscriptResult transcriptResult =
                    youTubeTranscriptService.fetchTranscript(urlString, preferredLanguage);

            String videoId = transcriptResult.getVideoId();
            String title = transcriptResult.getTitle();
            String transcript = transcriptResult.getTranscript();

            logger.info("Successfully fetched transcript for video: {} (ID: {}), {} segments, {} characters",
                    title, videoId, transcriptResult.getSegments().size(), transcript.length());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "YouTube transcript fetched successfully");
            response.put("videoId", videoId);
            response.put("videoTitle", title);
            response.put("language", transcriptResult.getLanguage());
            response.put("transcriptLength", transcript.length());
            response.put("segmentCount", transcriptResult.getSegments().size());
            response.put("metadata", transcriptResult.getMetadata());

            // Optionally save transcript to file
            Path transcriptFile = null;
            if (saveFile && this.uploadsPath != null) {
                String fileName = "youtube_" + videoId + "_transcript.txt";
                transcriptFile = this.uploadsPath.resolve(fileName).normalize();

                // Build formatted transcript with timestamps
                StringBuilder formattedTranscript = new StringBuilder();
                formattedTranscript.append("YouTube Video Transcript\n");
                formattedTranscript.append("========================\n");
                formattedTranscript.append("Title: ").append(title).append("\n");
                formattedTranscript.append("Video ID: ").append(videoId).append("\n");
                formattedTranscript.append("URL: https://www.youtube.com/watch?v=").append(videoId).append("\n");
                formattedTranscript.append("Language: ").append(transcriptResult.getLanguage()).append("\n");
                formattedTranscript.append("\n--- Transcript ---\n\n");
                formattedTranscript.append(transcript);

                Files.writeString(transcriptFile, formattedTranscript.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                response.put("savedToFile", fileName);
                response.put("filePath", transcriptFile.toString());
                logger.info("Saved transcript to file: {}", transcriptFile);
            }

            // Process for indexing if DocumentIngestService is available
            if (documentIngestService != null) {
                String taskId = UUID.randomUUID().toString();
                logger.info("Starting async processing for YouTube transcript, taskId: {}", taskId);

                // Convert to Spring AI Document
                org.springframework.ai.document.Document document = youTubeTranscriptService.toDocument(transcriptResult);

                // If we saved to file, process that file
                if (transcriptFile != null && Files.exists(transcriptFile)) {
                    documentIngestService.processDocumentAsync(
                            taskId,
                            transcriptFile,
                            null, // auto-detect loader (will use Tika/text loader)
                            chunkerName,
                            null // auto processing mode
                    );
                    response.put("taskId", taskId);
                    response.put("processingStarted", true);
                } else {
                    // Direct document processing without file
                    // Note: This requires DocumentIngestService to support direct document ingestion
                    response.put("processingStarted", false);
                    response.put("processingNote", "Transcript fetched but direct document ingestion not available. " +
                            "Enable saveTranscriptFile=true to save and process the transcript.");
                }
            } else {
                response.put("processingStarted", false);
                response.put("processingNote", "DocumentIngestService not available. Transcript was fetched but not indexed.");
            }

            return ResponseEntity.ok(response);

        } catch (YouTubeTranscriptService.YouTubeTranscriptException e) {
            logger.error("YouTube transcript error for {}: {}", urlString, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Failed to fetch YouTube transcript",
                            "details", e.getMessage(),
                            "url", urlString
                    ));
        } catch (Exception e) {
            logger.error("Error processing YouTube URL {}: {}", urlString, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process YouTube URL",
                            "details", e.getMessage(),
                            "url", urlString
                    ));
        }
    }

    /**
     * Request record for text/clipboard content
     */
    public record AddTextRequest(
            String content,
            String sourceName,
            String chunkerName,
            String processingMode
    ) {}

    /**
     * Endpoint to add text content directly as a document source.
     * Takes raw text (e.g., pasted from clipboard) and processes it for indexing.
     *
     * @param request The request containing text content and optional parameters
     * @return Response with processing status
     */
    @PostMapping("/add-text")
    public ResponseEntity<?> handleAddText(@RequestBody AddTextRequest request) {
        if (request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Text content cannot be empty."));
        }

        String content = request.content();
        String sourceName = request.sourceName();
        if (sourceName == null || sourceName.trim().isEmpty()) {
            sourceName = "Pasted Text " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        String chunkerName = request.chunkerName();
        String processingMode = request.processingMode();

        try {
            logger.info("Processing text content: '{}', {} characters, {} words",
                    sourceName,
                    content.length(),
                    content.trim().split("\\s+").length);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Text content received successfully");
            response.put("sourceName", sourceName);
            response.put("contentLength", content.length());
            response.put("wordCount", content.trim().split("\\s+").length);

            // Save text to file for processing
            Path textFile = null;
            if (this.uploadsPath != null) {
                // Sanitize source name for file system
                String safeFileName = sourceName.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (safeFileName.length() > 50) {
                    safeFileName = safeFileName.substring(0, 50);
                }
                String fileName = "text_" + System.currentTimeMillis() + "_" + safeFileName + ".txt";
                textFile = this.uploadsPath.resolve(fileName).normalize();

                // Write content to file
                Files.writeString(textFile, content,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                response.put("filePath", textFile.toString());
                logger.info("Saved text content to file: {}", textFile);
            }

            // Process for indexing if DocumentIngestService is available
            if (documentIngestService != null && textFile != null && Files.exists(textFile)) {
                String taskId = UUID.randomUUID().toString();
                logger.info("Starting async processing for text content, taskId: {}", taskId);

                documentIngestService.processDocumentAsync(
                        taskId,
                        textFile,
                        null, // auto-detect loader (will use Tika/text loader)
                        chunkerName,
                        parseProcessingMode(processingMode)
                );
                response.put("taskId", taskId);
                response.put("processingStarted", true);
            } else if (documentIngestService == null) {
                response.put("processingStarted", false);
                response.put("processingNote", "DocumentIngestService not available. Text was saved but not indexed.");
            } else {
                response.put("processingStarted", false);
                response.put("processingNote", "Text content saved but could not be processed.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing text content '{}': {}", sourceName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process text content",
                            "details", e.getMessage(),
                            "sourceName", sourceName
                    ));
        }
    }

    /**
     * Request record for Slack channel ingestion
     */
    public record AddSlackRequest(
            String channelId,
            String token,
            Integer messageLimit,
            Boolean includeThreads,
            String chunkerName
    ) {}

    /**
     * Request record for Slack history ingestion
     */
    public record AddSlackHistoryRequest(
            String channelId,
            String token,
            String startDate,
            String endDate,
            Integer daysBack,
            Integer maxMessages,
            Boolean includeThreads,
            Boolean loadAllChannels,
            String chunkerName
    ) {}

    /**
     * Endpoint to add Slack channel messages as a document source.
     * Fetches messages from a Slack channel for indexing.
     *
     * @param request The request containing channel ID and optional parameters
     * @return Response with ingestion status
     */
    @PostMapping("/add-slack")
    public ResponseEntity<?> handleAddSlack(@RequestBody AddSlackRequest request) {
        if (documentLoadingService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Document loading service is not available"));
        }

        if (request.channelId() == null || request.channelId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Slack channel ID cannot be empty."));
        }

        try {
            String channelId = request.channelId().trim();
            logger.info("Processing Slack channel: {}", channelId);

            // Build metadata for the source descriptor
            Map<String, Object> metadata = new HashMap<>();
            if (request.token() != null && !request.token().isEmpty()) {
                metadata.put("slackToken", request.token());
            }
            if (request.messageLimit() != null) {
                metadata.put("limit", request.messageLimit());
            }
            if (request.includeThreads() != null) {
                metadata.put("includeThreads", request.includeThreads());
            }

            // Create source descriptor for Slack
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.SLACK)
                    .pathOrUrl(channelId)
                    .sourceId("slack-" + channelId.replace("#", ""))
                    .metadata(metadata)
                    .build();

            // Load documents using the document loading service
            List<Document> documents = documentLoadingService.loadDocumentsFromSource(sourceDescriptor, "Slack Channel Loader");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Slack messages loaded successfully");
            response.put("channelId", channelId);
            response.put("messageCount", documents.size());

            // Index documents if IndexerService is available
            if (indexerService != null && !documents.isEmpty()) {
                logger.info("Indexing {} Slack messages from channel {}", documents.size(), channelId);
                // Convert Spring AI Documents to RetrievedDocs
                List<RetrievedDoc> retrievedDocs = documents.stream()
                        .map(doc -> new RetrievedDoc(doc.getId(), doc.getText(), doc.getMetadata()))
                        .collect(Collectors.toList());
                indexerService.indexDocuments(retrievedDocs);
                response.put("indexed", true);
                response.put("processingNote", "Messages have been indexed successfully.");
            } else {
                response.put("indexed", false);
                if (documents.isEmpty()) {
                    response.put("processingNote", "No messages found in the channel.");
                } else {
                    response.put("processingNote", "IndexerService not available. Messages were loaded but not indexed.");
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing Slack channel {}: {}", request.channelId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process Slack channel",
                            "details", e.getMessage(),
                            "channelId", request.channelId()
                    ));
        }
    }

    /**
     * Endpoint to add Slack message history as a document source.
     * Fetches historical messages from Slack channels for indexing.
     *
     * @param request The request containing channel IDs and date range
     * @return Response with ingestion status
     */
    @PostMapping("/add-slack-history")
    public ResponseEntity<?> handleAddSlackHistory(@RequestBody AddSlackHistoryRequest request) {
        if (documentLoadingService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Document loading service is not available"));
        }

        if ((request.channelId() == null || request.channelId().trim().isEmpty()) && !Boolean.TRUE.equals(request.loadAllChannels())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Slack channel ID is required unless loadAllChannels is true."));
        }

        try {
            String channelId = request.channelId() != null ? request.channelId().trim() : "";
            logger.info("Processing Slack history for channel(s): {}", channelId.isEmpty() ? "all channels" : channelId);

            // Build metadata for the source descriptor
            Map<String, Object> metadata = new HashMap<>();
            if (request.token() != null && !request.token().isEmpty()) {
                metadata.put("slackToken", request.token());
            }
            if (request.startDate() != null && !request.startDate().isEmpty()) {
                metadata.put("startDate", request.startDate());
            }
            if (request.endDate() != null && !request.endDate().isEmpty()) {
                metadata.put("endDate", request.endDate());
            }
            if (request.daysBack() != null) {
                metadata.put("daysBack", request.daysBack());
            }
            if (request.maxMessages() != null) {
                metadata.put("maxMessages", request.maxMessages());
            }
            if (request.includeThreads() != null) {
                metadata.put("includeThreads", request.includeThreads());
            }
            if (Boolean.TRUE.equals(request.loadAllChannels())) {
                metadata.put("loadAllChannels", true);
            }

            // Create source descriptor for Slack History
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.SLACK_HISTORY)
                    .pathOrUrl(channelId)
                    .sourceId("slack-history-" + (channelId.isEmpty() ? "all" : channelId.replace("#", "").replace(",", "-")))
                    .metadata(metadata)
                    .build();

            // Load documents using the document loading service
            List<Document> documents = documentLoadingService.loadDocumentsFromSource(sourceDescriptor, "Slack History Loader");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Slack history loaded successfully");
            response.put("channelId", channelId.isEmpty() ? "all accessible channels" : channelId);
            response.put("messageCount", documents.size());
            if (request.startDate() != null) {
                response.put("startDate", request.startDate());
            }
            if (request.endDate() != null) {
                response.put("endDate", request.endDate());
            }
            if (request.daysBack() != null) {
                response.put("daysBack", request.daysBack());
            }

            // Index documents if IndexerService is available
            if (indexerService != null && !documents.isEmpty()) {
                logger.info("Indexing {} Slack history messages", documents.size());
                // Convert Spring AI Documents to RetrievedDocs
                List<RetrievedDoc> retrievedDocs = documents.stream()
                        .map(doc -> new RetrievedDoc(doc.getId(), doc.getText(), doc.getMetadata()))
                        .collect(Collectors.toList());
                indexerService.indexDocuments(retrievedDocs);
                response.put("indexed", true);
                response.put("processingNote", "Messages have been indexed successfully.");
            } else {
                response.put("indexed", false);
                if (documents.isEmpty()) {
                    response.put("processingNote", "No messages found in the specified channels/time range.");
                } else {
                    response.put("processingNote", "IndexerService not available. Messages were loaded but not indexed.");
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing Slack history for {}: {}", request.channelId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process Slack history",
                            "details", e.getMessage(),
                            "channelId", request.channelId() != null ? request.channelId() : "all"
                    ));
        }
    }

    @GetMapping("/sources")
    public ResponseEntity<List<String>> listConfiguredSources() {
        List<String> sources = sourceProperties.getSources();
        if (sources == null || sources.isEmpty()) {
            return ResponseEntity.ok(Collections.singletonList(
                    "No primary document sources configured in 'app.document.sources'. Uploaded files will be processed if uploads path is configured and included."));
        }
        return ResponseEntity.ok(sources);
    }

    @GetMapping("/uploaded-files")
    public ResponseEntity<?> listUploadedFiles() {
        if (this.uploadsPath == null
                || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server."));
        }
        try {
            if (!Files.exists(uploadsPath) || !Files.isDirectory(uploadsPath)) {
                logger.info("Uploads directory does not exist or is not a directory: {}", uploadsPath);
                return ResponseEntity.ok(
                        Map.of("uploaded_files_location", uploadsPath.toString(), "files", Collections.emptyList()));
            }
            List<Map<String, Object>> fileDetails;
            try (Stream<Path> walk = Files.list(uploadsPath)) {
                fileDetails = walk.filter(Files::isRegularFile)
                        .map(path -> {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("fileName", path.getFileName().toString());
                            info.put("filePath", path.toString());
                            try {
                                info.put("sizeBytes", Files.size(path));
                            } catch (IOException e) {
                                info.put("sizeBytes", -1L);
                            }
                            try {
                                String mimeType = Files.probeContentType(path);
                                info.put("mimeType", mimeType != null ? mimeType : "application/octet-stream");
                            } catch (IOException e) {
                                info.put("mimeType", "application/octet-stream");
                            }
                            try {
                                info.put("lastModifiedMs", Files.getLastModifiedTime(path).toMillis());
                            } catch (IOException e) {
                                info.put("lastModifiedMs", 0L);
                            }
                            return info;
                        })
                        .collect(Collectors.toList());
            }
            return ResponseEntity.ok(Map.of("uploaded_files_location", uploadsPath.toString(), "files", fileDetails));
        } catch (IOException e) {
            logger.error("Error listing files in uploads directory {}: {}", uploadsPath, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not list uploaded files: " + e.getMessage()));
        }
    }

    /**
     * New endpoint to process a specific uploaded file by name
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

            logger.info("Processing specific uploaded file: {}", fileName);
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

    /**
     * Endpoint to get processing status and statistics
     */
    @GetMapping("/processing-status")
    public ResponseEntity<?> getProcessingStatus() {
        try {
            Map<String, Object> status = new HashMap<>();

            // Check if indexer service is available
            boolean indexerAvailable = indexerService != null && indexerService.isIndexAvailable();
            status.put("indexerAvailable", indexerAvailable);

            // Get approximate document count
            long documentCount = 0;
            if (indexerAvailable) {
                documentCount = indexerService.getApproxTotalDocCount(null);
            }
            status.put("approximateDocumentCount", documentCount);

            // Get available loaders and chunkers count
            int loaderCount = (documentLoaders != null) ? documentLoaders.size() : 0;
            int chunkerCount = (textChunkers != null) ? textChunkers.size() : 0;
            status.put("availableLoaders", loaderCount);
            status.put("availableChunkers", chunkerCount);

            // Check uploads directory status
            boolean uploadsDirectoryReady = this.uploadsPath != null &&
                    !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString()) &&
                    Files.exists(this.uploadsPath) && Files.isDirectory(this.uploadsPath);
            status.put("uploadsDirectoryReady", uploadsDirectoryReady);
            status.put("uploadsPath", this.uploadsPath != null ? this.uploadsPath.toString() : "Not configured");

            // Count uploaded files
            int uploadedFileCount = 0;
            if (uploadsDirectoryReady) {
                try (Stream<Path> files = Files.list(this.uploadsPath)) {
                    uploadedFileCount = (int) files.filter(Files::isRegularFile).count();
                }
            }
            status.put("uploadedFileCount", uploadedFileCount);

            status.put("systemReady", indexerAvailable && uploadsDirectoryReady && loaderCount > 0);
            status.put("message",
                    indexerAvailable && uploadsDirectoryReady && loaderCount > 0
                            ? "System is ready for document processing."
                            : "System has some configuration issues that may affect document processing.");

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            logger.error("Error getting processing status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get processing status: " + e.getMessage()));
        }
    }

    /**
     * Parse a processing mode string into a ProcessingMode enum.
     *
     * @param modeString the mode string (case-insensitive): "auto", "subprocess",
     *                   or "inprocess"
     * @return the corresponding ProcessingMode enum value, defaults to AUTO for
     *         unrecognized values
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
        // Only create options map if at least one value is provided
        if (heapSize == null && timeoutMinutes == null && heartbeatSeconds == null && staleThresholdSeconds == null) {
            return null;
        }
        Map<String, Object> options = new java.util.HashMap<>();
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

    /**
     * Get the list of available processing modes for document ingestion.
     * Useful for frontend UI to present options to the user.
     */
    @GetMapping("/processing-modes")
    public ResponseEntity<Map<String, Object>> getProcessingModes() {
        Map<String, Object> response = new LinkedHashMap<>();

        List<Map<String, String>> modes = new ArrayList<>();
        modes.add(Map.of(
                "value", "auto",
                "label", "Auto (Use Global Setting)",
                "description", "Use the global subprocess configuration to decide processing mode"));
        modes.add(Map.of(
                "value", "inprocess",
                "label", "In-Process (Same JVM)",
                "description",
                "Process in the same JVM - faster startup, easier debugging, but crashes affect main app"));
        modes.add(Map.of(
                "value", "subprocess",
                "label", "Subprocess (Isolated JVM)",
                "description", "Process in a separate JVM - better isolation, crashes don't affect main app"));

        response.put("modes", modes);
        response.put("default", "auto");

        return ResponseEntity.ok(response);
    }
}
