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
import ai.kompile.core.loaders.CompositePdfLoader;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/documents/debug")
public class DocumentDebuggerController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentDebuggerController.class);

    private final Path uploadsPath;
    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;

    @Autowired
    public DocumentDebuggerController(
            AppDocumentSourceProperties appDocumentSourceProperties,
            List<DocumentLoader> documentLoaders,
            List<TextChunker> textChunkers
    ) {
        this.documentLoaders = documentLoaders;
        this.textChunkers = textChunkers;

        if (appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            this.uploadsPath = Paths.get("./error_uploads_path_not_configured");
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    public record LoaderDebugInfo(
            String name,
            String className,
            boolean isNoOp,
            boolean supportsFile,
            String supportReason
    ) {}

    public record ChunkerDebugInfo(
            String name,
            String className,
            boolean isNoOp,
            String reason
    ) {}

    public record DocumentDebugInfo(
            String id,
            String text,
            int contentLength,
            boolean hasContent,
            Map<String, Object> metadata,
            List<String> contentLines,
            Map<String, Object> contentStats
    ) {}

    public record ChunkDebugInfo(
            String id,
            String text,
            int contentLength,
            int chunkIndex,
            Map<String, Object> metadata,
            Double score
    ) {}

    public record RetrievedDocDebugInfo(
            String id,
            String text,
            int contentLength,
            boolean hasContent,
            Map<String, Object> metadata,
            Double score,
            String contentPreview,
            Map<String, Object> contentStats
    ) {}

    public record DebugAnalysisResult(
            String fileName,
            String filePath,
            long fileSize,
            List<LoaderDebugInfo> availableLoaders,
            LoaderDebugInfo selectedLoader,
            List<RetrievedDocDebugInfo> loadedDocuments,
            List<ChunkerDebugInfo> availableChunkers,
            ChunkerDebugInfo selectedChunker,
            List<ChunkDebugInfo> chunks,
            Map<String, Object> processingStats,
            String errorMessage,
            Map<String, Object> compositeLoaderComparison
    ) {
        // Constructor without compositeLoaderComparison for backward compatibility
        public DebugAnalysisResult(
                String fileName,
                String filePath,
                long fileSize,
                List<LoaderDebugInfo> availableLoaders,
                LoaderDebugInfo selectedLoader,
                List<RetrievedDocDebugInfo> loadedDocuments,
                List<ChunkerDebugInfo> availableChunkers,
                ChunkerDebugInfo selectedChunker,
                List<ChunkDebugInfo> chunks,
                Map<String, Object> processingStats,
                String errorMessage) {
            this(fileName, filePath, fileSize, availableLoaders, selectedLoader,
                 loadedDocuments, availableChunkers, selectedChunker, chunks,
                 processingStats, errorMessage, null);
        }
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
     * Converts a RetrievedDoc to debug info
     */
    private RetrievedDocDebugInfo convertToRetrievedDocDebugInfo(RetrievedDoc doc) {
        String text = doc.getText();
        String preview = null;

        if (text != null) {
            preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        }

        return new RetrievedDocDebugInfo(
                doc.getId(),
                text,
                text != null ? text.length() : 0,
                text != null && !text.trim().isEmpty(),
                doc.getMetadata(),
                doc.getScore(),
                preview,
                analyzeContent(text)
        );
    }

    /**
     * Determines if a loader is a no-op/stub implementation
     */
    private boolean isNoOpLoader(DocumentLoader loader) {
        if (loader == null) return true;

        String className = loader.getClass().getSimpleName().toLowerCase();
        String loaderName = loader.getName().toLowerCase();
        String fullClassName = loader.getClass().getName().toLowerCase();

        return className.contains("noop") ||
                className.contains("dummy") ||
                className.contains("mock") ||
                className.contains("stub") ||
                className.contains("default") ||
                className.contains("fallback") ||
                loaderName.contains("noop") ||
                loaderName.contains("dummy") ||
                loaderName.contains("disabled") ||
                (fullClassName.contains(".core.") &&
                        (fullClassName.contains("noop") || fullClassName.contains("default")));
    }

    /**
     * Determines if a chunker is a no-op/stub implementation
     */
    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null) return true;

        String className = chunker.getClass().getSimpleName().toLowerCase();
        String chunkerName = chunker.getName().toLowerCase();
        String fullClassName = chunker.getClass().getName().toLowerCase();

        return className.contains("noop") ||
                className.contains("dummy") ||
                className.contains("mock") ||
                className.contains("stub") ||
                className.contains("default") ||
                className.contains("fallback") ||
                className.contains("empty") ||
                chunkerName.contains("noop") ||
                chunkerName.contains("no-op") ||
                chunkerName.contains("dummy") ||
                chunkerName.contains("disabled") ||
                chunkerName.contains("none") ||
                (fullClassName.contains(".core.") &&
                        (fullClassName.contains("noop") || fullClassName.contains("default")));
    }

    /**
     * Mapping of UI chunker strategy IDs to backend chunker names.
     * This handles the mismatch between frontend naming conventions and backend getName() values.
     * Key = UI name (from CHUNKER_STRATEGIES in api-models.ts)
     * Value = Backend chunker getName() return value
     */
    private static final Map<String, String> CHUNKER_ALIASES = Map.ofEntries(
            // UI "spring_recursive_character" -> backend "recursive-character" (RecursiveCharacterTextChunker)
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
            Map.entry("spring_markdown", "spring_markdown")
    );

    /**
     * Finds a chunker by name, supporting both exact matches and UI alias mappings.
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

        // Try partial/contains match as fallback
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
     * Gets the best available chunker, prioritizing real implementations
     */
    private TextChunker selectBestChunker() {
        if (textChunkers == null || textChunkers.isEmpty()) {
            return null;
        }

        List<TextChunker> realChunkers = textChunkers.stream()
                .filter(chunker -> !isNoOpChunker(chunker))
                .collect(Collectors.toList());

        if (realChunkers.isEmpty()) {
            return null;
        }

        // Prioritize chunkers by type preference
        List<String> preferredPatterns = Arrays.asList(
                "opennlp", "recursive", "character", "sentence", "markdown", "token"
        );

        for (String pattern : preferredPatterns) {
            Optional<TextChunker> preferred = realChunkers.stream()
                    .filter(chunker -> chunker.getName().toLowerCase().contains(pattern) ||
                            chunker.getClass().getSimpleName().toLowerCase().contains(pattern))
                    .findFirst();
            if (preferred.isPresent()) {
                return preferred.get();
            }
        }

        return realChunkers.get(0);
    }

    /**
     * Analyzes content characteristics
     */
    private Map<String, Object> analyzeContent(String content) {
        Map<String, Object> stats = new HashMap<>();

        if (content == null) {
            stats.put("isNull", true);
            return stats;
        }

        stats.put("isNull", false);
        stats.put("length", content.length());
        stats.put("isEmpty", content.trim().isEmpty());
        stats.put("lineCount", content.lines().count());
        stats.put("wordCount", content.isEmpty() ? 0 : content.split("\\s+").length);
        stats.put("hasSpecialChars", content.contains("�") || content.contains("\ufffd"));
        stats.put("hasEncodingIssues", content.matches(".*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F].*"));
        stats.put("containsCommonWords",
                content.toLowerCase().contains("the") ||
                        content.toLowerCase().contains("and") ||
                        content.toLowerCase().contains("chapter") ||
                        content.toLowerCase().contains("page"));

        return stats;
    }

    @GetMapping("/status")
    public ResponseEntity<?> getDebugStatus() {
        Map<String, Object> status = new HashMap<>();

        status.put("uploadsPathConfigured",
                this.uploadsPath != null &&
                        !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString()));
        status.put("uploadsPath", this.uploadsPath != null ? this.uploadsPath.toString() : "Not configured");

        // Loader analysis
        int totalLoaders = documentLoaders != null ? documentLoaders.size() : 0;
        int realLoaders = documentLoaders != null ?
                (int) documentLoaders.stream().filter(l -> !isNoOpLoader(l)).count() : 0;

        status.put("totalLoaders", totalLoaders);
        status.put("realLoaders", realLoaders);
        status.put("noOpLoaders", totalLoaders - realLoaders);

        // Chunker analysis
        int totalChunkers = textChunkers != null ? textChunkers.size() : 0;
        int realChunkers = textChunkers != null ?
                (int) textChunkers.stream().filter(c -> !isNoOpChunker(c)).count() : 0;

        status.put("totalChunkers", totalChunkers);
        status.put("realChunkers", realChunkers);
        status.put("noOpChunkers", totalChunkers - realChunkers);

        return ResponseEntity.ok(status);
    }

    @PostMapping("/analyze-file")
    public ResponseEntity<DebugAnalysisResult> analyzeFile(
            @RequestParam("fileName") String fileName,
            @RequestParam(name = "loaderName", required = false) String loaderName,
            @RequestParam(name = "chunkerName", required = false) String chunkerName,
            @RequestParam(name = "chunkSize", required = false, defaultValue = "1000") Integer chunkSize,
            @RequestParam(name = "overlap", required = false, defaultValue = "200") Integer overlap,
            @RequestParam(name = "useCompositePdfLoader", required = false, defaultValue = "false") Boolean useCompositePdfLoader) {

        try {
            if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new DebugAnalysisResult(fileName, null, 0, null, null, null, null, null, null, null,
                                "Uploads directory not configured"));
            }

            Path filePath = this.uploadsPath.resolve(fileName).normalize();

            if (!filePath.startsWith(this.uploadsPath.normalize())) {
                return ResponseEntity.badRequest()
                        .body(new DebugAnalysisResult(fileName, null, 0, null, null, null, null, null, null, null,
                                "Invalid file path"));
            }

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.badRequest()
                        .body(new DebugAnalysisResult(fileName, null, 0, null, null, null, null, null, null, null,
                                "File not found: " + fileName));
            }

            long fileSize = Files.size(filePath);

            // Analyze all loaders
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(fileName)
                    .sourceId("debug_" + fileName)
                    .build();

            List<LoaderDebugInfo> loaderInfos = new ArrayList<>();
            DocumentLoader selectedLoader = null;

            for (DocumentLoader loader : documentLoaders) {
                boolean supports = loader.supports(sourceDescriptor);
                boolean isNoOp = isNoOpLoader(loader);
                String supportReason = supports ? "Supports this file type" : "Does not support this file type";

                LoaderDebugInfo info = new LoaderDebugInfo(
                        loader.getName(),
                        loader.getClass().getName(),
                        isNoOp,
                        supports,
                        supportReason
                );
                loaderInfos.add(info);

                // Select loader
                if (selectedLoader == null) {
                    if (loaderName != null && loaderName.equals(loader.getName())) {
                        selectedLoader = loader;
                    } else if (loaderName == null && supports && !isNoOp) {
                        selectedLoader = loader;
                    }
                }
            }

            // Fallback to any supporting loader if no real loader found
            if (selectedLoader == null) {
                selectedLoader = documentLoaders.stream()
                        .filter(l -> l.supports(sourceDescriptor))
                        .findFirst()
                        .orElse(null);
            }

            LoaderDebugInfo selectedLoaderInfo = null;
            List<RetrievedDocDebugInfo> documentInfos = new ArrayList<>();
            Map<String, Object> compositeLoaderComparison = null;

            // Check if we should use composite PDF loader
            boolean isPdfFile = fileName.toLowerCase().endsWith(".pdf");
            boolean shouldUseComposite = isPdfFile && useCompositePdfLoader && loaderName == null;

            if (shouldUseComposite) {
                // Collect all PDF-supporting loaders for composite loading
                List<DocumentLoader> pdfLoaders = documentLoaders.stream()
                        .filter(l -> l.supports(sourceDescriptor))
                        .filter(l -> !isNoOpLoader(l))
                        .collect(Collectors.toList());

                if (pdfLoaders.size() > 1) {
                    logger.info("Using composite PDF loader with {} loaders for file: {}",
                               pdfLoaders.size(), fileName);

                    CompositePdfLoader compositePdfLoader = new CompositePdfLoader(pdfLoaders, true);
                    try {
                        CompositePdfLoader.LoaderComparisonResult comparisonResult =
                                compositePdfLoader.loadWithComparison(sourceDescriptor);

                        selectedLoader = comparisonResult.getSelectedLoader();
                        List<Document> documents = comparisonResult.getDocuments();

                        // Convert to RetrievedDocs for consistent handling
                        for (Document doc : documents) {
                            RetrievedDoc retrievedDoc = convertToRetrievedDoc(doc);
                            RetrievedDocDebugInfo debugInfo = convertToRetrievedDocDebugInfo(retrievedDoc);
                            documentInfos.add(debugInfo);
                        }

                        // Build comparison info for response
                        compositeLoaderComparison = new LinkedHashMap<>();
                        compositeLoaderComparison.put("compositeLoaderUsed", true);
                        compositeLoaderComparison.put("selectedLoader", selectedLoader.getName());
                        compositeLoaderComparison.put("selectionReason", comparisonResult.getSelectionReason());
                        compositeLoaderComparison.put("loadersCompared", comparisonResult.getLoaderStats().size());

                        // Add per-loader stats
                        Map<String, Object> loaderStatsMap = new LinkedHashMap<>();
                        for (Map.Entry<String, CompositePdfLoader.LoaderStats> entry :
                                comparisonResult.getLoaderStats().entrySet()) {
                            loaderStatsMap.put(entry.getKey(), entry.getValue().toMap());
                        }
                        compositeLoaderComparison.put("loaderStats", loaderStatsMap);

                        selectedLoaderInfo = new LoaderDebugInfo(
                                selectedLoader.getName() + " (via Composite Loader)",
                                selectedLoader.getClass().getName(),
                                isNoOpLoader(selectedLoader),
                                true,
                                "Auto-selected best PDF loader: " + comparisonResult.getSelectionReason()
                        );

                    } catch (Exception e) {
                        logger.error("Composite PDF loading failed, falling back to single loader: {}",
                                    e.getMessage(), e);
                        // Fall back to single loader mode
                        shouldUseComposite = false;
                    }
                } else {
                    // Only one PDF loader available, no need for composite
                    shouldUseComposite = false;
                    logger.debug("Only {} PDF loader(s) available, skipping composite mode", pdfLoaders.size());
                }
            }

            // Standard single-loader path (used when composite is not applicable or as fallback)
            if (!shouldUseComposite && selectedLoader != null && documentInfos.isEmpty()) {
                selectedLoaderInfo = new LoaderDebugInfo(
                        selectedLoader.getName(),
                        selectedLoader.getClass().getName(),
                        isNoOpLoader(selectedLoader),
                        true,
                        "Selected for processing"
                );

                // Load documents
                try {
                    List<Document> documents = selectedLoader.load(sourceDescriptor);

                    // Convert to RetrievedDocs for consistent handling
                    for (Document doc : documents) {
                        RetrievedDoc retrievedDoc = convertToRetrievedDoc(doc);
                        RetrievedDocDebugInfo debugInfo = convertToRetrievedDocDebugInfo(retrievedDoc);
                        documentInfos.add(debugInfo);
                    }

                } catch (Exception e) {
                    logger.error("Error loading documents: {}", e.getMessage(), e);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new DebugAnalysisResult(fileName, filePath.toString(), fileSize,
                                    loaderInfos, selectedLoaderInfo, null, null, null, null, null,
                                    "Failed to load documents: " + e.getMessage()));
                }
            }

            // Analyze chunkers
            List<ChunkerDebugInfo> chunkerInfos = new ArrayList<>();
            for (TextChunker chunker : textChunkers) {
                boolean isNoOp = isNoOpChunker(chunker);
                String reason = isNoOp ? "No-op/stub implementation" : "Real chunker implementation";

                ChunkerDebugInfo info = new ChunkerDebugInfo(
                        chunker.getName(),
                        chunker.getClass().getName(),
                        isNoOp,
                        reason
                );
                chunkerInfos.add(info);
            }

            // Select chunker (using flexible name matching for UI compatibility)
            TextChunker selectedChunker = null;
            if (chunkerName != null && !chunkerName.isEmpty()) {
                selectedChunker = findChunkerByName(chunkerName);
            }
            if (selectedChunker == null) {
                selectedChunker = selectBestChunker();
            }

            ChunkerDebugInfo selectedChunkerInfo = null;
            List<ChunkDebugInfo> chunkInfos = new ArrayList<>();

            if (selectedChunker != null && !documentInfos.isEmpty()) {
                selectedChunkerInfo = new ChunkerDebugInfo(
                        selectedChunker.getName(),
                        selectedChunker.getClass().getName(),
                        isNoOpChunker(selectedChunker),
                        "Selected for chunking"
                );

                // Chunk the first document
                try {
                    // documentInfos contains RetrievedDocDebugInfo, which is derived from RetrievedDoc
                    RetrievedDocDebugInfo firstDocDebugInfo = documentInfos.get(0);

                    // Create an ai.kompile.core.retrievers.RetrievedDoc instance for chunking
                    // using the information from RetrievedDocDebugInfo.
                    // The RetrievedDoc.builder() pattern is assumed based on typical usage
                    // and previous context about RetrievedDoc.java.
                    RetrievedDoc documentToChunk = RetrievedDoc.builder()
                            .id(firstDocDebugInfo.id()) // Use the ID from the debug representation
                            .text(firstDocDebugInfo.text())
                            .metadata(new HashMap<>(firstDocDebugInfo.metadata())) // Pass a copy of the metadata
                            .build();

                    Map<String, Object> chunkingOptions = new HashMap<>();
                    // Use chunk size and overlap from request parameters
                    chunkingOptions.put("chunkSize", chunkSize);
                    chunkingOptions.put("overlap", overlap);

                    // selectedChunker is of type ai.kompile.app.core.chunking.TextChunker
                    // Its chunk method takes RetrievedDoc and returns List<RetrievedDoc>
                    List<RetrievedDoc> chunks = selectedChunker.chunk(documentToChunk, chunkingOptions);

                    for (int i = 0; i < chunks.size(); i++) {
                        RetrievedDoc retrievedChunk = chunks.get(i); // This is already an ai.kompile.core.retrievers.RetrievedDoc

                        ChunkDebugInfo chunkInfo = new ChunkDebugInfo(
                                retrievedChunk.getId(),
                                retrievedChunk.getText(),
                                retrievedChunk.getText() != null ? retrievedChunk.getText().length() : 0,
                                i, // chunkIndex
                                retrievedChunk.getMetadata(),
                                retrievedChunk.getScore() // RetrievedDoc is expected to have getScore()
                        );
                        chunkInfos.add(chunkInfo);
                    }
                } catch (Exception e) {
                    logger.error("Error chunking document: {}", e.getMessage(), e);
                    // Consider adding more specific error feedback to the DebugAnalysisResult if chunking fails
                }
            }

               // Processing stats
            Map<String, Object> processingStats = new HashMap<>();
            processingStats.put("fileSize", fileSize);
            processingStats.put("documentsLoaded", documentInfos.size());
            processingStats.put("chunksCreated", chunkInfos.size());
            processingStats.put("totalContentLength",
                    documentInfos.stream().mapToInt(RetrievedDocDebugInfo::contentLength).sum());
            processingStats.put("avgChunkSize",
                    chunkInfos.isEmpty() ? 0 :
                            chunkInfos.stream().mapToInt(ChunkDebugInfo::contentLength).average().orElse(0));
            processingStats.put("configuredChunkSize", chunkSize);
            processingStats.put("configuredOverlap", overlap);

            // Add composite loader info to processing stats if used
            if (compositeLoaderComparison != null) {
                processingStats.put("compositeLoaderUsed", true);
            }

            return ResponseEntity.ok(new DebugAnalysisResult(
                    fileName,
                    filePath.toString(),
                    fileSize,
                    loaderInfos,
                    selectedLoaderInfo,
                    documentInfos,
                    chunkerInfos,
                    selectedChunkerInfo,
                    chunkInfos,
                    processingStats,
                    null,
                    compositeLoaderComparison
            ));

        } catch (Exception e) {
            logger.error("Error analyzing file {}: {}", fileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DebugAnalysisResult(fileName, null, 0, null, null, null, null, null, null, null,
                            "Analysis failed: " + e.getMessage()));
        }
    }

    @PostMapping("/test-upload")
    public ResponseEntity<?> testUpload(@RequestParam("file") MultipartFile file) {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly."));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty."));
        }

        try {
            String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(), "test_upload_" + UUID.randomUUID());
            String sanitizedFileName = "debug_" + originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");

            Path destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path"));
            }

            // Use REPLACE_EXISTING for atomic file replacement (avoids race condition with delete-then-copy)
            // Handle edge case where destination might be a directory
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
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                inputStream.transferTo(outputStream);
            }

            return ResponseEntity.ok(Map.of(
                    "message", "File uploaded successfully for debugging",
                    "fileName", sanitizedFileName,
                    "filePath", destinationFile.toString(),
                    "fileSize", Files.size(destinationFile)
            ));

        } catch (Exception e) {
            logger.error("Failed to upload debug file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    /**
     * Enhanced endpoint to test RetrievedDoc functionality
     */
    @PostMapping("/test-retrieved-doc")
    public ResponseEntity<?> testRetrievedDoc(@RequestParam("fileName") String fileName) {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly."));
        }

        try {
            Path filePath = this.uploadsPath.resolve(fileName).normalize();

            if (!filePath.startsWith(this.uploadsPath.normalize()) || !Files.exists(filePath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "File not found: " + fileName));
            }

            // Load document using the first available loader
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(fileName)
                    .sourceId("test_retrieved_doc_" + fileName)
                    .build();

            DocumentLoader loader = documentLoaders.stream()
                    .filter(l -> l.supports(sourceDescriptor))
                    .findFirst()
                    .orElse(null);

            if (loader == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No suitable loader found for file"));
            }

            List<Document> documents = loader.load(sourceDescriptor);
            if (documents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No documents loaded from file"));
            }

            // Convert to RetrievedDocs and test various operations
            List<RetrievedDoc> retrievedDocs = documents.stream()
                    .map(this::convertToRetrievedDoc)
                    .collect(Collectors.toList());

            Map<String, Object> testResults = new HashMap<>();
            testResults.put("fileName", fileName);
            testResults.put("originalDocumentCount", documents.size());
            testResults.put("retrievedDocCount", retrievedDocs.size());

            // Test RetrievedDoc functionality
            List<Map<String, Object>> docTests = new ArrayList<>();
            for (int i = 0; i < retrievedDocs.size(); i++) {
                RetrievedDoc doc = retrievedDocs.get(i);
                Map<String, Object> docTest = new HashMap<>();

                docTest.put("index", i);
                docTest.put("id", doc.getId());
                docTest.put("hasText", doc.isText());
                docTest.put("textLength", doc.getText() != null ? doc.getText().length() : 0);
                docTest.put("score", doc.getScore());
                docTest.put("metadataKeys", doc.getMetadata().keySet());
                docTest.put("metadataSize", doc.getMetadata().size());

                // Test builder pattern
                try {
                    RetrievedDoc mutatedDoc = doc.mutate()
                            .score(0.95)
                            .metadata("test_key", "test_value")
                            .build();

                    docTest.put("builderPatternWorks", true);
                    docTest.put("mutatedScore", mutatedDoc.getScore());
                    docTest.put("mutatedMetadataSize", mutatedDoc.getMetadata().size());
                } catch (Exception e) {
                    docTest.put("builderPatternWorks", false);
                    docTest.put("builderError", e.getMessage());
                }

                // Test formatted content
                try {
                    String formattedContent = doc.getFormattedContent();
                    docTest.put("formattedContentWorks", true);
                    docTest.put("formattedContentLength", formattedContent != null ? formattedContent.length() : 0);
                } catch (Exception e) {
                    docTest.put("formattedContentWorks", false);
                    docTest.put("formattedContentError", e.getMessage());
                }

                docTests.add(docTest);
            }

            testResults.put("documentTests", docTests);

            // Test chunking with RetrievedDoc conversion
            if (!retrievedDocs.isEmpty() && !textChunkers.isEmpty()) {
                TextChunker chunker = selectBestChunker();
                if (chunker != null) {
                    try {
                        Document originalDoc = documents.get(0);
                        Map<String, Object> chunkingOptions = new HashMap<>();
                        chunkingOptions.put("chunkSize", 500);
                        chunkingOptions.put("overlap", 100);
                        RetrievedDoc docToChunk = convertToRetrievedDoc(originalDoc);

                        List<RetrievedDoc> retrievedChunks = chunker.chunk(docToChunk, chunkingOptions);

                        Map<String, Object> chunkingTest = new HashMap<>();
                        chunkingTest.put("chunkerUsed", chunker.getName());
                        chunkingTest.put("originalChunks", retrievedChunks.size());
                        chunkingTest.put("retrievedChunks", retrievedChunks.size());
                        chunkingTest.put("chunkingSuccessful", retrievedChunks.size() == retrievedChunks.size());

                        // Sample chunk analysis
                        List<Map<String, Object>> chunkSamples = new ArrayList<>();
                        for (int i = 0; i < Math.min(3, retrievedChunks.size()); i++) {
                            RetrievedDoc chunk = retrievedChunks.get(i);
                            Map<String, Object> sample = new HashMap<>();
                            sample.put("index", i);
                            sample.put("id", chunk.getId());
                            sample.put("textLength", chunk.getText() != null ? chunk.getText().length() : 0);
                            sample.put("hasMetadata", !chunk.getMetadata().isEmpty());
                            sample.put("preview", chunk.getText() != null && chunk.getText().length() > 100 ?
                                    chunk.getText().substring(0, 100) + "..." : chunk.getText());
                            chunkSamples.add(sample);
                        }
                        chunkingTest.put("sampleChunks", chunkSamples);
                        testResults.put("chunkingTest", chunkingTest);

                    } catch (Exception e) {
                        Map<String, Object> chunkingError = new HashMap<>();
                        chunkingError.put("error", e.getMessage());
                        chunkingError.put("chunkerUsed", chunker.getName());
                        testResults.put("chunkingError", chunkingError);
                    }
                }
            }

            return ResponseEntity.ok(testResults);

        } catch (Exception e) {
            logger.error("Failed to test RetrievedDoc functionality: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to test RetrievedDoc: " + e.getMessage()));
        }
    }

    /**
     * Endpoint to compare Document vs RetrievedDoc functionality
     */
    @PostMapping("/compare-doc-types")
    public ResponseEntity<?> compareDocumentTypes(@RequestParam("fileName") String fileName) {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly."));
        }

        try {
            Path filePath = this.uploadsPath.resolve(fileName).normalize();

            if (!filePath.startsWith(this.uploadsPath.normalize()) || !Files.exists(filePath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "File not found: " + fileName));
            }

            // Load document
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(fileName)
                    .sourceId("compare_" + fileName)
                    .build();

            DocumentLoader loader = documentLoaders.stream()
                    .filter(l -> l.supports(sourceDescriptor))
                    .findFirst()
                    .orElse(null);

            if (loader == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No suitable loader found for file"));
            }

            List<Document> documents = loader.load(sourceDescriptor);
            if (documents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No documents loaded from file"));
            }

            Document originalDoc = documents.get(0);
            RetrievedDoc retrievedDoc = convertToRetrievedDoc(originalDoc);

            Map<String, Object> comparison = new HashMap<>();
            comparison.put("fileName", fileName);

            // Compare basic properties
            Map<String, Object> basicComparison = new HashMap<>();
            basicComparison.put("original_id", originalDoc.getId());
            basicComparison.put("retrieved_id", retrievedDoc.getId());
            basicComparison.put("ids_match", Objects.equals(originalDoc.getId(), retrievedDoc.getId()));
            basicComparison.put("original_text_length", originalDoc.getText() != null ? originalDoc.getText().length() : 0);
            basicComparison.put("retrieved_text_length", retrievedDoc.getText() != null ? retrievedDoc.getText().length() : 0);
            basicComparison.put("text_lengths_match",
                    Objects.equals(originalDoc.getText() != null ? originalDoc.getText().length() : 0,
                            retrievedDoc.getText() != null ? retrievedDoc.getText().length() : 0));
            basicComparison.put("original_metadata_size", originalDoc.getMetadata().size());
            basicComparison.put("retrieved_metadata_size", retrievedDoc.getMetadata().size());
            basicComparison.put("metadata_sizes_match",
                    originalDoc.getMetadata().size() == retrievedDoc.getMetadata().size());

            comparison.put("basicComparison", basicComparison);

            // Test unique RetrievedDoc features
            Map<String, Object> retrievedDocFeatures = new HashMap<>();
            retrievedDocFeatures.put("has_score_field", retrievedDoc.getScore() != null);
            retrievedDocFeatures.put("score_value", retrievedDoc.getScore());
            retrievedDocFeatures.put("is_text_type", retrievedDoc.isText());
            retrievedDocFeatures.put("has_builder_pattern", true); // Always true for RetrievedDoc

            try {
                String formattedContent = retrievedDoc.getFormattedContent();
                retrievedDocFeatures.put("formatted_content_available", true);
                retrievedDocFeatures.put("formatted_content_length", formattedContent.length());
            } catch (Exception e) {
                retrievedDocFeatures.put("formatted_content_available", false);
                retrievedDocFeatures.put("formatted_content_error", e.getMessage());
            }

            comparison.put("retrievedDocFeatures", retrievedDocFeatures);

            // Test conversion accuracy
            Map<String, Object> conversionAccuracy = new HashMap<>();
            conversionAccuracy.put("text_content_identical",
                    Objects.equals(originalDoc.getText(), retrievedDoc.getText()));
            conversionAccuracy.put("metadata_keys_identical",
                    originalDoc.getMetadata().keySet().equals(retrievedDoc.getMetadata().keySet()));

            // Check if metadata values are preserved
            boolean metadataValuesMatch = true;
            for (Map.Entry<String, Object> entry : originalDoc.getMetadata().entrySet()) {
                if (!Objects.equals(entry.getValue(), retrievedDoc.getMetadata().get(entry.getKey()))) {
                    metadataValuesMatch = false;
                    break;
                }
            }
            conversionAccuracy.put("metadata_values_identical", metadataValuesMatch);
            conversionAccuracy.put("conversion_perfect",
                    Objects.equals(originalDoc.getText(), retrievedDoc.getText()) &&
                            metadataValuesMatch &&
                            Objects.equals(originalDoc.getId(), retrievedDoc.getId()));

            comparison.put("conversionAccuracy", conversionAccuracy);

            return ResponseEntity.ok(comparison);

        } catch (Exception e) {
            logger.error("Failed to compare document types: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to compare document types: " + e.getMessage()));
        }
    }
}