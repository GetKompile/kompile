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

package ai.kompile.anserini;

import ai.kompile.anserini.config.AnseriniConfig;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.anserini.index.IndexCollection;
import io.anserini.search.SimpleSearcher;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service("anseriniIndexerService")
public class AnseriniIndexerServiceImpl implements IndexerService {
    private static final Logger logger = LogManager.getLogger(AnseriniIndexerServiceImpl.class);
    private final AnseriniConfig anseriniConfig;
    private final ObjectMapper objectMapper;
    private final DocumentLoadingService documentLoadingService;
    private final List<DocumentLoader> documentLoaders;
    private final VectorStore vectorStore;

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final String DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME = "default_anserini_index";


    @Autowired
    public AnseriniIndexerServiceImpl(AnseriniConfig anseriniConfig,
                                      ObjectMapper objectMapper,
                                      DocumentLoadingService documentLoadingService,
                                      List<DocumentLoader> documentLoaders,
                                      VectorStore vectorStore) {
        this.anseriniConfig = anseriniConfig;
        this.objectMapper = objectMapper;
        this.documentLoadingService = documentLoadingService;
        this.documentLoaders = documentLoaders;
        this.vectorStore = vectorStore;
        logger.info("AnseriniIndexerServiceImpl constructed. VectorStore available: {}. DocumentLoaders count: {}",
                vectorStore != null, this.documentLoaders == null ? 0 : this.documentLoaders.size());
        if (CollectionUtils.isEmpty(this.documentLoaders)) {
            logger.warn("No DocumentLoader beans were injected. Ad-hoc file/directory indexing (indexFile, indexDirectory) will fail if called.");
        }
    }

    private String getEffectiveLogCollectionName(String collectionNameParam) {
        return StringUtils.hasText(collectionNameParam) ? collectionNameParam : DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME;
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
        // Call the public interface method that takes List<Document> only
        indexDocuments(allLoadedDocs);
    }

    // Method from the minimal IndexerService interface (as per Turn 12 definition)
    @Override
    public void indexDocuments(List<Document> documents) throws IOException {
        indexDocumentsInternal(documents, DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME);
    }

    // --- Methods below are assumed to be part of the richer IndexerService interface ---
    // --- The user should ensure their IndexerService interface declares these if @Override is used ---

    // This method should be present if your IndexerService interface has this signature.
    // If it does, uncomment @Override.
    // @Override
    @SneakyThrows
    public void indexDocuments(List<Document> documents, String collectionNameParam)  {
        indexDocumentsInternal(documents, collectionNameParam);
    }

    private void indexDocumentsInternal(List<Document> documents, String collectionNameParamForLogging) throws IOException {
        String logContextCollectionName = getEffectiveLogCollectionName(collectionNameParamForLogging);

        logger.info("Anserini keyword indexing and Vector Store population requested for {} documents. Anserini Index: '{}'. Logging Collection Context: '{}'",
                documents == null ? 0 : documents.size(),
                anseriniConfig.getIndexPath(),
                logContextCollectionName);

        if (vectorStore != null) {
            if (!CollectionUtils.isEmpty(documents)) {
                try {
                    logger.info("Populating Vector Store with {} documents (logging context: {})...",
                            documents.size(), logContextCollectionName);
                    vectorStore.add(documents);
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
        createOrClearAnseriniKeywordIndex(documents);
    }

    private DocumentLoader findLoaderForPath(Path filePath) throws IOException {
        if (CollectionUtils.isEmpty(documentLoaders)) {
            String errorMsg = "No DocumentLoaders configured. Cannot find loader for path: " + filePath;
            logger.error(errorMsg);
            throw new IOException(errorMsg);
        }
        // Create a basic descriptor for the supports() check.
        // Loaders should ideally not require sourceId, metadata, or collectionName for supports() logic,
        // but if they do, this part might need more context.
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
        // If loop completes, no loader supports the file.
        String availableLoaders = documentLoaders.stream().map(dl -> dl.getName() + " (" + dl.getClass().getSimpleName() + ")").collect(Collectors.joining(", "));
        String errorMsg = "No suitable DocumentLoader found that explicitly supports file: " + filePath +
                ". Checked " + documentLoaders.size() + " loaders: [" + availableLoaders + "].";
        logger.error(errorMsg);
        throw new IOException(errorMsg); // Throw error as requested
    }

    // @Override // Uncomment if this signature is on your IndexerService interface
    public void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException {
        String effectiveCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        logger.info("Request to index file: {} with sourceId: {}. Target Anserini Index: '{}'. Logging/Descriptor Collection: {}",
                filePath, sourceId, anseriniConfig.getIndexPath(), effectiveCollectionName);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            logger.error("File not found or is not a regular file: {}", filePath);
            throw new IOException("File not found or is not a regular file: " + filePath);
        }

        DocumentLoader loader = findLoaderForPath(filePath); // This will now throw IOException if no loader found

        DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .sourceId(sourceId)
                .metadata(Collections.emptyMap())
                .collectionName(effectiveCollectionName)
                .build();

        List<Document> documents;
        try {
            documents = loader.load(sourceDescriptor);
        } catch (Exception e) {
            logger.error("Failed to load documents from file: {} using loader: '{}' ({}): {}",
                    filePath, loader.getName(), loader.getClass().getSimpleName(), e.getMessage(), e);
            throw new IOException("Failed to load documents from file: " + filePath, e);
        }

        if (CollectionUtils.isEmpty(documents)) {
            logger.warn("Loader '{}' produced no documents for file: {}", loader.getName(), filePath);
            return;
        }
        indexDocuments(documents, effectiveCollectionName); // Calls the version with collectionName
        logger.info("Successfully processed file: {} ({} documents). Anserini Index: '{}'. Logging Collection: {}.",
                filePath, documents.size(), anseriniConfig.getIndexPath(), effectiveCollectionName);
    }

    // @Override // Uncomment if this signature is on your IndexerService interface
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

        List<Document> batchDocuments = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directoryPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            logger.info("Found {} files in directory {} for potential indexing.", files.size(), directoryPath);
            int processedFileCount = 0;

            for (Path filePath : files) {
                try {
                    DocumentLoader loader = findLoaderForPath(filePath); // Will throw if no loader
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
                    List<Document> loadedDocs = loader.load(sourceDescriptor);

                    if (!CollectionUtils.isEmpty(loadedDocs)) {
                        batchDocuments.addAll(loadedDocs);
                    } else {
                        logger.warn("Loader '{}' produced no documents for file: {}", loader.getName(), filePath);
                    }

                    if (batchDocuments.size() >= DEFAULT_BATCH_SIZE) {
                        logger.info("Indexing batch of {} documents from directory scan. Logging collection: {}",
                                batchDocuments.size(), effectiveCollectionName);
                        indexDocuments(new ArrayList<>(batchDocuments), effectiveCollectionName);
                        batchDocuments.clear();
                    }
                    processedFileCount++;
                } catch (IOException e) { // Catch IOException from findLoaderForPath or loader.load
                    logger.error("Could not load/process file: {} in directory {}. Error: {}. Skipping file.", filePath, directoryPath, e.getMessage());
                } catch (Exception e) { // Catch other exceptions from loader.load
                    logger.error("Unexpected error loading file: {} in directory {}. Skipping file.", filePath, directoryPath, e);
                }
            }
            if (!batchDocuments.isEmpty()) {
                logger.info("Indexing remaining batch of {} documents from directory scan. Logging collection: {}",
                        batchDocuments.size(), effectiveCollectionName);
                indexDocuments(batchDocuments, effectiveCollectionName);
            }
            logger.info("Successfully processed {} files from directory: {}. Logging collection: {}.",
                    processedFileCount, directoryPath, effectiveCollectionName);
        }
    }

    // @Override // Uncomment if this signature is on your IndexerService interface
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
        return vectorStoreSuccess; // Success primarily reflects vector store part.
    }

    // @Override // Uncomment if this signature is on your IndexerService interface
    @SneakyThrows
    public boolean deleteAll(String collectionNameParam)  {
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
            try (Directory dir = FSDirectory.open(indexPath);
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

    // @Override // Uncomment if this signature is on your IndexerService interface
    public long getApproxTotalDocCount(String collectionNameParam) {
        String loggedCollectionName = getEffectiveLogCollectionName(collectionNameParam);
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.warn("Cannot get document count: Anserini index path is not configured. Logging collection: {}", loggedCollectionName);
            return 0;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (!isIndexAvailable()) { // Use the method that checks via SimpleSearcher
            logger.warn("Cannot get document count: Anserini keyword index at {} is not available or invalid. Logging collection: {}", indexPath, loggedCollectionName);
            return 0;
        }
        try (Directory anseriniDir = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(anseriniDir)) {
            long numDocs = reader.numDocs();
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

    // Method from the minimal IndexerService interface
    @Override
    public boolean isIndexAvailable() {
        if (!StringUtils.hasText(anseriniConfig.getIndexPath())) {
            logger.warn("isIndexAvailable: Anserini index path is not configured.");
            return false;
        }
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        if (Files.exists(indexPath) && Files.isDirectory(indexPath)) {
            try (SimpleSearcher checker = new SimpleSearcher(indexPath.toString())) {
                // If SimpleSearcher constructor doesn't throw, index is considered basically valid.
                // It also checks for non-empty segments_N file.
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

    // This is the core Anserini Lucene indexing method, adapted from user's provided file
    private void createOrClearAnseriniKeywordIndex(List<Document> springAiDocuments) throws IOException {
        if (!StringUtils.hasText(anseriniConfig.getCorpusPath()) || !StringUtils.hasText(anseriniConfig.getIndexPath())) {
            String msg = "Anserini corpusPath (for staging JSONs) or indexPath is not configured. Cannot create keyword index.";
            logger.error(msg);
            throw new IOException(msg);
        }
        Path stagingPath = Paths.get(anseriniConfig.getCorpusPath());
        Path indexPath = Paths.get(anseriniConfig.getIndexPath());
        logger.info("Preparing Anserini keyword index for {} documents. Staging JSON at: {}, Final index at: {}",
                springAiDocuments == null ? 0 : springAiDocuments.size(), stagingPath, indexPath);

        if (Files.exists(stagingPath)) {
            try (Stream<Path> walk = Files.walk(stagingPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        Files.createDirectories(stagingPath);
        logger.info("Anserini keyword index staging directory {} prepared.", stagingPath);

        int docCounter = 0;
        if (!CollectionUtils.isEmpty(springAiDocuments)) {
            for (Document springDoc : springAiDocuments) {
                if (springDoc == null || !StringUtils.hasText(springDoc.getText())) {
                    logger.warn("Skipping a null document or document with empty content for Anserini keyword index. Doc ID if available: {}",
                            springDoc != null ? springDoc.getId() : "null document");
                    continue;
                }
                ObjectNode anseriniJsonDoc = objectMapper.createObjectNode();
                String docId = StringUtils.hasText(springDoc.getId()) ? springDoc.getId() : UUID.randomUUID().toString();
                String anseriniStagingFileId = docId.replaceAll("[^a-zA-Z0-9_.-]", "_");
                if (anseriniStagingFileId.length() > 200) {
                    anseriniStagingFileId = anseriniStagingFileId.substring(0, 195);
                }
                anseriniStagingFileId = anseriniStagingFileId + "_" + docCounter; // Ensure unique filename

                anseriniJsonDoc.put("id", anseriniStagingFileId);
                anseriniJsonDoc.put("contents", springDoc.getText());

                // Optionally include all metadata as a nested JSON object
                // Map<String, Object> metadata = springDoc.getMetadata();
                // if (metadata != null && !metadata.isEmpty()) {
                //    anseriniJsonDoc.set("metadata", objectMapper.valueToTree(metadata));
                // }

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
            try (Directory dir = FSDirectory.open(indexPath);
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
            // Use defaults from your originally provided AnseriniIndexerServiceImpl
            args.threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            args.storePositions = true;
            args.storeDocvectors = true;
            args.storeRaw = true;

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
}