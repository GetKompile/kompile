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
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.core.loaders.DocumentSourceDescriptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.anserini.index.Constants;
import io.anserini.index.IndexCollection;
import io.anserini.search.SimpleSearcher;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext; // Correct import for LeafReaderContext
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits; // Correct import for Bits
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
    private  VectorStore vectorStore;

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final String DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME = "default_anserini_index";


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
        indexDocuments(allLoadedDocs);
    }

    @Override
    public void indexDocuments(List<Document> documents) throws IOException {
        indexDocumentsInternal(documents, DEFAULT_ANSERINI_LOGGING_COLLECTION_NAME);
    }

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

        List<Document> batchDocuments = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directoryPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            logger.info("Found {} files in directory {} for potential indexing.", files.size(), directoryPath);
            int processedFileCount = 0;

            for (Path filePath : files) {
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
                } catch (IOException e) {
                    logger.error("Could not load/process file: {} in directory {}. Error: {}. Skipping file.", filePath, directoryPath, e.getMessage());
                } catch (Exception e) {
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
        try (Directory anseriniDir = FSDirectory.open(indexPath);
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
                // Use the original docId for the 'id' field in JSON for Anserini if possible,
                // ensure filenames are unique if staging multiple docs with potentially conflicting simple IDs
                String anseriniStagingFileId = docId.replaceAll("[^a-zA-Z0-9_.-]", "_");
                if (anseriniStagingFileId.length() > 200) { // Max filename length considerations
                    anseriniStagingFileId = anseriniStagingFileId.substring(0, 195) + "_" + UUID.randomUUID().toString().substring(0,4);
                }
                // Ensure unique filenames if multiple docs might simplify to the same staging ID
                anseriniStagingFileId = anseriniStagingFileId + "_" + docCounter;


                anseriniJsonDoc.put(Constants.ID, docId); // Use original Spring AI doc ID as Anserini's 'id' field
                anseriniJsonDoc.put(Constants.CONTENTS, springDoc.getText());

                ObjectNode metadataNode = objectMapper.createObjectNode();
                if (springDoc.getMetadata() != null) {
                    springDoc.getMetadata().forEach((key, value) -> {
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
        try (Directory anseriniDir = FSDirectory.open(indexPath);
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

        try (Directory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, config)) {

            // Retrieve the existing document to preserve its other fields
            Map<String, Object> existingDocMap = getIndexedDocument(docId);
            if (existingDocMap == null) {
                logger.error("Document with ID {} not found. Cannot update.", docId);
                return false;
            }

            org.apache.lucene.document.Document newLuceneDoc = new org.apache.lucene.document.Document();
            newLuceneDoc.add(new StringField(Constants.ID, docId, Field.Store.YES));
            newLuceneDoc.add(new TextField(Constants.CONTENTS, newContent, Field.Store.YES));
            // Also store in raw if that's your primary content field for Anserini
            newLuceneDoc.add(new TextField(Constants.RAW, newContent, Field.Store.YES));


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
                    newLuceneDoc.add(new TextField("metadata", objectMapper.writeValueAsString(metadataNode), Field.Store.YES));
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