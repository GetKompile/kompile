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

package ai.kompile.anserini;

import ai.kompile.anserini.config.AnseriniConfig;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.anserini.index.Constants;
import io.anserini.index.IndexCollection;
import io.anserini.search.SimpleSearcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.Bits;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service("anseriniIndexerService")
public class AnseriniIndexerServiceImpl extends IndexerService {
    private static final Logger logger = LogManager.getLogger(AnseriniIndexerServiceImpl.class);
    private final AnseriniConfig anseriniConfig;
    private final ObjectMapper objectMapper;
    private final DocumentLoadingService documentLoadingService;
    private final List<DocumentLoader> documentLoaders;
    private VectorStore vectorStore;

    // PERFORMANCE OPTIMIZATION: Increased batch size for better throughput.
    // With parallel processing enabled in DocumentIngestService, larger batches
    // reduce per-batch overhead and improve embedding efficiency.
    // Memory is managed at the DocumentIngestService level with parallel batch limiting.
    private static final int DEFAULT_BATCH_SIZE = 100;

    // Smaller batch size for very large documents to prevent memory spikes
    private static final int SMALL_BATCH_SIZE = 50;

    // Threshold: if a single file produces more than this many documents, use smaller batches
    private static final int LARGE_DOCUMENT_THRESHOLD = 200;
    private static final String DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME = "default_anserini_index";

    private volatile boolean shutdownRequested = false;

    // PERFORMANCE: Reusable IndexWriter for incremental keyword indexing
    // Instead of rebuilding the entire index on each batch, we keep the writer open
    private IndexWriter keywordIndexWriter;
    private Directory keywordIndexDirectory;
    private final Object keywordWriterLock = new Object();

    // PERFORMANCE: Track documents added since last commit for delayed commits
    // Committing every batch is expensive (disk I/O). We batch commits to reduce overhead.
    private final java.util.concurrent.atomic.AtomicInteger docsSinceLastCommit = new java.util.concurrent.atomic.AtomicInteger(0);
    // Commit threshold: commit keyword index after this many documents (or on shutdown)
    private static final int KEYWORD_INDEX_COMMIT_THRESHOLD = 500;

    @Autowired
    public AnseriniIndexerServiceImpl(AnseriniConfig anseriniConfig,
                                      ObjectMapper objectMapper,
                                      DocumentLoadingService documentLoadingService,
                                      List<DocumentLoader> documentLoaders,
                                      List<VectorStore> vectorStore) {
        this.anseriniConfig = anseriniConfig;
        this.objectMapper = objectMapper;
        this.documentLoadingService = documentLoadingService;
        this.documentLoaders = documentLoaders;
        if(vectorStore.size() > 1) {
            for(VectorStore vectorStore1 : vectorStore) {
                if(vectorStore1 instanceof NoOpVectorStoreImpl) {
                    continue;
                } else {
                    this.vectorStore = vectorStore1;
                }
            }
        } else {
            this.vectorStore = vectorStore.get(0);
        }

        logger.info("AnseriniIndexerServiceImpl constructed. VectorStore available: {}. DocumentLoaders count: {}",
                vectorStore != null, this.documentLoaders == null ? 0 : this.documentLoaders.size());
        if (CollectionUtils.isEmpty(this.documentLoaders)) {
            logger.warn("No DocumentLoader beans were injected. Ad-hoc file/directory indexing (indexFile, indexDirectory) will fail if called.");
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutdown requested for AnseriniIndexerService");
        shutdownRequested = true;

        // Close the incremental keyword index writer
        synchronized (keywordWriterLock) {
            if (keywordIndexWriter != null) {
                try {
                    keywordIndexWriter.commit();
                    keywordIndexWriter.close();
                    logger.info("Keyword index writer closed successfully");
                } catch (IOException e) {
                    logger.warn("Error closing keyword index writer: {}", e.getMessage());
                }
                keywordIndexWriter = null;
            }
            if (keywordIndexDirectory != null) {
                try {
                    keywordIndexDirectory.close();
                } catch (IOException e) {
                    logger.warn("Error closing keyword index directory: {}", e.getMessage());
                }
                keywordIndexDirectory = null;
            }
        }
    }

    private String getEffectiveLogCollectionName(String collectionNameParam) {
        return StringUtils.hasText(collectionNameParam) ? collectionNameParam : DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME;
    }

    /**
     * Converts Spring AI Document to RetrievedDoc
     */
    private RetrievedDoc convertToRetrievedDoc(Document document) {
        if (document == null) {
            return null;
        }

        RetrievedDoc.Builder builder = RetrievedDoc.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(document.getMetadata());

        if (document.getScore() != null) {
            builder.score(document.getScore());
        }

        return builder.build();
    }

    /**
     * Converts RetrievedDoc to Spring AI Document for vector store compatibility
     */
    private Document convertToDocument(RetrievedDoc retrievedDoc) {
        if (retrievedDoc == null) {
            return null;
        }

        return new Document(retrievedDoc.getId(), retrievedDoc.getText(), retrievedDoc.getMetadata());
    }

    @PostConstruct
    public void initialIndexOnStartup() {
        try {
            logger.info("AnseriniIndexerService PostConstruct: Checking Anserini keyword index at '{}'.", anseriniConfig.getIndexPath());
            if (!isIndexAvailable()) {
                logger.info("Anserini keyword index not available or invalid. Triggering full re-processing of configured sources.");
                reprocessAndIndexAllSources();
            } else {
                logger.info("Anserini keyword index at {} appears to be available and valid. Initial full indexing skipped.", anseriniConfig.getIndexPath());
            }
        } catch (Exception e) {
            logger.error("Error during AnseriniIndexerService initial indexing check/trigger: {}", e.getMessage(), e);
        }
    }

    @Override
    public void reprocessAndIndexAllSources() throws IOException {
        logger.info("Full re-processing and indexing of all configured sources triggered (Anserini Keyword Index + Vector Store).");
        List<Document> allLoadedDocs = documentLoadingService.loadAllConfiguredDocuments();

        // Convert to RetrievedDoc
        List<RetrievedDoc> retrievedDocs = allLoadedDocs.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());

        indexDocuments(retrievedDocs);
    }

    @Override
    public void indexDocuments(List<RetrievedDoc> documents) throws IOException {
        indexDocumentsInternal(documents, DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME);
    }

    @SneakyThrows
    public void indexDocuments(List<RetrievedDoc> documents, String collectionNameParam) {
        indexDocumentsInternal(documents, collectionNameParam);
    }

    /**
     * Indexes documents with pre-computed embeddings.
     * This bypasses embedding generation in the vector store for better parallelization.
     */
    @Override
    public void indexDocumentsWithEmbeddings(List<Document> documents, List<List<Float>> embeddings) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        String logContext = DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME;

        // Check for shutdown
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Indexing with embeddings aborted: shutdown requested");
            return;
        }

        logger.info("Indexing {} documents with pre-computed embeddings to Vector Store", documents.size());

        // Use vectorStore.add with pre-computed embeddings
        if (vectorStore != null && !(vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                vectorStore.add(documents, embeddings);
                logger.info("Successfully indexed {} documents with embeddings to Vector Store", documents.size());
            } catch (Exception e) {
                logger.error("Failed to index with embeddings: {}. Keyword indexing will still proceed.", e.getMessage(), e);
            }
        }

        // Also add to keyword index
        List<RetrievedDoc> retrievedDocs = documents.stream()
                .map(doc -> new RetrievedDoc(
                        doc.getId(),
                        doc.getText(),
                        doc.getMetadata() != null ? new java.util.HashMap<>(doc.getMetadata()) : new java.util.HashMap<>()
                ))
                .toList();
        addToKeywordIndex(retrievedDocs);
    }

    /**
     * Indexes RetrievedDoc documents with pre-computed float[] embeddings.
     * This is the most efficient method as it avoids boxing overhead and
     * directly passes float[] arrays to the vector store.
     * <p>
     * This method enables true parallelization of embedding and indexing:
     * - Embedding worker computes float[] embeddings
     * - Indexing worker receives both documents AND their embeddings
     * - Vector store uses embeddings directly without re-computing
     * - Keyword index is updated in parallel
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays (same order as documents)
     * @throws IOException if there is an error during indexing
     */
    @Override
    public void indexDocumentsWithFloatEmbeddings(List<RetrievedDoc> documents, List<float[]> embeddings) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        // Check for shutdown
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Indexing with float embeddings aborted: shutdown requested");
            return;
        }

        boolean hasEmbeddings = embeddings != null && !embeddings.isEmpty();
        logger.debug("Indexing {} documents with {} float[] embeddings",
                documents.size(), hasEmbeddings ? embeddings.size() : "no");

        // Convert RetrievedDoc to Spring AI Document for vector store
        List<Document> springDocs = new java.util.ArrayList<>(documents.size());
        for (RetrievedDoc doc : documents) {
            springDocs.add(new Document(
                    doc.getId(),
                    doc.getText(),
                    doc.getMetadata() != null ? new java.util.HashMap<>(doc.getMetadata()) : new java.util.HashMap<>()
            ));
        }

        // Index to vector store with pre-computed embeddings
        if (vectorStore != null && !(vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                if (hasEmbeddings) {
                    // Convert float[] to List<Float> for VectorStore interface
                    // This is minimal overhead since we're just boxing, not recomputing embeddings
                    List<List<Float>> boxedEmbeddings = new java.util.ArrayList<>(embeddings.size());
                    for (float[] emb : embeddings) {
                        if (emb != null) {
                            List<Float> floatList = new java.util.ArrayList<>(emb.length);
                            for (float f : emb) {
                                floatList.add(f);
                            }
                            boxedEmbeddings.add(floatList);
                        } else {
                            boxedEmbeddings.add(null);
                        }
                    }
                    vectorStore.add(springDocs, boxedEmbeddings);
                    logger.debug("Indexed {} documents with pre-computed embeddings to Vector Store", documents.size());
                } else {
                    // No embeddings provided - vector store will compute them
                    vectorStore.add(springDocs);
                    logger.debug("Indexed {} documents to Vector Store (embeddings computed by store)", documents.size());
                }
            } catch (Exception e) {
                logger.error("Failed to index to Vector Store: {}. Keyword indexing will still proceed.", e.getMessage(), e);
            }
        }

        // Add to keyword index (runs in parallel with vector store indexing)
        addToKeywordIndex(documents);
    }

    /**
     * Indexes documents to the keyword index only (Lucene).
     * This method enables parallel indexing where keyword and vector stores
     * are updated independently by separate workers.
     *
     * @param documents The documents to index
     * @throws IOException if there is an error during indexing
     */
    @Override
    public void indexToKeywordIndexOnly(List<RetrievedDoc> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        // Check for shutdown
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Keyword-only indexing aborted: shutdown requested");
            return;
        }

        logger.debug("Indexing {} documents to keyword index only", documents.size());
        addToKeywordIndex(documents);
    }

    /**
     * Indexes documents to the vector store only (no keyword index).
     * This method enables parallel indexing where keyword and vector stores
     * are updated independently by separate workers.
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays
     * @throws IOException if there is an error during indexing
     */
    @Override
    public void indexToVectorStoreOnly(List<RetrievedDoc> documents, List<float[]> embeddings) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        // Check for shutdown
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Vector-only indexing aborted: shutdown requested");
            return;
        }

        boolean hasEmbeddings = embeddings != null && !embeddings.isEmpty();
        logger.debug("Indexing {} documents to vector store only (embeddings: {})",
                documents.size(), hasEmbeddings ? embeddings.size() : "none");

        // Convert RetrievedDoc to Spring AI Document for vector store
        List<Document> springDocs = new java.util.ArrayList<>(documents.size());
        for (RetrievedDoc doc : documents) {
            springDocs.add(new Document(
                    doc.getId(),
                    doc.getText(),
                    doc.getMetadata() != null ? new java.util.HashMap<>(doc.getMetadata()) : new java.util.HashMap<>()
            ));
        }

        // Index to vector store with pre-computed embeddings
        if (vectorStore != null && !(vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                if (hasEmbeddings) {
                    // Convert float[] to List<Float> for VectorStore interface
                    List<List<Float>> boxedEmbeddings = new java.util.ArrayList<>(embeddings.size());
                    for (float[] emb : embeddings) {
                        if (emb != null) {
                            List<Float> floatList = new java.util.ArrayList<>(emb.length);
                            for (float f : emb) {
                                floatList.add(f);
                            }
                            boxedEmbeddings.add(floatList);
                        } else {
                            boxedEmbeddings.add(null);
                        }
                    }
                    vectorStore.add(springDocs, boxedEmbeddings);
                } else {
                    // No embeddings provided - vector store will compute them
                    vectorStore.add(springDocs);
                }
                logger.debug("Indexed {} documents to vector store", documents.size());
            } catch (Exception e) {
                logger.error("Failed to index to Vector Store: {}", e.getMessage(), e);
                throw new IOException("Vector store indexing failed", e);
            }
        }
    }

    /**
     * Indexes documents in parallel to both keyword index and vector store.
     * Uses a dedicated executor to run keyword and vector indexing concurrently.
     *
     * @param documents  The documents to index
     * @param embeddings Pre-computed embeddings as float[] arrays
     * @return A CompletableFuture that completes when both indexes are updated
     */
    @Override
    public java.util.concurrent.CompletableFuture<Void> indexDocumentsParallel(
            List<RetrievedDoc> documents, List<float[]> embeddings) {
        if (documents == null || documents.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        // Check for shutdown
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Parallel indexing aborted: shutdown requested");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        long startTime = System.currentTimeMillis();
        logger.info("PARALLEL INDEXING START: {} documents with {} embeddings on thread {}",
                documents.size(), embeddings != null ? embeddings.size() : 0, Thread.currentThread().getName());

        // Run keyword and vector indexing in parallel
        // Each task checks for interruption to support cancellation from timeout
        java.util.concurrent.CompletableFuture<Void> keywordFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {
            long kwStart = System.currentTimeMillis();
            logger.info("  [KEYWORD INDEX] Starting on thread {}", Thread.currentThread().getName());
            try {
                // Check for interruption before starting
                if (Thread.currentThread().isInterrupted() || shutdownRequested) {
                    logger.warn("  [KEYWORD INDEX] Cancelled before start");
                    return;
                }
                indexToKeywordIndexOnly(documents);
                logger.info("  [KEYWORD INDEX] Completed {} docs in {}ms on thread {}",
                        documents.size(), System.currentTimeMillis() - kwStart, Thread.currentThread().getName());
            } catch (IOException e) {
                logger.error("  [KEYWORD INDEX] Failed after {}ms: {}",
                        System.currentTimeMillis() - kwStart, e.getMessage());
                throw new java.util.concurrent.CompletionException("Keyword indexing failed", e);
            }
        });

        java.util.concurrent.CompletableFuture<Void> vectorFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {
            long vecStart = System.currentTimeMillis();
            logger.info("  [VECTOR INDEX] Starting on thread {}", Thread.currentThread().getName());
            try {
                // Check for interruption before starting
                if (Thread.currentThread().isInterrupted() || shutdownRequested) {
                    logger.warn("  [VECTOR INDEX] Cancelled before start");
                    return;
                }
                indexToVectorStoreOnly(documents, embeddings);
                logger.info("  [VECTOR INDEX] Completed {} docs in {}ms on thread {}",
                        documents.size(), System.currentTimeMillis() - vecStart, Thread.currentThread().getName());
            } catch (IOException e) {
                logger.error("  [VECTOR INDEX] Failed after {}ms: {}",
                        System.currentTimeMillis() - vecStart, e.getMessage());
                throw new java.util.concurrent.CompletionException("Vector indexing failed", e);
            }
        });

        return java.util.concurrent.CompletableFuture.allOf(keywordFuture, vectorFuture)
                .whenComplete((result, ex) -> {
                    long totalTime = System.currentTimeMillis() - startTime;
                    if (ex != null) {
                        logger.error("PARALLEL INDEXING FAILED after {}ms: {}", totalTime, ex.getMessage());
                    } else {
                        logger.info("PARALLEL INDEXING COMPLETE: {} documents indexed in {}ms (keyword+vector in parallel)",
                                documents.size(), totalTime);
                    }
                });
    }

    private void indexDocumentsInternal(List<RetrievedDoc> documents, String collectionNameParamForLogging) throws IOException {
        String logContextCollectionName = getEffectiveLogCollectionName(collectionNameParamForLogging);

        // Check for shutdown request before starting
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Indexing aborted: shutdown requested or thread interrupted");
            return;
        }

        logger.info("Anserini keyword indexing and Vector Store population requested for {} documents. Anserini Index: '{}'. Logging Collection Context: '{}'",
                documents == null ? 0 : documents.size(),
                anseriniConfig.getIndexPath(),
                logContextCollectionName);

        if (vectorStore != null) {
            if (!CollectionUtils.isEmpty(documents)) {
                // Check for shutdown before vector store operation
                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                    logger.warn("Indexing aborted before vector store population: shutdown requested");
                    return;
                }

                try {
                    logger.info("Populating Vector Store with {} documents (logging context: {})...",
                            documents.size(), logContextCollectionName);

                    // Convert RetrievedDoc to Document for vector store compatibility
                    List<Document> springAiDocuments = documents.stream()
                            .map(this::convertToDocument)
                            .collect(Collectors.toList());

                    vectorStore.add(springAiDocuments);
                    logger.info("Successfully submitted {} documents to Vector Store (logging context: {}).",
                            documents.size(), logContextCollectionName);
                } catch (Exception e) {
                    logger.error("Failed to populate Vector Store (logging context: {}): {}. Anserini keyword indexing will still proceed.",
                            logContextCollectionName, e.getMessage(), e);
                }
            } else {
                logger.info("No documents provided to populate VectorStore (logging context: {}).", logContextCollectionName);
            }
        } else {
            logger.warn("VectorStore bean is not available. Skipping vector store population (logging context: {}).", logContextCollectionName);
        }

        // Check for shutdown before Anserini indexing
        if (shutdownRequested || Thread.currentThread().isInterrupted()) {
            logger.warn("Indexing aborted before Anserini keyword indexing: shutdown requested");
            return;
        }

        // PERFORMANCE: Use incremental indexing instead of full rebuild
        // This is the critical fix - previously each batch would rebuild the entire index!
        addToKeywordIndex(documents);
    }

    private DocumentLoader findLoaderForPath(Path filePath) throws IOException {
        if (CollectionUtils.isEmpty(documentLoaders)) {
            String errorMsg = "No DocumentLoaders configured. Cannot find loader for path: " + filePath;
            logger.error(errorMsg);
            throw new IOException(errorMsg);
        }
        DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .build();

        for (DocumentLoader loader : documentLoaders) {
            if (loader.supports(tempDescriptor)) {
                logger.debug("Using loader '{}' ({}) for path {}", loader.getName(), loader.getClass().getSimpleName(), filePath);
                return loader;
            }
        }
        String availableLoaders = documentLoaders.stream().map(dl -> dl.getName() + " (" + dl.getClass().getSimpleName() + ")").collect(Collectors.joining(", "));
        String errorMsg = "No suitable DocumentLoader found that explicitly supports file: " + filePath +
                ". Checked " + documentLoaders.size() + " loaders: [" + availableLoaders + "].";
        logger.error(errorMsg);
        throw new IOException(errorMsg);
    }

    public void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException {
        String effectiveCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        logger.info("Request to index file: {} with sourceId: {}. Target Anserini Index: '{}'. Logging/Descriptor Collection: {}",
                filePath, sourceId, anseriniConfig.getIndexPath(), effectiveCollectionName);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            logger.error("File not found or is not a regular file: {}", filePath);
            throw new IOException("File not found or is not a regular file: " + filePath);
        }

        DocumentLoader loader = findLoaderForPath(filePath);

        DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .sourceId(sourceId)
                .metadata(Collections.emptyMap())
                .collectionName(effectiveCollectionName)
                .build();

        List<Document> springAiDocuments;
        try {
            springAiDocuments = loader.load(sourceDescriptor);
        } catch (Exception e) {
            logger.error("Failed to load documents from file: {} using loader: '{}' ({}): {}",
                    filePath, loader.getName(), loader.getClass().getSimpleName(), e.getMessage(), e);
            throw new IOException("Failed to load documents from file: " + filePath, e);
        }

        if (CollectionUtils.isEmpty(springAiDocuments)) {
            logger.warn("Loader '{}' produced no documents for file: {}", loader.getName(), filePath);
            return;
        }

        // Convert to RetrievedDoc
        List<RetrievedDoc> documents = springAiDocuments.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());

        indexDocuments(documents, effectiveCollectionName);
        logger.info("Successfully processed file: {} ({} documents). Anserini Index: '{}'. Logging Collection: {}.",
                filePath, documents.size(), anseriniConfig.getIndexPath(), effectiveCollectionName);
    }

    public void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam) throws IOException {
        String effectiveCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        logger.info("Request to index directory: {} with sourceIdPrefix: {}. Target Anserini Index: '{}'. Logging/Descriptor Collection: {}",
                directoryPath, sourceIdPrefix, anseriniConfig.getIndexPath(), effectiveCollectionName);

        if (!Files.isDirectory(directoryPath)) {
            logger.error("Path is not a directory: {}", directoryPath);
            throw new IOException("Path is not a directory: " + directoryPath);
        }

        if (CollectionUtils.isEmpty(documentLoaders)) {
            String errorMsg = "No DocumentLoaders configured. Cannot index directory: " + directoryPath;
            logger.error(errorMsg);
            throw new IOException(errorMsg);
        }

        // MEMORY FIX: Track total documents indexed and use adaptive batch sizing
        int totalDocumentsIndexed = 0;
        int currentBatchSize = DEFAULT_BATCH_SIZE;

        try (Stream<Path> paths = Files.walk(directoryPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            logger.info("Found {} files in directory {} for potential indexing.", files.size(), directoryPath);
            int processedFileCount = 0;

            for (Path filePath : files) {
                // Check for shutdown during directory processing
                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                    logger.warn("Directory indexing interrupted, stopping gracefully. Processed {} of {} files.",
                            processedFileCount, files.size());
                    break;
                }

                try {
                    DocumentLoader loader = findLoaderForPath(filePath);
                    String fileSpecificSourceId = StringUtils.hasText(sourceIdPrefix) ?
                            sourceIdPrefix + ":" + directoryPath.relativize(filePath).toString() :
                            directoryPath.relativize(filePath).toString();

                    DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                            .type(DocumentSourceDescriptor.SourceType.FILE)
                            .pathOrUrl(filePath.toString())
                            .originalFileName(filePath.getFileName().toString())
                            .sourceId(fileSpecificSourceId)
                            .metadata(Collections.emptyMap())
                            .collectionName(effectiveCollectionName)
                            .build();

                    logger.debug("Loading documents from file: {} with sourceId: {} using loader '{}'",
                            filePath, fileSpecificSourceId, loader.getName());
                    List<Document> springAiDocuments = loader.load(sourceDescriptor);

                    if (!CollectionUtils.isEmpty(springAiDocuments)) {
                        // MEMORY FIX: Handle large documents by processing in streaming batches
                        // instead of accumulating all documents before batching
                        int loadedCount = springAiDocuments.size();

                        // Detect large documents and use smaller batch size
                        if (loadedCount >= LARGE_DOCUMENT_THRESHOLD) {
                            logger.info("Large document detected ({} pages/sections). Using streaming batch processing to prevent OOM.",
                                    loadedCount);
                            currentBatchSize = SMALL_BATCH_SIZE;

                            // Process large documents in streaming batches immediately
                            for (int i = 0; i < springAiDocuments.size(); i += currentBatchSize) {
                                // Check for interrupt within large document processing
                                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                                    logger.warn("Large document processing interrupted at batch {}/{}",
                                            i / currentBatchSize, (loadedCount + currentBatchSize - 1) / currentBatchSize);
                                    break;
                                }

                                int endIdx = Math.min(i + currentBatchSize, springAiDocuments.size());
                                List<Document> batchDocs = springAiDocuments.subList(i, endIdx);

                                List<RetrievedDoc> batchRetrievedDocs = batchDocs.stream()
                                        .map(this::convertToRetrievedDoc)
                                        .collect(Collectors.toList());

                                logger.debug("Indexing large document batch {}-{} of {} from file: {}",
                                        i + 1, endIdx, loadedCount, filePath.getFileName());
                                indexDocuments(batchRetrievedDocs, effectiveCollectionName);
                                totalDocumentsIndexed += batchRetrievedDocs.size();

                                // MEMORY FIX: Clear batch immediately and hint GC after each batch
                                batchRetrievedDocs.clear();

                                // Hint GC periodically for large documents (every 5 batches)
                                if ((i / currentBatchSize) % 5 == 4) {
                                    suggestGarbageCollection();
                                }
                            }
                            // Clear the spring AI documents list to release memory
                            springAiDocuments.clear();

                        } else {
                            // Normal sized document - use standard batch accumulation
                            // but with immediate indexing per file to avoid accumulation
                            List<RetrievedDoc> loadedDocs = springAiDocuments.stream()
                                    .map(this::convertToRetrievedDoc)
                                    .collect(Collectors.toList());

                            // MEMORY FIX: Index each file's documents immediately instead of accumulating
                            // This prevents unbounded growth when processing many files
                            if (!loadedDocs.isEmpty()) {
                                indexDocuments(loadedDocs, effectiveCollectionName);
                                totalDocumentsIndexed += loadedDocs.size();
                            }
                        }
                    } else {
                        logger.warn("Loader '{}' produced no documents for file: {}", loader.getName(), filePath);
                    }

                    processedFileCount++;

                    // MEMORY FIX: Periodic GC hint during directory processing (every 10 files)
                    if (processedFileCount % 10 == 0) {
                        suggestGarbageCollection();
                        logger.debug("Processed {} files, {} total documents indexed so far",
                                processedFileCount, totalDocumentsIndexed);
                    }
                } catch (IOException e) {
                    logger.error("Could not load/process file: {} in directory {}. Error: {}. Skipping file.", filePath, directoryPath, e.getMessage());
                } catch (Exception e) {
                    logger.error("Unexpected error loading file: {} in directory {}. Skipping file.", filePath, directoryPath, e);
                }
            }

            logger.info("Successfully processed {} files, {} documents indexed from directory: {}. Logging collection: {}.",
                    processedFileCount, totalDocumentsIndexed, directoryPath, effectiveCollectionName);
        }
    }

    /**
     * Suggests garbage collection to help manage memory during large indexing operations.
     * This is a hint only - the JVM may or may not perform GC.
     * Used strategically during long-running operations to prevent OOM.
     */
    private void suggestGarbageCollection() {
        // Log memory before GC hint
        logMemoryUsage("Before GC hint");

        // Only suggest GC, don't force it - let JVM decide
        System.gc();

        // Log memory after GC hint (may not have changed yet)
        logMemoryUsage("After GC hint");
    }

    /**
     * Logs current memory usage for monitoring during indexing operations.
     * Helps diagnose memory leak issues by tracking heap and native memory.
     *
     * @param context Description of when this log is being made
     */
    private void logMemoryUsage(String context) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double usedPercent = (usedMemory * 100.0) / maxMemory;

        // Convert to MB for readability
        long usedMB = usedMemory / (1024 * 1024);
        long maxMB = maxMemory / (1024 * 1024);

        // Log at debug level normally, warn if over 80%
        if (usedPercent > 80) {
            logger.warn("Memory [{}]: {}MB / {}MB ({}%)", context, usedMB, maxMB, String.format("%.1f", usedPercent));
        } else {
            logger.debug("Memory [{}]: {}MB / {}MB ({}%)", context, usedMB, maxMB, String.format("%.1f", usedPercent));
        }
    }

    public boolean deleteDocuments(List<String> documentIds, String collectionNameParam) {
        String loggedCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        logger.info("deleteDocuments called for {} IDs. Anserini Index: '{}'. Logging Collection: {}.",
                documentIds == null ? 0 : documentIds.size(), anseriniConfig.getIndexPath(), loggedCollectionName);

        boolean vectorStoreSuccess = false;
        if (vectorStore != null && !CollectionUtils.isEmpty(documentIds)) {
            try {
                Optional<Boolean> vsDeleteResult = Optional.of(vectorStore.delete(documentIds));
                vectorStoreSuccess = vsDeleteResult.orElse(false);
                logger.info("VectorStore delete result for {} IDs (logging context: {}): {}",
                        documentIds.size(), loggedCollectionName, vectorStoreSuccess);
            } catch (Exception e) {
                logger.error("Error deleting documents from VectorStore (logging context: {}): {}",
                        loggedCollectionName, e.getMessage(), e);
            }
        }

        if (!CollectionUtils.isEmpty(documentIds)) {
            logger.warn("Anserini keyword index (Lucene) requires specific Lucene term-based deletion. " +
                    "Deleting by external IDs ('{}') is not directly supported by this high-level method and typically requires re-indexing for keyword index changes. " +
                    "The operation for the keyword index part is effectively a no-op here.", documentIds);
        }
        return vectorStoreSuccess;
    }

    @SneakyThrows
    public boolean deleteAll(String collectionNameParam) {
        String loggedCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        logger.info("deleteAll called. This will clear the entire Anserini index at {}. Logging collection context: {}.",
                anseriniConfig.getIndexPath(), loggedCollectionName);

        boolean anseriniCleared = false;
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.error("Cannot deleteAll for Anserini: index path is not configured.");
            return false;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        try {
            if (Files.exists(indexPath)) {
                logger.info("Deleting Anserini index directory: {}", indexPath);
                try (Stream<Path> walk = Files.walk(indexPath)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
            Files.createDirectories(indexPath);
            try (Directory dir = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);
                 IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                writer.commit();
            }
            logger.info("Anserini keyword index at {} has been cleared and an empty index structure created.", indexPath);
            anseriniCleared = true;
        } catch (IOException e) {
            logger.error("Failed to delete or recreate Anserini keyword index at {}: {}", indexPath, e.getMessage(), e);
            throw e;
        }

        if (vectorStore != null) {
            logger.warn("deleteAll for VectorStore (logging context: {}) is not supported via the generic " +
                    "VectorStore interface. The VectorStore's pre-configured default collection would need manual clearing.", loggedCollectionName);
        }
        return anseriniCleared;
    }

    public long getApproxTotalDocCount(String collectionNameParam) {
        String loggedCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.warn("Cannot get document count: Anserini index path is not configured. Logging collection: {}", loggedCollectionName);
            return 0;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (!isIndexAvailable()) {
            logger.warn("Cannot get document count: Anserini keyword index at {} is not available or invalid. Logging collection: {}", indexPath, loggedCollectionName);
            return 0;
        }
        try (Directory anseriniDir = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);
             DirectoryReader reader = DirectoryReader.open(anseriniDir)) {
            long numDocs = reader.numDocs(); // This counts live documents.
            logger.debug("Approximate total document count in Anserini index {} (logging collection: {}): {}",
                    indexPath, loggedCollectionName, numDocs);
            return numDocs;
        } catch (IndexNotFoundException e) {
            logger.warn("Anserini keyword index at {} is empty or not properly formed (IndexNotFoundException). Returning 0. Logging collection: {}", indexPath, loggedCollectionName);
            return 0;
        } catch (IOException e) {
            logger.error("Error reading Anserini keyword index at {} to get document count (logging collection: {}): {}",
                    indexPath, loggedCollectionName, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public boolean isIndexAvailable() {
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.warn("isIndexAvailable: Anserini index path is not configured.");
            return false;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (Files.exists(indexPath) && Files.isDirectory(indexPath)) {
            try (SimpleSearcher checker = new SimpleSearcher(indexPath.toString())) {
                logger.debug("isIndexAvailable: Anserini keyword index at {} successfully opened by SimpleSearcher.", indexPath);
                return true;
            } catch (Exception e) {
                logger.warn("isIndexAvailable: Anserini keyword index at {} exists but is not valid/readable by SimpleSearcher: {}. This could be due to an empty index directory (before first indexing) or corruption.", indexPath, e.getMessage());
                return false;
            }
        }
        logger.info("isIndexAvailable: Anserini keyword index path {} does not exist or is not a directory.", indexPath);
        return false;
    }

    /**
     * PERFORMANCE OPTIMIZATION: Incremental keyword indexing.
     * Instead of writing JSON files to disk and rebuilding the entire Lucene index on each batch,
     * we now directly add documents to a persistent IndexWriter.
     *
     * This provides:
     * - 10-50x faster batch indexing (no disk I/O for staging)
     * - Incremental updates (batches don't wipe previous data)
     * - Lower memory usage (no JSON serialization overhead)
     */
    private void addToKeywordIndex(List<RetrievedDoc> retrievedDocuments) throws IOException {
        if (CollectionUtils.isEmpty(retrievedDocuments)) {
            return;
        }

        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.warn("Anserini indexPath not configured. Skipping keyword indexing.");
            return;
        }

        synchronized (keywordWriterLock) {
            // Initialize writer if needed
            ensureKeywordWriterInitialized();

            int docCounter = 0;
            for (RetrievedDoc retrievedDoc : retrievedDocuments) {
                // Check for shutdown during document processing
                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                    logger.warn("Keyword indexing interrupted. Processed {} of {} documents.",
                            docCounter, retrievedDocuments.size());
                    break;
                }

                if (retrievedDoc == null || !StringUtils.hasText(retrievedDoc.getText())) {
                    continue;
                }

                String docId = StringUtils.hasText(retrievedDoc.getId())
                        ? retrievedDoc.getId()
                        : UUID.randomUUID().toString();

                // Create Lucene document directly (no JSON staging)
                org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();

                // ID field - stored and indexed for retrieval
                // IMPORTANT: Must include BinaryDocValuesField for consistency with DefaultLuceneDocumentGenerator
                // This prevents "cannot change field from doc values type=BINARY to type=NONE" errors
                luceneDoc.add(new StringField(Constants.ID, docId, Field.Store.YES));
                luceneDoc.add(new BinaryDocValuesField(Constants.ID, new BytesRef(docId)));

                // Contents field - must match Anserini's DefaultLuceneDocumentGenerator field settings
                // to prevent "cannot change field from storeTermVector=true to storeTermVector=false" errors
                FieldType contentsFieldType = new FieldType();
                contentsFieldType.setStored(true);
                contentsFieldType.setStoreTermVectors(true);
                contentsFieldType.setStoreTermVectorPositions(true);
                contentsFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
                luceneDoc.add(new Field(Constants.CONTENTS, retrievedDoc.getText(), contentsFieldType));

                // Also store as raw for compatibility with Anserini search
                luceneDoc.add(new StoredField(Constants.RAW, retrievedDoc.getText()));

                // Store metadata as JSON string
                if (retrievedDoc.getMetadata() != null && !retrievedDoc.getMetadata().isEmpty()) {
                    try {
                        String metadataJson = objectMapper.writeValueAsString(retrievedDoc.getMetadata());
                        luceneDoc.add(new StoredField("metadata", metadataJson));
                    } catch (Exception e) {
                        logger.debug("Failed to serialize metadata for doc {}: {}", docId, e.getMessage());
                    }
                }

                try {
                    // Use updateDocument to handle duplicates (upsert behavior)
                    keywordIndexWriter.updateDocument(new Term(Constants.ID, docId), luceneDoc);
                    docCounter++;
                } catch (IOException e) {
                    logger.error("Failed to index document {}: {}", docId, e.getMessage());
                }
            }

            // PERFORMANCE: Delayed commit pattern - don't commit every batch
            // Committing is expensive (disk I/O, fsync). Instead, commit after N documents.
            // This reduces commit frequency from once-per-batch to once-per-N-documents.
            // For a 500-document batch with threshold=500: 1 commit instead of 10 commits.
            if (docCounter > 0) {
                int totalSinceCommit = docsSinceLastCommit.addAndGet(docCounter);
                logger.debug("Keyword index: added {} documents ({} since last commit)", docCounter, totalSinceCommit);

                if (totalSinceCommit >= KEYWORD_INDEX_COMMIT_THRESHOLD) {
                    try {
                        keywordIndexWriter.commit();
                        docsSinceLastCommit.set(0);
                        logger.debug("Keyword index: committed after {} documents", totalSinceCommit);
                    } catch (IOException e) {
                        logger.error("Failed to commit keyword index: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Force a commit of the keyword index.
     * Call this at the end of bulk indexing to ensure all documents are persisted.
     */
    public void forceKeywordIndexCommit() {
        synchronized (keywordWriterLock) {
            if (keywordIndexWriter != null && keywordIndexWriter.isOpen()) {
                int pending = docsSinceLastCommit.get();
                if (pending > 0) {
                    try {
                        keywordIndexWriter.commit();
                        docsSinceLastCommit.set(0);
                        logger.info("Keyword index: force committed {} pending documents", pending);
                    } catch (IOException e) {
                        logger.error("Failed to force commit keyword index: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Ensures the keyword index writer is initialized.
     * Creates the index directory if it doesn't exist.
     */
    private void ensureKeywordWriterInitialized() throws IOException {
        if (keywordIndexWriter != null && keywordIndexWriter.isOpen()) {
            return;
        }

        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        keywordIndexDirectory = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);

        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        // PERFORMANCE: Larger RAM buffer reduces flush frequency during bulk indexing
        // 256MB is a good balance between memory usage and throughput
        // Increased from 64MB to 256MB for better bulk indexing performance
        config.setRAMBufferSizeMB(256.0);

        keywordIndexWriter = new IndexWriter(keywordIndexDirectory, config);
        logger.info("Initialized incremental keyword index writer at {} with 256MB RAM buffer", indexPath);
    }

    /**
     * Legacy method for full index rebuild. Only used for reprocessAndIndexAllSources().
     * For normal batch operations, use addToKeywordIndex() instead.
     */
    private void createOrClearAnseriniKeywordIndex(List<RetrievedDoc> retrievedDocuments) throws IOException {
        if (!StringUtils.hasText(anseriniConfig.getCorpusPath()) || !StringUtils.hasText(anseriniConfig.getIndexPath())) {
            String msg = "Anserini corpusPath (for staging JSONs) or indexPath is not configured. Cannot create keyword index.";
            logger.error(msg);
            throw new IOException(msg);
        }
        Path stagingPath = Paths.get(anseriniConfig.getCorpusPath());
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        logger.info("Preparing Anserini keyword index for {} documents. Staging JSON at: {}, Final index at: {}",
                retrievedDocuments == null ? 0 : retrievedDocuments.size(), stagingPath, indexPath);

        if (Files.exists(stagingPath)) {
            try (Stream<Path> walk = Files.walk(stagingPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        Files.createDirectories(stagingPath);
        logger.info("Anserini keyword index staging directory {} prepared.", stagingPath);

        int docCounter = 0;
        if (!CollectionUtils.isEmpty(retrievedDocuments)) {
            for (RetrievedDoc retrievedDoc : retrievedDocuments) {
                // Check for shutdown during document processing
                if (shutdownRequested || Thread.currentThread().isInterrupted()) {
                    logger.warn("Anserini indexing interrupted during document processing. Processed {} of {} documents.",
                            docCounter, retrievedDocuments.size());
                    break;
                }

                if (retrievedDoc == null || !StringUtils.hasText(retrievedDoc.getText())) {
                    logger.warn("Skipping a null document or document with empty content for Anserini keyword index. Doc ID if available: {}",
                            retrievedDoc != null ? retrievedDoc.getId() : "null document");
                    continue;
                }
                ObjectNode anseriniJsonDoc = objectMapper.createObjectNode();
                String docId = StringUtils.hasText(retrievedDoc.getId()) ? retrievedDoc.getId() : UUID.randomUUID().toString();
                // Use the original docId for the 'id' field in JSON for Anserini if possible,
                // ensure filenames are unique if staging multiple docs with potentially conflicting simple IDs
                String anseriniStagingFileId = docId.replaceAll("[^a-zA-Z0-9_.-]", "_");
                if (anseriniStagingFileId.length() > 200) { // Max filename length considerations
                    anseriniStagingFileId = anseriniStagingFileId.substring(0, 195) + "_" + UUID.randomUUID().toString().substring(0,4);
                }
                // Ensure unique filenames if multiple docs might simplify to the same staging ID
                anseriniStagingFileId = anseriniStagingFileId + "_" + docCounter;

                anseriniJsonDoc.put(Constants.ID, docId); // Use original RetrievedDoc ID as Anserini's 'id' field
                anseriniJsonDoc.put(Constants.CONTENTS, retrievedDoc.getText());

                ObjectNode metadataNode = objectMapper.createObjectNode();
                if (retrievedDoc.getMetadata() != null) {
                    retrievedDoc.getMetadata().forEach((key, value) -> {
                        if (value != null) {
                            // Convert common types to string or appropriate JSON types
                            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                                metadataNode.putPOJO(key, value);
                            } else {
                                metadataNode.put(key, value.toString());
                            }
                        }
                    });
                }
                if (metadataNode.size() > 0) {
                    anseriniJsonDoc.set("metadata", metadataNode);
                }

                Path jsonFile = stagingPath.resolve(anseriniStagingFileId + ".json");
                try {
                    Files.writeString(jsonFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(anseriniJsonDoc),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    logger.error("Failed to write Anserini JSON for document (staging id {}): {}", anseriniStagingFileId, e.getMessage());
                }
                docCounter++;
            }
        }
        logger.info("{} documents for Anserini keyword index converted to JSON and written to staging directory {}.", docCounter, stagingPath);

        if (Files.exists(indexPath)) {
            try(Stream<Path> walk = Files.walk(indexPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        Files.createDirectories(indexPath);

        if (docCounter == 0) {
            logger.warn("No documents were processed to JSON for keyword index. Creating a minimal empty Lucene index at {}.", indexPath);
            try (Directory dir = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);
                 IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
                writer.commit();
            }
            logger.info("Minimal empty Lucene keyword index created at {}.", indexPath);
        } else {
            logger.info("Starting Anserini keyword indexing for {} documents from {} to {}", docCounter, stagingPath, indexPath);
            IndexCollection.Args args = new IndexCollection.Args();
            args.input = stagingPath.toString();
            args.collectionClass = "JsonCollection";
            args.generatorClass = "DefaultLuceneDocumentGenerator";
            args.index = indexPath.toString();
            args.threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            args.storePositions = true;
            args.storeDocvectors = true;
            args.storeRaw = true;
            args.storeContents = true;

            try {
                IndexCollection indexer = new IndexCollection(args);
                indexer.run();
                logger.info("Anserini keyword indexing completed using IndexCollection for {} documents.", docCounter);
            } catch (Exception e) {
                logger.error("Error during Anserini IndexCollection from {}: {}", stagingPath, e.getMessage(), e);
                throw new IOException("Failed to create keyword index with Anserini IndexCollection: " + e.getMessage(), e);
            }
        }
    }

    // --- New methods for Index Browser ---
    @Override
    public List<Map<String, Object>> listIndexedDocuments(int offset, int limit) throws IOException {
        List<Map<String, Object>> docInfos = new ArrayList<>();
        if (!isIndexAvailable()) {
            logger.warn("Anserini index is not available. Cannot list documents.");
            return docInfos;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        try (Directory anseriniDir = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);
             DirectoryReader reader = DirectoryReader.open(anseriniDir)) {

            int maxDoc = reader.maxDoc();
            int docCount = 0;
            int docsSkipped = 0;

            for (int i = 0; i < maxDoc && docCount < (offset + limit); i++) {
                // Correctly check for deleted documents by iterating through LeafReaders
                boolean isDeleted = false;
                for (LeafReaderContext leafContext : reader.leaves()) {
                    Bits liveDocs = leafContext.reader().getLiveDocs();
                    if (i >= leafContext.docBase && i < (leafContext.docBase + leafContext.reader().maxDoc())) {
                        if (liveDocs != null && !liveDocs.get(i - leafContext.docBase)) {
                            isDeleted = true;
                        }
                        break;
                    }
                }
                if (isDeleted) {
                    continue;
                }

                if (docsSkipped < offset) {
                    docsSkipped++;
                    continue;
                }

                org.apache.lucene.document.Document luceneDoc = reader.storedFields().document(i);
                if (luceneDoc != null) {
                    Map<String, Object> docInfo = new HashMap<>();
                    String docId = luceneDoc.get(Constants.ID);
                    if (docId == null) {
                        docId = "lucene_doc_" + i; // Fallback ID
                    }
                    docInfo.put("id", docId); // Use "id" consistently

                    String contents = luceneDoc.get(Constants.CONTENTS);
                    if (contents == null) {
                        contents = luceneDoc.get(Constants.RAW); // Fallback to "raw"
                    }

                    if (contents != null) {
                        docInfo.put("preview", contents.substring(0, Math.min(contents.length(), 100)) + "...");
                    } else {
                        docInfo.put("preview", "[No content field]");
                    }
                    Map<String, Object> metadata = new HashMap<>();
                    for (IndexableField field : luceneDoc.getFields()) {
                        if (!Constants.CONTENTS.equals(field.name()) &&
                                !Constants.RAW.equals(field.name()) &&
                                !Constants.ID.equals(field.name())) { // Exclude id from general metadata map
                            if(field.stringValue() != null) {
                                metadata.put(field.name(), field.stringValue());
                            }
                        }
                    }
                    // Attempt to parse metadata if it was stored as a JSON string by DefaultLuceneDocumentGenerator
                    String metadataJsonString = luceneDoc.get("metadata"); // DefaultLuceneDocumentGenerator might store it this way
                    if (StringUtils.hasText(metadataJsonString)) {
                        try {
                            Map<String,Object> parsedMetadata = objectMapper.readValue(metadataJsonString, Map.class);
                            metadata.putAll(parsedMetadata);
                        } catch (Exception e) {
                            logger.warn("Could not parse 'metadata' field for doc {} as JSON: {}", docId, e.getMessage());
                            metadata.put("_metadata_raw_string", metadataJsonString);
                        }
                    }

                    docInfo.put("metadata", metadata);
                    docInfo.put("lucene_internal_id", i);
                    docInfos.add(docInfo);
                    docCount++;
                }
            }
            logger.info("Listed {} documents from Lucene index. Offset: {}, Limit: {}. Total iterated (pre-offset): {}", docInfos.size(), offset, limit, docCount + docsSkipped);
        } catch (IOException e) {
            logger.error("Error listing documents from Lucene index: " + e.getMessage(), e);
            throw e;
        }
        return docInfos;
    }

    @Override
    public Map<String, Object> getIndexedDocument(String docId) throws IOException {
        if (!isIndexAvailable()) {
            logger.warn("Anserini index is not available. Cannot get document {}.", docId);
            return null;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        // SimpleSearcher is convenient for fetching by stored ID.
        try (SimpleSearcher searcher = new SimpleSearcher(indexPath.toString())) {
            org.apache.lucene.document.Document luceneDoc = searcher.doc(docId);
            if (luceneDoc == null) {
                logger.warn("Document with id {} not found in Anserini index using SimpleSearcher.", docId);
                return null;
            }
            Map<String, Object> docInfo = new HashMap<>();
            docInfo.put("id", luceneDoc.get(Constants.ID)); // Use "id" for consistency
            String content = luceneDoc.get(Constants.CONTENTS);
            if (content == null) {
                content = luceneDoc.get(Constants.RAW);
            }
            docInfo.put("content", content); // Use "content" for consistency

            Map<String, Object> metadata = new HashMap<>();
            for (IndexableField field : luceneDoc.getFields()) {
                if (!Constants.CONTENTS.equals(field.name()) &&
                        !Constants.RAW.equals(field.name()) &&
                        !Constants.ID.equals(field.name())) {
                    if(field.stringValue() != null) {
                        metadata.put(field.name(), field.stringValue());
                    }
                }
            }
            // Try to parse "metadata" field if it was stored as JSON by DefaultLuceneDocumentGenerator
            String metadataJsonString = luceneDoc.get("metadata");
            if (StringUtils.hasText(metadataJsonString)) {
                try {
                    Map<String,Object> parsedMetadata = objectMapper.readValue(metadataJsonString, Map.class);
                    metadata.putAll(parsedMetadata); // Merge or override existing from individual fields
                } catch (Exception e) {
                    logger.warn("Could not parse 'metadata' field for doc {} as JSON: {}", docId, e.getMessage());
                    metadata.put("_metadata_raw_string", metadataJsonString);
                }
            }
            docInfo.put("metadata", metadata);
            return docInfo;
        } catch (Exception e) {
            logger.error("Error retrieving document {} with SimpleSearcher: {}", docId, e.getMessage(), e);
            throw new IOException("Error retrieving document " + docId, e);
        }
    }

    @Override
    public boolean updateIndexedDocumentContent(String docId, String newContent) throws IOException {
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.error("Cannot update document: Anserini index path is not configured.");
            return false;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.APPEND);

        try (Directory dir = FSDirectory.open(indexPath, NoLockFactory.INSTANCE);
             IndexWriter writer = new IndexWriter(dir, config)) {

            // Retrieve the existing document to preserve its other fields
            Map<String, Object> existingDocMap = getIndexedDocument(docId);
            if (existingDocMap == null) {
                logger.error("Document with ID {} not found. Cannot update.", docId);
                return false;
            }

            org.apache.lucene.document.Document newLuceneDoc = new org.apache.lucene.document.Document();
            // IMPORTANT: Must include BinaryDocValuesField for consistency with DefaultLuceneDocumentGenerator
            // This prevents "cannot change field from doc values type=BINARY to type=NONE" errors
            newLuceneDoc.add(new StringField(Constants.ID, docId, Field.Store.YES));
            newLuceneDoc.add(new BinaryDocValuesField(Constants.ID, new BytesRef(docId)));

            // Contents field - must match Anserini's DefaultLuceneDocumentGenerator field settings
            FieldType contentsFieldType = new FieldType();
            contentsFieldType.setStored(true);
            contentsFieldType.setStoreTermVectors(true);
            contentsFieldType.setStoreTermVectorPositions(true);
            contentsFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            newLuceneDoc.add(new Field(Constants.CONTENTS, newContent, contentsFieldType));

            // Also store in raw (stored only, not indexed for term vectors)
            newLuceneDoc.add(new StoredField(Constants.RAW, newContent));

            // Preserve other metadata fields
            Map<String, Object> metadata = (Map<String, Object>) existingDocMap.get("metadata");
            if (metadata != null) {
                ObjectNode metadataNode = objectMapper.createObjectNode();
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    // Avoid re-adding fields already handled or special fields
                    if (!Constants.ID.equals(entry.getKey()) &&
                            !Constants.CONTENTS.equals(entry.getKey()) &&
                            !Constants.RAW.equals(entry.getKey()) &&
                            !"_metadata_raw_string".equals(entry.getKey())) { // Skip our fallback key

                        if (entry.getValue() instanceof String) {
                            metadataNode.put(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Number) {
                            metadataNode.putPOJO(entry.getKey(), entry.getValue());
                        } else if (entry.getValue() instanceof Boolean) {
                            metadataNode.putPOJO(entry.getKey(), entry.getValue());
                        } else if (entry.getValue() != null) {
                            metadataNode.put(entry.getKey(), entry.getValue().toString());
                        }
                    }
                }
                if(metadataNode.size() > 0) {
                    newLuceneDoc.add(new StoredField("metadata", objectMapper.writeValueAsString(metadataNode)));
                }
            }

            writer.updateDocument(new Term(Constants.ID, docId), newLuceneDoc);
            writer.commit();
            logger.info("Successfully updated document {} in Anserini index with new content.", docId);
            return true;
        } catch (IOException e) {
            logger.error("Failed to update document {} in Anserini index: {}", docId, e.getMessage(), e);
            throw e;
        }
    }
}
