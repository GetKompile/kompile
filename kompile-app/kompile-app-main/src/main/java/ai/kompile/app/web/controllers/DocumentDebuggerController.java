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
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
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
            String content,
            int contentLength,
            boolean hasContent,
            Map<String, Object> metadata,
            List<String> contentLines,
            Map<String, Object> contentStats
    ) {}

    public record ChunkDebugInfo(
            String id,
            String content,
            int contentLength,
            int chunkIndex,
            Map<String, Object> metadata
    ) {}

    public record DebugAnalysisResult(
            String fileName,
            String filePath,
            long fileSize,
            List<LoaderDebugInfo> availableLoaders,
            LoaderDebugInfo selectedLoader,
            List<DocumentDebugInfo> loadedDocuments,
            List<ChunkerDebugInfo> availableChunkers,
            ChunkerDebugInfo selectedChunker,
            List<ChunkDebugInfo> chunks,
            Map<String, Object> processingStats,
            String errorMessage
    ) {}

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
            @RequestParam(name = "chunkerName", required = false) String chunkerName) {

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
            List<DocumentDebugInfo> documentInfos = new ArrayList<>();

            if (selectedLoader != null) {
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

                    for (int i = 0; i < documents.size(); i++) {
                        Document doc = documents.get(i);
                        String content = doc.getText();
                        Map<String, Object> contentStats = analyzeContent(content);

                        List<String> contentLines = new ArrayList<>();
                        if (content != null && !content.isEmpty()) {
                            contentLines = content.lines().limit(20).collect(Collectors.toList());
                        }

                        DocumentDebugInfo docInfo = new DocumentDebugInfo(
                                doc.getId(),
                                content,
                                content != null ? content.length() : 0,
                                content != null && !content.trim().isEmpty(),
                                doc.getMetadata(),
                                contentLines,
                                contentStats
                        );
                        documentInfos.add(docInfo);
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

            // Select chunker
            TextChunker selectedChunker = null;
            if (chunkerName != null && !chunkerName.isEmpty()) {
                selectedChunker = textChunkers.stream()
                        .filter(c -> chunkerName.equals(c.getName()))
                        .findFirst()
                        .orElse(null);
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
                    Document firstDoc = new Document(documentInfos.get(0).content());
                    firstDoc.getMetadata().putAll(documentInfos.get(0).metadata());

                    Map<String, Object> chunkingOptions = new HashMap<>();
                    chunkingOptions.put("chunkSize", 1000);
                    chunkingOptions.put("overlap", 200);

                    List<Document> chunks = selectedChunker.chunk(firstDoc, chunkingOptions);

                    for (int i = 0; i < chunks.size(); i++) {
                        Document chunk = chunks.get(i);
                        ChunkDebugInfo chunkInfo = new ChunkDebugInfo(
                                chunk.getId(),
                                chunk.getText(),
                                chunk.getText() != null ? chunk.getText().length() : 0,
                                i,
                                chunk.getMetadata()
                        );
                        chunkInfos.add(chunkInfo);
                    }
                } catch (Exception e) {
                    logger.error("Error chunking document: {}", e.getMessage(), e);
                }
            }

            // Processing stats
            Map<String, Object> processingStats = new HashMap<>();
            processingStats.put("fileSize", fileSize);
            processingStats.put("documentsLoaded", documentInfos.size());
            processingStats.put("chunksCreated", chunkInfos.size());
            processingStats.put("totalContentLength",
                    documentInfos.stream().mapToInt(DocumentDebugInfo::contentLength).sum());
            processingStats.put("avgChunkSize",
                    chunkInfos.isEmpty() ? 0 :
                            chunkInfos.stream().mapToInt(ChunkDebugInfo::contentLength).average().orElse(0));

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
                    null
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

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
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
}