/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService; // Import DocumentLoadingService
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

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
    private final AppDocumentSourceProperties sourceProperties;
    private final RestTemplate restTemplate;
    private final List<DocumentLoader> documentLoaders;
    private final DocumentLoadingService documentLoadingService; // Inject DocumentLoadingService

    @Autowired
    public DocumentManagementController(
            AppDocumentSourceProperties appDocumentSourceProperties,
            RestTemplate restTemplate,
            List<DocumentLoader> documentLoaders,
            DocumentLoadingService documentLoadingService // Autowire DocumentLoadingService
    ) {
        this.sourceProperties = appDocumentSourceProperties;
        this.restTemplate = restTemplate;
        this.documentLoaders = documentLoaders;
        this.documentLoadingService = documentLoadingService; // Store it

        if (appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            logger.error("CRITICAL: 'app.document.uploads-path' is not configured in application.properties. Document upload/add URL functionality will be impaired.");
            this.uploadsPath = Paths.get("./error_uploads_path_not_configured");
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    // ... (PostConstruct, AddUrlRequest, LoaderInfo remain the same) ...
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
        }
    }

    public record AddUrlRequest(String url, String fileName, String loader) {}
    public record LoaderInfo(String name, String className) {}

    // For batch processing request
    public record BatchProcessRequest(List<DocumentLoadingService.BatchLoadRequestItem> items, String defaultLoaderName) {}


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

    @PostMapping("/process-batch")
    public ResponseEntity<?> handleProcessBatch(@RequestBody BatchProcessRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch request items cannot be empty."));
        }
        logger.info("Received batch processing request for {} items with default loader '{}'", request.items().size(), request.defaultLoaderName());
        try {
            Map<String, Object> results = documentLoadingService.loadDocumentsBatch(request.items(), request.defaultLoaderName());
            // The result from loadDocumentsBatch already contains either List<Document> or error string per item.
            // For now, we return a summary. A more detailed response structure might be needed.
            long successCount = results.values().stream().filter(v -> v instanceof List).count();
            long errorCount = results.size() - successCount;

            return ResponseEntity.ok(Map.of(
                    "message", "Batch processing completed.",
                    "successful_items", successCount,
                    "failed_items", errorCount,
                    "details", results // Consider whether to return full document content or just summaries
            ));
        } catch (Exception e) {
            logger.error("Error during batch processing: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed during batch processing: " + e.getMessage()));
        }
    }


    // ... (handleFileUpload, handleAddUrl, listConfiguredSources, listUploadedFiles remain mostly the same,
    // ensure they use the updated AddUrlRequest and handle loaderName for upload if applicable) ...
    @PostMapping("/upload")
    public ResponseEntity<?> handleFileUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(name = "loader", required = false) String loaderName) {
        // ... (existing implementation for upload) ...
        // The selectedLoader part in the response is good.
        // Current implementation just saves the file. The batch processing endpoint will handle actual loading.
        // If direct processing on upload is desired (outside of batch), this would need more changes.
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server. Cannot save file."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty."));
        }

        String originalFileName = Objects.requireNonNullElse(file.getOriginalFilename(), "uploaded_file_" + UUID.randomUUID());
        String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitizedFileName.isEmpty()) {
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

            if (loaderName != null && !loaderName.isEmpty()) {
                logger.info("Uploaded file '{}' was associated with loader: {}", sanitizedFileName, loaderName);
                // If you need to immediately process with the specified loader, you would call documentLoadingService here.
                // For example:
                // DocumentSourceDescriptor descriptor = new DocumentSourceDescriptor(DocumentSourceDescriptor.SourceType.FILE, destinationFile.toString(), sanitizedFileName);
                // List<org.springframework.ai.document.Document> loadedDocs = documentLoadingService.loadDocumentsFromSource(descriptor, loaderName);
                // logger.info("Directly processed {} docs from uploaded file {} with loader {}", loadedDocs.size(), sanitizedFileName, loaderName);
                // Then decide what to do with loadedDocs (e.g., add to index, return info)
            }


            return ResponseEntity.ok(Map.of(
                    "message", "File '" + sanitizedFileName + "' uploaded successfully.",
                    "details", "The file is now in the directory: " + this.uploadsPath +
                            (loaderName != null && !loaderName.isEmpty() ? ". Associated with loader: " + loaderName + "." : "") +
                            " Trigger a re-index or use batch processing to include it.",
                    "fileName", sanitizedFileName,
                    "selectedLoader", loaderName != null ? loaderName : "Default (auto-detect)"
            ));

        } catch (Exception e) { // Catching generic Exception because loadDocumentsFromSource can throw it
            logger.error("Failed to store or process uploaded file {}: {}", sanitizedFileName, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to store or process uploaded file: " + e.getMessage()));
        }
    }

    @PostMapping("/add-url")
    public ResponseEntity<?> handleAddUrl(@RequestBody AddUrlRequest request) {
        // ... (existing implementation for add-url) ...
        // Similar to upload, if direct processing is needed after download, add logic here.
        // The selectedLoader part in the response is good.
        if (this.uploadsPath == null || "error_uploads_path_not_configured".equals(this.uploadsPath.getFileName().toString())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Uploads directory is not configured correctly on the server. Cannot save URL content."));
        }
        if (request.url() == null || request.url().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL cannot be empty."));
        }

        String urlString = request.url();
        String outputFileName = request.fileName();
        String loaderName = request.loader();

        try {
            URI uri = new URI(urlString);
            if (outputFileName == null || outputFileName.trim().isEmpty()) {
                Path urlPath = Paths.get(uri.getPath());
                String nameFromUrl = (urlPath.getFileName() != null) ? urlPath.getFileName().toString() : "";
                if (nameFromUrl.isEmpty() || nameFromUrl.equals("/") || nameFromUrl.equals("\\")) {
                    nameFromUrl = "webpage_" + UUID.randomUUID().toString().substring(0, 8);
                }
                if (!nameFromUrl.matches(".*\\.[a-zA-Z0-9]{1,5}$")) {
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

            if (loaderName != null && !loaderName.isEmpty()) {
                logger.info("URL content '{}' was associated with loader: {}", outputFileName, loaderName);
                // If direct processing:
                // DocumentSourceDescriptor descriptor = new DocumentSourceDescriptor(DocumentSourceDescriptor.SourceType.FILE, destinationFile.toString(), outputFileName);
                // List<org.springframework.ai.document.Document> loadedDocs = documentLoadingService.loadDocumentsFromSource(descriptor, loaderName);
                // logger.info("Directly processed {} docs from URL file {} with loader {}", loadedDocs.size(), outputFileName, loaderName);
            }

            return ResponseEntity.ok(Map.of(
                    "message", "Content from URL '" + urlString + "' saved successfully as '" + outputFileName + "'.",
                    "details", "The file is now in the directory: " + this.uploadsPath +
                            (loaderName != null && !loaderName.isEmpty() ? ". Associated with loader: " + loaderName + "." : "") +
                            " Trigger a re-index or use batch processing to include it.",
                    "fileName", outputFileName,
                    "selectedLoader", loaderName != null ? loaderName : "Default (auto-detect)"
            ));

        } catch (Exception e) { // Catch generic Exception
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

    @GetMapping("/sources")
    public ResponseEntity<List<String>> listConfiguredSources() {
        List<String> sources = sourceProperties.getSources();
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
                // Return the configured path even if it doesn't exist, for user info
                return ResponseEntity.ok(Map.of("uploaded_files_location", uploadsPath.toString(), "files", Collections.emptyList()));
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