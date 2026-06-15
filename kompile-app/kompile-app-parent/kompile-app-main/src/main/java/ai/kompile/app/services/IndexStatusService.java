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
 *  limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that tracks and reports the status of indices (vector store and
 * keyword index).
 * Provides auto-discovery of available indices on startup and prominent status
 * reporting.
 */
@Service
public class IndexStatusService {

    private static final Logger log = LoggerFactory.getLogger(IndexStatusService.class);

    private final String dataDir;
    private final VectorStore vectorStore;
    private final IndexerService indexerService;

    // Cached status
    private volatile IndexStatus cachedStatus;
    private volatile long statusCacheTime = 0;
    private static final long STATUS_CACHE_TTL_MS = 5000; // 5 seconds

    @Autowired
    public IndexStatusService(
            @Value("${kompile.data.dir:#{null}}") String dataDir,
            @Autowired(required = false) List<VectorStore> vectorStores,
            @Autowired(required = false) List<IndexerService> indexerServices) {
        this.dataDir = dataDir;
        this.vectorStore = selectNonNoOp(vectorStores, NoOpVectorStoreImpl.class);
        this.indexerService = selectNonNoOp(indexerServices, NoOpIndexerService.class);

        log.info("IndexStatusService initialized with dataDir: {}", dataDir);
    }

    @SuppressWarnings("unchecked")
    private <T> T selectNonNoOp(List<T> items, Class<?> noOpClass) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (T item : items) {
            if (!noOpClass.isInstance(item)) {
                return item;
            }
        }
        return items.get(0);
    }

    /**
     * Log index status after application is fully ready.
     * Uses ApplicationReadyEvent to ensure all beans are initialized.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100) // Run after other startup tasks
    public void onApplicationReady() {
        log.info("IndexStatusService checking index status on startup...");
        IndexStatus status = getStatus();

        // ALWAYS log the status prominently
        log.warn("");
        log.warn("═══════════════════════════════════════════════════════════════════");
        log.warn("                    INDEX STATUS CHECK                              ");
        log.warn("═══════════════════════════════════════════════════════════════════");
        log.warn("Vector Index Path: {}", status.getVectorStorePath());
        log.warn("Vector Index Loaded: {} (documents: {})", status.isVectorIndexLoaded(),
                status.getVectorDocumentCount());
        log.warn("Keyword Index Path: {}", status.getKeywordIndexPath());
        log.warn("Keyword Index Loaded: {} (documents: {})", status.isKeywordIndexLoaded(),
                status.getKeywordDocumentCount());
        log.warn("═══════════════════════════════════════════════════════════════════");

        // Additional warnings if indices are not loaded
        if (!status.isVectorIndexLoaded() || !status.isKeywordIndexLoaded()) {
            log.warn("");
            log.warn("!!! WARNING: INDEX NOT LOADED !!!");
            log.warn("═══════════════════════════════════════════════════════════════════");

            if (!status.isVectorIndexLoaded()) {
                log.warn("VECTOR INDEX: NOT LOADED");
                log.warn("  Current path: {}", status.getVectorStorePath());
                log.warn("  Document count: {}", status.getVectorDocumentCount());
                if (status.isVectorStoreNoOp()) {
                    log.warn("  Status: Using NoOp implementation - searches will return empty results");
                }
            } else {
                log.info("VECTOR INDEX: LOADED at {} ({} documents)",
                        status.getVectorStorePath(), status.getVectorDocumentCount());
            }

            if (!status.isKeywordIndexLoaded()) {
                log.warn("KEYWORD INDEX: NOT LOADED");
                log.warn("  Current path: {}", status.getKeywordIndexPath());
                if (status.isIndexerServiceNoOp()) {
                    log.warn("  Status: Using NoOp implementation - keyword searches will return empty results");
                }
            } else {
                log.info("KEYWORD INDEX: LOADED at {} ({} documents)",
                        status.getKeywordIndexPath(), status.getKeywordDocumentCount());
            }

            // Log available indices that could be loaded
            if (!status.getAvailableVectorIndices().isEmpty()) {
                log.warn("═══════════════════════════════════════════════════════════════════");
                log.warn("Available vector indices that could be loaded:");
                for (String path : status.getAvailableVectorIndices()) {
                    log.warn("  - {}", path);
                }
            }

            if (!status.getAvailableKarchFiles().isEmpty()) {
                log.warn("═══════════════════════════════════════════════════════════════════");
                log.warn("Available .karch archives that could be imported:");
                for (String path : status.getAvailableKarchFiles()) {
                    log.warn("  - {}", path);
                }
            }

            log.warn("═══════════════════════════════════════════════════════════════════");
            log.warn("To load an index: Upload documents via the UI or import a .karch file");
            log.warn("═══════════════════════════════════════════════════════════════════");
        } else {
            log.info("Index status: Vector index loaded at {} ({} docs), Keyword index loaded at {} ({} docs)",
                    status.getVectorStorePath(), status.getVectorDocumentCount(),
                    status.getKeywordIndexPath(), status.getKeywordDocumentCount());
        }
    }

    /**
     * Gets the current index status.
     * Results are cached for 5 seconds to avoid expensive checks on every request.
     */
    public synchronized IndexStatus getStatus() {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && (now - statusCacheTime) < STATUS_CACHE_TTL_MS) {
            return cachedStatus;
        }

        IndexStatus status = computeStatus();
        cachedStatus = status;
        statusCacheTime = now;
        return status;
    }

    /**
     * Forces a refresh of the status cache.
     */
    public synchronized IndexStatus refreshStatus() {
        cachedStatus = null;
        statusCacheTime = 0;
        return getStatus();
    }

    private IndexStatus computeStatus() {
        IndexStatus.IndexStatusBuilder builder = IndexStatus.builder();

        // Vector store status
        boolean vectorStoreNoOp = vectorStore == null || vectorStore instanceof NoOpVectorStoreImpl;
        builder.vectorStoreNoOp(vectorStoreNoOp);

        if (vectorStore != null) {
            String vectorPath = vectorStore.getVectorStorePath();
            builder.vectorStorePath(vectorPath);
            builder.vectorStoreAvailable(vectorStore.isVectorStoreAvailable());

            long vectorCount = vectorStore.getApproxVectorCount();
            builder.vectorDocumentCount(vectorCount);
            builder.vectorIndexLoaded(vectorCount > 0);
            builder.vectorIndexEmpty(vectorCount == 0 && vectorStore.isVectorStoreAvailable());
        } else {
            builder.vectorStorePath("N/A");
            builder.vectorStoreAvailable(false);
            builder.vectorDocumentCount(0);
            builder.vectorIndexLoaded(false);
            builder.vectorIndexEmpty(true);
        }

        // Keyword index status
        boolean indexerNoOp = indexerService == null || indexerService instanceof NoOpIndexerService;
        builder.indexerServiceNoOp(indexerNoOp);

        if (indexerService != null) {
            String keywordPath = indexerService.getIndexPath();
            builder.keywordIndexPath(keywordPath);
            builder.keywordIndexAvailable(indexerService.isIndexAvailable());

            long keywordCount = indexerService.getApproxTotalDocCount(null);
            builder.keywordDocumentCount(keywordCount);
            builder.keywordIndexLoaded(keywordCount > 0);
            builder.keywordIndexEmpty(keywordCount == 0 && indexerService.isIndexAvailable());
        } else {
            builder.keywordIndexPath("N/A");
            builder.keywordIndexAvailable(false);
            builder.keywordDocumentCount(0);
            builder.keywordIndexLoaded(false);
            builder.keywordIndexEmpty(true);
        }

        // Discover available indices
        builder.availableVectorIndices(discoverVectorIndices());
        builder.availableKarchFiles(discoverKarchFiles());

        // Overall status
        boolean anyIndexLoaded = builder.build().isVectorIndexLoaded() || builder.build().isKeywordIndexLoaded();
        builder.anyIndexLoaded(anyIndexLoaded);

        // Generate warning message if needed
        if (!anyIndexLoaded) {
            builder.warningMessage(
                    "No indices are currently loaded. Upload documents or import a .karch archive to enable search functionality.");
        } else if (!builder.build().isVectorIndexLoaded()) {
            builder.warningMessage(
                    "Vector index is empty. Semantic search will not return results until documents are indexed.");
        } else if (!builder.build().isKeywordIndexLoaded()) {
            builder.warningMessage(
                    "Keyword index is empty. Keyword search will not return results until documents are indexed.");
        }

        return builder.build();
    }

    /**
     * Discovers available vector indices in the data directory.
     */
    private List<String> discoverVectorIndices() {
        List<String> indices = new ArrayList<>();

        // Skip discovery if dataDir not configured
        if (dataDir == null) {
            log.debug("Cannot discover vector indices - kompile.data.dir not configured");
            return indices;
        }

        Path dataDirPath = Paths.get(dataDir);
        if (!Files.exists(dataDirPath)) {
            return indices;
        }

        // Check default location
        Path defaultVectorIndex = dataDirPath.resolve("anserini-vector-index");
        if (isValidLuceneIndex(defaultVectorIndex)) {
            indices.add(defaultVectorIndex.toString());
        }

        // Check fact-sheets directories
        Path factSheetsDir = dataDirPath.resolve("fact-sheets");
        if (Files.exists(factSheetsDir) && Files.isDirectory(factSheetsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(factSheetsDir)) {
                for (Path factSheetDir : stream) {
                    if (Files.isDirectory(factSheetDir)) {
                        Path vectorIndex = factSheetDir.resolve("vector-index");
                        if (isValidLuceneIndex(vectorIndex)) {
                            indices.add(vectorIndex.toString());
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Error scanning fact-sheets directory: {}", e.getMessage());
            }
        }

        return indices;
    }

    /**
     * Discovers available .karch files in the data directory.
     */
    private List<String> discoverKarchFiles() {
        List<String> karchFiles = new ArrayList<>();

        // Skip discovery if dataDir not configured
        if (dataDir == null) {
            log.debug("Cannot discover karch files - kompile.data.dir not configured");
            return karchFiles;
        }

        Path dataDirPath = Paths.get(dataDir);
        if (!Files.exists(dataDirPath)) {
            return karchFiles;
        }

        // Check archives directory
        Path archivesDir = dataDirPath.resolve("archives");
        if (Files.exists(archivesDir) && Files.isDirectory(archivesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(archivesDir, "*.karch")) {
                for (Path karchFile : stream) {
                    karchFiles.add(karchFile.toString());
                }
            } catch (IOException e) {
                log.debug("Error scanning archives directory: {}", e.getMessage());
            }
        }

        // Also check the data directory root
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirPath, "*.karch")) {
            for (Path karchFile : stream) {
                karchFiles.add(karchFile.toString());
            }
        } catch (IOException e) {
            log.debug("Error scanning data directory for .karch files: {}", e.getMessage());
        }

        return karchFiles;
    }

    /**
     * Checks if a path contains a valid Lucene index.
     */
    private boolean isValidLuceneIndex(Path indexPath) {
        if (!Files.exists(indexPath) || !Files.isDirectory(indexPath)) {
            return false;
        }

        // Check for segments file (indicates a valid Lucene index)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexPath, "segments*")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Gets the first available index path that could be auto-loaded.
     */
    public String getFirstAvailableIndexPath() {
        List<String> available = discoverVectorIndices();
        return available.isEmpty() ? null : available.get(0);
    }

    /**
     * Data class representing the current index status.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexStatus {
        // Vector store status
        private String vectorStorePath;
        private boolean vectorStoreAvailable;
        private boolean vectorStoreNoOp;
        private long vectorDocumentCount;
        private boolean vectorIndexLoaded;
        private boolean vectorIndexEmpty;

        // Keyword index status
        private String keywordIndexPath;
        private boolean keywordIndexAvailable;
        private boolean indexerServiceNoOp;
        private long keywordDocumentCount;
        private boolean keywordIndexLoaded;
        private boolean keywordIndexEmpty;

        // Discovery
        private List<String> availableVectorIndices;
        private List<String> availableKarchFiles;

        // Overall status
        private boolean anyIndexLoaded;
        private String warningMessage;
    }
}
