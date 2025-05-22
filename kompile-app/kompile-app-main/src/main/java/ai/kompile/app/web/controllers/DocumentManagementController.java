/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
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

    @Autowired
    public DocumentManagementController(
            AppDocumentSourceProperties appDocumentSourceProperties,
            RestTemplate restTemplate,
            List<DocumentLoader> documentLoaders,
            DocumentLoadingService documentLoadingService,
            List<TextChunker> textChunkers
    ) {
        this.sourceProperties = appDocumentSourceProperties;
        this.restTemplate = restTemplate;
        this.documentLoaders = documentLoaders;
        this.documentLoadingService = documentLoadingService;
        this.textChunkers = textChunkers;

        if (appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            logger.error("CRITICAL: 'app.document.uploads-path' is not configured in application.properties. Document upload/add URL functionality will be impaired.");
            this.uploadsPath = Paths.get("./error_uploads_path_not_configured");
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
        }
    }

    public record AddUrlRequest(String url, String fileName, String loader, String chunkerName) {} // chunkerName, options via batch
    public record LoaderInfo(String name, String className) {}
    public record ChunkerInfo(String name, String className) {}

    public record ControllerBatchLoadRequestItem(
            DocumentSourceDescriptor source,
            String loaderName,
            String chunkerName,
            Map<String, Object> chunkerOptions, // Changed to Map
            String vectorStoreName,
            Map<String, Object> metadata
    ) {}

    public record BatchProcessRequest(
            List<ControllerBatchLoadRequestItem> items,
            String defaultLoaderName,
            String defaultChunkerName,
            Map<String, Object> defaultChunkerOptions, // Changed to Map
            String defaultVectorStoreName
    ) {}


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

    @PostMapping("/process-batch")
    public ResponseEntity<?> handleProcessBatch(@RequestBody BatchProcessRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch request items cannot be empty."));
        }
        logger.info("Received batch processing request for {} items. Default Loader: '{}', Default Chunker: '{}', Default Chunker Options: {}",
                request.items().size(), request.defaultLoaderName(), request.defaultChunkerName(), request.defaultChunkerOptions());

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
                String loaderToUse = (item.loaderName() != null && !item.loaderName().isEmpty()) ?
                        item.loaderName() : request.defaultLoaderName();
                String chunkerNameToUse = (item.chunkerName() != null && !item.chunkerName().isEmpty()) ?
                        item.chunkerName() : request.defaultChunkerName();

                if (loaderToUse == null || loaderToUse.isEmpty()) {
                    logger.warn("Skipping item {} as no loader is specified (neither item-specific nor default).", itemKey);
                    aggregatedResults.put(itemKey, Map.of("error", "Loader not specified for item."));
                    errorCount++;
                    continue;
                }

                logger.info("Loading documents for item: {} with loader: '{}'", itemKey, loaderToUse);
                List<org.springframework.ai.document.Document> loadedDocs =
                        documentLoadingService.loadDocumentsFromSource(sourceDescriptor, loaderToUse);

                if (loadedDocs == null) {
                    logger.warn("Loader '{}' returned null for item {}. Treating as empty list.", loaderToUse, itemKey);
                    loadedDocs = Collections.emptyList();
                }
                logger.info("Loaded {} documents for item: {} with loader: '{}'", loadedDocs.size(), itemKey, loaderToUse);

                List<org.springframework.ai.document.Document> finalDocs = loadedDocs;

                if (chunkerNameToUse != null && !chunkerNameToUse.isEmpty()) {
                    TextChunker selectedChunker = null;
                    if (this.textChunkers != null) {
                        selectedChunker = this.textChunkers.stream()
                                .filter(c -> chunkerNameToUse.equals(c.getName()))
                                .findFirst()
                                .orElse(null);
                    }

                    if (selectedChunker != null) {
                        Map<String, Object> effectiveChunkerOptions = new HashMap<>();
                        if (request.defaultChunkerOptions() != null) {
                            effectiveChunkerOptions.putAll(request.defaultChunkerOptions());
                        }
                        if (item.chunkerOptions() != null) {
                            effectiveChunkerOptions.putAll(item.chunkerOptions()); // Item options override batch defaults
                        }

                        logger.info("Applying chunker: '{}' to {} loaded documents for item {} with options: {}",
                                chunkerNameToUse, loadedDocs.size(), itemKey, effectiveChunkerOptions);

                        // Assuming TextChunker implementations can handle a Map<String, Object> for options,
                        // or there's an adapter/overload that makes this call valid.
                        // This call must align with how chunker beans are actually implemented to use the options map.
                        // If the strict TextChunker.java interface chunk(docs, int, int) is the ONLY available method,
                        // then specific keys (e.g. "chunkSize", "overlap") would need to be extracted from 'effectiveChunkerOptions' here.
                        // However, per user's last instruction, passing the map directly.
                       List<Document> finalDocs2 = new ArrayList<>();
                        for(Document doc : loadedDocs) {
                           finalDocs2.addAll(selectedChunker.chunk(doc,effectiveChunkerOptions));
                       }
                        finalDocs = finalDocs2;
                        logger.info("Chunking resulted in {} documents for item {}", finalDocs.size(), itemKey);

                    } else {
                        logger.warn("Chunker '{}' not found for item {}. Using loaded documents without chunking.", chunkerNameToUse, itemKey);
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
                "details", aggregatedResults
        ));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> handleFileUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(name = "loader", required = false) String loaderName,
                                              @RequestParam(name = "chunkerName", required = false) String chunkerName) {
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

        Path destinationFile = null;
        try {
            destinationFile = this.uploadsPath.resolve(sanitizedFileName).normalize();

            if (!destinationFile.startsWith(this.uploadsPath.normalize())) {
                logger.warn("Attempt to save file outside designated uploads directory: {}", destinationFile);
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path (directory traversal attempt)."));
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("File uploaded successfully to: {}", destinationFile);

            StringBuilder detailsMessage = new StringBuilder("The file is now in the directory: ").append(this.uploadsPath);
            if (loaderName != null && !loaderName.isEmpty()) {
                logger.info("Uploaded file '{}' was associated with loader: {}", sanitizedFileName, loaderName);
                detailsMessage.append(". Associated with loader: ").append(loaderName);
            }
            if (chunkerName != null && !chunkerName.isEmpty()) {
                logger.info("Uploaded file '{}' was associated with chunker: {}", sanitizedFileName, chunkerName);
                detailsMessage.append(". Associated with chunker: ").append(chunkerName);
            }
            detailsMessage.append(". Trigger a re-index or use batch processing to include it.");

            return ResponseEntity.ok(Map.of(
                    "message", "File '" + sanitizedFileName + "' uploaded successfully.",
                    "details", detailsMessage.toString(),
                    "fileName", sanitizedFileName,
                    "filePath", destinationFile.toString(),
                    "selectedLoader", loaderName != null ? loaderName : "Default (auto-detect)",
                    "selectedChunkerName", chunkerName != null ? chunkerName : "Default (or none)"
            ));

        } catch (Exception e) {
            String fileNameForError = (destinationFile != null) ? destinationFile.getFileName().toString() : sanitizedFileName;
            logger.error("Failed to store or process uploaded file {}: {}", fileNameForError, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to store or process uploaded file: " + e.getMessage()));
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
                finalOutputFileName = nameFromUrl.matches(".*\\.[a-zA-Z0-9]{1,5}$") ? nameFromUrl : nameFromUrl + ".html";
            } else {
                finalOutputFileName = requestedFileName;
            }

            finalOutputFileName = finalOutputFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (finalOutputFileName.isEmpty()) {
                finalOutputFileName = "url_doc_" + UUID.randomUUID().toString().substring(0,8) + ".html";
            }

            destinationFile = this.uploadsPath.resolve(finalOutputFileName).normalize();
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

            StringBuilder detailsMessage = new StringBuilder("The file is now in the directory: ").append(this.uploadsPath);
            if (loaderName != null && !loaderName.isEmpty()) {
                logger.info("URL content '{}' was associated with loader: {}", finalOutputFileName, loaderName);
                detailsMessage.append(". Associated with loader: ").append(loaderName);
            }
            if (chunkerName != null && !chunkerName.isEmpty()) {
                logger.info("URL content '{}' was associated with chunker: {}", finalOutputFileName, chunkerName);
                detailsMessage.append(". Associated with chunker: ").append(chunkerName);
            }
            detailsMessage.append(". Trigger a re-index or use batch processing to include it.");

            return ResponseEntity.ok(Map.of(
                    "message", "Content from URL '" + urlString + "' saved successfully as '" + finalOutputFileName + "'.",
                    "details", detailsMessage.toString(),
                    "fileName", finalOutputFileName,
                    "filePath", destinationFile.toString(),
                    "selectedLoader", loaderName != null ? loaderName : "Default (auto-detect)",
                    "selectedChunkerName", chunkerName != null ? chunkerName : "Default (or none)"
            ));

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
                return ResponseEntity.ok(Map.of("uploaded_files_location", uploadsPath.toString(), "files", Collections.emptyList()));
            }
            List<Map<String, String>> fileDetails;
            try (Stream<Path> walk = Files.list(uploadsPath)) {
                fileDetails = walk.filter(Files::isRegularFile)
                        .map(path -> Map.of(
                                "fileName", path.getFileName().toString(),
                                "filePath", path.toString()
                        ))
                        .collect(Collectors.toList());
            }
            return ResponseEntity.ok(Map.of("uploaded_files_location", uploadsPath.toString(), "files", fileDetails));
        } catch (IOException e) {
            logger.error("Error listing files in uploads directory {}: {}", uploadsPath, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not list uploaded files: " + e.getMessage()));
        }
    }
}