/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

// Corrected import for AppDocumentSourceProperties
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
// IndexerService is not directly used to trigger indexing in this version of the controller.
// It informs the user to use the IndexerController.
// If direct re-indexing was desired here, IndexerService would be injected and used.
// import ai.kompile.core.indexers.IndexerService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentManagementController.class);

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties; // For listing configured sources & getting uploadsPath
    private final RestTemplate restTemplate;
    // private final IndexerService indexerService; // Optional: if you want this controller to trigger indexing

    @Autowired
    public DocumentManagementController(
            AppDocumentSourceProperties appDocumentSourceProperties,
            RestTemplate restTemplate
            /* IndexerService indexerService */ // Uncomment if you want to trigger indexing from here
    ) {
        this.sourceProperties = appDocumentSourceProperties;
        this.restTemplate = restTemplate;
        // this.indexerService = indexerService;

        if (appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            logger.error("CRITICAL: 'app.document.uploads-path' is not configured in application.properties. Document upload/add URL functionality will be impaired.");
            this.uploadsPath = Paths.get("./error_uploads_path_not_configured"); // Fallback
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    @PostConstruct
    private void initializeUploadsDirectory() {
        try {
            if (this.uploadsPath != null && !"error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
                if (!Files.exists(this.uploadsPath)) {
                    Files.createDirectories(this.uploadsPath);
                    logger.info("Created uploads directory for DocumentManagementController: {}", this.uploadsPath);
                } else {
                    logger.info("Uploads directory already exists: {}", this.uploadsPath);
                }
            } else {
                logger.error("Uploads path is not properly configured (remains as fallback). Upload functionality will likely fail.");
            }
        } catch (IOException e) {
            logger.error("FATAL: Could not create or access uploads directory at {}: {}", this.uploadsPath, e.getMessage(), e);
            // In a real app, you might want to throw a RuntimeException here to prevent startup
            // if this functionality is absolutely critical.
        }
    }

    public record AddUrlRequest(String url, String fileName) {}

    @PostMapping("/upload")
    public ResponseEntity<?> handleFileUpload(@RequestParam("file") MultipartFile file) {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server. Cannot save file."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty."));
        }

        String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(), "uploaded_file_" + UUID.randomUUID());
        String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitizedFileName.isEmpty()) { // Handle cases where sanitization results in empty name
            sanitizedFileName = "upload_" + UUID.randomUUID().toString().substring(0,8);
        }


        try {
            Path destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save file outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path (directory traversal attempt)."));
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("File uploaded successfully to: {}", destinationFile);

            return ResponseEntity.ok(Map.of(
                    "message", "File '" + sanitizedFileName + "' uploaded successfully.",
                    "details", "The file is now in the directory: " + this.uploadsPath +
                            ". Trigger a re-index via POST /api/indexer/rebuild-all-sources to include it in the search.",
                    "fileName", sanitizedFileName
            ));

        } catch (IOException e) {
            logger.error("Failed to store uploaded file {}: {}", sanitizedFileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to store uploaded file: " + e.getMessage()));
        }
    }

    @PostMapping("/add-url")
    public ResponseEntity<?> handleAddUrl(@RequestBody AddUrlRequest request) {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server. Cannot save URL content."));
        }
        if (request.url() == null || request.url().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL cannot be empty."));
        }

        String urlString = request.url();
        String outputFileName = request.fileName();

        try {
            URI uri = new URI(urlString); // Validates URL syntax
            if (outputFileName == null || outputFileName.trim().isEmpty()) {
                Path urlPath = Paths.get(uri.getPath());
                String nameFromUrl = (urlPath.getFileName() != null) ? urlPath.getFileName().toString() : "";
                if (nameFromUrl.isEmpty() || nameFromUrl.equals("/") || nameFromUrl.equals("\\")) {
                    nameFromUrl = "webpage_" + UUID.randomUUID().toString().substring(0, 8);
                }
                // Attempt to retain or add a common extension if not present
                if (!nameFromUrl.matches(".*\\.[a-zA-Z0-9]{1,5}$")) { // Basic extension check
                    outputFileName = nameFromUrl + ".html";
                } else {
                    outputFileName = nameFromUrl;
                }
            }

            outputFileName = outputFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (outputFileName.isEmpty()) {
                outputFileName = "url_doc_" + UUID.randomUUID().toString().substring(0,8) + ".html";
            }


            Path destinationFile = this.uploadsPath.resolve(outputFileName).normalize();
            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save URL content outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path derived from URL (directory traversal attempt)."));
            }

            logger.info("Fetching content from URL: {}", urlString);
            String content = restTemplate.getForObject(uri, String.class);
            if (content == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to fetch content from URL (received null): " + urlString));
            }

            Files.writeString(destinationFile, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Content from URL {} saved successfully to: {}", urlString, destinationFile);

            return ResponseEntity.ok(Map.of(
                    "message", "Content from URL '" + urlString + "' saved successfully as '" + outputFileName + "'.",
                    "details", "The file is now in the directory: " + this.uploadsPath +
                            ". Trigger a re-index via POST /api/indexer/rebuild-all-sources to include it in the search.",
                    "fileName", outputFileName
            ));

        } catch (URISyntaxException e) {
            logger.error("Invalid URL syntax: {}", urlString, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid URL syntax: " + e.getMessage()));
        } catch (RestClientException e) {
            logger.error("Failed to fetch content from URL {}: {}", urlString, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Failed to fetch content from URL: " + e.getMessage()));
        } catch (IOException e) {
            logger.error("Failed to save content from URL {} to file: {}", urlString, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save content from URL: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error while processing URL {}: {}", urlString, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process URL: " + e.getMessage()));
        }
    }

    @GetMapping("/sources")
    public ResponseEntity<List<String>> listConfiguredSources() {
        List<String> sources = sourceProperties.getSources();
        // Provide a more informative message if sources are not configured
        if (sources == null || sources.isEmpty()) {
            return ResponseEntity.ok(Collections.singletonList("No primary document sources configured in 'app.document.sources'. Uploaded files will be processed if uploads path is configured and included."));
        }
        return ResponseEntity.ok(sources);
    }

    @GetMapping("/uploaded-files")
    public ResponseEntity<?> listUploadedFiles() {
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server."));
        }
        try {
            if (!Files.exists(uploadsPath) || !Files.isDirectory(uploadsPath)) {
                logger.info("Uploads directory does not exist or is not a directory: {}", uploadsPath);
                return ResponseEntity.ok(Map.of("message", "Uploads directory does not exist or is not yet created.", "path_configured", uploadsPath.toString(), "files", Collections.emptyList()));
            }
            List<String> fileNames;
            try (Stream<Path> walk = Files.list(uploadsPath)) {
                fileNames = walk.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
            }
            return ResponseEntity.ok(Map.of("uploaded_files_location", uploadsPath.toString(), "files", fileNames));
        } catch (IOException e) {
            logger.error("Error listing files in uploads directory {}: {}", uploadsPath, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not list uploaded files: " + e.getMessage()));
        }
    }
}