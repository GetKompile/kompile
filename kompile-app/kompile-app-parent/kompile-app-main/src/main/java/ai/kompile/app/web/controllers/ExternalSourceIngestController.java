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
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.SourceMarkdownConversionService;
import ai.kompile.app.services.YouTubeTranscriptService;
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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REST controller for external content source ingestion endpoints.
 * Handles ingestion from server paths, URLs, YouTube transcripts,
 * raw text, and Slack channels/history.
 *
 * Extracted from DocumentManagementController as a focused controller
 * for external source ingestion.
 */
@RestController
@RequestMapping("/api/documents")
public class ExternalSourceIngestController {

    private static final Logger logger = LoggerFactory.getLogger(ExternalSourceIngestController.class);

    // -------------------------------------------------------------------------
    // Inner records
    // -------------------------------------------------------------------------

    public record AddPathRequest(String path, String loader, String chunkerName) {
    }

    public record AddUrlRequest(String url, String fileName, String loader, String chunkerName,
            boolean convertToMarkdown) {
    }

    public record AddYouTubeRequest(String url, String language, String chunkerName, boolean saveTranscriptFile) {
    }

    public record AddTextRequest(
            String content,
            String sourceName,
            String chunkerName,
            String processingMode) {
    }

    public record AddSlackRequest(
            String channelId,
            String token,
            Integer messageLimit,
            Boolean includeThreads,
            String chunkerName) {
    }

    public record AddSlackHistoryRequest(
            String channelId,
            String token,
            String startDate,
            String endDate,
            Integer daysBack,
            Integer maxMessages,
            Boolean includeThreads,
            Boolean loadAllChannels,
            String chunkerName) {
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Path uploadsPath;
    private final AppDocumentSourceProperties sourceProperties;
    private final RestTemplate restTemplate;
    private final List<DocumentLoader> documentLoaders;
    private final DocumentLoadingService documentLoadingService;
    private final List<TextChunker> textChunkers;
    private IndexerService indexerService;
    private final DocumentIngestService documentIngestService;
    private final IngestProgressTracker progressTracker;
    private final YouTubeTranscriptService youTubeTranscriptService;
    private final SourceDocumentStorageService sourceDocumentStorageService;
    private final SourceMarkdownConversionService sourceMarkdownConversionService;
    private final FactSheetService factSheetService;

    /**
     * Mapping of UI chunker strategy IDs to backend chunker names.
     * Key = UI name (from CHUNKER_STRATEGIES in api-models.ts)
     * Value = Backend chunker getName() return value
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

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Autowired
    public ExternalSourceIngestController(
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) RestTemplate restTemplate,
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) DocumentLoadingService documentLoadingService,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) List<IndexerService> indexerService,
            @Autowired(required = false) DocumentIngestService documentIngestService,
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) YouTubeTranscriptService youTubeTranscriptService,
            @Autowired(required = false) SourceDocumentStorageService sourceDocumentStorageService,
            @Autowired(required = false) SourceMarkdownConversionService sourceMarkdownConversionService,
            @Autowired(required = false) FactSheetService factSheetService) {

        this.sourceProperties = appDocumentSourceProperties;
        this.youTubeTranscriptService = youTubeTranscriptService;
        this.restTemplate = restTemplate;
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.documentLoadingService = documentLoadingService;
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.documentIngestService = documentIngestService;
        this.progressTracker = progressTracker;
        this.sourceDocumentStorageService = sourceDocumentStorageService != null ? sourceDocumentStorageService
                : new SourceDocumentStorageService();
        this.sourceMarkdownConversionService = sourceMarkdownConversionService != null
                ? sourceMarkdownConversionService
                : new SourceMarkdownConversionService(this.sourceDocumentStorageService, appDocumentSourceProperties);
        this.factSheetService = factSheetService;

        if (documentIngestService == null) {
            logger.warn("ExternalSourceIngestController: DocumentIngestService is not available");
        }
        if (progressTracker == null) {
            logger.warn("ExternalSourceIngestController: IngestProgressTracker is not available");
        }
        if (documentLoaders == null || documentLoaders.isEmpty()) {
            logger.warn("ExternalSourceIngestController: No DocumentLoaders available");
        }
        if (textChunkers == null || textChunkers.isEmpty()) {
            logger.warn("ExternalSourceIngestController: No TextChunkers available");
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
            logger.warn("ExternalSourceIngestController: No IndexerService available");
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

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * Endpoint to trigger ingestion from a server-side path.
     * Useful for ingesting directories, existing indices, or large files already
     * present on the server.
     */
    @PostMapping("/add-path")
    public ResponseEntity<?> handleAddPath(@RequestBody AddPathRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        String pathStr = request.path().trim();
        Path path = Paths.get(pathStr).normalize().toAbsolutePath();

        // Block path traversal: reject paths containing ".." components
        if (pathStr.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path traversal not allowed"));
        }

        if (!Files.exists(path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path does not exist on server: " + pathStr));
        }

        String taskId = UUID.randomUUID().toString();
        String safePathStr = pathStr.replace('\n', ' ').replace('\r', ' ');
        logger.info("Received request to ingest path: {} (Task ID: {})", safePathStr, taskId);

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
            logger.error("Failed to start processing for path: {}", safePathStr, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start processing: " + e.getMessage()));
        }
    }

    /**
     * Endpoint to add a URL for content download and ingestion.
     * Includes SSRF protection (blocks internal/private addresses).
     */
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

            // SSRF protection: only allow http/https schemes and block internal addresses
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only http and https URLs are allowed."));
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "URL must have a valid host."));
            }
            String hostLower = host.toLowerCase();
            if (hostLower.equals("localhost") || hostLower.equals("127.0.0.1") || hostLower.equals("::1")
                    || hostLower.equals("[::1]") || hostLower.startsWith("10.")
                    || hostLower.startsWith("192.168.") || hostLower.startsWith("169.254.")
                    || hostLower.equals("metadata.google.internal")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "URLs pointing to internal/private addresses are not allowed."));
            }

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
                try (Stream<Path> walk = Files.walk(destinationFile)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
            Files.writeString(destinationFile, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Content from URL {} saved successfully to: {}", urlString, destinationFile);

            SourceMarkdownConversionService.ConversionResult markdownConversion = null;
            if (request.convertToMarkdown()) {
                markdownConversion = sourceMarkdownConversionService.convertPath(destinationFile, finalOutputFileName,
                        urlString);
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
                DocumentManagementController.DocumentProcessingResult processingResult =
                        processUploadedFile(destinationFile, loaderName, chunkerName, urlString);

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
                org.springframework.ai.document.Document document =
                        youTubeTranscriptService.toDocument(transcriptResult);

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
                    response.put("processingStarted", false);
                    response.put("processingNote",
                            "Transcript fetched but direct document ingestion not available. "
                                    + "Enable saveTranscriptFile=true to save and process the transcript.");
                }
            } else {
                response.put("processingStarted", false);
                response.put("processingNote",
                        "DocumentIngestService not available. Transcript was fetched but not indexed.");
            }

            return ResponseEntity.ok(response);

        } catch (YouTubeTranscriptService.YouTubeTranscriptException e) {
            logger.error("YouTube transcript error for {}: {}", urlString, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Failed to fetch YouTube transcript",
                            "details", e.getMessage(),
                            "url", urlString));
        } catch (Exception e) {
            logger.error("Error processing YouTube URL {}: {}", urlString, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process YouTube URL",
                            "details", e.getMessage(),
                            "url", urlString));
        }
    }

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
                        parseProcessingMode(processingMode));
                response.put("taskId", taskId);
                response.put("processingStarted", true);
            } else if (documentIngestService == null) {
                response.put("processingStarted", false);
                response.put("processingNote",
                        "DocumentIngestService not available. Text was saved but not indexed.");
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
                            "sourceName", sourceName));
        }
    }

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
            List<Document> documents = documentLoadingService.loadDocumentsFromSource(sourceDescriptor,
                    "Slack Channel Loader");

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
                    response.put("processingNote",
                            "IndexerService not available. Messages were loaded but not indexed.");
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing Slack channel {}: {}", request.channelId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process Slack channel",
                            "details", e.getMessage(),
                            "channelId", request.channelId()));
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

        if ((request.channelId() == null || request.channelId().trim().isEmpty())
                && !Boolean.TRUE.equals(request.loadAllChannels())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Slack channel ID is required unless loadAllChannels is true."));
        }

        try {
            String channelId = request.channelId() != null ? request.channelId().trim() : "";
            logger.info("Processing Slack history for channel(s): {}",
                    channelId.isEmpty() ? "all channels" : channelId);

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
                    .sourceId("slack-history-"
                            + (channelId.isEmpty() ? "all" : channelId.replace("#", "").replace(",", "-")))
                    .metadata(metadata)
                    .build();

            // Load documents using the document loading service
            List<Document> documents = documentLoadingService.loadDocumentsFromSource(sourceDescriptor,
                    "Slack History Loader");

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
                    response.put("processingNote",
                            "IndexerService not available. Messages were loaded but not indexed.");
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing Slack history for {}: {}", request.channelId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process Slack history",
                            "details", e.getMessage(),
                            "channelId", request.channelId() != null ? request.channelId() : "all"));
        }
    }

    // -------------------------------------------------------------------------
    // Private utility methods
    // -------------------------------------------------------------------------

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
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * This overload accepts an optional source URL for web-sourced documents.
     */
    private DocumentManagementController.DocumentProcessingResult processUploadedFile(
            Path filePath, String loaderName, String chunkerName, String sourceUrl) throws Exception {
        return processUploadedFileWithTracking(filePath, loaderName, chunkerName, null, sourceUrl);
    }

    /**
     * Processes an uploaded file through the complete workflow: loading, chunking,
     * and indexing.
     * If taskId is provided, sends real-time progress updates via WebSocket.
     * If sourceUrl is provided, stores the original URL for web-sourced documents.
     */
    private DocumentManagementController.DocumentProcessingResult processUploadedFileWithTracking(
            Path filePath, String loaderName, String chunkerName, String taskId, String sourceUrl) throws Exception {
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
     *
     * @param sourceUrl Optional original URL for web-sourced documents
     */
    private DocumentManagementController.DocumentProcessingResult doProcessUploadedFile(
            Path filePath, String loaderName, String chunkerName,
            IngestProgressTracker.TaskProgressContext progress, String sourceUrl) throws Exception {

        logger.info(
                "Starting end-to-end processing for uploaded file: {} with loader: '{}', chunker: '{}', sourceUrl: '{}'",
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
                throw new IllegalArgumentException("Specified loader '" + loaderName + "' not found. Available loaders: "
                        + documentLoaders.stream().map(DocumentLoader::getName).collect(Collectors.joining(", ")));
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
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No suitable loader found for file: " + filePath
                                    + ". Available loaders: "
                                    + documentLoaders.stream().map(DocumentLoader::getName)
                                            .collect(Collectors.joining(", "))));
        }

        logger.info("Selected loader: {} ({})", selectedLoader.getName(), selectedLoader.getClass().getSimpleName());

        // Send progress update: Loading phase started
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

        // Store the document and its metadata (including source_url) for later retrieval
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
                .pathOrUrl(filePath.toString()) // Always use local file path for loader to read
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

        if (loadedDocuments.isEmpty()) {
            logger.error("CRITICAL: No documents loaded from file: {}", filePath);
            return new DocumentManagementController.DocumentProcessingResult(
                    0, 0, Collections.emptyList(), selectedLoader.getName(),
                    chunkerName != null ? chunkerName : "none", false,
                    "No documents could be loaded from the file.");
        }

        // Detailed content analysis for each loaded document
        for (int i = 0; i < loadedDocuments.size(); i++) {
            Document doc = loadedDocuments.get(i);
            String docContent = doc.getText();
            Map<String, Object> docMetadata = doc.getMetadata();

            logger.info("=== DOCUMENT {} ANALYSIS ===", i);
            logger.info("Document ID: {}", doc.getId());
            logger.info("Content is null: {}", docContent == null);

            if (docContent != null) {
                logger.info("Content length: {} characters", docContent.length());
                logger.info("Content is empty/whitespace only: {}", docContent.trim().isEmpty());

                if (docContent.length() > 0) {
                    String preview = docContent.length() > 500 ? docContent.substring(0, 500) + "..." : docContent;
                    logger.info("Content preview (first 500 chars): '{}'", preview);

                    if (docContent.length() > 200) {
                        String ending = docContent.substring(Math.max(0, docContent.length() - 200));
                        logger.info("Content ending (last 200 chars): '{}'", ending);
                    }

                    long lineCount = docContent.lines().count();
                    long wordCount = docContent.split("\\s+").length;
                    boolean containsCommonWords = docContent.toLowerCase().contains("the") ||
                            docContent.toLowerCase().contains("and") ||
                            docContent.toLowerCase().contains("chapter");

                    logger.info("Content stats - Lines: {}, Words: {}, Contains common words: {}",
                            lineCount, wordCount, containsCommonWords);

                    boolean hasSpecialChars = docContent.contains("") || docContent.contains("\ufffd");
                    boolean hasEncodingIssues = docContent
                            .matches(".*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F].*");

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
                    logger.info("Page count: {}",
                            docMetadata.getOrDefault("page_count", docMetadata.get("pageCount")));
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
                logger.warn(
                        "Specified chunker '{}' not found. Available chunkers: {}. Will attempt auto-selection.",
                        chunkerName,
                        textChunkers.stream().map(TextChunker::getName).collect(Collectors.joining(", ")));
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

            // Send progress update: Chunking phase started
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
            // CRITICAL: Disable garbage collection to prevent all chunks from being filtered
            // into a single "garbage" chunk.
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
                        logger.warn(
                                "WARNING: Chunker returned 0 chunks for document {}. Adding original document.", i);
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
                                String chunkPreview = chunkText.length() > 100
                                        ? chunkText.substring(0, 100) + "..."
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
            logger.info(
                    "Chunking completed with '{}'. {} original documents became {} chunks (total created: {})",
                    selectedChunker.getName(), loadedDocuments.size(), finalDocuments.size(), totalChunksCreated);

            // Send progress update: Chunking complete
            if (progress != null) {
                progress.setChunksCreated(finalDocuments.size());
                progress.updateProgress(IngestProgressUpdate.IngestPhase.CHUNKING, 60,
                        "Chunking complete",
                        String.format("Created %d chunks from %d documents", finalDocuments.size(),
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

        // Send progress update: Indexing complete
        if (progress != null) {
            progress.setDocumentsIndexed(finalDocuments.size());
            progress.updateProgress(IngestProgressUpdate.IngestPhase.INDEXING, 95,
                    "Indexing complete",
                    String.format("Indexed %d documents successfully", finalDocuments.size()));
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

        final DocumentLoader finalSelectedLoader = selectedLoader;
        String processingDetails = String.format(
                "Successfully processed file through complete pipeline. Loader: %s, Chunker: %s, Original docs: %d, Final chunks: %d, Indexed: %s. "
                        + "Content lengths: %s",
                finalSelectedLoader.getName(), actualChunkerUsed, loadedDocuments.size(), finalDocuments.size(),
                indexingSuccessful,
                loadedDocuments.stream()
                        .map(doc -> String.valueOf(doc.getText() != null ? doc.getText().length() : 0))
                        .collect(Collectors.joining(", ", "[", "]")));

        return new DocumentManagementController.DocumentProcessingResult(
                loadedDocuments.size(),
                finalDocuments.size(),
                processedDocumentIds,
                finalSelectedLoader.getName(),
                actualChunkerUsed,
                indexingSuccessful,
                processingDetails);
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
                    textChunkers.stream()
                            .map(c -> c.getName() + "(" + c.getClass().getSimpleName() + ")")
                            .collect(Collectors.joining(", ")));
            return null;
        }

        // Prioritize chunkers by type/name preference
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

        // If no preferred chunker found, use the first real chunker
        TextChunker selected = realChunkers.get(0);
        logger.info("No preferred chunker found, using first real chunker: {} ({})",
                selected.getName(), selected.getClass().getSimpleName());
        return selected;
    }
}
