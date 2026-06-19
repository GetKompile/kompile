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
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REST controller providing document management overview, status, and metadata endpoints.
 *
 * <p>Upload, debug, and external source ingestion endpoints have been extracted to:
 * <ul>
 *   <li>{@link DocumentUploadController} — file upload and ingest task lifecycle</li>
 *   <li>{@link ExternalSourceIngestController} — URL, YouTube, text, Slack ingestion</li>
 *   <li>{@link DocumentIngestDebugController} — chunker debug and document analysis</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentManagementController.class);

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties;
    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private IndexerService indexerService;

    @Autowired
    public DocumentManagementController(
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) List<IndexerService> indexerService) {
        this.sourceProperties = appDocumentSourceProperties;
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.textChunkers = textChunkers != null ? textChunkers : List.of();

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

    // ── Shared record types (referenced by other split controllers) ──────────

    public record LoaderInfo(String name, String className) {
    }

    public record ChunkerInfo(String name, String className) {
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

    // ── API overview ─────────────────────────────────────────────────────────

    /**
     * Base path handler - returns API overview and available endpoints.
     * This handles GET /api/documents requests.
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

        boolean uploadsDirectoryReady = this.uploadsPath != null &&
                !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString());
        apiInfo.put("uploadsDirectoryConfigured", uploadsDirectoryReady);
        apiInfo.put("loadersAvailable", documentLoaders != null ? documentLoaders.size() : 0);
        apiInfo.put("chunkersAvailable", textChunkers != null ? textChunkers.size() : 0);

        return ResponseEntity.ok(apiInfo);
    }

    // ── Loader and chunker listing ───────────────────────────────────────────

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

    // ── Source and status listing ────────────────────────────────────────────

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

    // ── Processing status and modes ─────────────────────────────────────────

    @GetMapping("/processing-status")
    public ResponseEntity<?> getProcessingStatus() {
        try {
            Map<String, Object> status = new HashMap<>();

            boolean indexerAvailable = indexerService != null && indexerService.isIndexAvailable();
            status.put("indexerAvailable", indexerAvailable);

            long documentCount = 0;
            if (indexerAvailable) {
                documentCount = indexerService.getApproxTotalDocCount(null);
            }
            status.put("approximateDocumentCount", documentCount);

            int loaderCount = (documentLoaders != null) ? documentLoaders.size() : 0;
            int chunkerCount = (textChunkers != null) ? textChunkers.size() : 0;
            status.put("availableLoaders", loaderCount);
            status.put("availableChunkers", chunkerCount);

            boolean uploadsDirectoryReady = this.uploadsPath != null &&
                    !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString()) &&
                    Files.exists(this.uploadsPath) && Files.isDirectory(this.uploadsPath);
            status.put("uploadsDirectoryReady", uploadsDirectoryReady);
            status.put("uploadsPath", this.uploadsPath != null ? this.uploadsPath.toString() : "Not configured");

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
