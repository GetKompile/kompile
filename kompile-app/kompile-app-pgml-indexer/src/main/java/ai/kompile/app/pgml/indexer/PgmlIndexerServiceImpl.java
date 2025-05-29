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

package ai.kompile.app.pgml.indexer;

import ai.kompile.app.pgml.indexer.config.PgmlIndexerProperties;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component("pgMlIndexer")
public class PgmlIndexerServiceImpl extends IndexerService {

    private static final Logger logger = LoggerFactory.getLogger(PgmlIndexerServiceImpl.class);

    private final PgmlIndexerProperties properties;
    private  VectorStore vectorStore;
    private final List<DocumentLoader> documentLoaders;
    private final ApplicationContext applicationContext;

    public PgmlIndexerServiceImpl(PgmlIndexerProperties properties,
                                  ApplicationContext applicationContext,
                                  List<DocumentLoader> documentLoaders,
                                  List<VectorStore> vectorStore) {
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.documentLoaders = documentLoaders;
        if (CollectionUtils.isEmpty(this.documentLoaders)) {
            logger.warn("No DocumentLoader beans found. File and directory indexing capabilities will be limited if used.");
        }
        logger.info("PgmlIndexerServiceImpl initialized with VectorStore: {} (expected to use default collection: '{}') and {} document loaders.",
                vectorStore.getClass().getName(), properties.getDefaultCollectionName(), this.documentLoaders.size());
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

    }

    private String getEffectiveCollectionName(String collectionNameFromParam) {
        return StringUtils.hasText(collectionNameFromParam) ? collectionNameFromParam : properties.getDefaultCollectionName();
    }

    private DocumentLoader findLoaderForPath(Path filePath) {
        if (CollectionUtils.isEmpty(documentLoaders)) {
            return null;
        }
        String fileNameLower = filePath.getFileName().toString().toLowerCase();

        for (DocumentLoader loader : documentLoaders) {
            // Create a basic descriptor for the supports() check.
            DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(filePath.getFileName().toString())
                    .build();
            if (loader.supports(tempDescriptor)) {
                logger.debug("Using loader '{}' for: {}", loader.getName(), filePath);
                return loader;
            }
        }

        if (!documentLoaders.isEmpty()) {
            DocumentLoader firstLoader = documentLoaders.get(0);
            logger.debug("No specific or Tika loader found for {}. Attempting to use the first available loader: {}",
                    filePath, firstLoader.getClass().getSimpleName());
            return firstLoader;
        }
        return null;
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

    @Override
    public void indexDocuments(List<RetrievedDoc> documents, String collectionNameParam) {
        String loggedCollectionName = getEffectiveCollectionName(collectionNameParam);
        if (CollectionUtils.isEmpty(documents)) {
            logger.warn("No documents provided for indexing. Target collection for logging: {}", loggedCollectionName);
            return;
        }

        logger.info("Submitting {} documents for indexing via VectorStore: {}. " +
                        "VectorStore is expected to use its pre-configured default collection (logged as: {}).",
                documents.size(), vectorStore.getClass().getSimpleName(), loggedCollectionName);

        // Convert RetrievedDoc to Document for vector store compatibility
        List<Document> springAiDocuments = documents.stream()
                .map(this::convertToDocument)
                .collect(Collectors.toList());

        vectorStore.add(springAiDocuments);

        logger.info("Successfully submitted {} documents to VectorStore. Assumed target collection for logging: {}",
                documents.size(), loggedCollectionName);
    }


    @Override
    public void indexFile(Path filePath, String sourceId, String collectionNameParam) throws IOException {
        String effectiveCollectionName = getEffectiveCollectionName(collectionNameParam);
        logger.info("Request to index file: {} with sourceId: {}. Target collection context for logging/descriptor: {}",
                filePath, sourceId, effectiveCollectionName);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            logger.error("File not found or is not a regular file: {}", filePath);
            throw new IOException("File not found or is not a regular file: " + filePath);
        }

        DocumentLoader loader = findLoaderForPath(filePath);
        if (loader == null) {
            String errorMsg = "No suitable document loader found for file: " + filePath +
                    ". Ensure DocumentLoader beans (e.g., TikaLoaderImpl, PdfLoaderImpl) are available.";
            logger.error(errorMsg);
            throw new IOException(errorMsg);
        }

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
            logger.error("Failed to load documents from file: {} using loader: {}",
                    filePath, loader.getClass().getSimpleName(), e);
            throw new IOException("Failed to load documents from file: " + filePath, e);
        }

        if (CollectionUtils.isEmpty(springAiDocuments)) {
            logger.warn("Loader {} produced no documents for file: {}", loader.getClass().getSimpleName(), filePath);
            return;
        }

        // Convert to RetrievedDoc
        List<RetrievedDoc> documents = springAiDocuments.stream()
                .map(this::convertToRetrievedDoc)
                .collect(Collectors.toList());

        indexDocuments(documents, effectiveCollectionName);
        logger.info("Successfully processed file: {} ({} documents). Target collection context for logging: {}.",
                filePath, documents.size(), effectiveCollectionName);
    }

    @Override
    public void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam) throws IOException {
        String effectiveCollectionName = getEffectiveCollectionName(collectionNameParam);
        logger.info("Request to index directory: {} with sourceIdPrefix: {}. Target collection context for logging/descriptor: {}",
                directoryPath, sourceIdPrefix, effectiveCollectionName);

        if (!Files.isDirectory(directoryPath)) {
            logger.error("Path is not a directory: {}", directoryPath);
            throw new IOException("Path is not a directory: " + directoryPath);
        }

        if (CollectionUtils.isEmpty(documentLoaders)) {
            String errorMsg = "No DocumentLoaders configured. Cannot index directory: " + directoryPath;
            logger.error(errorMsg);
            throw new IOException(errorMsg);
        }

        List<RetrievedDoc> batchDocuments = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directoryPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());

            logger.info("Found {} files in directory {} for potential indexing.", files.size(), directoryPath);
            int processedFileCount = 0;

            for (Path filePath : files) {
                try {
                    DocumentLoader loader = findLoaderForPath(filePath);
                    if (loader != null) {
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

                        logger.debug("Loading documents from file: {} with sourceId: {} using loader {}",
                                filePath, fileSpecificSourceId, loader.getClass().getSimpleName());
                        List<Document> springAiDocuments = loader.load(sourceDescriptor);

                        if (!CollectionUtils.isEmpty(springAiDocuments)) {
                            // Convert to RetrievedDoc
                            List<RetrievedDoc> loadedDocs = springAiDocuments.stream()
                                    .map(this::convertToRetrievedDoc)
                                    .collect(Collectors.toList());
                            batchDocuments.addAll(loadedDocs);
                        } else {
                            logger.warn("Loader {} produced no documents for file: {}", loader.getClass().getSimpleName(), filePath);
                        }

                        if (batchDocuments.size() >= properties.getBatchSize()) {
                            logger.info("Indexing batch of {} documents from directory scan. Target collection for logging: {}",
                                    batchDocuments.size(), effectiveCollectionName);
                            indexDocuments(new ArrayList<>(batchDocuments), effectiveCollectionName);
                            batchDocuments.clear();
                        }
                        processedFileCount++;
                    } else {
                        logger.warn("No suitable loader found for file in directory: {}. Skipping.", filePath);
                    }
                } catch (Exception e) {
                    logger.error("Failed to load or process file: {} in directory {}. Skipping file.", filePath, directoryPath, e);
                }
            }
            if (!batchDocuments.isEmpty()) {
                logger.info("Indexing remaining batch of {} documents from directory scan. Target collection for logging: {}",
                        batchDocuments.size(), effectiveCollectionName);
                indexDocuments(batchDocuments, effectiveCollectionName);
            }
            logger.info("Successfully processed {} files from directory: {}. Target collection context for logging: {}.",
                    processedFileCount, directoryPath, effectiveCollectionName);
        }
    }

    @Override
    public boolean deleteDocuments(List<String> documentIds, String collectionNameParam) {
        String loggedCollectionName = getEffectiveCollectionName(collectionNameParam);
        if (CollectionUtils.isEmpty(documentIds)) {
            logger.warn("No document IDs provided for deletion. Target collection for logging: {}", loggedCollectionName);
            return false;
        }
        logger.info("Request to delete {} documents via VectorStore: {}. " +
                        "VectorStore is expected to use its pre-configured default collection (logged as: {}).",
                documentIds.size(), vectorStore.getClass().getSimpleName(), loggedCollectionName);

        Optional<Boolean> result = Optional.of(vectorStore.delete(documentIds));

        boolean deleted = result.orElse(false);
        logger.info("Deletion of {} documents via VectorStore status: {}. Assumed target collection for logging: {}",
                documentIds.size(), deleted, loggedCollectionName);
        return deleted;
    }

    @Override
    public boolean deleteAll(String collectionNameParam) {
        String loggedCollectionName = getEffectiveCollectionName(collectionNameParam);
        logger.warn("deleteAll operation for collection '{}' is NOT SUPPORTED by the generic VectorStore interface " +
                        "and thus not directly by PgmlIndexerServiceImpl. The configured VectorStore ({}) must be managed " +
                        "directly for such operations on its default collection (e.g., via its own specific API or direct DB commands if it's PgVectorStoreImpl and you want to clear a specific table). " +
                        "This operation will currently do nothing through the IndexerService.",
                loggedCollectionName, vectorStore.getClass().getSimpleName());
        return false;
    }

    @Override
    public long getApproxTotalDocCount(String collectionNameParam) {
        String loggedCollectionName = getEffectiveCollectionName(collectionNameParam);
        logger.warn("getApproxTotalDocCount for collection '{}' is not supported by the generic VectorStore interface. " +
                        "This capability depends on the specific VectorStore implementation ({}). Returning -1.",
                loggedCollectionName, vectorStore.getClass().getSimpleName());
        return -1;
    }

    @Override
    public void indexDocuments(List<RetrievedDoc> documents) throws IOException {
        // Use default collection name for logging context if not specified
        indexDocuments(documents, properties.getDefaultCollectionName());
    }

    @Override
    public void reprocessAndIndexAllSources() throws IOException {
        // This is a significant simplification.
        // A true reprocess would involve fetching sources from a DocumentLoadingService.
        // PgmlIndexerServiceImpl, as a pure indexer, might not own that responsibility.
        // For now, it will log a warning and attempt to delete all from the default collection.
        logger.warn("reprocessAndIndexAllSources called on PgmlIndexerServiceImpl. " +
                        "This will attempt to delete all documents from the default collection ('{}') but will NOT automatically re-fetch and re-index all sources. " +
                        "Document re-loading and re-submission must be handled externally.",
                properties.getDefaultCollectionName());
        deleteAll(properties.getDefaultCollectionName());
        // Actual re-indexing logic (loading docs and calling indexDocuments) should be orchestrated externally.
    }

    @Override
    public boolean isIndexAvailable() {
        // For a VectorStore-based indexer, "availability" usually means the VectorStore service is reachable.
        // A more robust check might involve a ping or status check if the VectorStore interface supported it.
        // For PgML, this would mean the database is up and the pgvector/pgml extensions are working.
        // We assume if vectorStore bean is initialized, it's "available".
        logger.debug("isIndexAvailable for PgmlIndexerServiceImpl assumes VectorStore ({}) is available if initialized.",
                vectorStore.getClass().getSimpleName());
        return this.vectorStore != null;
    }

    // --- New methods for Index Browser ---

    @Override
    public List<Map<String, Object>> listIndexedDocuments(int offset, int limit) throws IOException {
        logger.warn("listIndexedDocuments is not fully supported by PgmlIndexerServiceImpl. " +
                        "This operation depends on the VectorStore's ('{}') capability to list documents with content and metadata, " +
                        "which is not a standard part of the generic VectorStore interface. Returning an empty list.",
                vectorStore.getClass().getSimpleName());
        // If your specific VectorStore (e.g., PgVectorStoreImpl) has a method to list documents/metadata,
        // you could implement that here. Otherwise, this is a placeholder.
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getIndexedDocument(String docId) throws IOException {
        logger.warn("getIndexedDocument for ID '{}' is not fully supported by PgmlIndexerServiceImpl. " +
                        "This operation depends on the VectorStore's ('{}') capability to fetch a specific document by ID with its content, " +
                        "which is not a standard part of the generic VectorStore interface. Returning null.",
                docId, vectorStore.getClass().getSimpleName());
        // If your specific VectorStore has a method like `getDocumentById(String id)`, you could call it here.
        // For example, PgVectorStoreImpl might query its table by a document_id column.
        return null;
    }

    @Override
    public boolean updateIndexedDocumentContent(String docId, String newContent) throws IOException {
        logger.warn("updateIndexedDocumentContent for ID '{}' is not directly supported by PgmlIndexerServiceImpl in a way that " +
                        "preserves the original vector ID with new content through the generic VectorStore interface. " +
                        "A true update would require deleting the old document (and its vector) and adding a new document " +
                        "(with a new vector calculated from the new content), potentially with a new ID or careful ID management. " +
                        "This operation requires an EmbeddingModel to re-embed, which is not available here. Returning false.",
                docId);
        // To implement this properly, you would typically:
        // 1. Need an EmbeddingModel instance.
        // 2. Create a new Spring AI Document: new Document(docId, newContent, existingMetadata)
        // 3. Embed the new document: embeddingModel.embed(newDocument) - or a list.
        // 4. Delete the old document from VectorStore: vectorStore.delete(List.of(docId))
        // 5. Add the new, re-embedded document: vectorStore.add(List.of(newEmbeddedDocument))
        // This is a complex operation not suited for a simple "update content" on a generic VectorStore.
        return false;
    }
}
