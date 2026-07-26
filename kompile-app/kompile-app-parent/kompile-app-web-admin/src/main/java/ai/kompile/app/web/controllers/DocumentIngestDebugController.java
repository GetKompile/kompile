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
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents")
public class DocumentIngestDebugController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestDebugController.class);

    private final Path uploadsPath;
    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private final DocumentIngestService documentIngestService;

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

    @Autowired
    public DocumentIngestDebugController(
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) DocumentIngestService documentIngestService) {

        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.documentIngestService = documentIngestService;

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

    // -------------------------------------------------------------------------
    // Private helpers (copied verbatim from DocumentManagementController)
    // -------------------------------------------------------------------------

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
}
