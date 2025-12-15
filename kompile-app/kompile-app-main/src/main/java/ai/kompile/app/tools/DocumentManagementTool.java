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

package ai.kompile.app.tools;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.app.core.chunking.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP Tool for document management operations.
 * Exposes functionality to list, inspect, and manage documents in the RAG system.
 */
@Component
public class DocumentManagementTool {

    private static final Logger logger = LoggerFactory.getLogger(DocumentManagementTool.class);

    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private final VectorStore vectorStore;

    @Value("${app.document.uploads-path:./data/input_documents/uploads}")
    private String uploadsPath;

    @Autowired
    public DocumentManagementTool(
            @Autowired(required = false) List<DocumentLoader> documentLoaders,
            @Autowired(required = false) List<TextChunker> textChunkers,
            @Autowired(required = false) VectorStore vectorStore) {
        this.documentLoaders = documentLoaders != null ? documentLoaders : Collections.emptyList();
        this.textChunkers = textChunkers != null ? textChunkers : Collections.emptyList();
        this.vectorStore = vectorStore;
        logger.info("DocumentManagementTool initialized with {} loaders, {} chunkers",
                this.documentLoaders.size(), this.textChunkers.size());
    }

    // Input records for tools
    public record ListDocumentsInput(String directory, Integer limit) {}
    public record ListLoadersInput(Boolean includeDetails) {}
    public record ListChunkersInput(Boolean includeDetails) {}
    public record GetProcessingStatusInput() {}
    public record DeleteDocumentInput(String documentId) {}
    public record GetDocumentCountInput() {}

    /**
     * Lists uploaded documents in the system.
     */
    @Tool(name = "list_documents",
            description = "Lists documents that have been uploaded to the system. Optionally specify a directory path and limit for results. Returns file names, sizes, and last modified dates.")
    public Map<String, Object> listDocuments(ListDocumentsInput input) {
        logger.info("Listing documents in directory: {}, limit: {}", input.directory(), input.limit());

        try {
            String targetDir = input.directory() != null ? input.directory() : uploadsPath;
            int limit = input.limit() != null && input.limit() > 0 ? input.limit() : 100;

            Path dirPath = Paths.get(targetDir);
            if (!Files.exists(dirPath)) {
                return Map.of("status", "success", "documents", Collections.emptyList(),
                        "message", "Upload directory does not exist yet: " + targetDir);
            }

            List<Map<String, Object>> documents = new ArrayList<>();

            try (Stream<Path> paths = Files.list(dirPath)) {
                paths.filter(Files::isRegularFile)
                        .limit(limit)
                        .forEach(path -> {
                            try {
                                File file = path.toFile();
                                Map<String, Object> doc = new LinkedHashMap<>();
                                doc.put("name", file.getName());
                                doc.put("path", file.getAbsolutePath());
                                doc.put("size", file.length());
                                doc.put("sizeFormatted", formatFileSize(file.length()));
                                doc.put("lastModified", new Date(file.lastModified()).toString());
                                doc.put("extension", getFileExtension(file.getName()));
                                documents.add(doc);
                            } catch (Exception e) {
                                logger.warn("Error reading file info: {}", path, e);
                            }
                        });
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("directory", targetDir);
            result.put("documentCount", documents.size());
            result.put("documents", documents);

            return result;

        } catch (Exception e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list documents: " + e.getMessage());
        }
    }

    /**
     * Lists available document loaders.
     */
    @Tool(name = "list_document_loaders",
            description = "Lists all available document loaders that can parse different file formats (PDF, Office docs, text, etc.). Set includeDetails=true for more information about each loader.")
    public Map<String, Object> listDocumentLoaders(ListLoadersInput input) {
        logger.info("Listing document loaders, includeDetails: {}", input.includeDetails());

        try {
            boolean includeDetails = input.includeDetails() != null && input.includeDetails();

            List<Map<String, Object>> loaderList = new ArrayList<>();
            for (DocumentLoader loader : documentLoaders) {
                Map<String, Object> loaderInfo = new LinkedHashMap<>();
                loaderInfo.put("name", loader.getName());
                loaderInfo.put("simpleName", loader.getClass().getSimpleName());

                if (includeDetails) {
                    loaderInfo.put("className", loader.getClass().getName());
                }

                loaderList.add(loaderInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("loaderCount", loaderList.size());
            result.put("loaders", loaderList);

            return result;

        } catch (Exception e) {
            logger.error("Error listing document loaders: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list loaders: " + e.getMessage());
        }
    }

    /**
     * Lists available text chunkers.
     */
    @Tool(name = "list_text_chunkers",
            description = "Lists all available text chunking strategies (recursive, sentence-based, token-based, etc.). Set includeDetails=true for configuration options.")
    public Map<String, Object> listTextChunkers(ListChunkersInput input) {
        logger.info("Listing text chunkers, includeDetails: {}", input.includeDetails());

        try {
            boolean includeDetails = input.includeDetails() != null && input.includeDetails();

            List<Map<String, Object>> chunkerList = new ArrayList<>();
            for (TextChunker chunker : textChunkers) {
                Map<String, Object> chunkerInfo = new LinkedHashMap<>();
                chunkerInfo.put("name", chunker.getClass().getSimpleName());

                if (includeDetails) {
                    chunkerInfo.put("className", chunker.getClass().getName());
                }

                chunkerList.add(chunkerInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("chunkerCount", chunkerList.size());
            result.put("chunkers", chunkerList);

            return result;

        } catch (Exception e) {
            logger.error("Error listing text chunkers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list chunkers: " + e.getMessage());
        }
    }

    /**
     * Gets the current document processing status.
     */
    @Tool(name = "get_processing_status",
            description = "Gets the current status of document processing including indexed document count, vector store status, and available processing capabilities.")
    public Map<String, Object> getProcessingStatus(GetProcessingStatusInput input) {
        logger.info("Getting document processing status");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Vector store info
            if (vectorStore != null) {
                result.put("vectorStoreAvailable", true);
                result.put("vectorStoreType", vectorStore.getClass().getSimpleName());
                // Note: Count not available via interface, use getDocumentCount tool for implementation-specific count
                result.put("indexedDocumentCount", "use get_document_count for details");
            } else {
                result.put("vectorStoreAvailable", false);
            }

            // Processing capabilities
            result.put("availableLoaders", documentLoaders.size());
            result.put("availableChunkers", textChunkers.size());
            result.put("uploadsDirectory", uploadsPath);

            // Check if uploads directory exists
            Path uploadsDir = Paths.get(uploadsPath);
            result.put("uploadsDirectoryExists", Files.exists(uploadsDir));

            if (Files.exists(uploadsDir)) {
                try (Stream<Path> files = Files.list(uploadsDir)) {
                    result.put("pendingUploads", files.filter(Files::isRegularFile).count());
                }
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting processing status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get status: " + e.getMessage());
        }
    }

    /**
     * Gets the count of indexed documents.
     */
    @Tool(name = "get_document_count",
            description = "Gets the total count of documents currently indexed in the vector store.")
    public Map<String, Object> getDocumentCount(GetDocumentCountInput input) {
        logger.info("Getting indexed document count");

        try {
            if (vectorStore == null) {
                return Map.of("status", "error", "error", "Vector store not available");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("vectorStoreType", vectorStore.getClass().getSimpleName());

            // Try to get count via reflection if available
            try {
                var countMethod = vectorStore.getClass().getMethod("getDocumentCount");
                long count = (Long) countMethod.invoke(vectorStore);
                result.put("indexedDocumentCount", count);
            } catch (NoSuchMethodException e1) {
                try {
                    var countMethod = vectorStore.getClass().getMethod("count");
                    long count = (Long) countMethod.invoke(vectorStore);
                    result.put("indexedDocumentCount", count);
                } catch (NoSuchMethodException e2) {
                    result.put("indexedDocumentCount", "not supported by implementation");
                    result.put("note", "This vector store implementation does not expose document count");
                }
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting document count: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get count: " + e.getMessage());
        }
    }

    // Helper methods
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1).toLowerCase() : "";
    }
}
