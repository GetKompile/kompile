package ai.kompile.app.pgml.indexer;

import ai.kompile.app.pgml.indexer.config.PgmlIndexerProperties;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor; // Correct import
import ai.kompile.loader.pdf.PdfLoaderImpl;
import ai.kompile.loader.tika.TikaLoaderImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PgmlIndexerServiceImpl implements IndexerService {

    private static final Logger logger = LoggerFactory.getLogger(PgmlIndexerServiceImpl.class);

    private final PgmlIndexerProperties properties;
    private final VectorStore vectorStore;
    private final List<DocumentLoader> documentLoaders;
    private final ApplicationContext applicationContext;

    public PgmlIndexerServiceImpl(PgmlIndexerProperties properties,
                                  ApplicationContext applicationContext,
                                  List<DocumentLoader> documentLoaders) {
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.vectorStore = getBean(properties.getVectorStoreBeanName(), VectorStore.class);
        this.documentLoaders = documentLoaders;
        if (CollectionUtils.isEmpty(this.documentLoaders)) {
            logger.warn("No DocumentLoader beans found. File and directory indexing capabilities will be limited if used.");
        }
        logger.info("PgmlIndexerServiceImpl initialized with VectorStore: {} (expected to use default collection: '{}') and {} document loaders.",
                vectorStore.getClass().getName(), properties.getDefaultCollectionName(), this.documentLoaders.size());
    }

    private <T> T getBean(String beanName, Class<T> beanClass) {
        try {
            return applicationContext.getBean(beanName, beanClass);
        } catch (NoSuchBeanDefinitionException e) {
            String errorMessage = String.format(
                    "CRITICAL: Could not find bean with name '%s' and type '%s' for PgmlIndexerServiceImpl. " +
                            "Ensure this bean (e.g., a correctly configured PgVectorStoreImpl) is available.",
                    beanName, beanClass.getSimpleName()
            );
            logger.error(errorMessage, e);
            throw new IllegalStateException(errorMessage, e);
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
            if (loader instanceof PdfLoaderImpl && fileNameLower.endsWith(".pdf")) {
                logger.debug("Using PdfLoaderImpl for: {}", filePath);
                return loader;
            }
        }
        for (DocumentLoader loader : documentLoaders) {
            if (loader instanceof TikaLoaderImpl) {
                logger.debug("Using TikaLoaderImpl as a general purpose loader for: {}", filePath);
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

    @Override
    public void indexDocuments(List<Document> documents, String collectionNameParam) {
        String loggedCollectionName = getEffectiveCollectionName(collectionNameParam);
        if (CollectionUtils.isEmpty(documents)) {
            logger.warn("No documents provided for indexing. Target collection for logging: {}", loggedCollectionName);
            return;
        }

        logger.info("Submitting {} documents for indexing via VectorStore: {}. " +
                        "VectorStore is expected to use its pre-configured default collection (logged as: {}).",
                documents.size(), vectorStore.getClass().getSimpleName(), loggedCollectionName);

        vectorStore.add(documents);

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
                .metadata(Collections.emptyMap()) // Defaulting to empty metadata
                .collectionName(effectiveCollectionName)
                .build();

        List<Document> documents;
        try {
            documents = loader.load(sourceDescriptor);
        } catch (Exception e) { // DocumentLoader.load can throw Exception
            logger.error("Failed to load documents from file: {} using loader: {}",
                    filePath, loader.getClass().getSimpleName(), e);
            throw new IOException("Failed to load documents from file: " + filePath, e);
        }

        if (CollectionUtils.isEmpty(documents)) {
            logger.warn("Loader {} produced no documents for file: {}", loader.getClass().getSimpleName(), filePath);
            return;
        }
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
                    if (loader != null) {
                        String fileSpecificSourceId = StringUtils.hasText(sourceIdPrefix) ?
                                sourceIdPrefix + ":" + directoryPath.relativize(filePath).toString() :
                                directoryPath.relativize(filePath).toString();

                        DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                                .type(DocumentSourceDescriptor.SourceType.FILE) // Each item in dir is treated as FILE for loader
                                .pathOrUrl(filePath.toString())
                                .originalFileName(filePath.getFileName().toString())
                                .sourceId(fileSpecificSourceId)
                                .metadata(Collections.emptyMap())
                                .collectionName(effectiveCollectionName)
                                .build();

                        logger.debug("Loading documents from file: {} with sourceId: {} using loader {}",
                                filePath, fileSpecificSourceId, loader.getClass().getSimpleName());
                        List<Document> loadedDocs = loader.load(sourceDescriptor);

                        if (!CollectionUtils.isEmpty(loadedDocs)) {
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
                } catch (Exception e) { // DocumentLoader.load can throw Exception
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
    public void indexDocuments(List<Document> documents) throws IOException {
        indexDocuments(documents,"default");
    }

    @Override
    public void reprocessAndIndexAllSources() throws IOException {
        deleteAll("default");
    }

    @Override
    public boolean isIndexAvailable() {
        return true;
    }
}