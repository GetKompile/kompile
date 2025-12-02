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

package ai.kompile.vectorstore.anserini;

import ai.kompile.core.embeddings.VectorStore;
import io.anserini.index.Constants;
import io.anserini.search.BaseDenseSearcher;
import io.anserini.search.ScoredDoc;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.BytesRef;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced Anserini-based implementation of the VectorStore interface.
 * Supports both flat and HNSW indexing strategies with configurable similarity functions.
 */
@Slf4j
@Service("anseriniVectorStoreImpl")
@ConditionalOnProperty(name = "kompile.vectorstore.anserini.enabled", havingValue = "true", matchIfMissing = false)
public class AnseriniVectorStoreImpl implements VectorStore, DisposableBean {

    private String indexPath;
    private final EmbeddingModel embeddingModel;
    private final AnseriniVectorStoreProperties properties;
    private Directory directory;
    private IndexWriter indexWriter;
    private IndexReader cachedReader;  // Cached reader for document retrieval
    private BaseDenseSearcher<String> searcher;
    private final Object writerLock = new Object();
    private final Object readerLock = new Object();
    private volatile boolean shuttingDown = false;
    private volatile boolean usingFallbackIndex = false;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private Thread shutdownHook;

    public AnseriniVectorStoreImpl(AnseriniVectorStoreProperties properties, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.indexPath = properties.getIndexPath();
        this.embeddingModel = embeddingModel;

        try {
            initializeWithFallback();
            registerShutdownHook();
            log.info("AnseriniVectorStoreImpl initialized with index path: {}, HNSW: {}, fallback: {}",
                    indexPath, properties.getHnsw().isEnabled(), usingFallbackIndex);
        } catch (IOException e) {
            log.error("Failed to initialize Anserini vector store at path: {}", indexPath, e);
            throw new RuntimeException("Failed to initialize Anserini vector store", e);
        }
    }

    /**
     * Register a JVM shutdown hook to ensure resources are cleaned up even on abnormal termination.
     * This is critical for releasing Lucene index locks.
     */
    private void registerShutdownHook() {
        shutdownHook = new Thread(() -> {
            log.info("JVM shutdown hook triggered for AnseriniVectorStoreImpl");
            try {
                doDestroy();
            } catch (Exception e) {
                log.error("Error during shutdown hook cleanup", e);
            }
        }, "anserini-vectorstore-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.debug("Registered JVM shutdown hook for Lucene index cleanup");
    }

    private void initializeWithFallback() throws IOException {
        Path path = Paths.get(indexPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        // ALWAYS try to clear stale lock files on startup - this is the most common issue
        // when an application crashes or is force-killed
        Path lockFile = path.resolve("write.lock");
        if (Files.exists(lockFile)) {
            log.info("Detected existing lock file at {}. Attempting to clear...", lockFile);
            // First try: just delete the lock file directly
            // This almost always works for stale locks from crashed processes
            boolean cleared = forceDeleteLockFile(lockFile);
            if (cleared) {
                log.info("Successfully cleared stale lock file at {}", lockFile);
            } else {
                log.warn("Could not delete lock file directly, will try additional approaches");
                // Try aggressive clearing
                aggressiveLockClear(path);
            }
        }

        // Try with retries
        int maxRetries = 3;
        int retryDelayMs = 500;
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Try to open the configured index path
                if (this.directory != null) {
                    try { this.directory.close(); } catch (Exception ignored) {}
                }
                this.directory = FSDirectory.open(path, NoLockFactory.INSTANCE);
                initializeIndexWriter();
                log.info("Successfully opened index on attempt {}", attempt);
                return; // Success!
            } catch (IOException e) {
                // Catch all IOExceptions (including LockObtainFailedException) to ensure fallback triggers
                lastException = e;
                boolean isLockError = e instanceof LockObtainFailedException;
                log.warn("Attempt {} of {}: Index at {} encountered {}: {}",
                        attempt, maxRetries, indexPath,
                        isLockError ? "lock error" : "IO error",
                        e.getMessage());

                // Close directory before retry
                if (directory != null) {
                    try { directory.close(); } catch (Exception ignored) {}
                    directory = null;
                }

                // Try aggressive lock clearing between attempts
                if (attempt < maxRetries) {
                    if (isLockError) {
                        log.info("Attempting aggressive lock clear before retry...");
                        aggressiveLockClear(path);
                    }

                    // Wait before retry
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries failed, fall back to temporary index
        log.warn("All {} attempts to open primary index failed. Error: {}",
                maxRetries, lastException != null ? lastException.getMessage() : "unknown");

        // Fall back to a temporary index directory
        String fallbackPath = System.getProperty("java.io.tmpdir") +
                "/anserini-vector-index-" + UUID.randomUUID().toString();
        log.warn("Falling back to temporary index at: {}. Data will not be persisted!", fallbackPath);

        // Close the locked directory first
        if (directory != null) {
            try {
                directory.close();
            } catch (Exception ignored) {}
            directory = null;
        }

        // Try the fallback directory with its own error handling
        try {
            Path fallbackPathObj = Paths.get(fallbackPath);
            Files.createDirectories(fallbackPathObj);

            this.indexPath = fallbackPath;
            this.directory = FSDirectory.open(fallbackPathObj, NoLockFactory.INSTANCE);
            this.usingFallbackIndex = true;
            initializeIndexWriter();
            log.info("Successfully initialized fallback index at: {}", fallbackPath);
        } catch (IOException fallbackException) {
            // Last resort: try an in-memory approach or throw detailed error
            log.error("CRITICAL: Failed to initialize even fallback index at {}: {}",
                    fallbackPath, fallbackException.getMessage());

            // Include both the original and fallback errors in the exception
            IOException combinedError = new IOException(
                    "Failed to initialize index. Primary error: " +
                    (lastException != null ? lastException.getMessage() : "unknown") +
                    ". Fallback error: " + fallbackException.getMessage(),
                    lastException);
            combinedError.addSuppressed(fallbackException);
            throw combinedError;
        }
    }

    /**
     * Force delete a lock file. This is safe for stale locks from crashed processes.
     *
     * @param lockFile Path to the lock file
     * @return true if successfully deleted
     */
    private boolean forceDeleteLockFile(Path lockFile) {
        try {
            // On Linux/Mac, we can often just delete stale lock files
            Files.deleteIfExists(lockFile);
            return !Files.exists(lockFile);
        } catch (IOException e) {
            log.debug("Could not delete lock file: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Aggressively attempt to clear a lock file.
     * This method tries multiple approaches including:
     * 1. Direct file deletion
     * 2. Rename and delete (works better on some systems)
     * 3. Creating a new directory and moving the old index
     *
     * @param indexPath Path to the index directory
     */
    private void aggressiveLockClear(Path indexPath) {
        Path lockFile = indexPath.resolve("write.lock");

        if (!Files.exists(lockFile)) {
            log.debug("No lock file exists at {}", lockFile);
            return;
        }

        log.info("Attempting aggressive lock clear on {}", lockFile);

        // Approach 1: Try direct deletion
        try {
            Files.deleteIfExists(lockFile);
            if (!Files.exists(lockFile)) {
                log.info("Successfully deleted lock file directly");
                return;
            }
        } catch (Exception e) {
            log.debug("Direct deletion failed: {}", e.getMessage());
        }

        // Approach 2: Try rename then delete (works better on some file systems)
        try {
            Path tempLockFile = indexPath.resolve("write.lock.old." + System.currentTimeMillis());
            Files.move(lockFile, tempLockFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tempLockFile);
            log.info("Successfully cleared lock file via rename-delete");
            return;
        } catch (Exception e) {
            log.debug("Rename-delete approach failed: {}", e.getMessage());
        }

        // Approach 3: Try to overwrite with an empty file then delete
        try {
            // Write an empty file to "break" any stale lock
            Files.write(lockFile, new byte[0], java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(lockFile);
            if (!Files.exists(lockFile)) {
                log.info("Successfully cleared lock file via truncate-delete");
                return;
            }
        } catch (Exception e) {
            log.debug("Truncate-delete approach failed: {}", e.getMessage());
        }

        // Approach 4: Try using ProcessBuilder to force-remove on Unix systems
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("mac") || os.contains("unix")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("rm", "-f", lockFile.toString());
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0 && !Files.exists(lockFile)) {
                    log.info("Successfully cleared lock file via system rm command");
                    return;
                }
            } catch (Exception e) {
                log.debug("System rm command failed: {}", e.getMessage());
            }
        }

        log.warn("All aggressive lock clearing approaches failed for {}", lockFile);
    }

    private void initializeIndexWriter() throws IOException {
        this.indexWriter = new IndexWriter(directory, AnseriniIndexUtils.createIndexWriterConfig(properties));
    }

    /**
     * Returns whether this vector store is using a fallback temporary index.
     * When true, data will not be persisted between restarts.
     */
    public boolean isUsingFallbackIndex() {
        return usingFallbackIndex;
    }

    @Override
    public void add(List<org.springframework.ai.document.Document> documents, List<List<Float>> embeddings) {
        if (documents == null || documents.isEmpty()) {
            log.debug("No documents provided to add to Anserini VectorStore.");
            return;
        }

        if (embeddings != null && embeddings.size() != documents.size()) {
            log.warn("Pre-computed embeddings size ({}) does not match documents size ({}). " +
                    "Will generate embeddings using configured EmbeddingModel.", 
                    embeddings.size(), documents.size());
        }

        synchronized (writerLock) {
            // Check if we're shutting down
            if (shuttingDown) {
                log.info("VectorStore is shutting down, skipping document addition");
                return;
            }
            
            int addedCount = 0;
            int skippedCount = 0;
            boolean interrupted = false;
            
            try {
                for (int i = 0; i < documents.size(); i++) {
                    // Check for interrupt before processing each document
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Document addition interrupted after processing {} documents", addedCount);
                        interrupted = true;
                        break;
                    }
                    
                    org.springframework.ai.document.Document springAiDoc = documents.get(i);
                    
                    // Use pre-computed embeddings if available and matching, otherwise generate
                    float[] embedding;
                    if (embeddings != null && i < embeddings.size() && embeddings.get(i) != null) {
                        List<Float> embeddingList = embeddings.get(i);
                        embedding = new float[embeddingList.size()];
                        for (int j = 0; j < embeddingList.size(); j++) {
                            embedding[j] = embeddingList.get(j);
                        }
                    } else {
                        // Generate embedding using the EmbeddingModel
                        // Wrap in try-catch to handle native pointer errors from ND4J
                        try {
                            embedding = embeddingModel.embed(springAiDoc);
                        } catch (NullPointerException e) {
                            // This catches JavaCPP "Pointer address of argument X is NULL" errors
                            // that can occur during native ND4J operations
                            log.warn("Native pointer error during embedding generation for document {}, skipping: {}",
                                    springAiDoc.getId(), e.getMessage());
                            embedding = null;
                        } catch (RuntimeException e) {
                            // Catch other runtime exceptions from native operations
                            log.warn("Runtime error during embedding generation for document {}, skipping: {}",
                                    springAiDoc.getId(), e.getMessage());
                            embedding = null;
                        }
                    }

                    // Skip documents with empty embeddings (can happen during shutdown/interrupt)
                    if (embedding == null || embedding.length == 0) {
                        log.debug("Skipping document {} with empty embedding (likely due to interrupt)", 
                                springAiDoc.getId());
                        skippedCount++;
                        continue;
                    }

                    Document luceneDoc = createLuceneDocument(springAiDoc, embedding);
                    indexWriter.addDocument(luceneDoc);
                    addedCount++;
                }
                
                // Only commit if we weren't interrupted and not shutting down
                if (!interrupted && !shuttingDown && !Thread.currentThread().isInterrupted()) {
                    try {
                        indexWriter.commit();
                        
                        if (skippedCount > 0) {
                            log.info("Added {} documents to Anserini VectorStore, skipped {} documents with empty embeddings", 
                                    addedCount, skippedCount);
                        } else {
                            log.info("Successfully added {} documents to Anserini VectorStore", addedCount);
                        }
                        
                        // Refresh searcher after adding documents
                        refreshSearcher();
                    } catch (Exception e) {
                        // IndexWriter might be closed during shutdown
                        if (shuttingDown || Thread.currentThread().isInterrupted()) {
                            log.info("Skipping commit during shutdown, added {} documents before interruption", addedCount);
                        } else {
                            throw e;
                        }
                    }
                } else {
                    log.info("Skipping commit due to interrupt or shutdown, {} documents were added to IndexWriter but not committed", addedCount);
                }
                
            } catch (IOException e) {
                // Check if this is due to shutdown
                if (shuttingDown || Thread.currentThread().isInterrupted()) {
                    log.info("IndexWriter operation interrupted during shutdown after adding {} documents", addedCount);
                } else {
                    log.error("Error adding documents to Anserini VectorStore", e);
                    throw new RuntimeException("Failed to add documents to Anserini VectorStore", e);
                }
            }
        }
    }

    @Override
    public void add(List<org.springframework.ai.document.Document> documents) {
        add(documents, null);
    }

    @Override
    public boolean delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.debug("No IDs provided for deletion from Anserini VectorStore.");
            return true;
        }

        synchronized (writerLock) {
            if (shuttingDown) {
                log.info("VectorStore is shutting down, skipping document deletion");
                return false;
            }
            
            try {
                for (String id : ids) {
                    indexWriter.deleteDocuments(new org.apache.lucene.index.Term(Constants.ID, id));
                }
                indexWriter.commit();
                log.info("Successfully deleted {} documents from Anserini VectorStore", ids.size());
                
                // Refresh searcher after deletion
                refreshSearcher();
                return true;
                
            } catch (IOException e) {
                log.error("Error deleting documents from Anserini VectorStore: {}", ids, e);
                return false;
            }
        }
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("Empty query embedding provided for similarity search");
            return Collections.emptyList();
        }

        float[] queryVector = new float[queryEmbedding.size()];
        for (int i = 0; i < queryEmbedding.size(); i++) {
            queryVector[i] = queryEmbedding.get(i);
        }

        return performSearch(queryVector, k, threshold);
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0);
    }

    @Override
    public List<org.springframework.ai.document.Document> similaritySearch(String query, int k, double threshold) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("Empty query string provided for similarity search");
            return Collections.emptyList();
        }

        // Generate embedding for the query string
        // Wrap in try-catch to handle native pointer errors from ND4J
        float[] queryVector;
        try {
            queryVector = embeddingModel.embed(query);
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            log.warn("Native pointer error during query embedding generation: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            // Catch other runtime exceptions from native operations
            log.warn("Runtime error during query embedding generation: {}", e.getMessage());
            return Collections.emptyList();
        }

        // Handle empty embeddings (can happen during shutdown/interrupt)
        if (queryVector == null || queryVector.length == 0) {
            log.debug("Empty query embedding generated (likely due to interrupt), returning empty results");
            return Collections.emptyList();
        }
        
        return performSearch(queryVector, k, threshold);
    }

    private List<org.springframework.ai.document.Document> performSearch(float[] queryVector, int k, double threshold) {
        try {
            ensureSearcherInitialized();
            
            ScoredDoc[] scoredDocs = searcher.search(null, queryVector, k);
            List<org.springframework.ai.document.Document> results = new ArrayList<>();

            for (ScoredDoc scoredDoc : scoredDocs) {
                if (scoredDoc.score >= threshold) {
                    org.springframework.ai.document.Document springAiDoc = convertToSpringAiDocument(scoredDoc);
                    if (springAiDoc != null) {
                        results.add(springAiDoc);
                    }
                }
            }

            log.debug("Similarity search returned {} documents (threshold: {})", results.size(), threshold);
            return results;
            
        } catch (IOException e) {
            log.error("Error performing similarity search", e);
            return Collections.emptyList();
        }
    }

    private Document createLuceneDocument(org.springframework.ai.document.Document springAiDoc, float[] embedding) {
        Document luceneDoc = new Document();
        
        // Add document ID
        luceneDoc.add(new StringField(Constants.ID, springAiDoc.getId(), Field.Store.YES));
        luceneDoc.add(new BinaryDocValuesField(Constants.ID, new BytesRef(springAiDoc.getId())));
        
        // Add vector field with configured similarity function
        VectorSimilarityFunction similarityFunction = parseSimilarityFunction(properties.getSimilarityFunction());
        luceneDoc.add(new KnnFloatVectorField(Constants.VECTOR, embedding, similarityFunction));
        
        // Store document content
        luceneDoc.add(new StoredField("content", springAiDoc.getText()));
        
        // Store metadata as JSON
        if (springAiDoc.getMetadata() != null && !springAiDoc.getMetadata().isEmpty()) {
            try {
                String metadataJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(springAiDoc.getMetadata());
                luceneDoc.add(new StoredField("metadata", metadataJson));
            } catch (Exception e) {
                log.warn("Failed to serialize metadata for document {}: {}", springAiDoc.getId(), e.getMessage());
            }
        }
        
        return luceneDoc;
    }

    private VectorSimilarityFunction parseSimilarityFunction(String function) {
        switch (function.toUpperCase()) {
            case "DOT_PRODUCT":
                return VectorSimilarityFunction.DOT_PRODUCT;
            case "EUCLIDEAN":
                return VectorSimilarityFunction.EUCLIDEAN;
            case "COSINE":
            default:
                return VectorSimilarityFunction.COSINE;
        }
    }

    private org.springframework.ai.document.Document convertToSpringAiDocument(ScoredDoc scoredDoc) {
        try {
            // Use cached reader to avoid opening/closing for each document
            IndexReader reader = getCachedReader();
            Document luceneDoc = reader.document(Integer.parseInt(scoredDoc.docid));

            String id = luceneDoc.get(Constants.ID);
            String content = luceneDoc.get("content");
            String metadataJson = luceneDoc.get("metadata");

            Map<String, Object> metadata = new HashMap<>();
            if (metadataJson != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsedMetadata = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(metadataJson, Map.class);
                    metadata.putAll(parsedMetadata);
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for document {}: {}", id, e.getMessage());
                }
            }

            // Add score to metadata
            metadata.put("score", (double) scoredDoc.score);

            return new org.springframework.ai.document.Document(id, content, metadata);

        } catch (IOException e) {
            log.error("Error converting ScoredDoc to Spring AI Document", e);
            return null;
        }
    }

    /**
     * Get or create a cached IndexReader for document retrieval.
     * The reader is refreshed if the index has changed.
     */
    private IndexReader getCachedReader() throws IOException {
        synchronized (readerLock) {
            if (cachedReader == null) {
                cachedReader = DirectoryReader.open(directory);
            } else {
                // Check if the reader needs to be refreshed (index has changed)
                DirectoryReader newReader = DirectoryReader.openIfChanged((DirectoryReader) cachedReader);
                if (newReader != null) {
                    // Index has changed, close old reader and use new one
                    try {
                        cachedReader.close();
                    } catch (IOException e) {
                        log.debug("Error closing old cached reader: {}", e.getMessage());
                    }
                    cachedReader = newReader;
                }
            }
            return cachedReader;
        }
    }

    /**
     * Invalidate the cached reader so it will be refreshed on next access.
     * Called after index modifications.
     */
    private void invalidateCachedReader() {
        synchronized (readerLock) {
            if (cachedReader != null) {
                try {
                    cachedReader.close();
                } catch (IOException e) {
                    log.debug("Error closing cached reader during invalidation: {}", e.getMessage());
                }
                cachedReader = null;
            }
        }
    }

    private void ensureSearcherInitialized() throws IOException {
        if (searcher == null) {
            refreshSearcher();
        }
    }

    private void refreshSearcher() throws IOException {
        try {
            // Close old searcher if it exists
            closeSearcherQuietly();

            searcher = AnseriniSearcherFactory.createSearcher(properties, indexPath);
            log.debug("Refreshed searcher for index: {}", indexPath);

        } catch (Exception e) {
            throw new IOException("Failed to refresh searcher", e);
        }
    }

    /**
     * Close the current searcher quietly, ignoring any exceptions.
     */
    private void closeSearcherQuietly() {
        if (searcher != null) {
            try {
                if (searcher instanceof AutoCloseable) {
                    ((AutoCloseable) searcher).close();
                } else if (searcher instanceof Closeable) {
                    ((Closeable) searcher).close();
                }
            } catch (Exception e) {
                log.debug("Error closing searcher: {}", e.getMessage());
            }
            searcher = null;
        }
    }

    @Override
    public void destroy() throws Exception {
        log.info("Spring DisposableBean.destroy() called for AnseriniVectorStoreImpl");

        // Remove the shutdown hook since we're being destroyed normally
        // This prevents double-cleanup
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                log.debug("Removed JVM shutdown hook (normal shutdown path)");
            } catch (IllegalStateException e) {
                // JVM is already shutting down, hook can't be removed - that's fine
                log.debug("Could not remove shutdown hook (JVM already shutting down)");
            }
        }

        doDestroy();
    }

    /**
     * Internal cleanup method that can be called from both destroy() and the JVM shutdown hook.
     * Uses atomic flag to prevent double-cleanup.
     */
    private void doDestroy() {
        // Use atomic flag to prevent double cleanup
        if (!destroyed.compareAndSet(false, true)) {
            log.debug("AnseriniVectorStoreImpl already destroyed, skipping cleanup");
            return;
        }

        log.info("Cleaning up AnseriniVectorStoreImpl resources...");
        shuttingDown = true;

        // Close searcher first (it holds a reader reference)
        closeSearcherQuietly();
        log.debug("Searcher closed");

        // Close cached reader
        invalidateCachedReader();
        log.debug("Cached reader closed");

        synchronized (writerLock) {
            // Close IndexWriter - this releases the write.lock
            if (indexWriter != null) {
                try {
                    // Commit any pending changes before closing
                    if (indexWriter.isOpen()) {
                        try {
                            indexWriter.commit();
                            log.debug("Committed pending changes before closing IndexWriter");
                        } catch (Exception e) {
                            log.debug("Could not commit before close: {}", e.getMessage());
                        }
                    }
                    indexWriter.close();
                    log.info("IndexWriter closed successfully - write.lock should be released");
                } catch (Exception e) {
                    log.warn("Error closing IndexWriter during shutdown: {}", e.getMessage());
                    // Try to force rollback if normal close fails
                    try {
                        indexWriter.rollback();
                    } catch (Exception re) {
                        log.debug("Rollback also failed: {}", re.getMessage());
                    }
                }
                indexWriter = null;
            }

            // Close directory
            if (directory != null) {
                try {
                    directory.close();
                    log.debug("Directory closed");
                } catch (Exception e) {
                    log.warn("Error closing Directory during shutdown: {}", e.getMessage());
                }
                directory = null;
            }

            // Explicitly delete the lock file to ensure clean shutdown
            // This handles cases where Lucene doesn't clean up properly
            if (indexPath != null && !usingFallbackIndex) {
                Path lockFile = Paths.get(indexPath).resolve("write.lock");
                if (Files.exists(lockFile)) {
                    try {
                        Files.deleteIfExists(lockFile);
                        log.info("Explicitly deleted write.lock file during shutdown");
                    } catch (Exception e) {
                        log.debug("Could not delete write.lock file: {}", e.getMessage());
                    }
                }
            }

            // Clean up temporary fallback index directory
            if (usingFallbackIndex && indexPath != null) {
                cleanupFallbackIndex();
            }
        }

        log.info("AnseriniVectorStoreImpl destroyed and all resources cleaned up");
    }

    /**
     * Clean up the temporary fallback index directory.
     */
    private void cleanupFallbackIndex() {
        try {
            Path fallbackPath = Paths.get(indexPath);
            if (Files.exists(fallbackPath)) {
                // Delete all files in the directory
                Files.walk(fallbackPath)
                        .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.debug("Could not delete fallback index file: {}", path);
                            }
                        });
                log.info("Cleaned up temporary fallback index at: {}", indexPath);
            }
        } catch (Exception e) {
            log.warn("Error cleaning up fallback index directory: {}", e.getMessage());
        }
    }

    /**
     * Force release the index lock. Use with caution - only when you're certain
     * no other process is using the index.
     *
     * @return true if lock was released or didn't exist
     */
    public boolean forceReleaseLock() {
        if (indexPath == null) {
            return false;
        }
        Path lockFile = Paths.get(indexPath).resolve("write.lock");
        if (!Files.exists(lockFile)) {
            return true;
        }
        // First try simple delete, then aggressive if that fails
        boolean deleted = forceDeleteLockFile(lockFile);
        if (!deleted) {
            aggressiveLockClear(Paths.get(indexPath));
            deleted = !Files.exists(lockFile);
        }
        return deleted;
    }

    /**
     * Check if this instance has been destroyed.
     */
    public boolean isDestroyed() {
        return destroyed.get();
    }
}
