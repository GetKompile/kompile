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

import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.repository.FactRepository;
import ai.kompile.app.services.SourceMarkdownConversionService;
import ai.kompile.core.source.SourceDocumentStorageService;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Controller for viewing facts (original source documents).
 * Provides APIs to retrieve and preview fact content.
 * Note: Also responds on /api/sources for backward compatibility.
 */
@RestController
@RequestMapping({"/api/facts", "/api/sources"})
public class SourceViewerController {

    private static final Logger logger = LoggerFactory.getLogger(SourceViewerController.class);

    // Supported text extensions for inline viewing
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "ts",
            "java", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "yaml", "yml",
            "csv", "tsv", "log", "conf", "cfg", "ini", "sh", "bash", "zsh", "fish",
            "sql", "properties", "env", "gitignore", "dockerfile", "makefile"
    );

    // Supported image extensions
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico"
    );

    // Supported viewable document extensions
    private static final Set<String> VIEWABLE_EXTENSIONS = Set.of(
            "pdf"
    );

    private final SourceDocumentStorageService storageService;
    private final SourceMarkdownConversionService markdownConversionService;
    private final AppDocumentSourceProperties sourceProperties;
    private final FactRepository factRepository;
    private final Path uploadsPath;

    @Autowired
    public SourceViewerController(
            @Autowired(required = false) SourceDocumentStorageService storageService,
            @Autowired(required = false) SourceMarkdownConversionService markdownConversionService,
            @Autowired(required = false) AppDocumentSourceProperties appDocumentSourceProperties,
            @Autowired(required = false) FactRepository factRepository) {
        this.storageService = storageService != null ? storageService : new SourceDocumentStorageService();
        this.markdownConversionService = markdownConversionService != null
                ? markdownConversionService
                : new SourceMarkdownConversionService(this.storageService, appDocumentSourceProperties);
        this.sourceProperties = appDocumentSourceProperties;
        this.factRepository = factRepository;

        if (appDocumentSourceProperties == null ||
                appDocumentSourceProperties.getUploadsPath() == null ||
                appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            this.uploadsPath = Paths.get("./uploads").toAbsolutePath();
        } else {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        }
    }

    /**
     * Get the list of available stored sources.
     */
    @GetMapping
    public ResponseEntity<SourceListResponse> listSources(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        try {
            List<SourceInfo> sources = new ArrayList<>();

            // List files from uploads directory
            if (Files.exists(uploadsPath)) {
                try (Stream<Path> files = Files.list(uploadsPath)) {
                    List<Path> uploadedFiles = files
                            .filter(Files::isRegularFile)
                            .sorted(Comparator.<Path, Long>comparing(p -> {
                                try {
                                    return Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    return 0L;
                                }
                            }).reversed())
                            .collect(Collectors.toList());

                    for (Path file : uploadedFiles) {
                        try {
                            String fileName = file.getFileName().toString();
                            String extension = getFileExtension(fileName);
                            long size = Files.size(file);
                            String modified = Files.getLastModifiedTime(file).toString();

                            SourceInfo info = new SourceInfo(
                                    fileName,
                                    file.toString(),
                                    null, // No checksum calculated yet
                                    "UPLOAD",
                                    extension,
                                    getMimeType(fileName),
                                    size,
                                    modified,
                                    getViewMode(extension),
                                    canPreview(extension),
                                    null // Uploaded files don't have source URLs
                            );
                            sources.add(info);
                        } catch (IOException e) {
                            logger.warn("Error reading file info: {}", file, e);
                        }
                    }
                }
            }

            // List files from stored documents (checksum-based storage)
            Path storageRoot = storageService.getStorageRoot();
            if (storageService.isEnabled() && Files.exists(storageRoot)) {
                try (Stream<Path> dirs = Files.list(storageRoot)) {
                    dirs.filter(Files::isDirectory).forEach(prefixDir -> {
                        try (Stream<Path> files = Files.list(prefixDir)) {
                            files.filter(Files::isRegularFile)
                                    .filter(file -> !file.getFileName().toString().endsWith(".meta.json"))
                                    .forEach(file -> {
                                try {
                                    String fileName = file.getFileName().toString();
                                    // Parse checksum from filename (checksum.extension format)
                                    String checksum = fileName.contains(".") ?
                                            fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                                    String extension = getFileExtension(fileName);
                                    long size = Files.size(file);
                                    String modified = Files.getLastModifiedTime(file).toString();
                                    String sourceUrl = lookupSourceUrl(checksum);
                                    String sourceType = lookupStoredSourceType(checksum, sourceUrl);

                                    SourceInfo info = new SourceInfo(
                                            fileName,
                                            file.toString(),
                                            checksum,
                                            sourceType,
                                            extension,
                                            getMimeType(fileName),
                                            size,
                                            modified,
                                            getViewMode(extension),
                                            canPreview(extension),
                                            sourceUrl
                                    );
                                    sources.add(info);
                                } catch (IOException e) {
                                    logger.warn("Error reading stored file: {}", file, e);
                                }
                            });
                        } catch (IOException e) {
                            logger.warn("Error listing stored files in: {}", prefixDir, e);
                        }
                    });
                } catch (IOException e) {
                    logger.warn("Error listing storage root: {}", storageRoot, e);
                }
            }

            // Apply pagination
            int totalCount = sources.size();
            List<SourceInfo> paginatedSources = sources.stream()
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(new SourceListResponse(paginatedSources, totalCount, offset, limit));
        } catch (Exception e) {
            logger.error("Error listing sources", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SourceListResponse(List.of(), 0, offset, limit));
        }
    }

    /**
     * Get source content by checksum (from stored documents).
     */
    @GetMapping("/checksum/{checksum}")
    public ResponseEntity<Resource> getSourceByChecksum(
            @PathVariable String checksum,
            @RequestParam(value = "download", defaultValue = "false") boolean download) {
        try {
            Optional<Path> storedPath = storageService.getStoredPath(checksum);
            if (storedPath.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path path = storedPath.get();
            return buildFileResponse(path, download);
        } catch (Exception e) {
            logger.error("Error retrieving source by checksum: {}", checksum, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get source content by file name (from uploads directory).
     */
    @GetMapping("/file/{fileName}")
    public ResponseEntity<Resource> getSourceByFileName(
            @PathVariable String fileName,
            @RequestParam(value = "download", defaultValue = "false") boolean download) {
        try {
            // Security: Prevent path traversal
            String sanitizedFileName = Paths.get(fileName).getFileName().toString();
            Path filePath = uploadsPath.resolve(sanitizedFileName);

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }

            // Verify the resolved path is within the uploads directory
            if (!filePath.toRealPath().startsWith(uploadsPath.toRealPath())) {
                logger.warn("Attempted path traversal attack: {}", fileName);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            return buildFileResponse(filePath, download);
        } catch (Exception e) {
            logger.error("Error retrieving source by filename: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get text content of a file for inline viewing.
     */
    @GetMapping("/text/{fileName}")
    public ResponseEntity<TextContentResponse> getTextContent(
            @PathVariable String fileName,
            @RequestParam(value = "maxLines", defaultValue = "10000") int maxLines,
            @RequestParam(value = "encoding", defaultValue = "UTF-8") String encoding) {
        try {
            // Security: Prevent path traversal
            String sanitizedFileName = Paths.get(fileName).getFileName().toString();
            Path filePath = uploadsPath.resolve(sanitizedFileName);

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                // Try stored documents
                filePath = findInStoredDocuments(sanitizedFileName);
                if (filePath == null) {
                    return ResponseEntity.notFound().build();
                }
            }

            // Verify the path is within allowed directories
            if (!isAllowedPath(filePath)) {
                logger.warn("Attempted path traversal attack: {}", fileName);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String extension = getFileExtension(fileName);
            if (!TEXT_EXTENSIONS.contains(extension.toLowerCase())) {
                return ResponseEntity.badRequest()
                        .body(new TextContentResponse(null, fileName, extension, 0, 0,
                                "File type not supported for text viewing", false));
            }

            long fileSize = Files.size(filePath);
            List<String> lines = Files.readAllLines(filePath, getCharset(encoding));

            boolean truncated = false;
            if (lines.size() > maxLines) {
                lines = lines.subList(0, maxLines);
                truncated = true;
            }

            String content = String.join("\n", lines);
            return ResponseEntity.ok(new TextContentResponse(
                    content,
                    fileName,
                    extension,
                    fileSize,
                    lines.size(),
                    null,
                    truncated
            ));
        } catch (Exception e) {
            logger.error("Error reading text content: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new TextContentResponse(null, fileName, null, 0, 0,
                            "Error reading file: " + e.getMessage(), false));
        }
    }

    /**
     * Get metadata about a source file.
     */
    @GetMapping("/info/{fileName}")
    public ResponseEntity<SourceInfo> getSourceInfo(@PathVariable String fileName) {
        try {
            // Security: Prevent path traversal
            String sanitizedFileName = Paths.get(fileName).getFileName().toString();
            Path filePath = uploadsPath.resolve(sanitizedFileName);

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                // Try stored documents
                filePath = findInStoredDocuments(sanitizedFileName);
                if (filePath == null) {
                    return ResponseEntity.notFound().build();
                }
            }

            if (!isAllowedPath(filePath)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String extension = getFileExtension(sanitizedFileName);
            long size = Files.size(filePath);
            String modified = Files.getLastModifiedTime(filePath).toString();

            // Try to get checksum
            String checksum = null;
            String pathStr = filePath.toString();
            if (pathStr.contains(storageService.getStorageRoot().toString())) {
                String fileBaseName = filePath.getFileName().toString();
                checksum = fileBaseName.contains(".") ?
                        fileBaseName.substring(0, fileBaseName.lastIndexOf('.')) : fileBaseName;
            }

            // Look up source URL for stored documents
            String sourceUrl = lookupSourceUrl(checksum);
            String sourceType = checksum != null ? lookupStoredSourceType(checksum, sourceUrl) : "UPLOAD";

            SourceInfo info = new SourceInfo(
                    sanitizedFileName,
                    filePath.toString(),
                    checksum,
                    sourceType,
                    extension,
                    getMimeType(sanitizedFileName),
                    size,
                    modified,
                    getViewMode(extension),
                    canPreview(extension),
                    sourceUrl
            );

            return ResponseEntity.ok(info);
        } catch (Exception e) {
            logger.error("Error getting source info: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Convert a browsed source into a Markdown file under the uploads folder.
     */
    @PostMapping("/markdown/convert")
    public ResponseEntity<?> convertSourceToMarkdown(@RequestBody MarkdownConversionRequest request) {
        try {
            SourceMarkdownConversionService.ConversionResult result = markdownConversionService.convertSource(
                    request.fileName(), request.checksum());
            return ResponseEntity.ok(MarkdownConversionResponse.from(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error converting source to markdown: {}", request, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to convert source to markdown: " + e.getMessage()));
        }
    }

    /**
     * Get supported file extensions and their view modes.
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedTypes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("textExtensions", TEXT_EXTENSIONS);
        result.put("imageExtensions", IMAGE_EXTENSIONS);
        result.put("viewableExtensions", VIEWABLE_EXTENSIONS);
        result.put("allSupported", getAllSupportedExtensions());
        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private ResponseEntity<Resource> buildFileResponse(Path path, boolean download) throws IOException {
        String fileName = path.getFileName().toString();
        String mimeType = getMimeType(fileName);
        long contentLength = Files.size(path);

        Resource resource = new FileSystemResource(path);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeType));
        headers.setContentLength(contentLength);

        if (download) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build());
        } else {
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    private Path findInStoredDocuments(String fileName) {
        try {
            Path storageRoot = storageService.getStorageRoot();
            if (!Files.exists(storageRoot)) {
                return null;
            }

            // Search through stored documents
            try (Stream<Path> walk = Files.walk(storageRoot, 2)) {
                return walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            logger.warn("Error searching stored documents for: {}", fileName, e);
            return null;
        }
    }

    private boolean isAllowedPath(Path path) throws IOException {
        Path realPath = path.toRealPath();
        Path uploadsReal = uploadsPath.toRealPath();
        Path storageReal = storageService.getStorageRoot().toRealPath();

        return realPath.startsWith(uploadsReal) || realPath.startsWith(storageReal);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    private String getMimeType(String fileName) {
        String extension = getFileExtension(fileName);
        return switch (extension) {
            case "txt" -> "text/plain";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "pdf" -> "application/pdf";
            case "md", "markdown" -> "text/markdown";
            case "csv" -> "text/csv";
            case "java" -> "text/x-java-source";
            case "py" -> "text/x-python";
            case "yaml", "yml" -> "application/x-yaml";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> URLConnection.guessContentTypeFromName(fileName);
        };
    }

    private String getViewMode(String extension) {
        if (TEXT_EXTENSIONS.contains(extension.toLowerCase())) {
            return "TEXT";
        } else if (IMAGE_EXTENSIONS.contains(extension.toLowerCase())) {
            return "IMAGE";
        } else if (VIEWABLE_EXTENSIONS.contains(extension.toLowerCase())) {
            return "EMBEDDED";
        } else {
            return "DOWNLOAD_ONLY";
        }
    }

    private boolean canPreview(String extension) {
        return TEXT_EXTENSIONS.contains(extension.toLowerCase()) ||
                IMAGE_EXTENSIONS.contains(extension.toLowerCase()) ||
                VIEWABLE_EXTENSIONS.contains(extension.toLowerCase());
    }

    private Set<String> getAllSupportedExtensions() {
        Set<String> all = new HashSet<>();
        all.addAll(TEXT_EXTENSIONS);
        all.addAll(IMAGE_EXTENSIONS);
        all.addAll(VIEWABLE_EXTENSIONS);
        return all;
    }

    private java.nio.charset.Charset getCharset(String encoding) {
        try {
            return java.nio.charset.Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private String lookupStoredSourceType(String checksum, String sourceUrl) {
        if (checksum != null && !checksum.isEmpty()) {
            try {
                Optional<Map<String, Object>> metadata = storageService.getMetadata(checksum);
                if (metadata.isPresent()) {
                    Object sourceType = metadata.get().get(SourceMetadataConstants.SOURCE_TYPE);
                    if ("MARKDOWN_DERIVED".equals(sourceType)) {
                        return "MARKDOWN";
                    }
                }
            } catch (Exception e) {
                logger.debug("Error looking up source type for checksum {}: {}", checksum, e.getMessage());
            }
        }
        return sourceUrl != null ? "URL" : "STORED";
    }

    /**
     * Look up the source URL for a document by its checksum.
     * First tries the storage service metadata, then falls back to Fact repository.
     * Returns null if no URL is found.
     */
    private String lookupSourceUrl(String checksum) {
        if (checksum == null || checksum.isEmpty()) {
            return null;
        }

        // First try the storage service metadata (primary source)
        try {
            var sourceUrl = storageService.getSourceUrl(checksum);
            if (sourceUrl.isPresent()) {
                return sourceUrl.get();
            }
        } catch (Exception e) {
            logger.debug("Error looking up source URL from storage for checksum {}: {}", checksum, e.getMessage());
        }

        // Fall back to Fact repository if available
        if (factRepository != null) {
            try {
                List<Fact> facts = factRepository.findByChecksum(checksum);
                return facts.stream()
                        .map(Fact::getSourceUrl)
                        .filter(url -> url != null && !url.isEmpty())
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                logger.debug("Error looking up source URL from facts for checksum {}: {}", checksum, e.getMessage());
            }
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RESPONSE RECORDS
    // ═══════════════════════════════════════════════════════════════════════════════

    public record SourceListResponse(
            List<SourceInfo> sources,
            int totalCount,
            int offset,
            int limit
    ) {}

    public record SourceInfo(
            String fileName,
            String path,
            String checksum,
            String sourceType,
            String extension,
            String mimeType,
            long sizeBytes,
            String lastModified,
            String viewMode,
            boolean canPreview,
            String sourceUrl
    ) {}

    public record TextContentResponse(
            String content,
            String fileName,
            String extension,
            long fileSize,
            int lineCount,
            String error,
            boolean truncated
    ) {}

    public record MarkdownConversionRequest(
            String fileName,
            String checksum
    ) {}

    public record MarkdownConversionResponse(
            String fileName,
            String filePath,
            String checksum,
            String originalFileName,
            String originalPath,
            String originalChecksum,
            String sourceUrl,
            long sizeBytes,
            String convertedAt
    ) {
        public static MarkdownConversionResponse from(SourceMarkdownConversionService.ConversionResult result) {
            return new MarkdownConversionResponse(
                    result.fileName(),
                    result.filePath(),
                    result.checksum(),
                    result.originalFileName(),
                    result.originalPath(),
                    result.originalChecksum(),
                    result.sourceUrl(),
                    result.sizeBytes(),
                    result.convertedAt()
            );
        }
    }
}
